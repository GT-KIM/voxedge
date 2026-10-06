package com.conversationalai.agent.core.mcp

import com.conversationalai.agent.tts.ClauseInputBuilder
import com.conversationalai.agent.tts.TtsEngine
import com.conversationalai.agent.tts.TtsInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTtsTest {

    private class FakeBuilder : ClauseInputBuilder {
        val built = mutableListOf<Pair<String, String>>()
        override fun build(text: String, lang: String, seed: Long): TtsInputs {
            built += text to lang
            return TtsInputs(IntArray(64), FloatArray(64), FloatArray(1), FloatArray(1), FloatArray(1), FloatArray(1))
        }
    }

    private class FakeTts(private val failures: Int = 0) : TtsEngine {
        var calls = 0
        var lastK = 0
        override fun version() = "fake"
        override fun synthesizeClause(inputs: TtsInputs, k: Int): FloatArray? {
            calls += 1
            lastK = k
            return if (calls <= failures) null else FloatArray(3072) { 0.1f }
        }
    }

    @Test
    fun clauseGoesThroughToolsCallAndPcmComesBackByHandle() {
        val tts = FakeTts()
        val builder = FakeBuilder()
        val mcp = McpTts(tts, builder)
        val methods = mutableListOf<String>()
        mcp.tap = McpMessageTap { dir, method, _, _, _ -> if (dir == "out" && method != null) methods += method }

        val r = mcp.speak("안녕하세요.", "ko", clauseIndex = 0, chunkId = "c0", flowSteps = 4, generationId = 9)
        assertNotNull(r)
        assertEquals(3072, r!!.pcm.size)
        assertEquals("c0", r.chunkId)
        assertEquals(listOf("안녕하세요." to "ko"), builder.built)
        assertEquals(4, tts.lastK)
        assertEquals(listOf(Mcp.METHOD_TOOLS_CALL), methods)
        assertEquals(listOf(McpTts.TOOL_SPEAK), mcp.client.listTools().map { it.name })
    }

    @Test
    fun transientNativeFailureIsRetriedOnceServerSide() {
        val tts = FakeTts(failures = 1)
        val r = McpTts(tts, FakeBuilder()).speak("hi", "en", 0, "c0", 6, 1)
        assertNotNull(r)
        assertEquals(2, tts.calls)
    }

    @Test
    fun persistentFailureReturnsNullWithTheServerError() {
        val tts = FakeTts(failures = 5)
        val mcp = McpTts(tts, FakeBuilder())
        assertNull(mcp.speak("hi", "en", 0, "c0", 6, 1))
        assertEquals(2, tts.calls)
        assertTrue(mcp.lastError!!.contains("after retry"))
    }

    @Test
    fun blankTextIsAToolErrorNotASynthesisAttempt() {
        val tts = FakeTts()
        val mcp = McpTts(tts, FakeBuilder())
        assertNull(mcp.speak("   ", "en", 0, "c0", 6, 1))
        assertEquals(0, tts.calls)
    }
}
