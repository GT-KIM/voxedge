package com.conversationalai.agent.core.mcp

import com.conversationalai.agent.tts.ClauseInputBuilder
import com.conversationalai.agent.tts.TtsEngine
import com.conversationalai.agent.tts.TtsInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The external transport reuses the same server objects via handleText/encodeReply, and ships
 *  TTS audio inline. The local-socket plumbing itself is Android-only and exercised on device. */
class McpExternalEndpointTest {

    private val tts = McpTts(
        object : TtsEngine {
            override fun version() = "fake"
            override fun synthesizeClause(inputs: TtsInputs, k: Int) = FloatArray(4) { 0.5f }
        },
        object : ClauseInputBuilder {
            override fun build(text: String, lang: String, seed: Long) =
                TtsInputs(IntArray(64), FloatArray(1), FloatArray(1), FloatArray(1), FloatArray(1), FloatArray(1))
        },
    )

    @Test
    fun handleTextAnswersRequestsAndSwallowsNotifications() {
        val init = JsonRpcRequest(1, Mcp.METHOD_INITIALIZE, emptyMap()).toJson()
        val resp = tts.server.handleText(init)!!
        assertEquals(Mcp.PROTOCOL_VERSION, resp.result?.get("protocolVersion"))
        assertNull(tts.server.handleText(JsonRpcNotification(Mcp.METHOD_INITIALIZED).toJson()))
        assertTrue(tts.server.initialized)
        val bad = tts.server.handleText("nope")!!
        assertEquals(JsonRpc.PARSE_ERROR, bad.error?.code)
        // encodeReply is what both transports write out: a JSON-RPC 2.0 object with the same id.
        assertEquals(1L, Json.parseObject(tts.server.encodeReply(resp))["id"])
    }

    @Test
    fun externalSpeakResultCarriesInlineWavInsteadOfAPcmHandle() {
        val call = JsonRpcRequest(
            7, Mcp.METHOD_TOOLS_CALL,
            linkedMapOf("name" to McpTts.TOOL_SPEAK, "arguments" to linkedMapOf("text" to "hi", "chunk_id" to "c0")),
        )
        val internal = tts.server.handle(call)
        val external = McpExternalEndpoint.attachAudio(internal, tts)

        val content = Json.arr(external.result!!["content"])!!.mapNotNull { Json.obj(it) }
        assertEquals(listOf("text", "audio"), content.map { it["type"] })
        val audio = content[1]
        assertEquals("audio/wav", audio["mimeType"])
        val wav = java.util.Base64.getDecoder().decode(Json.str(audio["data"]))
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(44 + 4 * 2, wav.size)
        val sc = Json.obj(external.result!!["structuredContent"])!!
        assertNull(sc["pcm_ref"])              // handle is consumed, not leaked to the host
        // In-memory values keep their Kotlin types (Int); the wire form is what external clients see.
        assertEquals(wav.size, sc["audio_bytes"])
        assertEquals(44_100, sc["sample_rate"])
        val wire = Json.parseObject(external.toJson())
        assertEquals(wav.size.toLong(), Json.obj(Json.obj(wire["result"])!!["structuredContent"])!!["audio_bytes"])
    }

    @Test
    fun nonSpeakResultsPassThroughUnchanged() {
        val resp = JsonRpcResponse(3, result = linkedMapOf("tools" to emptyList<Any?>()))
        assertEquals(resp, McpExternalEndpoint.attachAudio(resp, tts))
        val err = JsonRpcResponse(4, error = JsonRpcError(JsonRpc.METHOD_NOT_FOUND, "x"))
        assertEquals(err, McpExternalEndpoint.attachAudio(err, tts))
    }

    @Test
    fun wavBytesEncodeLittleEndianPcm16() {
        val wav = McpTts.wavBytes(floatArrayOf(0f, 1f, -1f), sampleRate = 16_000)
        assertEquals(44 + 6, wav.size)
        // sample rate at offset 24 (LE)
        assertEquals(16_000, (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or ((wav[26].toInt() and 0xFF) shl 16))
        assertEquals(listOf(0, 0, 0xFF, 0x7F, 0x01, 0x80), wav.takeLast(6).map { it.toInt() and 0xFF })
    }
}
