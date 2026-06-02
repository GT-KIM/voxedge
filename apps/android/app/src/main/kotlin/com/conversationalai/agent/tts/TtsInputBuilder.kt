package com.conversationalai.agent.tts

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer
import kotlin.random.Random

/**
 * Kotlin port of tools/tts/prep_static_tts_inputs.py (--layout dlc). Builds one clause's static
 * inputs in DLC layout. Reads assets/tts/unicode_indexer.json (length-65536 int array, index =
 * unicode codepoint, value = token id) and assets/tts/<voice>.json (style_ttl/style_dp).
 *
 * latentMask is emitted as ones over the full LAT here; the native engine runs the
 * duration_predictor DLC and rebuilds the mask to the predicted clause length (killing the noise
 * tail and trimming the PCM). All multi-dim inputs — style_ttl AND style_dp — are transposed to
 * DLC layout (last two axes swapped); int32 text_ids are handled natively.
 */
class TtsInputBuilder(context: Context, voice: String = "F1") : ClauseInputBuilder {

    private val indexer: IntArray
    private val styleTtlOnnx: FloatArray  // [50,256] row-major (ONNX order)
    private val styleDp: FloatArray       // [8,16]

    init {
        val am = context.assets
        indexer = am.open("tts/unicode_indexer.json").bufferedReader().use { it.readText() }
            .let { parseIntArray(it) }
        val v = am.open("tts/$voice.json").bufferedReader().use { JSONObject(it.readText()) }
        styleTtlOnnx = readData(v.getJSONObject("style_ttl"))   // 50*256
        styleDp = readData(v.getJSONObject("style_dp"))         // 8*16
    }

    /** Build inputs for one short clause (<= T tokens after framing). */
    override fun build(text: String, lang: String, seed: Long): TtsInputs {
        val s = preprocess(text, lang)
        val ids = IntArray(s.length) { indexer[s[it].code] }
        require(ids.size <= T) { "clause too long for T=$T: ${ids.size} tokens — split it" }

        val textIds = IntArray(T)
        ids.copyInto(textIds)

        // text_mask ONNX [1,1,T] -> DLC [1,T,1]; both are just a length-T vector here.
        val textMask = FloatArray(T) { if (it < ids.size) 1f else 0f }

        // style_ttl ONNX [1,50,256] -> DLC [1,256,50]: transpose the 50x256 matrix.
        val styleTtlDlc = transpose2d(styleTtlOnnx, rows = 50, cols = 256)  // -> 256x50

        // style_dp ONNX [1,8,16] -> DLC [1,16,8]: like every QAIRT multi-dim input, the dp DLC
        // expects the last two axes transposed. Feeding ONNX order scrambles the speaker-rate
        // conditioning -> duration_predictor under-predicts and the clause gets truncated.
        val styleDpDlc = transpose2d(styleDp, rows = 8, cols = 16)  // -> 16x8

        // latent_mask = ones over LAT; the native engine rebuilds it from the dp duration.
        val latentMask = FloatArray(LAT) { 1f }

        // noisy_latent ONNX [1,144,LAT] -> DLC [1,LAT,144]; generate in DLC layout directly,
        // masked by latentMask (all ones here). Gaussian like np.random.randn.
        val rng = Random(seed)
        val noisy = FloatArray(LAT * LDIM_CCF) { gaussian(rng) }  // [LAT,144] row-major

        return TtsInputs(
            textIds = textIds,
            textMask = textMask,
            styleTtl = styleTtlDlc,
            styleDp = styleDpDlc,
            noisyLatent = noisy,
            latentMask = latentMask,
        )
    }

    private fun preprocess(text: String, lang: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKD)
        t = t.replace(Regex("\\s+"), " ").trim()
        if (!Regex("[.!?;:,]$").containsMatchIn(t)) t += "."
        return "<$lang>$t</$lang>"
    }

    private fun readData(obj: JSONObject): FloatArray {
        val arr = obj.getJSONArray("data")
        // data may be nested; flatten recursively into a FloatArray.
        val out = ArrayList<Float>(arr.length())
        flatten(arr, out)
        return FloatArray(out.size) { out[it] }
    }

    private fun flatten(arr: org.json.JSONArray, out: ArrayList<Float>) {
        for (i in 0 until arr.length()) {
            val e = arr.get(i)
            if (e is org.json.JSONArray) flatten(e, out)
            else out.add((e as Number).toFloat())
        }
    }

    private fun parseIntArray(json: String): IntArray {
        val arr = org.json.JSONArray(json)
        return IntArray(arr.length()) { arr.getInt(it) }
    }

    private fun transpose2d(src: FloatArray, rows: Int, cols: Int): FloatArray {
        val out = FloatArray(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) out[c * rows + r] = src[r * cols + c]
        return out
    }

    // Box–Muller standard normal.
    private fun gaussian(rng: Random): Float {
        val u1 = (rng.nextDouble()).coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return (Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2)).toFloat()
    }

    companion object {
        const val T = 64
        const val LAT = 128
        const val LDIM_CCF = 144   // latent_dim(24) * chunk_compress_factor(6)
    }
}
