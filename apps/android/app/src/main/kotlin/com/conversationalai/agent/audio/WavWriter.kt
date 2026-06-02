package com.conversationalai.agent.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes mono float[-1,1] PCM as a 16-bit WAV — for dumping captured mic audio to pull off-device
 *  and run the host ASR/denoiser CER eval on the REAL capture path. */
object WavWriter {
    fun write(file: File, samples: FloatArray, sampleRate: Int = 16000) {
        val dataLen = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataLen); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1)        // PCM
        buf.putShort(1)                                                       // mono
        buf.putInt(sampleRate); buf.putInt(sampleRate * 2)                    // byte rate
        buf.putShort(2); buf.putShort(16)                                     // block align, bits
        buf.put("data".toByteArray()); buf.putInt(dataLen)
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            buf.putShort(v.toShort())
        }
        file.outputStream().use { it.write(buf.array()) }
    }
}
