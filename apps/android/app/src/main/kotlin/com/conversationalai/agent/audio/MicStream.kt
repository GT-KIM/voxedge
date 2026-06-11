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
 * Always-on mic + Silero VAD. Capture profile depends on [bargeIn]:
 *  - bargeIn=true: the mic stays open during the assistant's SPEAKING so the user can interrupt, so
 *    we need echo suppression. Source = VOICE_COMMUNICATION + AcousticEchoCanceler + NoiseSuppressor
 *    try to keep the TTS output from being self-detected as user speech (device-dependent).
 *  - bargeIn=false (default): the mic is MUTED during playback (half-duplex), so the assistant's
 *    voice never reaches ASR and echo cancellation is unnecessary. We capture the user's speech with
 *    the ASR-tuned VOICE_RECOGNITION source and SKIP AEC/NS, which otherwise degrade the signal/SNR
 *    (consonant damage, AGC pumping). This is the measured "capture mode" ASR lever — validate the
 *    per-source CER on-device with tools/asr/ before trusting it.
 *
 * Callbacks fire on the capture thread and MUST be fast/non-blocking:
 *  - [onSpeechStart] at speech ONSET — drives the fast barge-in (stop playback).
 *  - [onUtteranceCandidate] at the EARLY endpoint checkpoint (~[candidateSilenceWindows] windows of
 *    trailing silence, well before the VAD's 600 ms endpoint) with the utterance audio so far —
 *    drives SPECULATIVE turns (the controller starts ASR+LLM early, gated until confirmation).
 *  - [onUtterance] with the full segment at the real endpoint — the authoritative transcript.
 */
class MicStream(
    private val vadModelPath: String,
    private val onSpeechStart: () -> Unit,
    private val onUtterance: (FloatArray) -> Unit,
    private val onUtteranceCandidate: (FloatArray) -> Unit = {},
    // Fire onSpeechStart only after this many CONSECUTIVE speech windows (~32 ms each), so brief
    // AEC-residual / echo blips during the assistant's playback don't cause a false barge-in.
    private val onsetMinWindows: Int = 6,
    // Early endpoint for the candidate VAD: ~350 ms before the 0.6 s authoritative endpoint.
    // Each false fire (mid-utterance pause) costs one cancelled speculative turn.
    private val candidateSilenceSeconds: Float = 0.25f,
    // Capture profile selector (see class doc): barge-in needs the open-mic AEC path; with it off we
    // prefer the cleaner ASR-tuned source. Fixed at start() — toggling barge-in restarts the mic.
    private val bargeIn: Boolean = false,
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
        fun vadWith(minSilence: Float): Vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadModelPath, threshold = 0.5f, minSilenceDuration = minSilence,
                    minSpeechDuration = 0.25f, windowSize = window, maxSpeechDuration = 15f,
                ),
                sampleRate = sampleRate, numThreads = 1, provider = "cpu",
            ),
        )
        val vad = try { vadWith(0.6f) } catch (t: Throwable) {
            Log.e(TAG, "VAD init failed", t); return false
        }
        // Second VAD with a SHORT endpoint: its segments are the early candidates for
        // speculative turns (same audio, ~350 ms earlier). Optional — failure just disables it.
        val vadEarly = try { vadWith(candidateSilenceSeconds) } catch (t: Throwable) {
            Log.w(TAG, "candidate VAD init failed (speculation disabled)", t); null
        }

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) { vad.release(); vadEarly?.release(); return false }
        // VOICE_RECOGNITION = ASR-tuned, minimal processing (clean signal for the recognizer);
        // VOICE_COMMUNICATION = VoIP path the platform AEC hooks into (needed only for open-mic barge-in).
        val source = if (bargeIn) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val rec = try {
            AudioRecord(
                source, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, window * 4),
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission missing", se)
            vad.release(); vadEarly?.release(); return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); vad.release(); vadEarly?.release()
            Log.e(TAG, "AudioRecord not initialized"); return false
        }

        // AEC/NS only when barge-in keeps the mic open during playback. With barge-in off the mic is
        // muted during the turn, so these only hurt the user's speech (NS damages final consonants).
        val sid = rec.audioSessionId
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        if (bargeIn) {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sid)?.also { it.setEnabled(true) }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sid)?.also { it.setEnabled(true) }
            }
        }
        Log.i(TAG, "capture source=${if (bargeIn) "VOICE_COMMUNICATION+AEC/NS (barge-in)" else "VOICE_RECOGNITION (asr-clean)"}; " +
            "AEC enabled=${aec?.enabled} NS enabled=${ns?.enabled}")

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
                if (wasMuted) {
                    wasMuted = false
                    vad.reset(); vadEarly?.reset()
                    speechRun = 0; firedOnset = false
                }
                for (i in 0 until n) fbuf[i] = sbuf[i] / 32768f
                val chunk = if (n == window) fbuf else fbuf.copyOf(n)
                vad.acceptWaveform(chunk)
                vadEarly?.acceptWaveform(chunk)
                if (vad.isSpeechDetected()) {
                    speechRun++
                    if (speechRun >= onsetMinWindows && !firedOnset) { firedOnset = true; onSpeechStart() }
                } else {
                    speechRun = 0; firedOnset = false
                }
                // Early endpoint: the short-silence VAD closes its segment ~350 ms before the
                // authoritative one — that's the speculative-turn candidate.
                while (vadEarly != null && !vadEarly.empty()) {
                    val seg = vadEarly.front(); vadEarly.pop()
                    onUtteranceCandidate(seg.samples)
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
            vadEarly?.release()
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
