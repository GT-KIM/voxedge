package com.conversationalai.agent.tts

import android.util.Log

/**
 * JNI handle to the resident Supertonic TTS engine (libsupertonic_tts.so).
 *
 * Resident: nativeInit loads the 3 short-chunk DLCs once; nativeSynthesize runs one clause
 * (text_encoder -> K x vector_estimator -> vocoder) and returns 44.1 kHz mono PCM.
 *
 * Contract (docs/design/android_app_architecture.md §3):
 *  - text_ids is Int_32 -> IntArray (UserBufferEncodingIntN(32) on the native side).
 *  - every multi-dim input must be in DLC layout (last two axes transposed vs ONNX):
 *    style_ttl [1,256,50], text_mask [1,64,1], noisy_latent [1,128,144], latent_mask [1,128,1].
 *    TtsInputBuilder produces them already transposed.
 */
class SupertonicTts : TtsEngine {

    private var handle: Long = 0L

    private external fun nativeVersion(): String
    private external fun nativeInit(dlcDir: String): Long
    private external fun nativeSynthesize(
        handle: Long,
        textIds: IntArray,
        textMask: FloatArray,
        styleTtl: FloatArray,
        styleDp: FloatArray,
        noisyLatent: FloatArray,
        latentMask: FloatArray,
        k: Int,
        speed: Float,
    ): FloatArray?
    private external fun nativeRelease(handle: Long)

    override fun version(): String = nativeVersion()

    /** Loads the DLCs in [dlcDir] (app-private dir with text_encoder/vector_estimator/vocoder.dlc). */
    fun init(dlcDir: String): Boolean {
        if (handle != 0L) return true
        handle = nativeInit(dlcDir)
        if (handle == 0L) Log.e(TAG, "nativeInit failed for $dlcDir")
        return handle != 0L
    }

    /** Synthesize one clause; returns 44.1 kHz mono float PCM, or null on failure. */
    override fun synthesizeClause(inputs: TtsInputs, k: Int): FloatArray? {
        check(handle != 0L) { "SupertonicTts not initialized" }
        return nativeSynthesize(
            handle, inputs.textIds, inputs.textMask, inputs.styleTtl,
            inputs.styleDp, inputs.noisyLatent, inputs.latentMask, k, inputs.speed,
        )
    }

    fun release() {
        if (handle != 0L) { nativeRelease(handle); handle = 0L }
    }

    companion object {
        private const val TAG = "SupertonicTts"
        @Volatile private var loaded = false
        fun ensureLoaded() {
            if (!loaded) { System.loadLibrary("supertonic_tts"); loaded = true }
        }
    }

    init { ensureLoaded() }
}
