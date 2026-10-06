package com.conversationalai.agent.core.mcp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Model Context Protocol (MCP) core: a tools-capable server, a client, and the transport seam.
 *
 * Every tool boundary in the speech loop speaks MCP over JSON-RPC 2.0 messages:
 *   - the 13 device tools are served by one MCP server (McpToolBridge),
 *   - the LLM-output -> TTS-input clause handoff is a `tools/call` on a TTS MCP server (McpTts).
 * The default transport is in-process ([InProcessTransport]) — the runtime stays offline and has no
 * local network bridge — but the wire format is real MCP, so a stdio/socket transport can be
 * attached later without touching the servers or callers.
 */
object Mcp {
    /** MCP protocol revision this implementation speaks. */
    const val PROTOCOL_VERSION = "2025-06-18"

    const val METHOD_INITIALIZE = "initialize"
    const val METHOD_INITIALIZED = "notifications/initialized"
    const val METHOD_PING = "ping"
    const val METHOD_TOOLS_LIST = "tools/list"
    const val METHOD_TOOLS_CALL = "tools/call"

    /** Default per-request wait before the client gives up. Generous: a TTS clause under HTP
     *  contention has been observed to take > 1 s; the in-process path normally answers inline. */
    const val DEFAULT_TIMEOUT_MS = 15_000L
}

/** `tools/list` entry: name, description, and a JSON Schema for the arguments object. */
data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
) {
    fun toJsonMap(): Map<String, Any?> =
        linkedMapOf("name" to name, "description" to description, "inputSchema" to inputSchema)

    companion object {
        fun fromJson(m: Map<String, Any?>): McpToolDescriptor = McpToolDescriptor(
            name = Json.str(m["name"]) ?: "",
            description = Json.str(m["description"]) ?: "",
            inputSchema = Json.obj(m["inputSchema"]) ?: emptyMap(),
        )
    }
}

/** `tools/call` result: text content blocks (model-facing), optional structured payload, error flag. */
data class McpCallResult(
    val text: String,
    val isError: Boolean = false,
    val structuredContent: Map<String, Any?>? = null,
    /** Additional MCP content blocks after the text block (e.g. an `audio` block). */
    val extraContent: List<Map<String, Any?>> = emptyList(),
) {
    fun toJsonMap(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "content" to (listOf(linkedMapOf<String, Any?>("type" to "text", "text" to text)) + extraContent),
            "isError" to isError,
        )
        if (structuredContent != null) m["structuredContent"] = structuredContent
        return m
    }

    companion object {
        fun fromJson(m: Map<String, Any?>): McpCallResult {
            val texts = Json.arr(m["content"]).orEmpty()
                .mapNotNull { Json.obj(it) }
                .filter { Json.str(it["type"]) == "text" }
                .mapNotNull { Json.str(it["text"]) }
            return McpCallResult(
                text = texts.joinToString("\n"),
                isError = m["isError"] == true,
                structuredContent = Json.obj(m["structuredContent"]),
            )
        }
    }
}

/** One server-side tool: descriptor plus the call handler. Handlers must not throw; the server
 *  guards anyway and turns exceptions into `isError` results. */
class McpToolHandler(
    val descriptor: McpToolDescriptor,
    val call: (arguments: Map<String, Any?>) -> McpCallResult,
)

/** Bidirectional message pipe. [send] delivers one JSON text to the peer; the peer's messages
 *  arrive on [onMessage]. Delivery may be synchronous (in-process) or asynchronous (I/O). */
interface McpTransport {
    fun send(json: String)
    var onMessage: ((String) -> Unit)?
}

/** Two linked endpoints inside one process; `send` on one invokes `onMessage` on the other. */
class InProcessTransport private constructor() : McpTransport {
    private lateinit var peer: InProcessTransport
    override var onMessage: ((String) -> Unit)? = null

    override fun send(json: String) {
        peer.onMessage?.invoke(json)
    }

