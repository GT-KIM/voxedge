package com.conversationalai.agent.core.mcp

import com.conversationalai.agent.core.tools.ToolCall
import com.conversationalai.agent.core.tools.ToolRegistry
import com.conversationalai.agent.core.tools.ToolResult
import com.conversationalai.agent.core.tools.ToolSpec

/**
 * Serves a [ToolRegistry] as an MCP server and gives the LLM side an MCP client to it.
 *
 * Both tool-call paths (the prompt-convention `[TOOL_CALL]` loop and LiteRT native function
 * calling) end in `ToolRegistry.dispatch`, which routes through [call] here, so every device-tool
 * invocation is a real `tools/call` round trip. The confirmation gate and the dispatch observer
 * stay on the registry (server side), unchanged.
 */
class McpToolBridge(private val registry: ToolRegistry) {

    private val clientSide: McpTransport
    private val serverSide: McpTransport
    val server: McpServer
    val client: McpClient

    init {
        val (c, s) = InProcessTransport.pair()
        clientSide = c
        serverSide = s
        server = McpServer(
            name = SERVER_NAME,
            version = SERVER_VERSION,
            tools = registry.specs.map { spec -> handler(spec) },
            transport = serverSide,
        )
        client = McpClient(clientSide, timeoutMs = Mcp.DEFAULT_TIMEOUT_MS)
        client.initialize()
    }

    /** Raw-traffic observer for both endpoints (counts and methods only, never arguments). */
    var tap: McpMessageTap?
        get() = client.tap
        set(value) {
            client.tap = value
            server.tap = value
        }

    /** LLM-side entry: encode the model's call as MCP, run it, decode the result. */
    fun call(call: ToolCall): ToolResult = try {
        val r = client.callTool(call.name, call.arguments)
        ToolResult(ok = !r.isError, content = r.text)
    } catch (e: McpClient.McpException) {
        // Protocol-level failure (unknown tool, bad params, timeout) -> model-readable text; keep
        // the server's list of available tools so the model can pick a valid one next step.
        val available = Json.obj(e.data)?.let { Json.arr(it["available"]) }
        val hint = if (available.isNullOrEmpty()) "" else "; available tools: " + available.joinToString(", ")
        ToolResult(ok = false, content = (e.message ?: "tool call failed") + hint)
    }

    private fun handler(spec: ToolSpec) = McpToolHandler(descriptor(spec)) { args ->
        val result = registry.serve(ToolCall(spec.name, stringArgs(args)))
        McpCallResult(text = result.content, isError = !result.ok)
    }

    companion object {
        const val SERVER_NAME = "voxedge-device-tools"
        const val SERVER_VERSION = "1"

        /** JSON Schema `inputSchema` for a [ToolSpec]. All parameters are strings (the model
         *  speaks them; tools coerce). Pure; unit-tested. */
        fun descriptor(spec: ToolSpec): McpToolDescriptor {
            val props = LinkedHashMap<String, Any?>()
            for (p in spec.params) {
                props[p.name] = linkedMapOf("type" to "string", "description" to p.description)
            }
            val schema = linkedMapOf<String, Any?>("type" to "object", "properties" to props)
            val required = spec.params.filter { it.required }.map { it.name }
            if (required.isNotEmpty()) schema["required"] = required
            return McpToolDescriptor(spec.name, spec.description, schema)
        }

        /** Tool implementations take string arguments; MCP arguments may be typed JSON. */
        fun stringArgs(args: Map<String, Any?>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for ((k, v) in args) {
                out[k] = when (v) {
                    null -> "null"
                    is String -> v
                    is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
                    is Map<*, *>, is List<*> -> Json.encode(v)
                    else -> v.toString()
                }
            }
            return out
        }
    }
}
