package com.conversationalai.agent.core

import com.conversationalai.agent.core.tools.ToolCall
import com.conversationalai.agent.core.tools.ToolCallParser

/**
 * Streaming filter between the LLM token stream and everything that renders it (UI deltas, the
 * ClauseSegmenter -> TTS). Plain text passes through [onText]; a `[TOOL_CALL]{json}[/TOOL_CALL]`
 * block — possibly split across many token chunks — is held back, never spoken, and surfaced as
 * [call] once complete.
 *
 * The markers are deliberately plain text, NOT Qwen's native `<tool_call>` tags: those are
 * special tokens in the model vocabulary and Genie strips special tokens from the response
 * stream (verified on-device 2026-06-10 — the wrapper never reaches the app). Square-bracket
 * markers detokenize as ordinary text on every engine.
 *
 * Behavior choices for a SPOKEN agent:
 *  - Text before the call is spoken normally ("One moment, let me check that. <tool_call>...").
 *  - One call per generation step: the first complete block wins; anything after it is dropped
 *    (the next agentic step produces the user-facing answer).
 *  - An unterminated block at stream end is dropped silently — partial JSON must never reach TTS.
 */
class ToolCallFilter(private val onText: (String) -> Unit) {

    private val buf = StringBuilder()
    private var inCall = false

    var call: ToolCall? = null
        private set

    fun accept(chunk: String) {
        if (call != null) return   // post-call text is dropped (one call per step)
        buf.append(chunk)
        process()
    }

    /** Flush at stream end: release held-back text that turned out not to be a marker. */
    fun finish() {
        if (call == null && !inCall && buf.isNotEmpty()) {
            onText(buf.toString())
        }
        buf.setLength(0)
    }

    private fun process() {
        while (true) {
            if (inCall) {
                val end = buf.indexOf(CLOSE)
                if (end < 0) return   // wait for the rest of the block
                call = ToolCallParser.parse(buf.substring(0, end))
                buf.setLength(0)
                inCall = false
                return                // one call per step; trailing text is dropped via accept()
            }
            val start = buf.indexOf(OPEN)
            if (start >= 0) {
                if (start > 0) onText(buf.substring(0, start))
                buf.delete(0, start + OPEN.length)
                inCall = true
                continue
            }
            // Emit everything except the longest tail that could still grow into "<tool_call>".
            val hold = longestSuffixThatPrefixesMarker()
            val emit = buf.length - hold
            if (emit > 0) {
                onText(buf.substring(0, emit))
                buf.delete(0, emit)
            }
            return
        }
    }

    private fun longestSuffixThatPrefixesMarker(): Int {
        val max = minOf(buf.length, OPEN.length - 1)
        for (k in max downTo 1) {
            if (buf.regionMatches(buf.length - k, OPEN, 0, k)) return k
        }
        return 0
    }

    private fun StringBuilder.indexOf(s: String): Int = indexOf(s, 0)

    companion object {
        const val OPEN = "[TOOL_CALL]"
        const val CLOSE = "[/TOOL_CALL]"
    }
}
