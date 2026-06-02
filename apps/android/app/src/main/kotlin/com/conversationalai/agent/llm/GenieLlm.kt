package com.conversationalai.agent.llm

import android.util.Log

/**
 * JNI handle to the resident Qwen3-4B Genie LLM engine (libgenie_llm.so -> libGenie.so).
 *
 * Resident: [init] loads the w4a16 Genie bundle once (4 ctx-bins on the HTP); [generate] runs one
 * query and streams the assistant text back through a [TokenSink] callback (BEGIN/CONTINUE/END
 * sentence codes), then resets the KV cache for an independent next turn.
 *
 * The DSP/HTP env (ADSP_LIBRARY_PATH + v79 skels, LD_LIBRARY_PATH) must already be set before the
 * native lib loads — MainActivity does this for the TTS engine and Genie reuses the same backend.
 */
class GenieLlm : LlmEngine {

    private var handle: Long = 0L

    /** SAM bridge the native query callback invokes per response chunk. */
    fun interface TokenSink {
        fun onToken(text: String, sentenceCode: Int)
    }

    private external fun nativeVersion(): String
    private external fun nativeInit(bundleDir: String): Long
    private external fun nativeGenerate(handle: Long, prompt: String, sink: TokenSink): Boolean
    private external fun nativeAbort(handle: Long)
    private external fun nativeRelease(handle: Long)

    override fun name(): String = if (handle != 0L) nativeVersion() else "genie(uninit)"

    /** Loads the Genie bundle in [bundleDir] (genie_config.json + tokenizer + 4 ctx-bins). */
    fun init(bundleDir: String): Boolean {
        if (handle != 0L) return true
        handle = nativeInit(bundleDir)
        if (handle == 0L) Log.e(TAG, "nativeInit failed for $bundleDir")
        return handle != 0L
    }

    /** [prompt] must already be ChatML-formatted (see PromptAssembler). Streams decoded text. */
    override fun generate(prompt: String, onToken: (String) -> Unit) {
        check(handle != 0L) { "GenieLlm not initialized" }
        nativeGenerate(handle, prompt, TokenSink { text, _ ->
            // Genie omits the special/stop tokens from the response text; forward what it emits.
            if (text.isNotEmpty()) onToken(text)
        })
    }

    /** Signal an in-flight [generate] to stop early (barge-in). No-op if idle. */
    override fun abort() {
        if (handle != 0L) nativeAbort(handle)
    }

    fun release() {
        if (handle != 0L) { nativeRelease(handle); handle = 0L }
    }

    companion object {
        private const val TAG = "GenieLlm"
        @Volatile private var loaded = false
        fun ensureLoaded() {
            if (!loaded) { System.loadLibrary("genie_llm"); loaded = true }
        }
    }

    init { ensureLoaded() }
}
