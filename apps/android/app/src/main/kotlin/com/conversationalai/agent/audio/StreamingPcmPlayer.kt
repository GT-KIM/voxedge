package com.conversationalai.agent.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * One persistent AudioTrack fed successive clause PCM buffers, so back-to-back clauses play
 * gaplessly (unlike PcmPlayer, which builds/drains a track per clip). [start] once, [write] each
 * clause's float[] (blocking until the ring buffer accepts it), [stopAndRelease] when done.
 *
 * Not a full streaming controller (no barge-in/pause yet — that arrives with the state machine in
 * build step 5); this is the step-3 pipeline sink.
 */
class StreamingPcmPlayer(private val sampleRate: Int = 44100) : PcmStreamPlayer {

    @Volatile private var track: AudioTrack? = null
    @Volatile private var interrupted = false
    private var framesWritten = 0

    override fun start() {
        if (track != null) return
        framesWritten = 0
        interrupted = false
        // ~2 s ring buffer (bytes) so TTS jitter between clauses doesn't underrun playback.
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(sampleRate * 2 * 4)
        track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .build(),
            minBuf,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        ).apply { play() }
    }

    /** Blocking write of one clause; returns when all samples are queued (or on interrupt). */
    override fun write(pcm: FloatArray) {
        val t = track ?: return
        var off = 0
        while (off < pcm.size && !interrupted) {
            val n = t.write(pcm, off, pcm.size - off, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            off += n
        }
        framesWritten += pcm.size  // mono: 1 sample == 1 frame
    }

    /** Barge-in: stop audible playback NOW (pause + flush buffered audio), within ~one buffer.
     *  Safe to call from another thread; unblocks any in-flight [write] and tears the track down. */
    override fun interrupt() {
        interrupted = true
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush(); t.stop() }
        t.release()
    }

    override fun stopAndRelease() {
        val t = track ?: return
        track = null
        // WRITE_BLOCKING returns once samples are QUEUED, not played — up to a full ring buffer can
        // still be buffered. Wait for the track to actually play out framesWritten before stopping,
        // else the last clause(s) get cut off (was: a fixed 200 ms sleep -> only clause 1 audible).
        val timeoutMs = framesWritten * 1000L / sampleRate + 500L
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (t.playbackHeadPosition < framesWritten && System.nanoTime() < deadline) {
            try { Thread.sleep(20L) } catch (_: InterruptedException) { break }
        }
        runCatching { t.stop() }
        t.release()
    }
}
