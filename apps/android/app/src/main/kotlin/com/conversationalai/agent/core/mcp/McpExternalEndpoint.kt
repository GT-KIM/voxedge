package com.conversationalai.agent.core.mcp

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional external MCP transport for the in-app servers: newline-delimited JSON-RPC over an
 * Android local socket in the abstract namespace (no network permission, no TCP port, nothing
 * reachable off the device). A host MCP client attaches through adb:
 *
 *     adb forward tcp:7777 localabstract:voxedge-mcp-tools
 *     python tools/mcp/mcp_client.py --port 7777 tools/list
 *
 * Off by default; started from the diagnostics panel for debugging and inspection. The servers are
 * the SAME objects the speech loop uses (same tool registry, same confirmation gate), so an
 * external `tools/call` is a real device-tool invocation. For the TTS server the audio comes back
 * inline as an MCP `audio` block (16-bit WAV, base64) because an external caller cannot redeem an
 * in-process PCM handle.
 */
class McpExternalEndpoint(
    private val name: String,
    private val server: McpServer,
    /** Post-process a tools/call result for external delivery (e.g. attach audio). */
    private val externalize: ((JsonRpcResponse) -> JsonRpcResponse)? = null,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: LocalServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile var clients: Int = 0
        private set

    val isRunning: Boolean get() = running.get()

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        val ss = try {
            LocalServerSocket(name)
        } catch (e: Exception) {
            Log.w(TAG, "cannot open local endpoint '$name': ${e.message}")
            return false
        }
        serverSocket = ss
        running.set(true)
        acceptThread = Thread({
            while (running.get()) {
                val client = try { ss.accept() } catch (e: Exception) { break }
                Thread({ serve(client) }, "mcp-ext-$name-client").start()
            }
        }, "mcp-ext-$name").also { it.isDaemon = true; it.start() }
        Log.i(TAG, "external MCP endpoint '$name' listening (abstract local socket)")
        return true
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        Log.i(TAG, "external MCP endpoint '$name' stopped")
    }

    private fun serve(client: LocalSocket) {
        clients += 1
        try {
            val reader = BufferedReader(InputStreamReader(client.inputStream, Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(client.outputStream, Charsets.UTF_8))
            while (running.get()) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val resp = server.handleText(line) ?: continue
                val out = externalize?.invoke(resp) ?: resp
                writer.write(server.encodeReply(out))
                writer.write("\n")
                writer.flush()
            }
        } catch (e: Exception) {
            Log.i(TAG, "external client on '$name' closed: ${e.message}")
        } finally {
            clients -= 1
            runCatching { client.close() }
        }
    }

    companion object {
        private const val TAG = "McpExternal"
        const val TOOLS_SOCKET = "voxedge-mcp-tools"
        const val TTS_SOCKET = "voxedge-mcp-tts"
        const val ASR_SOCKET = "voxedge-mcp-asr"

        /** Endpoint for the device-tool registry's server (results need no post-processing). */
        fun forTools(server: McpServer) = McpExternalEndpoint(TOOLS_SOCKET, server)

        /** Endpoint for an ASR server. No post-processing: the caller sends the utterance inline
         *  (`audio_wav`, base64 16-bit WAV) and the transcript is plain text already. */
        fun forAsr(asr: McpAsr) = McpExternalEndpoint(ASR_SOCKET, asr.server)

        /** Endpoint for a TTS server: swaps the in-process `pcm_ref` for an inline WAV audio block. */
        fun forTts(tts: McpTts) = McpExternalEndpoint(TTS_SOCKET, tts.server) { resp -> attachAudio(resp, tts) }

        /** Pure (given a pcm resolver): add an MCP `audio` content block for a speak result. */
        fun attachAudio(resp: JsonRpcResponse, tts: McpTts): JsonRpcResponse {
            val result = resp.result ?: return resp
            val sc = Json.obj(result["structuredContent"]) ?: return resp
            val ref = Json.str(sc["pcm_ref"]) ?: return resp
            val pcm = tts.takePcm(ref) ?: return resp
            val wav = McpTts.wavBytes(pcm)
            val content = Json.arr(result["content"]).orEmpty() + linkedMapOf<String, Any?>(
                "type" to "audio",
                "data" to java.util.Base64.getEncoder().encodeToString(wav),
                "mimeType" to "audio/wav",
            )
            val patched = LinkedHashMap(result)
            patched["content"] = content
            patched["structuredContent"] = LinkedHashMap(sc).also { it.remove("pcm_ref"); it["audio_bytes"] = wav.size }
            return JsonRpcResponse(resp.id, result = patched)
        }
    }
}
