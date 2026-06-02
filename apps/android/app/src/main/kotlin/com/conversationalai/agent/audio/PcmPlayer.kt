package com.conversationalai.agent.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** Minimal blocking PCM player for the TTS slice: plays a float[] mono clip at [sampleRate]. */
object PcmPlayer {
    fun playMono(pcm: FloatArray, sampleRate: Int = 44100) {
        if (pcm.isEmpty()) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(pcm.size * 4)
        val track = AudioTrack(
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
        )
        track.play()
        var off = 0
        while (off < pcm.size) {
            val n = track.write(pcm, off, pcm.size - off, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            off += n
        }
        // let the buffer drain, then stop
        Thread.sleep((pcm.size * 1000L / sampleRate) + 200L)
        track.stop()
        track.release()
    }
}
