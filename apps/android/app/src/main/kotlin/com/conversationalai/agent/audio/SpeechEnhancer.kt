package com.conversationalai.agent.audio

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig

/**
 * Neural speech enhancement (sherpa-onnx GTCRN, ~0.5 MB) applied to captured mic audio BEFORE ASR,
 * to suppress background noise so SenseVoice transcribes the speaker cleanly. GTCRN runs at 16 kHz
 * on CPU; [enhance] returns the denoised samples (and the denoiser's sample rate) to feed the ASR.
 */
class SpeechEnhancer(private val modelPath: String) {

    private var denoiser: OfflineSpeechDenoiser? = null

    fun init(): Boolean = try {
        denoiser = OfflineSpeechDenoiser(
            config = OfflineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = modelPath),
                    numThreads = 1,
                    provider = "cpu",
                ),
            ),
        )
        Log.i(TAG, "GTCRN denoiser ready ($modelPath)")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "init failed for $modelPath", t)
        false
    }

    /** Denoise [samples]; returns (cleanedSamples, sampleRate). Falls back to input if uninitialized. */
    fun enhance(samples: FloatArray, sampleRate: Int): Pair<FloatArray, Int> {
        val d = denoiser ?: return samples to sampleRate
        val out = d.run(samples, sampleRate)
        return out.samples to out.sampleRate
    }

    fun release() {
        denoiser?.release()
        denoiser = null
    }

    companion object { private const val TAG = "SpeechEnhancer" }
}
