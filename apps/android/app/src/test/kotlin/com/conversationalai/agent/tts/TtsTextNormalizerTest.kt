package com.conversationalai.agent.tts

import com.conversationalai.agent.core.ClauseSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The text the unicode indexer sees must contain only code points it knows. The reference
 * `_preprocess` table is the contract; the asset-backed test closes the loop from what the
 * segmenter can emit to what the indexer maps (a -1 fails the DLC executes on device).
 * ASCII-only source: non-ASCII via escapes.
 */
class TtsTextNormalizerTest {

    @Test
    fun emDashIsRewrittenAndATrailingDashIsDropped() {
        // Mid-clause dash: the reference table maps it to a hyphen.
        assertEquals("Start with water-drink a glass.", TtsTextNormalizer.forIndexer("Start with water—drink a glass"))
        // A clause the segmenter cut at the dash must not end in "-.".
        assertEquals("Start with water.", TtsTextNormalizer.forIndexer("Start with water—"))
        assertEquals("doing great.", TtsTextNormalizer.forIndexer("doing great – "))
    }

    @Test
    fun curlyQuotesBecomeStraightQuotes() {
        assertEquals("I'm \"fine\", 'ok'.", TtsTextNormalizer.forIndexer("I’m “fine”, ‘ok’"))
        assertEquals("it's.", TtsTextNormalizer.forIndexer("it´s"))
        assertEquals("it's.", TtsTextNormalizer.forIndexer("it`s"))
    }

    @Test
    fun separatorsBecomeSpacesAndWhitespaceCollapses() {
        assertEquals("a b c d e f.", TtsTextNormalizer.forIndexer("a_b[c]d|e/f"))
        assertEquals("tag one.", TtsTextNormalizer.forIndexer("  tag  #one  "))
    }

    @Test
    fun nfkdFoldsEllipsisAndFullwidthPunctuation() {
        assertEquals("well then...", TtsTextNormalizer.forIndexer("well then…"))
        assertEquals("하?", TtsTextNormalizer.forIndexer("하？"))   // 하？ -> jamo + ASCII ?
    }

    @Test
    fun terminalPunctuationAndLanguageFrame() {
        assertEquals("<en>Hello there.</en>", TtsTextNormalizer.frame("Hello there", "en"))
        assertEquals("<ko>한,</ko>", TtsTextNormalizer.frame("한,", "ko"))
        assertEquals("", TtsTextNormalizer.forIndexer("—"))   // nothing speakable left
    }

    @Test
    fun everythingTheSegmenterCanEmitIsMappedByTheIndexer() {
        // Gradle runs unit tests from the module directory; the indexer is a tracked asset.
        val asset = File("src/main/assets/tts/unicode_indexer.json")
        assumeTrue("indexer asset not found at ${asset.absolutePath}", asset.isFile)
        // Plain parse: android.jar's org.json is a stub under JVM unit tests (returns length 0).
        val indexer = asset.readText().trim().removePrefix("[").removeSuffix("]")
            .split(',').map { it.trim().toInt() }.toIntArray()
        assertEquals(65536, indexer.size)

        // Code points an LLM reply plausibly contains: printable ASCII, Latin-1 symbols, general
        // punctuation, CJK punctuation, fullwidth forms, a Hangul sample, Latin letters with
        // diacritics. Each goes through the real segmenter (its sanitize decides what survives),
        // then the normalizer, then the indexer lookup.
        val ranges = listOf(0x20..0x7E, 0xA0..0xFF, 0x2000..0x206F, 0x3000..0x303F, 0xFF00..0xFF65, 0xC0..0x17F)
        val hangul = "먼저 물을 마셔야 해요, 뇌가 깨어나는 거야."
        val samples = ranges.flatMap { r -> r.map { cp -> "ab ${cp.toChar()} cd." } } + hangul
        val unmapped = sortedSetOf<Int>()
        for (sample in samples) {
            val clauses = mutableListOf<String>()
            val seg = ClauseSegmenter(onClause = { clauses.add(it) })
            seg.accept(sample); seg.finish()
            for (clause in clauses) {
                for (ch in TtsTextNormalizer.frame(clause, "en")) {
                    if (indexer[ch.code] < 0) unmapped.add(ch.code)
                }
            }
        }
        // Contract: no punctuation, symbol, digit, space, or Hangul reaches the indexer unmapped.
        // A handful of exotic letters (eth, eng, Hangzhou numerals, kana repeat marks) are not in
        // the indexer; TtsInputBuilder drops those defensively, so only letter categories may
        // remain here.
        val letterTypes = setOf(
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.MODIFIER_LETTER,
            Character.OTHER_LETTER, Character.LETTER_NUMBER,
        ).map { it.toInt() }
        val offending = unmapped.filter { Character.getType(it) !in letterTypes || it in 0x1100..0x11FF }
        assertTrue(
            "segmenter output reaches the indexer with unmapped code points: " +
                offending.joinToString(" ") { "U+%04X".format(it) },
            offending.isEmpty(),
        )
    }
}
