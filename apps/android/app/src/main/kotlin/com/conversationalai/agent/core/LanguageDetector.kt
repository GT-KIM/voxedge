package com.conversationalai.agent.core

/**
 * Picks the TTS language tag for one clause so the Supertonic <lang>..</lang> wrapper matches the
 * text - feeding a Korean clause as <ko> and an English clause as <en> (mispronounced otherwise).
 *
 * Korean-first heuristic: any Hangul -> "ko" (handles KO clauses with embedded Latin words);
 * otherwise Latin letters -> "en"; otherwise (digits/punctuation only) default to "ko".
 * Uses codepoint integer ranges (not char literals) to keep this source strictly ASCII - non-ASCII
 * literals have tripped the Kotlin compiler on this toolchain.
 */
object LanguageDetector {

    fun detect(text: String): String {
        var hangul = 0
        var latin = 0
        for (c in text) {
            val cp = c.code
            when {
                cp in 0xAC00..0xD7A3 ||   // Hangul syllables
                    cp in 0x1100..0x11FF ||  // Hangul Jamo
                    cp in 0x3130..0x318F -> hangul++  // Hangul compatibility Jamo
                cp in 0x41..0x5A || cp in 0x61..0x7A -> latin++  // A-Z, a-z
            }
        }
        return when {
            hangul > 0 -> "ko"
            latin > 0 -> "en"
            else -> "ko"
        }
    }
}
