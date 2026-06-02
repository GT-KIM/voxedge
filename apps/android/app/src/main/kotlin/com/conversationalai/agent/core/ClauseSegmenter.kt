package com.conversationalai.agent.core

import java.text.Normalizer

/**
 * Splits a streamed LLM token sequence into short, speakable clauses for the TTS engine — the
 * `Genie decode -> clause stream -> tts.chunk_request` step of the pipeline.
 *
 * Behaviour:
 *  - emits on a STRONG (sentence) boundary always, on a WEAK (clause) boundary once enough has
 *    accumulated, and otherwise when the buffer hits a soft cap (breaking at the last space);
 *  - keeps the FIRST clause short to minimize first-audio latency;
 *  - sanitizes input: drops control/symbol chars and any codepoint > U+FFFF (emoji), so the
 *    unicode_indexer in TtsInputBuilder never sees a UTF-16 surrogate or out-of-range codepoint
 *    (😊 = U+1F60A would otherwise index garbage). Emoji become a space (a break, not glued words).
 *
 * Length is measured in NFKD-normalized units, NOT raw chars: TtsInputBuilder NFKD-decomposes the
 * clause (Korean 가 -> ㄱ+ㅏ, ~2-3x expansion) and REQUIRES the result + <lang></lang> tags fit in
 * T=64 tokens, else build() throws and the clause is dropped (-> "TTS silent / cut off"). The caps
 * below are NFKD-token budgets that leave room for the ~10-char tag overhead within 64.
 *
 * Not thread-safe: feed [accept] from a single producer, then call [finish].
 */
class ClauseSegmenter(
    private val firstClauseMaxTokens: Int = 16,
    private val maxTokens: Int = 46,
    private val weakMinTokens: Int = 12,
    private val onClause: (String) -> Unit,
) {
    private val buf = StringBuilder()
    private var firstEmitted = false

    /** NFKD length = the token count TtsInputBuilder will see (Hangul syllables expand to jamo). */
    private fun tokens(s: CharSequence): Int = Normalizer.normalize(s, Normalizer.Form.NFKD).length

    fun accept(chunk: String) {
        for (ch in sanitize(chunk)) {
            buf.append(ch)
            when {
                ch in STRONG -> emit()
                ch in WEAK && (!firstEmitted || tokens(buf) >= weakMinTokens) -> emit()
                tokens(buf) >= cap() -> emitAtBreak()
            }
        }
    }

    /** Flush whatever remains (assistant text that didn't end on punctuation). */
    fun finish() = emit()

    private fun cap() = if (firstEmitted) maxTokens else firstClauseMaxTokens

    private fun emit() {
        val s = buf.toString().trim()
        buf.setLength(0)
        if (s.isNotEmpty()) { firstEmitted = true; onClause(s) }
    }

    /** Hit the cap with no boundary: break at the last space so a word isn't cut mid-way. */
    private fun emitAtBreak() {
        val s = buf.toString()
        val sp = s.lastIndexOf(' ')
        if (sp in 1 until s.length - 1) {
            val head = s.substring(0, sp).trim()
            val tail = s.substring(sp + 1)
            buf.setLength(0); buf.append(tail)
            if (head.isNotEmpty()) { firstEmitted = true; onClause(head) }
        } else {
            emit()
        }
    }

    private fun sanitize(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val cc = Character.charCount(cp)
            when {
                cp > 0xFFFF -> sb.append(' ')                 // emoji/supplementary -> break
                speakable(cp.toChar()) -> sb.append(s, i, i + cc)
            }
            i += cc
        }
        return sb.toString()
    }

    private fun speakable(c: Char): Boolean =
        c.isLetterOrDigit() || c.isWhitespace() || c in KEEP_PUNCT

    companion object {
        // Sentence enders (always flush): KO/EN/CJK.
        private const val STRONG = ".!?…。！？\n"
        // Clause separators (flush once enough has accumulated).
        private const val WEAK = ",;:、，；："
        // Speakable punctuation kept inside a clause.
        private const val KEEP_PUNCT = ".!?;:,…。！？；：、，'\"()-—~ "
    }
}
