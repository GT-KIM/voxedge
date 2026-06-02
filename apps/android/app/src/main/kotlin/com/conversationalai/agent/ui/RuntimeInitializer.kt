package com.conversationalai.agent.ui

import android.content.Context
import android.system.Os
import android.util.Log
import com.conversationalai.agent.asr.OfflineAsr
import com.conversationalai.agent.audio.SpeechEnhancer
import com.conversationalai.agent.llm.GenieLlm
import com.conversationalai.agent.tts.DspAssets
import com.conversationalai.agent.tts.SupertonicTts
import com.conversationalai.agent.tts.TtsInputBuilder
import java.io.File

/** Initializes concrete Android runtime engines and probes provisioned model assets. */
class RuntimeInitializer(private val context: Context) {
    data class Result(
        val tts: SupertonicTts,
        val inputBuilder: TtsInputBuilder,
        val llm: GenieLlm,
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

        val llm = GenieLlm()
        val llmOk = initializeLlm(llm)
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

    private fun initializeLlm(llm: GenieLlm): Boolean {
        val llmBundle = File(context.filesDir, "llm_bundle")
        return if (!llmBundle.exists() || llmBundle.list()?.isEmpty() != false) {
            Log.w(TAG, "Genie bundle absent ??push to ${llmBundle.absolutePath} (adb)")
            false
        } else {
            val ok = llm.init(llmBundle.absolutePath)
            Log.i(TAG, if (ok) "LLM ready: ${llm.name()}" else "LLM nativeInit failed (see logcat)")
            ok
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
