package com.conversationalai.agent.tts

import java.text.Normalizer

/**
 * Text normalization in front of the Supertonic unicode indexer: the Kotlin port of
 * tools/tts/supertonic_tts.py `_preprocess`, which the app had skipped.
 *
 * The indexer (assets/tts/unicode_indexer.json) maps em/en dashes, curly quotes, backtick/acute
 * accents and a few ASCII symbols to -1. A -1 in text_ids makes the duration-predictor and
 * text-encoder DLCs fail on the HTP ("dp exec failed", "te exec failed"), the server-side retry
 * fails the same way, and the clause is dropped unspoken (device log, 2026-10-09: 63 drops, all
 * on replies with an em dash). The reference implementation rewrites those code points to ASCII
 * before indexing; this object does the same, in this order:
 *  1. the reference replacement table (dashes -> "-", curly quotes -> straight, separators -> " ");
 *  2. NFKD (also folds the ellipsis to "...", fullwidth punctuation to ASCII, NBSP to space);
 *  3. whitespace collapse, then a trailing dash run is dropped (a clause the segmenter cut at an
 *     em dash would otherwise end in "-." which the model reads as a stray symbol);
 *  4. a terminal punctuation mark if none, then the <lang> frame.
 *
 * Pure Kotlin, ASCII-only source (non-ASCII via escapes), JVM-tested in TtsTextNormalizerTest.
 */
object TtsTextNormalizer {

    // Same table as the reference `_preprocess` (insertion order kept for fidelity).
    private val REPLACEMENTS: List<Pair<String, String>> = listOf(
        "–" to "-",   // en dash
        "‑" to "-",   // non-breaking hyphen
        "—" to "-",   // em dash
        "_" to " ",
        "“" to "\"",  // left double quote
        "”" to "\"",  // right double quote
        "‘" to "'",   // left single quote
        "’" to "'",   // right single quote / apostrophe
        "´" to "'",   // acute accent
        "`" to "'",
        "[" to " ",
        "]" to " ",
        "|" to " ",
        "/" to " ",
        "#" to " ",
    )

    // Java's \s is ASCII-only; \p{Z} adds the Unicode separators (U+2028/2029, U+3000, ...) that
    // Kotlin's isWhitespace() lets through the segmenter but the indexer does not know.
    private val WHITESPACE = Regex("[\\s\\p{Z}]+")
    private val TRAILING_DASHES = Regex("[\\s\\p{Z}-]+$")
    private val ENDS_WITH_PUNCT = Regex("[.!?;:,]$")

    /** Reference preprocessing without the language frame: the text the indexer will see. */
    fun forIndexer(text: String): String {
        // Replacements first: NFKD would turn the acute accent (U+00B4) into space + combining
        // mark before the table could see it. The reference does NFKD first and misses that case.
        var t = text
        for ((from, to) in REPLACEMENTS) t = t.replace(from, to)
        t = Normalizer.normalize(t, Normalizer.Form.NFKD)
        t = t.replace(WHITESPACE, " ").trim()
        t = t.replace(TRAILING_DASHES, "")
        if (t.isNotEmpty() && !ENDS_WITH_PUNCT.containsMatchIn(t)) t += "."
        return t
    }

    /** [forIndexer] wrapped in the `<lang>...</lang>` frame the text encoder expects. */
    fun frame(text: String, lang: String): String = "<$lang>${forIndexer(text)}</$lang>"
}
