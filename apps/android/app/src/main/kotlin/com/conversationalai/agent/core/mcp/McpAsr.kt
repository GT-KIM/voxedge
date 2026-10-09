package com.conversationalai.agent.core.mcp

import com.conversationalai.agent.asr.AsrEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * The captured-utterance -> ASR boundary as MCP: one endpointed utterance becomes a `tools/call`
 * of [TOOL_TRANSCRIBE] on this ASR server, and the transcript comes back as the result's text
 * block plus `structuredContent` (text, timing, engine). Mirrors [McpTts] in the other direction:
 *
 *  - in-process (the speech loop): audio travels by handle (`pcm_ref`), never inlined in JSON,
 *    so the ASR leg costs the same ~1 ms encode/decode as a `speak` call;
 *  - external endpoint (host client over adb): the caller cannot hand over a handle, so it sends
 *    the utterance inline as a base64 16-bit PCM WAV in the `audio_wav` argument.
 *
 * VAD endpointing, capture, and enhancement stay outside MCP; the unit is the finished utterance,
 * exactly what [AsrEngine.transcribe] already takes. The server wraps whichever engine the loop
 * uses (the owned engine by default, or the platform recognizer when selected for testing); an
 * optional `engine` argument lets an external caller pick one explicitly for comparison runs.
 */
