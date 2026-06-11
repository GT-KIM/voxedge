package com.conversationalai.agent.llm

import android.util.Log

/**
 * JNI handle to the resident Qwen3-4B Genie LLM engine (libgenie_llm.so -> libGenie.so).
 *
 * Resident: [init] loads the w4a16 Genie bundle once (4 ctx-bins on the HTP); [generate] runs one
 * query and streams the assistant text back through a [TokenSink] callback (BEGIN/CONTINUE/END
 * sentence codes).
 *
 * SESSION-CAPABLE: the Genie KV cache persists across [generate] calls. A clean OK turn leaves the
 * session warm, so the next turn only needs the incremental ChatML user block (no history replay,
 * no prefill re-decode of past turns -> lower multi-turn TTFT). Any abort/overflow/error marks the
 * session cold; the caller re-prefills the full transcript after [resetSession] (policy in
 * core/LlmSessionPolicy).
 *
 * The DSP/HTP env (ADSP_LIBRARY_PATH + v79 skels, LD_LIBRARY_PATH) must already be set before the
 * native lib loads — MainActivity does this for the TTS engine and Genie reuses the same backend.
 */
class GenieLlm : LlmEngine {

    private var handle: Long = 0L
    @Volatile private var warm = false
    @Volatile private var rewindBroken = false

    /** SAM bridge the native query callback invokes per response chunk. */
    fun interface TokenSink {
        fun onToken(text: String, sentenceCode: Int)
    }

    private external fun nativeVersion(): String
    private external fun nativeInit(bundleDir: String): Long
    private external fun nativeGenerate(handle: Long, prompt: String, sink: TokenSink, rewind: Boolean): Int
    private external fun nativeReset(handle: Long)
    private external fun nativeContextOccupancy(handle: Long): Int
    private external fun nativeSetSampling(handle: Long, temp: Float, topK: Int, topP: Float): Boolean
    private external fun nativeSetMaxTokens(handle: Long, maxTokens: Int): Boolean
    private external fun nativeAbort(handle: Long)
    private external fun nativeRelease(handle: Long)

    override fun name(): String = if (handle != 0L) nativeVersion() else "genie(uninit)"

    /** Loads the Genie bundle in [bundleDir] (genie_config.json + tokenizer + 4 ctx-bins). */
    fun init(bundleDir: String): Boolean {
        if (handle != 0L) return true
        handle = nativeInit(bundleDir)
        if (handle == 0L) Log.e(TAG, "nativeInit failed for $bundleDir")
        return handle != 0L
    }

    /**
     * [prompt] must already be ChatML-formatted (see PromptAssembler): the full transcript on a
     * cold session, or only the new user turn on a warm one. Streams decoded text.
     */
    override fun generate(prompt: String, onToken: (String) -> Unit): LlmEngine.Result =
        runQuery(prompt, rewind = false, onToken = onToken)

    override val supportsRewind: Boolean get() = !rewindBroken

    /** KV$ prefix-match rewind (SDK tutorial): pass the FULL transcript; Genie rewinds the cache
     *  to the shared prefix and prefills only the difference. Requires the SMART_MASK KV update
     *  method — bundles with POINTER_SHIFT weight-shared bins reject it (QUERY_FAILED -6,
     *  observed with the Qwen3 w4a16 bundle). SELF-HEALING: a rewind that fails before emitting
     *  any token disables rewind for this process and retries the same prompt as a plain
     *  reset+prefill, so the user's turn still completes. */
    override fun generateRewind(prompt: String, onToken: (String) -> Unit): LlmEngine.Result {
        if (rewindBroken) {
            resetSession()
            return runQuery(prompt, rewind = false, onToken = onToken)
        }
        var emitted = false
        val result = runQuery(prompt, rewind = true) { text -> emitted = true; onToken(text) }
        if (result != LlmEngine.Result.OK && !emitted) {
            Log.w(TAG, "rewind failed ($result) with no output; disabling rewind, retrying plain")
            rewindBroken = true
            resetSession()
            return runQuery(prompt, rewind = false, onToken = onToken)
        }
        return result
    }

    private fun runQuery(prompt: String, rewind: Boolean, onToken: (String) -> Unit): LlmEngine.Result {
        check(handle != 0L) { "GenieLlm not initialized" }
        val status = nativeGenerate(handle, prompt, TokenSink { text, _ ->
            // Genie omits the special/stop tokens from the response text; forward what it emits.
            if (text.isNotEmpty()) onToken(text)
        }, rewind)
        val result = when (status) {
            0 -> LlmEngine.Result.OK
            1 -> LlmEngine.Result.CONTEXT_EXCEEDED
            2 -> LlmEngine.Result.ABORTED
            else -> LlmEngine.Result.ERROR
        }
        warm = result == LlmEngine.Result.OK
        // A failed query can leave partial state in the KV cache; drop it so the next full
        // re-prefill starts from a clean dialog.
        if (result == LlmEngine.Result.ERROR || result == LlmEngine.Result.CONTEXT_EXCEEDED) {
            nativeReset(handle)
        }
        return result
    }

    /** Signal an in-flight [generate] to stop early (barge-in). No-op if idle. The session goes
     *  cold either way: an aborted reply leaves KV that no longer matches the transcript. */
    override fun abort() {
        if (handle != 0L) {
            warm = false
            nativeAbort(handle)
        }
    }

    override val sessionCapable: Boolean get() = true

    override fun sessionWarm(): Boolean = handle != 0L && warm

    override fun resetSession() {
        warm = false
        if (handle != 0L) nativeReset(handle)
    }

    override fun contextOccupancyPercent(): Int =
        if (handle != 0L) nativeContextOccupancy(handle) else -1

    override fun setSampling(sampling: LlmSampling): Boolean {
        if (handle == 0L) return false
        val ok = nativeSetSampling(handle, sampling.temp, sampling.topK, sampling.topP)
        Log.i(TAG, "setSampling temp=${sampling.temp} topK=${sampling.topK} topP=${sampling.topP} ok=$ok")
        return ok
    }

    override fun setMaxResponseTokens(maxTokens: Int): Boolean =
        handle != 0L && nativeSetMaxTokens(handle, maxTokens)

    override fun chatTemplateId(): String = "chatml"

    override fun release() {
        if (handle != 0L) { nativeRelease(handle); handle = 0L; warm = false }
    }

    companion object {
        private const val TAG = "GenieLlm"
        @Volatile private var loaded = false
        fun ensureLoaded() {
            if (!loaded) { System.loadLibrary("genie_llm"); loaded = true }
        }
    }

    init { ensureLoaded() }
}
