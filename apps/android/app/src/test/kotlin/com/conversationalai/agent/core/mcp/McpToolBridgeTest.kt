package com.conversationalai.agent.core.mcp

import com.conversationalai.agent.core.tools.Tool
import com.conversationalai.agent.core.tools.ToolCall
import com.conversationalai.agent.core.tools.ToolParam
import com.conversationalai.agent.core.tools.ToolRegistry
import com.conversationalai.agent.core.tools.ToolResult
import com.conversationalai.agent.core.tools.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolBridgeTest {

    private class Timer : Tool {
        var lastArgs: Map<String, String>? = null
        override val spec = ToolSpec(
            name = "set_timer",
            description = "start a timer",
            params = listOf(ToolParam("minutes", "duration"), ToolParam("label", "name", required = false)),
            sideEffect = true,
        )
        override fun execute(args: Map<String, String>): ToolResult {
            lastArgs = args
            return ToolResult(true, "timer started")
        }
    }

    @Test
    fun descriptorIsAJsonSchemaObjectWithRequiredParams() {
        val d = McpToolBridge.descriptor(Timer().spec)
        assertEquals("set_timer", d.name)
        assertEquals("object", d.inputSchema["type"])
        val props = Json.obj(d.inputSchema["properties"])!!
        assertEquals(setOf("minutes", "label"), props.keys)
        assertEquals("string", Json.obj(props["minutes"])?.get("type"))
        assertEquals(listOf("minutes"), d.inputSchema["required"])
    }

    @Test
    fun registryDispatchIsARealToolsCallRoundTrip() {
        val timer = Timer()
        val registry = ToolRegistry(listOf(timer))
        val seen = mutableListOf<String>()
        registry.mcp.tap = McpMessageTap { dir, method, _, _, _ -> seen += "$dir:${method ?: "response"}" }

        val result = registry.dispatch(ToolCall("set_timer", mapOf("minutes" to "3")))
        assertTrue(result.ok)
        assertEquals("timer started", result.content)
        assertEquals(mapOf("minutes" to "3"), timer.lastArgs)
        // Client sent tools/call, server saw it, server answered, client received the answer.
        assertEquals(listOf("out:tools/call", "in:tools/call", "out:response", "in:response"), seen)
        assertEquals(listOf("set_timer"), registry.mcp.client.listTools().map { it.name })
    }

    @Test
    fun confirmationGateStillAppliesOnTheServerSide() {
        val timer = Timer()
        val registry = ToolRegistry(listOf(timer))
        registry.confirmSideEffects = true
        registry.beginTurn(1)
        val gated = registry.dispatch(ToolCall("set_timer", mapOf("minutes" to "3")))
        assertFalse(gated.ok)
        assertTrue(gated.content.contains("CONFIRMATION REQUIRED"))
        assertEquals(null, timer.lastArgs)
    }

    @Test
    fun unknownToolKeepsTheAvailableListForTheModel() {
        val registry = ToolRegistry(listOf(Timer()))
        val r = registry.dispatch(ToolCall("open_door", emptyMap()))
        assertFalse(r.ok)
        assertTrue(r.content.contains("unknown tool 'open_door'"))
        assertTrue(r.content.contains("set_timer"))
    }

    @Test
    fun typedMcpArgumentsAreNormalizedToStrings() {
        val args = McpToolBridge.stringArgs(
            linkedMapOf("i" to 3L, "d" to 2.0, "f" to 2.5, "b" to true, "s" to "x", "o" to linkedMapOf("k" to 1L)),
        )
        assertEquals(mapOf("i" to "3", "d" to "2", "f" to "2.5", "b" to "true", "s" to "x", "o" to """{"k":1}"""), args)
    }
}
