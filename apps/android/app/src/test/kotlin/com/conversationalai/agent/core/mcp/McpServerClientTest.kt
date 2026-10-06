package com.conversationalai.agent.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerClientTest {

    private fun echoServer(transport: McpTransport) = McpServer(
        name = "test-server",
        version = "0",
        tools = listOf(
            McpToolHandler(
                McpToolDescriptor("echo", "echo back", linkedMapOf("type" to "object")),
            ) { args -> McpCallResult("echo:" + Json.encode(args), structuredContent = linkedMapOf("n" to args.size)) },
            McpToolHandler(
                McpToolDescriptor("boom", "throws", linkedMapOf("type" to "object")),
            ) { throw IllegalStateException("kaboom") },
        ),
        transport = transport,
    )

    @Test
    fun handshakeListAndCallOverInProcessTransport() {
        val (c, s) = InProcessTransport.pair()
        val server = echoServer(s)
        val client = McpClient(c)

        val init = client.initialize()
        assertEquals(Mcp.PROTOCOL_VERSION, init["protocolVersion"])
        assertEquals("test-server", Json.obj(init["serverInfo"])?.get("name"))
        assertTrue(server.initialized)

        assertEquals(listOf("boom", "echo"), client.listTools().map { it.name })

        val r = client.callTool("echo", linkedMapOf("a" to 1L, "b" to "x"))
        assertFalse(r.isError)
        assertEquals("""echo:{"a":1,"b":"x"}""", r.text)
        assertEquals(2L, r.structuredContent?.get("n"))
    }

    @Test
    fun toolExceptionBecomesAnIsErrorResultNotAProtocolError() {
        val (c, s) = InProcessTransport.pair()
        echoServer(s)
        val client = McpClient(c)
        client.initialize()
        val r = client.callTool("boom", emptyMap())
        assertTrue(r.isError)
        assertTrue(r.text.contains("kaboom"))
    }

    @Test
    fun unknownToolAndUnknownMethodAreProtocolErrors() {
        val (c, s) = InProcessTransport.pair()
        echoServer(s)
        val client = McpClient(c)
        client.initialize()
        try {
            client.callTool("nope", emptyMap())
            throw AssertionError("expected McpException")
        } catch (e: McpClient.McpException) {
            assertEquals(JsonRpc.INVALID_PARAMS, e.code)
            assertEquals(listOf("boom", "echo"), Json.arr(Json.obj(e.data)?.get("available")))
        }
        try {
            client.request("resources/list")
            throw AssertionError("expected McpException")
        } catch (e: McpClient.McpException) {
            assertEquals(JsonRpc.METHOD_NOT_FOUND, e.code)
        }
    }

    @Test
    fun malformedInputGetsAParseErrorResponse() {
        val (c, s) = InProcessTransport.pair()
        echoServer(s)
        var got: String? = null
        c.onMessage = { got = it }
        c.send("{not json")
        val resp = JsonRpc.decode(got!!) as JsonRpcResponse
        assertEquals(JsonRpc.PARSE_ERROR, resp.error?.code)
        c.send("""{"jsonrpc":"1.0","id":1,"method":"ping"}""")
        assertEquals(JsonRpc.INVALID_REQUEST, (JsonRpc.decode(got!!) as JsonRpcResponse).error?.code)
    }

    @Test
    fun clientTimesOutWhenTheServerNeverAnswers() {
        val silent = object : McpTransport {
            override var onMessage: ((String) -> Unit)? = null
            override fun send(json: String) {}
        }
        val client = McpClient(silent, timeoutMs = 20)
        try {
            client.ping()
            throw AssertionError("expected timeout")
        } catch (e: McpClient.McpException) {
            assertTrue(e.message!!.contains("timeout"))
        }
    }
}
