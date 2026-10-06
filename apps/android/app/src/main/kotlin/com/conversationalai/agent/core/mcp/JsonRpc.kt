package com.conversationalai.agent.core.mcp

/**
 * JSON-RPC 2.0 envelope used by MCP. Ids are integers here (the protocol allows strings too; we
 * only generate numbers and accept either on the way in).
 */
sealed class JsonRpcMessage {
    abstract fun toJson(): String
}

class JsonRpcRequest(
    val id: Long,
    val method: String,
    val params: Map<String, Any?>? = null,
) : JsonRpcMessage() {
    override fun toJson(): String = Json.encode(
        linkedMapOf("jsonrpc" to JsonRpc.VERSION, "id" to id, "method" to method, "params" to (params ?: emptyMap<String, Any?>())),
    )
}

class JsonRpcNotification(
    val method: String,
    val params: Map<String, Any?>? = null,
) : JsonRpcMessage() {
    override fun toJson(): String {
        val m = linkedMapOf<String, Any?>("jsonrpc" to JsonRpc.VERSION, "method" to method)
        if (params != null) m["params"] = params
        return Json.encode(m)
    }
}

class JsonRpcResponse(
    val id: Long?,
    val result: Map<String, Any?>? = null,
    val error: JsonRpcError? = null,
) : JsonRpcMessage() {
    val isError: Boolean get() = error != null

    override fun toJson(): String {
        val m = linkedMapOf<String, Any?>("jsonrpc" to JsonRpc.VERSION, "id" to id)
        if (error != null) {
            val e = linkedMapOf<String, Any?>("code" to error.code, "message" to error.message)
            if (error.data != null) e["data"] = error.data
            m["error"] = e
        } else {
            m["result"] = result ?: emptyMap<String, Any?>()
        }
        return Json.encode(m)
    }
}

data class JsonRpcError(val code: Int, val message: String, val data: Any? = null)

object JsonRpc {
    const val VERSION = "2.0"

    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    /** Decode one message. Throws [Json.ParseException] on malformed JSON, [IllegalArgumentException]
     *  when the object is not a JSON-RPC 2.0 message. */
    fun decode(text: String): JsonRpcMessage {
        val m = Json.parseObject(text)
        require(m["jsonrpc"] == VERSION) { "not a JSON-RPC 2.0 message" }
        val method = Json.str(m["method"])
        val hasId = m.containsKey("id")
        return when {
            method != null && hasId -> JsonRpcRequest(
                id = Json.long(m["id"]) ?: throw IllegalArgumentException("non-numeric id"),
                method = method,
                params = Json.obj(m["params"]),
            )
            method != null -> JsonRpcNotification(method, Json.obj(m["params"]))
            hasId -> {
                val err = Json.obj(m["error"])
                JsonRpcResponse(
                    id = Json.long(m["id"]),
                    result = Json.obj(m["result"]),
                    error = err?.let {
                        JsonRpcError(
                            code = Json.long(it["code"])?.toInt() ?: INTERNAL_ERROR,
                            message = Json.str(it["message"]) ?: "",
                            data = it["data"],
                        )
                    },
                )
            }
            else -> throw IllegalArgumentException("message has neither method nor id")
        }
    }
}
