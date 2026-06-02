package com.conversationalai.agent.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Hands-free utterance capture (Silero VAD) for the step-5 LISTENING/CAPTURING regions.
 * [captureUtterance] opens the mic and reads 16 kHz mono until the VAD endpoints ONE speech
 * segment (speech then >= minSilenceDuration of silence), then closes the mic and returns that
 * segment's samples. Blocking — call off the main thread; [cancel] makes it return early.
 *
 * 5a keeps the mic OFF during the assistant turn (no barge-in yet -> no echo, no AEC). Barge-in
 * (step 5c) will keep the mic open during SPEAKING with AcousticEchoCanceler.
 */
class VadCapture(private val vadModelPath: String) {

    private val sampleRate = 16000
    private val window = 512  // silero VAD window
    private var vad: Vad? = null
    @Volatile private var cancelled = false

    fun init(): Boolean = try {
        vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadModelPath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.6f,   // endpoint after 0.6 s of silence
                    minSpeechDuration = 0.25f,
                    windowSize = window,
                    maxSpeechDuration = 15f,
                ),
                sampleRate = sampleRate,
                numThreads = 1,
                provider = "cpu",
            ),
        )
        Log.i(TAG, "VAD ready ($vadModelPath)")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "VAD init failed for $vadModelPath", t); false
    }

    /** Block until one endpointed utterance; returns its samples (empty if cancelled / no mic). */
    fun captureUtterance(): FloatArray {
        val v = vad ?: return FloatArray(0)
        v.reset()
        cancelled = false
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) { Log.e(TAG, "getMinBufferSize=$minBuf"); return FloatArray(0) }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, window * 4),
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission missing", se); return FloatArray(0)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); Log.e(TAG, "AudioRecord not initialized"); return FloatArray(0)
        }
        val shortBuf = ShortArray(window)
        val floatBuf = FloatArray(window)
        var result = FloatArray(0)
        rec.startRecording()
        while (!cancelled) {
            val n = rec.read(shortBuf, 0, window)
            if (n <= 0) continue
            for (i in 0 until n) floatBuf[i] = shortBuf[i] / 32768f
            v.acceptWaveform(if (n == window) floatBuf else floatBuf.copyOf(n))
            if (!v.empty()) {
                result = v.front().samples
                v.pop()
                break
            }
        }
        rec.stop()
        rec.release()
        return result
    }

    fun cancel() { cancelled = true }

    fun release() { vad?.release(); vad = null }

    companion object { private const val TAG = "VadCapture" }
}
