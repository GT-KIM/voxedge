package com.conversationalai.agent.ui

import android.content.Context
import android.system.Os
import android.util.Log
import com.conversationalai.agent.asr.OfflineAsr
import com.conversationalai.agent.audio.SpeechEnhancer
import com.conversationalai.agent.llm.GenieLlm
import com.conversationalai.agent.llm.LiteRtLlm
import com.conversationalai.agent.llm.LlmBackend
import com.conversationalai.agent.llm.LlmCatalog
import com.conversationalai.agent.llm.LlmEngine
import com.conversationalai.agent.llm.LlmModelSpec
import com.conversationalai.agent.tts.DspAssets
import com.conversationalai.agent.tts.SupertonicTts
import com.conversationalai.agent.tts.TtsInputBuilder
import java.io.File

/** Initializes concrete Android runtime engines and probes provisioned model assets. */
class RuntimeInitializer(private val context: Context) {

    private val settings: AppSettings by lazy { PrefsSettingsStore(context).load() }
    data class Result(
        val tts: SupertonicTts,
        val inputBuilder: TtsInputBuilder,
        val llm: LlmEngine,
        val llmModel: LlmModelSpec,
        val asr: OfflineAsr,
        val enhancer: SpeechEnhancer,
        val initOk: Boolean,
        val llmOk: Boolean,
        val asrOk: Boolean,
        val enhanceOk: Boolean,
        val vadOk: Boolean,
        val status: String,
    )

    fun initialize(): Result {
        configureDspEnvironment()

        val tts = SupertonicTts()
        val inputBuilder = TtsInputBuilder(context, voice = "F1")

        val dlcDir = File(context.filesDir, "tts_dlc")
        var initOk = false
        val status = if (!dlcDir.exists() || dlcDir.list()?.isEmpty() != false) {
            "push DLCs to ${dlcDir.absolutePath} (adb), then relaunch"
        } else {
            initOk = tts.init(dlcDir.absolutePath)
            if (initOk) "engine ready: ${tts.version()}" else "nativeInit failed (see logcat)"
        }

        val llmModel = selectLlmModel()
        val (llm, llmOk) = initializeLlm(llmModel)
        val asr = OfflineAsr(
            senseVoiceDir = File(context.filesDir, "asr").absolutePath,
            dolphinDir = File(context.filesDir, "asr_dolphin").absolutePath,
        )
        val asrOk = initializeAsr(asr)

        val enhancer = SpeechEnhancer(File(context.filesDir, "asr_denoiser/gtcrn_simple.onnx").absolutePath)
        val enhanceOk = initializeEnhancer(enhancer)

        val vadModel = File(context.filesDir, "vad/silero_vad.onnx")
        val vadOk = vadModel.exists()
        if (!vadOk) Log.w(TAG, "silero_vad model absent ??push to ${vadModel.parent} (adb)")
        else Log.i(TAG, "VAD model present")

        return Result(
            tts = tts,
            inputBuilder = inputBuilder,
            llm = llm,
            llmModel = llmModel,
            asr = asr,
            enhancer = enhancer,
            initOk = initOk,
            llmOk = llmOk,
            asrOk = asrOk,
            enhanceOk = enhanceOk,
            vadOk = vadOk,
            status = status,
        )
    }

    private fun configureDspEnvironment() {
        runCatching {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val dspDir = DspAssets.materialize(context)
            val adsp = listOf(
                dspDir, nativeDir,
                "/vendor/lib/rfsa/adsp", "/system/vendor/lib/rfsa/adsp",
                "/system/lib/rfsa/adsp", "/odm/lib/rfsa/adsp",
                "/dsp", "/vendor/dsp", "/vendor/dsp/cdsp",
            ).joinToString(";")
            Os.setenv("ADSP_LIBRARY_PATH", adsp, true)
            Os.setenv("LD_LIBRARY_PATH", "$nativeDir:" + (System.getenv("LD_LIBRARY_PATH") ?: ""), true)
            Log.i(TAG, "ADSP_LIBRARY_PATH=$adsp")
        }.onFailure { Log.e(TAG, "setenv failed", it) }
    }

    /** Persisted model choice (LlmCatalog id), falling back to whatever IS provisioned so a
     *  missing selection never bricks the loop. */
    private fun selectLlmModel(): LlmModelSpec {
        val wanted = LlmCatalog.byId(settings.llmModelId)
        if (isProvisioned(wanted)) return wanted
        val available = LlmCatalog.ALL.firstOrNull { isProvisioned(it) }
        if (available != null) {
            Log.w(TAG, "LLM '${wanted.id}' not provisioned; falling back to '${available.id}'")
            return available
        }
        return wanted   // nothing provisioned; init below logs the push instructions
    }

    private fun isProvisioned(spec: LlmModelSpec): Boolean =
        LlmCatalog.isProvisioned(context.filesDir, spec)

    private fun initializeLlm(spec: LlmModelSpec): Pair<LlmEngine, Boolean> {
        val path = File(context.filesDir, spec.relPath)
        if (!isProvisioned(spec)) {
            Log.w(TAG, "LLM '${spec.id}' absent ??push to ${path.absolutePath} (adb, see MODELS.md)")
            // Keep the historical default engine object so the UI can show "not ready".
            return when (spec.backend) {
                LlmBackend.GENIE -> GenieLlm() to false
                LlmBackend.LITERT -> LiteRtLlm() to false
            }
        }
        // Catalog sampling with any persisted user overrides (settings UI) on top.
        val sampling = settings.effectiveSampling(spec.sampling)
        return when (spec.backend) {
            LlmBackend.GENIE -> {
                val llm = GenieLlm()
                val ok = llm.init(path.absolutePath)
                Log.i(TAG, if (ok) "LLM ready: ${llm.name()}" else "LLM nativeInit failed (see logcat)")
                // Non-fatal if the runtime rejects it — generation then keeps the
                // genie_config.json sampler values.
                if (ok && !llm.setSampling(sampling)) Log.w(TAG, "sampling not applied")
                llm to ok
            }
            LlmBackend.LITERT -> {
                val llm = LiteRtLlm()
                val ok = llm.init(
                    modelPath = path.absolutePath,
                    cacheDir = context.cacheDir.absolutePath,
                    sampling = sampling,
                )
                Log.i(TAG, if (ok) "LLM ready: ${llm.name()}" else "LiteRT-LM init failed (see logcat)")
                llm to ok
            }
        }
    }

    private fun initializeAsr(asr: OfflineAsr): Boolean {
        val asrModel = File(context.filesDir, "asr/model.int8.onnx")
        return if (!asrModel.exists()) {
            Log.w(TAG, "SenseVoice model absent ??push to ${asrModel.parent} (adb)")
            false
        } else {
            val ok = asr.init()
            Log.i(TAG, if (ok) "ASR ready: ${asr.name()}" else "ASR init failed (see logcat)")
            ok
        }
    }

    private fun initializeEnhancer(enhancer: SpeechEnhancer): Boolean {
        val denoiseModel = File(context.filesDir, "asr_denoiser/gtcrn_simple.onnx")
        return if (!denoiseModel.exists()) {
            Log.w(TAG, "GTCRN model absent ??push to ${denoiseModel.parent} (adb)")
            false
        } else {
            val ok = enhancer.init()
            Log.i(TAG, if (ok) "enhancer ready (GTCRN)" else "enhancer init failed")
            ok
        }
    }

    companion object {
        private const val TAG = "RuntimeInit"
    }
}
