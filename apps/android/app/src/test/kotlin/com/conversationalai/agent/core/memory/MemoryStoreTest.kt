package com.conversationalai.agent.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Durable cross-session fact store: remember/recall/forget, key normalization, persistence. */
class MemoryStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store(name: String = "facts.tsv") = MemoryStore(File(tmp.root, name))

    @Test
    fun rememberAndRecall() {
        val m = store()
        assertTrue(m.remember("name", "Kim"))
        assertTrue(m.remember("favorite color", "blue"))
        assertEquals(listOf("name" to "Kim"), m.recall("name"))
        assertEquals(2, m.all().size)
    }

    @Test
    fun keysAreNormalized() {
        val m = store()
        m.remember("My Name", "Kim")
        m.remember("  my   name ", "Lee")   // same slot -> overwrite
        assertEquals(1, m.size())
        assertEquals(listOf("my name" to "Lee"), m.recall("name"))
    }

    @Test
    fun emptyKeyOrValueRejected() {
        val m = store()
        assertFalse(m.remember("", "x"))
        assertFalse(m.remember("k", "  "))
        assertEquals(0, m.size())
    }

    @Test
    fun forgetRemovesAndReportsExistence() {
        val m = store()
        m.remember("name", "Kim")
        assertTrue(m.forget("NAME"))      // normalized
        assertFalse(m.forget("name"))     // already gone
        assertEquals(0, m.size())
    }

    @Test
    fun recallMatchesKeyOrValueSubstring() {
        val m = store()
        m.remember("commute", "8am bus to downtown")
        m.remember("name", "Kim")
        assertEquals(listOf("commute" to "8am bus to downtown"), m.recall("bus"))
        assertEquals(2, m.recall("").size)   // blank query -> all
    }

    @Test
    fun persistsAcrossInstancesIncludingSpecialChars() {
        val f = File(tmp.root, "p.tsv")
        MemoryStore(f).apply {
            remember("note", "line1\nline2\twith tab")
            remember("name", "Kim")
        }
        val reloaded = MemoryStore(f)
        assertEquals("line1\nline2\twith tab", reloaded.recall("note").single().second)
        assertEquals(2, reloaded.size())
    }

    @Test
    fun promptSnapshotIsMostRecentFirstFormatted() {
        val m = store()
        m.remember("name", "Kim")
        m.remember("city", "Seoul")
        m.remember("name", "Lee")   // re-set sorts last
        val snap = m.promptSnapshot(limit = 2)
        assertEquals("- city: Seoul\n- name: Lee", snap)
        assertTrue(store("empty.tsv").promptSnapshot().isEmpty())
    }
}
