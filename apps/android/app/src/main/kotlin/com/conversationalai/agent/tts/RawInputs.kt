package com.conversationalai.agent.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug helper: load TtsInputs from a directory of .raw files produced by
 * tools/tts/prep_static_tts_inputs.py --layout dlc (the host-verified inputs). Lets us A/B the
 * native engine against known-good data, isolating engine/JNI from the Kotlin TtsInputBuilder.
 * Files: text_ids.raw(int32), text_mask/style_ttl/style_dp/noisy_latent/latent_mask (float32).
 */
object RawInputs {
    fun loadOrNull(dir: File): TtsInputs? {
        if (!dir.isDirectory) return null
        fun f(name: String): FloatArray? {
            val file = File(dir, name); if (!file.exists()) return null
            val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val fa = FloatArray(b.remaining() / 4); b.asFloatBuffer().get(fa); return fa
        }
        fun i(name: String): IntArray? {
            val file = File(dir, name); if (!file.exists()) return null
            val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val ia = IntArray(b.remaining() / 4); b.asIntBuffer().get(ia); return ia
        }
        val textIds = i("text_ids.raw") ?: return null
        return TtsInputs(
            textIds = textIds,
            textMask = f("text_mask.raw") ?: return null,
            styleTtl = f("style_ttl.raw") ?: return null,
            styleDp = f("style_dp.raw") ?: return null,
            noisyLatent = f("noisy_latent.raw") ?: return null,
            latentMask = f("latent_mask.raw") ?: return null,
        )
    }
}