class McpAsr(
    private val asr: AsrEngine,
    /** Resolve an explicit `engine` argument ("owned" | "platform") to an engine, or null when it
     *  is not available (e.g. no on-device language pack). Null resolver = argument not supported. */
    private val selectEngine: ((String) -> AsrEngine?)? = null,
    /** Apply a `language` argument ("ko" | "en") to the engines before transcribing. Returns ok. */
    private val setLanguage: ((String) -> Boolean)? = null,
) {
    class TranscribeResult(val text: String, val asrMs: Long, val engine: String)

    private val pcmByRef = ConcurrentHashMap<String, Pair<FloatArray, Int>>()
    val server: McpServer
    val client: McpClient

    init {
        val (c, s) = InProcessTransport.pair()
        server = McpServer(
            name = SERVER_NAME,
            version = SERVER_VERSION,
            tools = listOf(McpToolHandler(TRANSCRIBE_DESCRIPTOR) { args -> transcribeHandler(args) }),
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

    @Volatile var lastError: String? = null
        private set

    /**
     * Loop side: hand one endpointed utterance to ASR through MCP. Returns null on a protocol or
     * tool error (the caller treats it as "nothing heard"); the failure text is in [lastError].
     * An empty transcript is a normal result with empty text.
     */
    fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
        utteranceId: String,
        generationId: Long? = null,
        /** Explicit engine / language for comparison runs (diagnostics); null = the loop's current. */
        engine: String? = null,
        language: String? = null,
    ): TranscribeResult? {
        val ref = "pcm:$utteranceId:${System.nanoTime()}"
        pcmByRef[ref] = samples to sampleRate
        val args = linkedMapOf<String, Any?>(
            "pcm_ref" to ref,
            "sample_rate" to sampleRate,
            "utterance_id" to utteranceId,
        )
        if (generationId != null) args["generation_id"] = generationId
        if (engine != null) args["engine"] = engine
        if (language != null) args["language"] = language
        val r = try {
            client.callTool(TOOL_TRANSCRIBE, args)
        } catch (e: McpClient.McpException) {
            pcmByRef.remove(ref)
            lastError = e.message
            return null
        }
        pcmByRef.remove(ref)   // the handler consumed it; defensive for error paths
        if (r.isError) {
            lastError = r.text
            return null
        }
        val sc = r.structuredContent ?: run { lastError = "transcribe result has no structuredContent"; return null }
        return TranscribeResult(
            text = Json.str(sc["text"]) ?: r.text,
            asrMs = Json.long(sc["asr_ms"]) ?: 0L,
            engine = Json.str(sc["engine"]) ?: asr.name(),
        )
    }

    private fun transcribeHandler(args: Map<String, Any?>): McpCallResult {
        val utteranceId = Json.str(args["utterance_id"]) ?: "u?"
        // Audio: by handle (in-process) or inline WAV (external caller). Exactly one is expected.
        val ref = Json.str(args["pcm_ref"])
        val wavB64 = Json.str(args["audio_wav"])
        val (samples, sampleRate) = when {
            ref != null -> pcmByRef.remove(ref)
                ?: return McpCallResult("pcm_ref '$ref' not found", isError = true)
            wavB64 != null -> {
                val bytes = try {
                    java.util.Base64.getDecoder().decode(wavB64)
                } catch (e: IllegalArgumentException) {
                    return McpCallResult("audio_wav is not valid base64", isError = true)
                }
                wavSamples(bytes) ?: return McpCallResult("audio_wav is not a 16-bit PCM WAV", isError = true)
            }
            else -> return McpCallResult("missing audio: pass 'pcm_ref' or 'audio_wav'", isError = true)
        }
        if (samples.isEmpty()) return McpCallResult("audio is empty", isError = true)

        val engine = when (val want = Json.str(args["engine"])) {
            null -> asr
            else -> {
                val resolve = selectEngine ?: return McpCallResult("'engine' selection is not supported here", isError = true)
                resolve(want) ?: return McpCallResult("engine '$want' is not available", isError = true)
            }
        }
        Json.str(args["language"])?.let { lang ->
            val apply = setLanguage ?: return McpCallResult("'language' selection is not supported here", isError = true)
            if (!apply(lang)) return McpCallResult("language '$lang' could not be applied", isError = true)
        }

        val t0 = System.nanoTime()
        val text = try {
            // One recognizer per engine: an external call must not decode concurrently with the
            // live loop's utterance (same rule as the TTS server's SYNTH_LOCK).
            synchronized(ASR_LOCK) { engine.transcribe(samples, sampleRate) }
        } catch (e: Exception) {
            return McpCallResult(e.message ?: e::class.java.simpleName, isError = true)
        }
        val asrMs = (System.nanoTime() - t0) / 1_000_000
        return McpCallResult(
            text = text,
            structuredContent = linkedMapOf(
                "text" to text,
                "asr_ms" to asrMs,
                "engine" to engine.name(),
                "sample_rate" to sampleRate,
                "num_samples" to samples.size,
                "utterance_id" to utteranceId,
            ),
        )
    }

    companion object {
        const val SERVER_NAME = "voxedge-asr"
        const val SERVER_VERSION = "1"
        const val TOOL_TRANSCRIBE = "transcribe"
        private val ASR_LOCK = Any()

        /**
         * Decode a RIFF/WAVE container holding 16-bit PCM (mono, or multi-channel averaged to
         * mono) into float samples in [-1,1] plus the sample rate. Walks the chunk list, so a
         * `LIST` chunk before `data` is fine. Returns null for anything else. Pure; JVM-tested.
         */
        fun wavSamples(bytes: ByteArray): Pair<FloatArray, Int>? {
            if (bytes.size < 12 || !tag(bytes, 0, "RIFF") || !tag(bytes, 8, "WAVE")) return null
            var pos = 12
            var channels = 0
            var sampleRate = 0
            var bits = 0
            var format = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = le32(bytes, pos + 4)
                val body = pos + 8
                if (size < 0 || body + size > bytes.size) return null
                when (id) {
                    "fmt " -> {
                        if (size < 16) return null
                        format = le16(bytes, body)
                        channels = le16(bytes, body + 2)
                        sampleRate = le32(bytes, body + 4)
                        bits = le16(bytes, body + 14)
                    }
                    "data" -> {
                        if (format != 1 || bits != 16 || channels < 1 || sampleRate <= 0) return null
                        val frames = size / (2 * channels)
                        val out = FloatArray(frames)
                        var p = body
                        for (i in 0 until frames) {
                            var acc = 0f
                            for (c in 0 until channels) {
                                acc += (le16(bytes, p).toShort().toInt() / 32768f)
                                p += 2
                            }
                            out[i] = acc / channels
                        }
                        return out to sampleRate
                    }
                }
                pos = body + size + (size and 1)   // chunks are word-aligned
            }
            return null
        }

        private fun tag(b: ByteArray, at: Int, s: String) = String(b, at, 4, Charsets.US_ASCII) == s
        private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

        val TRANSCRIBE_DESCRIPTOR = McpToolDescriptor(
            name = TOOL_TRANSCRIBE,
            description = "Transcribe one endpointed mono utterance (audio by PCM handle in-process, or inline 16-bit WAV) and return the text.",
            inputSchema = linkedMapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "pcm_ref" to linkedMapOf("type" to "string", "description" to "in-process PCM handle (float mono)"),
                    "audio_wav" to linkedMapOf("type" to "string", "description" to "base64 16-bit PCM WAV (external callers)"),
                    "sample_rate" to linkedMapOf("type" to "integer", "description" to "sample rate of the pcm_ref audio"),
                    "language" to linkedMapOf("type" to "string", "description" to "ko | en (switches the engine language)"),
                    "engine" to linkedMapOf("type" to "string", "description" to "owned | platform (default: the loop's current engine)"),
                    "utterance_id" to linkedMapOf("type" to "string", "description" to "correlation id for the utterance"),
                    "generation_id" to linkedMapOf("type" to "integer", "description" to "cancel epoch, when already assigned"),
                ),
            ),
        )
    }
}
