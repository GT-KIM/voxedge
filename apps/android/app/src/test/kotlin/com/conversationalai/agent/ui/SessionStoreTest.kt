package com.conversationalai.agent.ui

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreTest {

    private fun store() = SessionStore(Files.createTempDirectory("sessions").toFile())

    // ASCII-only source (toolchain rule): Hangul via escapes (U+D0C0 U+C774 U+BA38 = "timer").
    private val hangul = "\uD0C0\uC774\uBA38"

    @Test
    fun roundTripsItemsIncludingEscapesAndUnicode() {
        val s = store()
        val userText = "set a \"3 minute\" $hangul\nplease"
        val items = listOf(
            TranscriptItem("u0", TranscriptRole.USER, userText),
            TranscriptItem(
                "a1", TranscriptRole.ASSISTANT, "$hangul started!",
                interrupted = true, spokenContent = "ok", tools = listOf("set_timer(ok)"),
                meta = "asr 0ms / TTFT 76ms",
            ),
        )
        s.save("s1", "timer session", 1000L, items)

        val loaded = s.load("s1")
        assertEquals(2, loaded.size)
        assertEquals(TranscriptRole.USER, loaded[0].role)
        assertEquals(userText, loaded[0].text)
        assertEquals("$hangul started!", loaded[1].text)
        assertTrue(loaded[1].interrupted)
        assertEquals(listOf("set_timer(ok)"), loaded[1].tools)
        assertEquals("asr 0ms / TTFT 76ms", loaded[1].meta)
    }

    @Test
    fun listsSessionsNewestFirstWithTitles() {
        val s = store()
        s.save("old", "first chat", 1000L, listOf(TranscriptItem("u0", TranscriptRole.USER, "hi")))
        s.save("new", "second chat", 2000L, listOf(TranscriptItem("u0", TranscriptRole.USER, "yo")))

        val metas = s.list()
        assertEquals(listOf("new", "old"), metas.map { it.id })
        assertEquals("second chat", metas[0].title)
        assertEquals(2000L, metas[0].updatedMs)
    }

    @Test
    fun loadOfMissingSessionIsEmptyAndDeleteWorks() {
        val s = store()
        assertEquals(emptyList<TranscriptItem>(), s.load("nope"))
        s.save("x", "t", 1L, listOf(TranscriptItem("u0", TranscriptRole.USER, "hi")))
        s.delete("x")
        assertEquals(emptyList<TranscriptItem>(), s.load("x"))
        assertTrue(s.list().isEmpty())
    }
}
