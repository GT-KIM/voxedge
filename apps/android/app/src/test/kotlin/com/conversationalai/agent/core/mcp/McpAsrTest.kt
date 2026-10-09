package com.conversationalai.agent.core.mcp

import com.conversationalai.agent.asr.AsrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpAsrTest {

    private class FakeAsr(private val label: String = "fake-asr", private val reply: String = "안녕하세요") : AsrEngine {
        var calls = 0
        var lastSamples: FloatArray? = null
        var lastRate = 0
        override fun name() = label
        override fun transcribe(samples: FloatArray, sampleRate: Int): String {
            calls += 1
            lastSamples = samples
            lastRate = sampleRate
            return reply
        }
    }

    @Test
    fun utteranceGoesThroughToolsCallByHandleAndTextComesBack() {
        val asr = FakeAsr()
        val mcp = McpAsr(asr)
        val methods = mutableListOf<String>()
        mcp.tap = McpMessageTap { dir, method, _, _, _ -> if (dir == "out" && method != null) methods += method }

        val samples = FloatArray(1600) { 0.01f * (it % 7) }
        val r = mcp.transcribe(samples, 16_000, utteranceId = "u3")
        assertNotNull(r)
        assertEquals("안녕하세요", r!!.text)
        assertEquals("fake-asr", r.engine)
        assertEquals(1, asr.calls)
        assertTrue(asr.lastSamples === samples)   // by handle: the same buffer, no copy
        assertEquals(16_000, asr.lastRate)
        assertEquals(listOf(Mcp.METHOD_TOOLS_CALL), methods)
        assertEquals(listOf(McpAsr.TOOL_TRANSCRIBE), mcp.client.listTools().map { it.name })
    }

    @Test
    fun emptyTranscriptIsANormalResultNotAnError() {
        val mcp = McpAsr(FakeAsr(reply = ""))
        val r = mcp.transcribe(FloatArray(160), 16_000, "u0")
        assertNotNull(r)
        assertEquals("", r!!.text)
        assertNull(mcp.lastError)
    }

    @Test
    fun engineFailureIsAToolErrorAndTheCallerGetsNull() {
        val mcp = McpAsr(object : AsrEngine {
            override fun name() = "broken"
            override fun transcribe(samples: FloatArray, sampleRate: Int): String = throw IllegalStateException("decoder gone")
        })
        assertNull(mcp.transcribe(FloatArray(160), 16_000, "u0"))
        assertEquals("decoder gone", mcp.lastError)
    }

    @Test
    fun externalCallerSendsInlineWavAndGetsTheTranscriptAsText() {
        val asr = FakeAsr(reply = "hello there")
        val mcp = McpAsr(asr)
        val pcm = floatArrayOf(0f, 0.5f, -0.5f, 0.25f)
        val wav = java.util.Base64.getEncoder().encodeToString(McpTts.wavBytes(pcm, sampleRate = 16_000))
        val call = JsonRpcRequest(
            5, Mcp.METHOD_TOOLS_CALL,
            linkedMapOf("name" to McpAsr.TOOL_TRANSCRIBE, "arguments" to linkedMapOf("audio_wav" to wav, "utterance_id" to "host")),
        )
        // The external endpoint feeds the server lines through handleText / encodeReply.
        val resp = mcp.server.handleText(call.toJson())!!
        val wire = Json.parseObject(mcp.server.encodeReply(resp))
        val result = Json.obj(wire["result"])!!
        assertEquals(false, result["isError"])
        val text = Json.arr(result["content"])!!.mapNotNull { Json.obj(it) }.single()
        assertEquals("text", text["type"])
        assertEquals("hello there", text["text"])
        val sc = Json.obj(result["structuredContent"])!!
        assertEquals("hello there", sc["text"])
        assertEquals(16_000L, sc["sample_rate"])
        assertEquals(4L, sc["num_samples"])
        assertEquals("host", sc["utterance_id"])
        assertEquals(16_000, asr.lastRate)
        assertEquals(4, asr.lastSamples!!.size)
        assertEquals(0.5f, asr.lastSamples!![1], 1e-3f)
        assertEquals(-0.5f, asr.lastSamples!![2], 1e-3f)
    }

    @Test
    fun missingOrBadAudioIsAToolErrorWithoutTouchingTheEngine() {
        val asr = FakeAsr()
        val mcp = McpAsr(asr)
        fun call(args: Map<String, Any?>): McpCallResult =
            McpCallResult.fromJson(mcp.server.handle(JsonRpcRequest(1, Mcp.METHOD_TOOLS_CALL, linkedMapOf("name" to McpAsr.TOOL_TRANSCRIBE, "arguments" to args))).result!!)
        assertTrue(call(emptyMap()).isError)
        assertTrue(call(mapOf("pcm_ref" to "pcm:nope")).isError)
        assertTrue(call(mapOf("audio_wav" to "@@not base64@@")).isError)
        assertTrue(call(mapOf("audio_wav" to java.util.Base64.getEncoder().encodeToString("RIFFxxxxWAVE".toByteArray()))).isError)
        assertEquals(0, asr.calls)
    }

    @Test
    fun explicitEngineAndLanguageAreResolvedByTheHostAppHooks() {
        val owned = FakeAsr("owned", "owned heard")
        val platform = FakeAsr("platform", "platform heard")
        val languages = mutableListOf<String>()
        val mcp = McpAsr(
            asr = owned,
            selectEngine = { name -> when (name) { "owned" -> owned; "platform" -> platform; else -> null } },
            setLanguage = { lang -> languages += lang; lang == "en" || lang == "ko" },
        )
        val samples = FloatArray(160)
        assertEquals("platform heard", mcp.transcribe(samples, 16_000, "d", engine = "platform", language = "en")!!.text)
        assertEquals("owned heard", mcp.transcribe(samples, 16_000, "d", engine = "owned")!!.text)
        assertEquals("owned heard", mcp.transcribe(samples, 16_000, "d")!!.text)
        assertEquals(listOf("en"), languages)
        assertNull(mcp.transcribe(samples, 16_000, "d", engine = "cloud"))
        assertTrue(mcp.lastError!!.contains("not available"))
        assertNull(mcp.transcribe(samples, 16_000, "d", language = "fr"))
        assertEquals(listOf("en", "fr"), languages)
    }

    @Test
    fun engineSelectionIsRefusedWhenTheHostProvidesNoResolver() {
        val mcp = McpAsr(FakeAsr())
        assertNull(mcp.transcribe(FloatArray(160), 16_000, "d", engine = "platform"))
        assertTrue(mcp.lastError!!.contains("not supported"))
    }

    @Test
    fun wavSamplesRoundTripsWavBytesAndAveragesStereo() {
        val pcm = floatArrayOf(0f, 1f, -1f, 0.5f)
        val (back, rate) = McpAsr.wavSamples(McpTts.wavBytes(pcm, sampleRate = 22_050))!!
        assertEquals(22_050, rate)
        assertEquals(4, back.size)
        assertEquals(0f, back[0], 1e-4f)
        assertEquals(1f, back[1], 1e-3f)
        assertEquals(-1f, back[2], 1e-3f)
        assertEquals(0.5f, back[3], 1e-3f)

        // Hand-built stereo file with a LIST chunk before data: L = 0.5, R = -0.5 -> mono 0.
        val out = java.io.ByteArrayOutputStream()
        fun le32(v: Int) { for (i in 0 until 4) out.write((v shr (8 * i)) and 0xFF) }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); le32(0); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(2); le32(8_000); le32(8_000 * 4); le16(4); le16(16)
        out.write("LIST".toByteArray()); le32(3); out.write(byteArrayOf(1, 2, 3)); out.write(0)   // odd size, padded
        out.write("data".toByteArray()); le32(4); le16(16_384); le16((-16_384) and 0xFFFF)
        val (stereo, stereoRate) = McpAsr.wavSamples(out.toByteArray())!!
        assertEquals(8_000, stereoRate)
        assertEquals(1, stereo.size)
        assertEquals(0f, stereo[0], 1e-6f)

        assertNull(McpAsr.wavSamples(ByteArray(3)))
        assertNull(McpAsr.wavSamples("RIFF....WAVEfmt ".toByteArray()))
    }
}
