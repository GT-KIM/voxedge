package com.conversationalai.agent.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Microphone capture for ASR: 16 kHz mono PCM (the rate SenseVoice/sherpa-onnx expects). [start]
 * records on a background thread into a growing buffer; [stop] returns the captured samples as
 * float[-1,1]. Push-to-talk for the step-4 loop (record only while not playing -> no echo, no AEC
 * needed yet); VAD endpointing + always-listening + barge-in come with the controller (step 5).
 *
 * VOICE_RECOGNITION source so the platform applies its capture tuning (NS/AGC) where available.
 */
class AudioCapture(private val sampleRate: Int = 16000) {

    @Volatile private var recording = false
    private var thread: Thread? = null
    private val chunks = ArrayList<ShortArray>()

    val isRecording: Boolean get() = recording

    /** Begin recording. Returns false if the mic can't be opened (e.g. permission missing). */
    fun start(): Boolean {
        if (recording) return true
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) { Log.e(TAG, "getMinBufferSize=$minBuf"); return false }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission missing", se); return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release(); Log.e(TAG, "AudioRecord not initialized"); return false
        }
        chunks.clear()
        recording = true
        record.startRecording()
        thread = Thread {
            val buf = ShortArray(minBuf)
            while (recording) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) chunks.add(buf.copyOf(n))
            }
            record.stop()
            record.release()
        }.also { it.start() }
        Log.i(TAG, "recording @ ${sampleRate}Hz")
        return true
    }

    /** Stop and return the captured mono samples as float[-1,1] (empty if nothing was captured). */
    fun stop(): FloatArray {
        if (!recording) return FloatArray(0)
        recording = false
        thread?.join(2000)
        thread = null
        val total = chunks.sumOf { it.size }
        val out = FloatArray(total)
        var i = 0
        for (c in chunks) for (s in c) out[i++] = s / 32768f
        chunks.clear()
        Log.i(TAG, "captured ${out.size} samples (${"%.1f".format(out.size / sampleRate.toFloat())}s)")
        return out
    }

    companion object { private const val TAG = "AudioCapture" }
}
