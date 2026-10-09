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
 * A second, looser form is accepted when [toolNames] is given: `[<tool name>]{json}` with the
 * tool name as the bracket tag and the arguments as the object (the model wrote
 * `[remember_fact]{"key": "favorite color", "value": "teal"}` on device once the tool module
 * moved earlier in the system prompt, 2026-10-09). The tag must be a registered tool name and be
 * followed by `{`, so ordinary bracketed text is never mistaken for a call.
 *
 * Behavior choices for a SPOKEN agent:
 *  - Text before the call is spoken normally ("One moment, let me check that. <tool_call>...").
 *  - One call per generation step: the first complete block wins; anything after it is dropped
 *    (the next agentic step produces the user-facing answer).
 *  - An unterminated block at stream end is dropped silently — partial JSON must never reach TTS.
 */
class ToolCallFilter(
    private val onText: (String) -> Unit,
    private val toolNames: Set<String> = emptySet(),
) {

    private val buf = StringBuilder()
    private var inCall = false
    private var lenientName: String? = null   // set while inside a `[<tool>]{...}` block

    var call: ToolCall? = null
        private set

    /** A [OPEN] block arrived but didn't parse as a tool call (bad JSON or unterminated at stream
     *  end). The agentic loop uses this to send the model one corrective retry. */
    var malformed = false
        private set

    fun accept(chunk: String) {
        if (call != null) return   // post-call text is dropped (one call per step)
        buf.append(chunk)
        process()
    }

    /** Flush at stream end: release held-back text that turned out not to be a marker. */
    fun finish() {
        if (inCall) malformed = true   // unterminated block: dropped, never spoken
        if (call == null && !inCall && buf.isNotEmpty()) {
            onText(buf.toString())
        }
        buf.setLength(0)
    }

    private fun process() {
        while (true) {
            if (inCall) {
                val name = lenientName
                if (name == null) {
                    val end = buf.indexOf(CLOSE)
                    if (end < 0) return   // wait for the rest of the block
                    call = ToolCallParser.parse(buf.substring(0, end))
                } else {
                    val end = jsonObjectEnd(buf)
                    if (end < 0) return   // wait for the closing brace
                    val body = buf.substring(0, end + 1)
                    call = ToolCallParser.parse(
                        if (body.contains("\"name\"")) body
                        else "{\"name\": \"$name\", \"arguments\": $body}",
                    )
                }
                if (call == null) malformed = true
                buf.setLength(0)
                inCall = false
                lenientName = null
                return                // one call per step; trailing text is dropped via accept()
            }
            val start = buf.indexOf(OPEN)
            val lenient = lenientOpen()
            if (lenient != null && (start < 0 || lenient.first < start)) {
                val (at, name, bodyStart) = lenient
                if (at > 0) onText(buf.substring(0, at))
                buf.delete(0, bodyStart)
                inCall = true
                lenientName = name
                continue
            }
            if (start >= 0) {
                if (start > 0) onText(buf.substring(0, start))
                buf.delete(0, start + OPEN.length)
                inCall = true
                continue
            }
            // Emit everything except the longest tail that could still grow into a marker.
            val hold = maxOf(longestSuffixThatPrefixesMarker(), lenientHold())
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

    /** Earliest complete `[<tool>]{` in the buffer: (index of '[', tool name, index of '{'). */
    private fun lenientOpen(): Triple<Int, String, Int>? {
        if (toolNames.isEmpty()) return null
        var from = 0
        while (true) {
            val open = buf.indexOf("[", from)
            if (open < 0) return null
            val close = buf.indexOf("]", open + 1)
            if (close < 0) return null
            val name = buf.substring(open + 1, close)
            if (name in toolNames) {
                var i = close + 1
                while (i < buf.length && buf[i].isWhitespace()) i++
                if (i < buf.length && buf[i] == '{') return Triple(open, name, i)
                if (i >= buf.length) return null   // still waiting for '{' (held by lenientHold)
            }
            from = open + 1
        }
    }

    /** Length of a tail that may still become `[<tool>]{`: an open '[' whose text so far is a
     *  prefix of a tool name, or a complete `[<tool>]` waiting for its '{'. */
    private fun lenientHold(): Int {
        if (toolNames.isEmpty()) return 0
        val open = buf.lastIndexOf("[")
        if (open < 0) return 0
        val tail = buf.substring(open + 1)
        val close = tail.indexOf(']')
        if (close < 0) {
            return if (toolNames.any { it.startsWith(tail) }) buf.length - open else 0
        }
        val name = tail.substring(0, close)
        val rest = tail.substring(close + 1)
        return if (name in toolNames && rest.isBlank()) buf.length - open else 0
    }

    /** Index of the '}' closing the object that starts at s[0], or -1 if not complete yet. */
    private fun jsonObjectEnd(s: CharSequence): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in s.indices) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    private fun StringBuilder.indexOf(s: String): Int = indexOf(s, 0)

    companion object {
        const val OPEN = "[TOOL_CALL]"
        const val CLOSE = "[/TOOL_CALL]"
    }
}
