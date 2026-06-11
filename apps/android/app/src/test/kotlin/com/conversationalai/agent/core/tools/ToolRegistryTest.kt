package com.conversationalai.agent.core.tools

import com.conversationalai.agent.llm.LiteRtToolAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private class CountingTool(sideEffect: Boolean) : Tool {
        var calls = 0
        override val spec = ToolSpec(
            name = "set_timer",
            description = "start a timer",
            params = listOf(ToolParam("minutes", "duration in minutes")),
            sideEffect = sideEffect,
        )
        override fun execute(args: Map<String, String>): ToolResult {
            calls += 1
            return ToolResult(true, "timer started")
        }
    }

    private val call = ToolCall("set_timer", mapOf("minutes" to "3"))

    @Test
    fun sideEffectToolRunsDirectlyWhenConfirmationsAreOff() {
        val tool = CountingTool(sideEffect = true)
        val registry = ToolRegistry(listOf(tool))
        registry.beginTurn(1)
        assertTrue(registry.dispatch(call).ok)
        assertEquals(1, tool.calls)
    }

    @Test
    fun confirmationGateDefersThenAllowsOnTheNextTurn() {
        val tool = CountingTool(sideEffect = true)
        val registry = ToolRegistry(listOf(tool))
        registry.confirmSideEffects = true

        registry.beginTurn(1)
        val first = registry.dispatch(call)
        assertFalse(first.ok)
        assertTrue(first.content.contains("CONFIRMATION REQUIRED"))
        assertEquals(0, tool.calls)

        // Same-turn retry stays gated (the model should ask, not insist).
        assertFalse(registry.dispatch(call).ok)
        assertEquals(0, tool.calls)

        // The user replied (next turn) and the model re-called: execute.
        registry.beginTurn(2)
        assertTrue(registry.dispatch(call).ok)
        assertEquals(1, tool.calls)
    }

    @Test
    fun pendingConfirmationExpiresIfNotReCalledOnTheNextTurn() {
        val tool = CountingTool(sideEffect = true)
        val registry = ToolRegistry(listOf(tool))
        registry.confirmSideEffects = true

        registry.beginTurn(1)
        assertFalse(registry.dispatch(call).ok)   // pending
        registry.beginTurn(2)                      // user talked about something else
        registry.beginTurn(3)                      // pending expired here
        assertFalse(registry.dispatch(call).ok)    // a fresh request must confirm again
        assertEquals(0, tool.calls)
    }

    @Test
    fun nonSideEffectToolIgnoresTheGate() {
        val clock = object : Tool {
            override val spec = ToolSpec("get_datetime", "time")
            override fun execute(args: Map<String, String>) = ToolResult(true, "noon")
        }
        val registry = ToolRegistry(listOf(clock))
        registry.confirmSideEffects = true
        registry.beginTurn(1)
        assertTrue(registry.dispatch(ToolCall("get_datetime", emptyMap())).ok)
    }
}

class LiteRtToolAdapterTest {

    @Test
    fun declarationJsonHasGeminiFunctionShape() {
        val spec = ToolSpec(
            name = "set_timer",
            description = "start a timer",
            params = listOf(
                ToolParam("minutes", "duration in minutes"),
                ToolParam("label", "what for", required = false),
            ),
            sideEffect = true,
        )
        val json = LiteRtToolAdapter.declarationJson(spec)
        assertTrue(json.contains("\"name\": \"set_timer\""))
        assertTrue(json.contains("\"parameters\": {\"type\": \"object\""))
        assertTrue(json.contains("\"minutes\": {\"type\": \"string\""))
        assertTrue(json.contains("\"required\": [\"minutes\"]"))
        assertFalse(json.contains("\"required\": [\"minutes\", \"label\"]"))
    }

    @Test
    fun parseArgsHandlesObjectsAndEmpty() {
        val parsed = LiteRtToolAdapter.parseArgs("set_timer", "{\"minutes\": 5, \"label\": \"tea\"}")
        assertEquals("set_timer", parsed.name)
        assertEquals("5", parsed.arguments["minutes"])
        assertEquals("tea", parsed.arguments["label"])
        assertEquals(emptyMap<String, String>(), LiteRtToolAdapter.parseArgs("x", "").arguments)
    }
}
