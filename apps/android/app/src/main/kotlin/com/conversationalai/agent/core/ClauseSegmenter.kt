package com.conversationalai.agent.core

import java.text.Normalizer

/**
 * Splits a streamed LLM token sequence into short, speakable clauses for the TTS engine — the
 * `Genie decode -> clause stream -> tts.chunk_request` step of the pipeline.
 *
 * PROSODY-FIRST rules (2026-06-12 rework after "the cuts sound awkward" feedback):
 *  - STRONG sentence enders flush — but trailing closers (quotes/brackets) and ender runs
 *    ("...", "!?") stay WITH the sentence instead of dangling into the next clause;
 *  - '.' and ',' BETWEEN DIGITS are content, not boundaries (0.008, 1,000);
 *  - WEAK separators (commas) flush only once a substantial phrase has accumulated — no more
 *    "네," fragments — with a lower threshold for the first clause to keep first-audio fast;
 *  - on budget overflow the break backtracks to the last space, else the last weak punctuation,
 *    and only then hard-cuts (spaceless Korean longer than the budget);
 *  - sanitizes input: drops control/symbol chars and any codepoint > U+FFFF (emoji), so the
 *    unicode_indexer in TtsInputBuilder never sees a UTF-16 surrogate or out-of-range codepoint.
 *
 * Length is measured in NFKD-normalized units, NOT raw chars: TtsInputBuilder NFKD-decomposes the
 * clause (Korean 가 -> ㄱ+ㅏ, ~2-3x expansion) and REQUIRES the result + <lang></lang> tags fit in
 * T=64 tokens, else build() throws and the clause is dropped (-> "TTS silent / cut off"). The caps
 * below are NFKD-token budgets that leave room for the ~10-char tag overhead within 64.
 *
 * Not thread-safe: feed [accept] from a single producer, then call [finish].
 */
class ClauseSegmenter(
    private val firstClauseMaxTokens: Int = 24,
    private val maxTokens: Int = 46,
    private val weakMinTokens: Int = 20,
    private val firstWeakMinTokens: Int = 8,
    private val onClause: (String) -> Unit,
) {
    private val buf = StringBuilder()
    private var firstEmitted = false
    private var pendingStrong = false       // sentence complete; absorbing trailing closers/enders
    private var pendingNumPunct = false     // '.'/',' right after a digit; decide on the next char

    /** NFKD length = the token count TtsInputBuilder will see (Hangul syllables expand to jamo). */
    private fun tokens(s: CharSequence): Int = Normalizer.normalize(s, Normalizer.Form.NFKD).length

    fun accept(chunk: String) {
        for (ch in sanitize(chunk)) feed(ch)
    }

    /** Flush whatever remains (assistant text that didn't end on punctuation). */
    fun finish() {
        pendingStrong = false
        pendingNumPunct = false
        emit()
    }

    private fun feed(ch: Char) {
        // Resolve a deferred digit-adjacent '.'/',' — "0.008"/"1,000" keep it as content;
        // anything else means it really was a boundary.
        if (pendingNumPunct) {
            pendingNumPunct = false
            if (!ch.isDigit()) {
                val punct = buf.lastOrNull()
                if (punct != null && punct in STRONG) {
                    pendingStrong = true
                } else if (tokens(buf) >= weakThreshold()) {
                    emit()
                }
            }
        }
        // A completed sentence absorbs trailing closers and ender runs ("...", "!?", '"').
        if (pendingStrong) {
            if (ch in CLOSERS || ch in STRONG) {
                buf.append(ch)
                return
            }
            pendingStrong = false
            emit()
        }
        if (ch.isWhitespace() && buf.isEmpty()) return   // no leading spaces on a fresh clause
        buf.append(ch)
        when {
            (ch == '.' || ch == ',' || ch == ':') && bufCharBeforeLastIsDigit() -> pendingNumPunct = true
            ch in STRONG -> pendingStrong = true
            ch in WEAK -> if (tokens(buf) >= weakThreshold()) emit()
            tokens(buf) >= cap() -> emitAtBreak()
        }
    }

    private fun bufCharBeforeLastIsDigit(): Boolean =
        buf.length >= 2 && buf[buf.length - 2].isDigit()

    private fun weakThreshold() = if (firstEmitted) weakMinTokens else firstWeakMinTokens

    private fun cap() = if (firstEmitted) maxTokens else firstClauseMaxTokens

    private fun emit() {
        val s = buf.toString().trim()
        buf.setLength(0)
        if (s.isNotEmpty()) { firstEmitted = true; onClause(s) }
    }

    /** Hit the cap with no boundary: backtrack to the last space, else the last weak punctuation,
     *  and only then hard-cut (a single spaceless run longer than the whole budget). */
    private fun emitAtBreak() {
        val s = buf.toString()
        var cut = s.lastIndexOf(' ')
        if (cut < 1) {
            for (i in s.length - 2 downTo 1) {
                if (s[i] in WEAK) { cut = i + 1; break }   // keep the punct with the head
            }
        }
        if (cut in 1 until s.length) {
            val head = s.substring(0, cut).trim()
            val tail = s.substring(cut).trimStart()
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
        // Sentence enders (flush, after absorbing trailing closers): KO/EN/CJK.
        private const val STRONG = ".!?…。！？\n"
        // Clause separators (flush once a substantial phrase has accumulated).
        private const val WEAK = ",;:、，；："
        // Closing punctuation that belongs to the JUST-ENDED sentence, not the next clause.
        private const val CLOSERS = "\"')]}”’»」』"
        // Speakable punctuation kept inside a clause.
        private const val KEEP_PUNCT = ".!?;:,…。！？；：、，'\"()-—~ "
    }
}
