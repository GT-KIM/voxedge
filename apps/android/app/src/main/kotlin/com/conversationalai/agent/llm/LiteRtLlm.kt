package com.conversationalai.agent.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * LiteRT-LM engine (.litertlm bundles, e.g. Gemma 4 E2B) behind the shared [LlmEngine] boundary.
 *
 * Unlike Genie, LiteRT-LM owns BOTH the chat template and the conversation history: its stateful
 * [Conversation] is the session. So this engine declares chatTemplateId "raw" — it receives plain
 * user text per turn — and the system prompt arrives via [setSystemPrompt], applied when the
 * Conversation is next (re)created. Transcript-history replay on re-prefill is not supported: a
 * session reset restarts from the system prompt alone (documented RAW-template limitation).
 *
 * Backend: GPU by default (the broadly-supported accelerated path; NPU via QNN needs per-SoC
 * provisioning and is a follow-up measurement). Engine.initialize() is heavy (seconds) — same
 * load-once-resident pattern as the Genie bundle.
 *
 * KNOWN LIMITATION: the Kotlin API exposes no mid-generation cancel yet, so [abort] only drops
 * the remaining stream — the model finishes decoding in the background. Barge-in correctness is
 * preserved by the GenerationEpoch (stale tokens are discarded), but the compute is not saved.
 */
class LiteRtLlm : LlmEngine {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    @Volatile private var warm = false
    @Volatile private var aborted = false
    private var systemPrompt: String = ""
    private var sampling: LlmSampling = LlmSampling()
    private var modelName: String = "litert-lm(uninit)"

    /** Load the .litertlm bundle once (resident). [cacheDir] speeds up subsequent loads.
     *  Tries the GPU backend first (the broadly-working fast path), then falls back to CPU so a
     *  missing/broken delegate degrades to slow instead of dead. [preferNpu] prepends the QNN/NPU
     *  backend (experimental; needs per-SoC support in the runtime). */
    fun init(
        modelPath: String,
        cacheDir: String? = null,
        sampling: LlmSampling = LlmSampling(),
        preferNpu: Boolean = false,
    ): Boolean {
        if (engine != null) return true
        this.sampling = sampling
        val backends: List<Pair<String, Backend>> = buildList {
            if (preferNpu) add("npu" to Backend.NPU())
            add("gpu" to Backend.GPU())
            add("cpu" to Backend.CPU())
        }
        for ((label, backend) in backends) {
            val result = runCatching {
                Engine(
                    EngineConfig(modelPath = modelPath, backend = backend, cacheDir = cacheDir),
                ).also { it.initialize() }
            }
            val e = result.getOrNull()
            if (e != null) {
                engine = e
                modelName = "litert-lm($label):" + File(modelPath).name
                Log.i(TAG, "engine ready ($modelName)")
                return true
            }
            Log.e(TAG, "engine init failed on $label backend for $modelPath", result.exceptionOrNull())
        }
        engine = null
        return false
    }

    override fun name(): String = modelName

    override fun generate(prompt: String, onToken: (String) -> Unit): LlmEngine.Result {
        val conv = conversation ?: createConversation() ?: return LlmEngine.Result.ERROR
        aborted = false
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        runCatching {
            conv.sendMessageAsync(prompt, object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (aborted) return
                    val text = message.toString()
                    if (text.isNotEmpty()) onToken(text)
                }
                override fun onDone() {
                    done.countDown()
                }
                override fun onError(throwable: Throwable) {
                    failure = throwable
                    done.countDown()
                }
            })
            done.await()
        }.onFailure { failure = it }

        val result = when {
            aborted -> LlmEngine.Result.ABORTED
            failure != null -> {
                Log.e(TAG, "generate failed", failure)
                LlmEngine.Result.ERROR
            }
            else -> LlmEngine.Result.OK
        }
        warm = result == LlmEngine.Result.OK
        if (result == LlmEngine.Result.ERROR) resetSession()
        return result
    }

    override fun abort() {
        aborted = true
        warm = false
    }

    override val sessionCapable: Boolean get() = true

    override fun sessionWarm(): Boolean = warm && conversation != null

    override fun resetSession() {
        warm = false
        runCatching { conversation?.close() }
        conversation = null
    }

    override fun setSystemPrompt(systemPrompt: String) {
        if (this.systemPrompt == systemPrompt) return
        this.systemPrompt = systemPrompt
        resetSession()   // applied when the Conversation is recreated on the next generate
    }

    override fun setSampling(sampling: LlmSampling): Boolean {
        this.sampling = sampling
        resetSession()   // SamplerConfig is fixed per Conversation; recreate to apply
        return true
    }

    override fun chatTemplateId(): String = "raw"

    override fun release() {
        resetSession()
        runCatching { engine?.close() }
        engine = null
    }

    private fun createConversation(): Conversation? {
        val e = engine ?: return null
        return runCatching {
            e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = SamplerConfig(
                        topK = sampling.topK,
                        topP = sampling.topP.toDouble(),
                        temperature = sampling.temp.toDouble(),
                    ),
                ),
            )
        }.getOrElse { ex ->
            Log.e(TAG, "createConversation failed", ex)
            null
        }?.also { conversation = it }
    }

    companion object {
        private const val TAG = "LiteRtLlm"
    }
}