    companion object {
        fun pair(): Pair<InProcessTransport, InProcessTransport> {
            val a = InProcessTransport()
            val b = InProcessTransport()
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}

/** Observer for raw traffic at one endpoint (event log / eval). Arguments are not exposed here. */
fun interface McpMessageTap {
    fun onMessage(direction: String, method: String?, id: Long?, isError: Boolean, bytes: Int)
}

/**
 * Tools-capable MCP server. Stateless apart from the initialize handshake; safe to call from
 * any thread (handlers decide their own thread-safety).
 */
class McpServer(
    val name: String,
    val version: String,
    tools: List<McpToolHandler>,
    private val transport: McpTransport,
    var tap: McpMessageTap? = null,
) {
    private val tools = ConcurrentHashMap<String, McpToolHandler>().apply {
        tools.forEach { put(it.descriptor.name, it) }
    }
    @Volatile var initialized: Boolean = false
        private set

    init {
        transport.onMessage = { text -> handleRaw(text) }
    }

    val toolNames: List<String> get() = tools.keys.sorted()

    fun handleRaw(text: String) {
        handleText(text)?.let { reply(it) }
    }

    /** Transport-independent entry: one incoming JSON text -> the response to send back, or null
     *  for a notification. Used by the in-process transport and by any external endpoint
     *  (McpExternalEndpoint) that hands this server lines from elsewhere. */
    fun handleText(text: String): JsonRpcResponse? {
        val msg = try {
            JsonRpc.decode(text)
        } catch (e: Json.ParseException) {
            return JsonRpcResponse(null, error = JsonRpcError(JsonRpc.PARSE_ERROR, "parse error: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            return JsonRpcResponse(null, error = JsonRpcError(JsonRpc.INVALID_REQUEST, e.message ?: "invalid request"))
        }
        return when (msg) {
            is JsonRpcRequest -> {
                tap?.onMessage("in", msg.method, msg.id, false, text.length)
                handle(msg)
            }
            is JsonRpcNotification -> {
                tap?.onMessage("in", msg.method, null, false, text.length)
                if (msg.method == Mcp.METHOD_INITIALIZED) initialized = true
                null
            }
            is JsonRpcResponse -> null   // a server does not issue requests here
        }
    }

    /** Pure request handler (also unit-testable without a transport). */
    fun handle(req: JsonRpcRequest): JsonRpcResponse = when (req.method) {
        Mcp.METHOD_INITIALIZE -> JsonRpcResponse(
            req.id,
            result = linkedMapOf(
                "protocolVersion" to Mcp.PROTOCOL_VERSION,
                "capabilities" to linkedMapOf("tools" to linkedMapOf("listChanged" to false)),
                "serverInfo" to linkedMapOf("name" to name, "version" to version),
            ),
        )
        Mcp.METHOD_PING -> JsonRpcResponse(req.id, result = emptyMap())
        Mcp.METHOD_TOOLS_LIST -> JsonRpcResponse(
            req.id,
            result = linkedMapOf("tools" to toolNames.map { tools.getValue(it).descriptor.toJsonMap() }),
        )
        Mcp.METHOD_TOOLS_CALL -> callTool(req)
        else -> JsonRpcResponse(req.id, error = JsonRpcError(JsonRpc.METHOD_NOT_FOUND, "unknown method '${req.method}'"))
    }

    private fun callTool(req: JsonRpcRequest): JsonRpcResponse {
        val name = Json.str(req.params?.get("name"))
            ?: return JsonRpcResponse(req.id, error = JsonRpcError(JsonRpc.INVALID_PARAMS, "missing tool name"))
        val args = Json.obj(req.params?.get("arguments")) ?: emptyMap()
        val handler = tools[name]
            // Unknown tool: MCP says report it as a protocol error (not a tool-execution error).
            ?: return JsonRpcResponse(
                req.id,
                error = JsonRpcError(
                    JsonRpc.INVALID_PARAMS,
                    "unknown tool '$name'",
                    data = linkedMapOf("available" to toolNames),
                ),
            )
        val result = runCatching { handler.call(args) }
            .getOrElse { e -> McpCallResult("tool '$name' failed: ${e.message ?: "error"}", isError = true) }
        return JsonRpcResponse(req.id, result = result.toJsonMap())
    }

    private fun reply(resp: JsonRpcResponse) {
        transport.send(encodeReply(resp))
    }

    /** Serialize a response and report it to the tap (shared by both transports). */
    fun encodeReply(resp: JsonRpcResponse): String {
        val text = resp.toJson()
        tap?.onMessage("out", null, resp.id, resp.isError, text.length)
        return text
    }
}

/**
 * MCP client with a blocking request API. Callers in this app are already on worker threads or
 * `Dispatchers.Default` coroutines around blocking native engines, so a latch-based wait keeps the
 * surface simple and transport-agnostic.
 */
class McpClient(
    private val transport: McpTransport,
    val clientName: String = "voxedge",
    val clientVersion: String = "1",
    private val timeoutMs: Long = Mcp.DEFAULT_TIMEOUT_MS,
    var tap: McpMessageTap? = null,
) {
    class McpException(val code: Int, message: String, val data: Any? = null) : RuntimeException(message)

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Pending>()
    @Volatile var serverInfo: Map<String, Any?>? = null
        private set

    private class Pending {
        val latch = CountDownLatch(1)
        @Volatile var response: JsonRpcResponse? = null
    }

    init {
        transport.onMessage = { text -> onRaw(text) }
    }

    private fun onRaw(text: String) {
        val msg = runCatching { JsonRpc.decode(text) }.getOrNull() ?: return
        if (msg is JsonRpcResponse) {
            tap?.onMessage("in", null, msg.id, msg.isError, text.length)
            val id = msg.id ?: return
            pending.remove(id)?.let { it.response = msg; it.latch.countDown() }
        }
    }

    /** `initialize` handshake + `notifications/initialized`. Returns the server's info. */
    fun initialize(): Map<String, Any?> {
        val result = request(
            Mcp.METHOD_INITIALIZE,
            linkedMapOf(
                "protocolVersion" to Mcp.PROTOCOL_VERSION,
                "capabilities" to emptyMap<String, Any?>(),
                "clientInfo" to linkedMapOf("name" to clientName, "version" to clientVersion),
            ),
        )
        notify(Mcp.METHOD_INITIALIZED)
        serverInfo = Json.obj(result["serverInfo"])
        return result
    }

    fun ping() { request(Mcp.METHOD_PING) }

    fun listTools(): List<McpToolDescriptor> =
        Json.arr(request(Mcp.METHOD_TOOLS_LIST)["tools"]).orEmpty()
            .mapNotNull { Json.obj(it) }
            .map { McpToolDescriptor.fromJson(it) }

    /** `tools/call`. Protocol-level errors (unknown tool, bad params) raise [McpException];
     *  tool-execution failures come back as a result with `isError = true`. */
    fun callTool(name: String, arguments: Map<String, Any?>): McpCallResult =
        McpCallResult.fromJson(request(Mcp.METHOD_TOOLS_CALL, linkedMapOf("name" to name, "arguments" to arguments)))

    fun notify(method: String, params: Map<String, Any?>? = null) {
        val text = JsonRpcNotification(method, params).toJson()
        tap?.onMessage("out", method, null, false, text.length)
        transport.send(text)
    }

    fun request(method: String, params: Map<String, Any?>? = null): Map<String, Any?> {
        val id = nextId.getAndIncrement()
        val slot = Pending()
        pending[id] = slot
        val text = JsonRpcRequest(id, method, params).toJson()
        tap?.onMessage("out", method, id, false, text.length)
        transport.send(text)
        if (!slot.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            pending.remove(id)
            throw McpException(JsonRpc.INTERNAL_ERROR, "timeout waiting for '$method' (id $id)")
        }
        val resp = slot.response!!
        resp.error?.let { throw McpException(it.code, it.message, it.data) }
        return resp.result ?: emptyMap()
    }
}
