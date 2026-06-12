package com.conversationalai.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prosody-first clause chunking: natural boundaries, no mid-number/mid-word/dangling-quote cuts. */
class ClauseSegmenterTest {

    private fun segment(text: String, chunkSize: Int = 3): List<String> {
        val out = mutableListOf<String>()
        val seg = ClauseSegmenter(onClause = { out.add(it) })
        text.chunked(chunkSize).forEach { seg.accept(it) }   // stream in LLM-like small chunks
        seg.finish()
        return out
    }

    // ASCII-only test source: Hangul via escapes.
    private val neAlgesseumnida = "\uB124, \uC54C\uACA0\uC2B5\uB2C8\uB2E4."                       // short ack
    private val greetingKo = "\uC548\uB155\uD558\uC138\uC694, \uB9CC\uB098\uC11C \uBC18\uAC11\uC2B5\uB2C8\uB2E4."     // greeting with comma

    @Test
    fun decimalsAndThousandsAreNotBoundaries() {
        // Short enough to fit the first-clause budget: numbers must never split a clause.
        assertEquals(listOf("rate is 0.008 today."), segment("rate is 0.008 today."))
        assertEquals(listOf("pay 1,000 won at 3:30."), segment("pay 1,000 won at 3:30."))
    }

    @Test
    fun trailingClosersStayWithTheirSentence() {
        assertEquals(
            listOf("He said \"stop right now!\"", "and then he left."),
            segment("He said \"stop right now!\" and then he left."),
        )
    }

    @Test
    fun ellipsisRunsStayTogether() {
        assertEquals(
            listOf("well then...", "maybe another day."),
            segment("well then... maybe another day."),
        )
    }

    @Test
    fun tinyFirstFragmentIsNotEmittedOnComma() {
        // "네," is far below the first-clause weak threshold -> one natural clause.
        assertEquals(listOf(neAlgesseumnida), segment(neAlgesseumnida))
    }

    @Test
    fun substantialFirstPhraseSplitsAtTheComma() {
        val clauses = segment(greetingKo)
        assertEquals(2, clauses.size)
        assertTrue(clauses[0].endsWith(","))
        assertTrue(clauses[1].endsWith("."))
    }

    @Test
    fun capOverflowBreaksAtASpaceNeverMidWord() {
        val text = "this is a very long english sentence without any punctuation that keeps " +
            "going and going far beyond the clause budget limit"
        val clauses = segment(text)
        assertTrue(clauses.size >= 2)
        // No clause may end or start mid-word: every boundary char pair must include a space
        // in the original, which trimming removed — so re-joining with spaces restores the text.
        assertEquals(text, clauses.joinToString(" "))
    }

    @Test
    fun nothingIsLostAcrossChunkSizes() {
        val text = "First sentence here. Second one, with a clause, follows it. And a third!"
        val whole = segment(text, chunkSize = 1000)
        val tiny = segment(text, chunkSize = 1)
        assertEquals(whole, tiny)
        assertEquals(text.replace(Regex("\\s+"), " "), whole.joinToString(" "))
    }

    @Test
    fun commasSplitRestClausesOnlyAfterSubstantialText() {
        val text = "Okay. Then comes a longer second thought, which continues onward."
        val clauses = segment(text)
        assertEquals("Okay.", clauses[0])
        // The comma split happens only once the phrase is substantial (>= 20 NFKD tokens).
        assertEquals("Then comes a longer second thought,", clauses[1])
        assertEquals("which continues onward.", clauses[2])
    }
}
