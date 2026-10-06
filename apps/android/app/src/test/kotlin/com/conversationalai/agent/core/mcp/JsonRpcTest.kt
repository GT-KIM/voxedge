package com.conversationalai.agent.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcTest {

    @Test
    fun jsonRoundTripsNestedValuesAndEscapes() {
        val value = linkedMapOf<String, Any?>(
            "s" to "quote\" backslash\\ newline\n 한글 \u0001",
            "n" to 42L,
            "d" to 1.5,
            "b" to true,
            "z" to null,
            "arr" to listOf(1L, "two", linkedMapOf("k" to listOf<Any?>())),
        )
        val text = Json.encode(value)
        val back = Json.parseObject(text)
        assertEquals(value, back)
        assertEquals("""{"a":[],"b":{}}""", Json.encode(linkedMapOf("a" to emptyList<Any?>(), "b" to emptyMap<String, Any?>())))
    }

    @Test
    fun jsonParserRejectsGarbage() {
        for (bad in listOf("", "{", "[1,]", "{\"a\" 1}", "tru", "\"unterminated", "{} x")) {
            try {
                Json.parse(bad)
                throw AssertionError("accepted: $bad")
            } catch (_: Json.ParseException) {
                // expected
            }
        }
    }

    @Test
    fun requestNotificationAndResponseDecodeToTheRightTypes() {
        val req = JsonRpc.decode("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"x","arguments":{"a":1}}}""")
        assertTrue(req is JsonRpcRequest)
        req as JsonRpcRequest
        assertEquals(7L, req.id)
        assertEquals("tools/call", req.method)
        assertEquals("x", req.params?.get("name"))

        val note = JsonRpc.decode("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertTrue(note is JsonRpcNotification)

        val ok = JsonRpc.decode("""{"jsonrpc":"2.0","id":7,"result":{"tools":[]}}""") as JsonRpcResponse
        assertEquals(7L, ok.id)
        assertNull(ok.error)

        val err = JsonRpc.decode("""{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"parse error"}}""") as JsonRpcResponse
        assertNull(err.id)
        assertEquals(JsonRpc.PARSE_ERROR, err.error?.code)
    }

    @Test
    fun encodedMessagesCarryTheJsonRpcVersion() {
        val req = Json.parseObject(JsonRpcRequest(1, "ping").toJson())
        assertEquals("2.0", req["jsonrpc"])
        assertEquals(1L, req["id"])
        val resp = Json.parseObject(JsonRpcResponse(1, error = JsonRpcError(-32601, "nope")).toJson())
        assertEquals(-32601L, Json.obj(resp["error"])?.get("code"))
        assertTrue(!resp.containsKey("result"))
    }
}
