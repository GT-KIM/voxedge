package com.conversationalai.agent.core.mcp

import com.conversationalai.agent.tts.ClauseInputBuilder
import com.conversationalai.agent.tts.TtsEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * The LLM-output -> TTS-input boundary as MCP: each clause the segmenter cuts from the LLM stream
 * becomes a `tools/call` of [TOOL_SPEAK] on this TTS server, and the synthesized audio comes back
 * as a `tools/call` result whose `structuredContent` carries a PCM handle (`pcm_ref`), sample rate,
 * and sample count. Audio bytes travel by handle, never inlined in JSON, which keeps the clause
 * streaming latency path intact (one short request per clause; encode/decode is microseconds
 * against a ~220 ms synthesis).
 *
 * In-process transport by default; the server itself is transport-agnostic, so an external MCP
 * client could drive the same `speak` tool over stdio/socket later.
 */
class McpTts(
    private val tts: TtsEngine,
    private val inputBuilder: ClauseInputBuilder,
) {
    class SpeakResult(val pcm: FloatArray, val synthMs: Long, val chunkId: String)

    private val pcmByRef = ConcurrentHashMap<String, FloatArray>()
    val server: McpServer
    val client: McpClient

    init {
        val (c, s) = InProcessTransport.pair()
        server = McpServer(
            name = SERVER_NAME,
            version = SERVER_VERSION,
            tools = listOf(McpToolHandler(SPEAK_DESCRIPTOR) { args -> speakHandler(args) }),
            transport = s,
        )
        client = McpClient(c, timeoutMs = Mcp.DEFAULT_TIMEOUT_MS)
        client.initialize()
    }

    var tap: McpMessageTap?
        get() = client.tap
        set(value) {
            client.tap = value
            server.tap = value
        }

    /**
     * LLM side: hand one clause to TTS through MCP. Returns null when synthesis failed (the
     * caller logs/drops the clause); the failure text is in [lastError].
     */
    fun speak(
        text: String,
        language: String,
        clauseIndex: Int,
        chunkId: String,
        flowSteps: Int,
        generationId: Long,
    ): SpeakResult? {
        val args = linkedMapOf<String, Any?>(
            "text" to text,
            "language" to language,
            "clause_index" to clauseIndex,
            "chunk_id" to chunkId,
            "flow_steps" to flowSteps,
            "generation_id" to generationId,
        )
        val r = try {
            client.callTool(TOOL_SPEAK, args)
        } catch (e: McpClient.McpException) {
            lastError = e.message
            return null
        }
        if (r.isError) {
            lastError = r.text
            return null
        }
        val sc = r.structuredContent ?: run { lastError = "speak result has no structuredContent"; return null }
        val ref = Json.str(sc["pcm_ref"]) ?: run { lastError = "speak result has no pcm_ref"; return null }
        val pcm = pcmByRef.remove(ref) ?: run { lastError = "pcm_ref '$ref' not found"; return null }
        return SpeakResult(pcm, Json.long(sc["synth_ms"]) ?: 0L, chunkId)
    }

    @Volatile var lastError: String? = null
        private set

    /** Redeem a `pcm_ref` from a speak result (removes it from the store). Used by the external
     *  endpoint, which has no shared memory with its caller and ships audio inline instead. */
    fun takePcm(ref: String): FloatArray? = pcmByRef.remove(ref)

    private fun speakHandler(args: Map<String, Any?>): McpCallResult {
        val text = Json.str(args["text"])?.takeIf { it.isNotBlank() }
            ?: return McpCallResult("missing 'text'", isError = true)
        val language = Json.str(args["language"]) ?: "ko"
        val k = Json.long(args["flow_steps"])?.toInt() ?: DEFAULT_FLOW_STEPS
        val chunkId = Json.str(args["chunk_id"]) ?: "c?"
        val clauseIndex = Json.long(args["clause_index"]) ?: -1L
        val t0 = System.nanoTime()
        // A null return is a native synthesis failure (transient SNPE exec errors happen under
        // HTP contention with the LLM): retry once, then report the failure as a tool error.
        val pcm = try {
            // One native TTS engine per process: never let an external MCP caller synthesize
            // concurrently with the live turn's consumer.
            synchronized(SYNTH_LOCK) {
                val inputs = inputBuilder.build(text, lang = language)
                tts.synthesizeClause(inputs, k = k) ?: tts.synthesizeClause(inputs, k = k)
            }
        } catch (e: Exception) {
            return McpCallResult(e.message ?: e::class.java.simpleName, isError = true)
        } ?: return McpCallResult("native synthesis returned null (after retry)", isError = true)
        val synthMs = (System.nanoTime() - t0) / 1_000_000
        val ref = "pcm:$chunkId:${System.nanoTime()}"
        pcmByRef[ref] = pcm
        return McpCallResult(
            text = "synthesized ${pcm.size} samples",
            structuredContent = linkedMapOf(
                "pcm_ref" to ref,
                "sample_rate" to SAMPLE_RATE,
                "num_samples" to pcm.size,
                "synth_ms" to synthMs,
                "chunk_id" to chunkId,
                "clause_index" to clauseIndex,
                "language" to language,
            ),
        )
    }

    companion object {
        const val SERVER_NAME = "voxedge-tts"
        const val SERVER_VERSION = "1"
        const val TOOL_SPEAK = "speak"
        const val SAMPLE_RATE = 44_100
        const val DEFAULT_FLOW_STEPS = 6
        private val SYNTH_LOCK = Any()

        /** 16-bit mono WAV bytes for [pcm] (for the MCP `audio` content block). Pure; JVM-tested. */
        fun wavBytes(pcm: FloatArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val dataLen = pcm.size * 2
            val out = java.io.ByteArrayOutputStream(44 + dataLen)
            fun le32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
            fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            out.write("RIFF".toByteArray()); le32(36 + dataLen); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
            out.write("data".toByteArray()); le32(dataLen)
            for (v in pcm) le16((v.coerceIn(-1f, 1f) * 32767f).toInt())
            return out.toByteArray()
        }

        val SPEAK_DESCRIPTOR = McpToolDescriptor(
            name = TOOL_SPEAK,
            description = "Synthesize one short spoken clause (<= one TTS chunk) and return a PCM handle.",
            inputSchema = linkedMapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "text" to linkedMapOf("type" to "string", "description" to "speakable clause text"),
                    "language" to linkedMapOf("type" to "string", "description" to "ko | en"),
                    "clause_index" to linkedMapOf("type" to "integer", "description" to "clause position within the turn"),
                    "chunk_id" to linkedMapOf("type" to "string", "description" to "correlation id for the clause"),
                    "flow_steps" to linkedMapOf("type" to "integer", "description" to "flow-matching steps (quality/latency)"),
                    "generation_id" to linkedMapOf("type" to "integer", "description" to "cancel epoch of the turn"),
                ),
                "required" to listOf("text"),
            ),
        )
    }
}
