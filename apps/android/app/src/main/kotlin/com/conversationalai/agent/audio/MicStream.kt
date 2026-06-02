package com.conversationalai.agent.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Always-on mic + Silero VAD with AcousticEchoCanceler, for step-5c barge-in: the mic stays open
 * during the assistant's SPEAKING so the user can interrupt. AEC + NoiseSuppressor try to keep the
 * TTS output from being self-detected as user speech (effectiveness is device-dependent — the main
 * barge-in risk). Source = VOICE_COMMUNICATION so the platform routes through the AEC path.
 *
 * Callbacks fire on the capture thread and MUST be fast/non-blocking:
 *  - [onSpeechStart] at speech ONSET — drives the fast barge-in (stop playback).
 *  - [onUtterance] with the full segment at endpoint — drives the next turn's ASR.
 */
class MicStream(
    private val vadModelPath: String,
    private val onSpeechStart: () -> Unit,
    private val onUtterance: (FloatArray) -> Unit,
    // Fire onSpeechStart only after this many CONSECUTIVE speech windows (~32 ms each), so brief
    // AEC-residual / echo blips during the assistant's playback don't cause a false barge-in.
    private val onsetMinWindows: Int = 6,
) {
    private val sampleRate = 16000
    private val window = 512
    @Volatile private var running = false
    /** Half-duplex gate: when true, mic audio is read+discarded (NOT fed to VAD, no callbacks), so
     *  the assistant's own TTS playback can't be captured as input. Set during the turn + tail. */
    @Volatile var muted = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    fun start(): Boolean {
        if (running) return true
        val vad = try {
            Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadModelPath, threshold = 0.5f, minSilenceDuration = 0.6f,
                        minSpeechDuration = 0.25f, windowSize = window, maxSpeechDuration = 15f,
                    ),
                    sampleRate = sampleRate, numThreads = 1, provider = "cpu",
                ),
            )
        } catch (t: Throwable) { Log.e(TAG, "VAD init failed", t); return false }

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) { vad.release(); return false }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, window * 4),
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission missing", se); vad.release(); return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); vad.release(); Log.e(TAG, "AudioRecord not initialized"); return false
        }

        val sid = rec.audioSessionId
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sid)?.also { it.setEnabled(true) }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(sid)?.also { it.setEnabled(true) }
        }
        Log.i(TAG, "AEC available=${AcousticEchoCanceler.isAvailable()} enabled=${aec?.enabled}; " +
            "NS available=${NoiseSuppressor.isAvailable()} enabled=${ns?.enabled}")

        running = true
        rec.startRecording()
        thread = Thread {
            val sbuf = ShortArray(window)
            val fbuf = FloatArray(window)
            var speechRun = 0           // consecutive speech windows
            var firedOnset = false      // onSpeechStart fired for the current run
            var wasMuted = false
            while (running) {
                val n = rec.read(sbuf, 0, window)
                if (n <= 0) continue
                if (muted) { wasMuted = true; continue }   // discard our own TTS / turn-time audio
                if (wasMuted) { wasMuted = false; vad.reset(); speechRun = 0; firedOnset = false }
                for (i in 0 until n) fbuf[i] = sbuf[i] / 32768f
                vad.acceptWaveform(if (n == window) fbuf else fbuf.copyOf(n))
                if (vad.isSpeechDetected()) {
                    speechRun++
                    if (speechRun >= onsetMinWindows && !firedOnset) { firedOnset = true; onSpeechStart() }
                } else {
                    speechRun = 0; firedOnset = false
                }
                while (!vad.empty()) {
                    val seg = vad.front(); vad.pop()
                    speechRun = 0; firedOnset = false
                    onUtterance(seg.samples)
                }
            }
            runCatching { rec.stop() }
            rec.release()
            aec?.release()
            ns?.release()
            vad.release()
        }.also { it.start() }
        Log.i(TAG, "mic stream started")
        return true
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    companion object { private const val TAG = "MicStream" }
}
