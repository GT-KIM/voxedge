package com.conversationalai.agent.tts

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Copies the bundled hexagon-v79 DSP skels (assets/qairt_dsp) into a real app-owned directory
 * (filesDir/qairt_dsp) that the FastRPC/DSP loader can read; APK assets are invisible to it.
 * Returns the absolute dir path to put first on ADSP_LIBRARY_PATH.
 */
object DspAssets {
    private const val TAG = "DspAssets"
    private const val ASSET_DIR = "qairt_dsp"

    fun materialize(ctx: Context): String {
        val out = File(ctx.filesDir, "qairt_dsp")
        out.mkdirs()
        val names = ctx.assets.list(ASSET_DIR) ?: emptyArray()
        for (n in names) {
            val dst = File(out, n)
            if (dst.exists() && dst.length() > 0L) continue   // already materialized
            ctx.assets.open("$ASSET_DIR/$n").use { input ->
                dst.outputStream().use { input.copyTo(it) }
            }
        }
        Log.i(TAG, "DSP skels in ${out.absolutePath}: ${out.list()?.joinToString()}")
        return out.absolutePath
    }
}
