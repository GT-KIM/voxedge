package com.conversationalai.agent.core

import com.conversationalai.agent.core.tools.ToolCallParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCallFilterTest {

    private fun run(vararg chunks: String): Pair<String, com.conversationalai.agent.core.tools.ToolCall?> {
        val out = StringBuilder()
        val filter = ToolCallFilter(onText = { out.append(it) })
        chunks.forEach { filter.accept(it) }
        filter.finish()
        return out.toString() to filter.call
    }

    private fun runLenient(vararg chunks: String): Pair<String, com.conversationalai.agent.core.tools.ToolCall?> {
        val out = StringBuilder()
        val filter = ToolCallFilter(onText = { out.append(it) }, toolNames = setOf("remember_fact", "get_datetime"))
        chunks.forEach { filter.accept(it) }
        filter.finish()
        return out.toString() to filter.call
    }

    @Test
    fun bracketedToolNameWithArgumentsIsAcceptedAsACall() {
        // Device 2026-10-09: the model wrote the tool name as the tag and the arguments as the body.
        val (text, call) = runLenient(
            "Got it. ", "[remember_fact]{\"key\": \"favorite color\", \"value\": \"teal\"}",
        )
        assertEquals("Got it. ", text)
        assertEquals("remember_fact", call?.name)
        assertEquals("teal", call?.arguments?.get("value"))
        assertEquals("favorite color", call?.arguments?.get("key"))
    }

    @Test
    fun bracketedToolNameSplitAcrossChunksAndWithExplicitNameField() {
        val (text, call) = runLenient(
            "[get", "_date", "time]", " {\"name\": \"get_datetime\", \"arguments\": {}}", " trailing",
        )
        assertEquals("", text)
        assertEquals("get_datetime", call?.name)
        // A nested string containing braces must not end the object early.
        val (_, call2) = runLenient("[remember_fact]{\"key\": \"a}b\", \"value\": \"{x\"}")
        assertEquals("a}b", call2?.arguments?.get("key"))
    }

    @Test
    fun bracketedTextThatIsNotAToolNameIsSpoken() {
        val (text, call) = runLenient("See [note] {this} and [remember_facts] {that}.")
        assertEquals("See [note] {this} and [remember_facts] {that}.", text)
        assertNull(call)
        // Without a tool-name set the loose form is ordinary text.
        val (plain, none) = run("[remember_fact]{\"value\": \"teal\"}")
        assertEquals("[remember_fact]{\"value\": \"teal\"}", plain)
        assertNull(none)
    }

    @Test
    fun plainTextPassesThroughUntouched() {
        val (text, call) = run("Hello ", "there, how are you?")
        assertEquals("Hello there, how are you?", text)
        assertNull(call)
    }

    @Test
    fun toolCallIsSuppressedAndParsed() {
        val (text, call) = run(
            "One moment. ",
            "[TOOL_CALL]{\"name\": \"set_timer\", \"arguments\": {\"minutes\": 5}}[/TOOL_CALL]",
        )
        assertEquals("One moment. ", text)
        assertEquals("set_timer", call?.name)
        assertEquals("5", call?.arguments?.get("minutes"))
    }

    @Test
    fun markerSplitAcrossManySmallChunksIsStillCaught() {
        val payload = "[TOOL_CALL]{\"name\":\"get_datetime\",\"arguments\":{}}[/TOOL_CALL]"
        val chunks = payload.chunked(3).toTypedArray()
        val (text, call) = run("Sure. ", *chunks)
        assertEquals("Sure. ", text)
        assertEquals("get_datetime", call?.name)
    }

    @Test
    fun angleBracketTextThatIsNotAMarkerIsEventuallyEmitted() {
        val (text, call) = run("a < b and <tool", "ish> things")
        assertEquals("a < b and <toolish> things", text)
        assertNull(call)
    }

    @Test
    fun unterminatedCallIsDroppedNeverSpoken() {
        val (text, call) = run("Okay. ", "[TOOL_CALL]{\"name\":\"set_timer\"")
        assertEquals("Okay. ", text)
        assertNull(call)
    }

    @Test
    fun textAfterCompletedCallIsDropped() {
        val (text, call) = run(
            "[TOOL_CALL]{\"name\":\"flashlight\",\"arguments\":{\"state\":\"on\"}}[/TOOL_CALL]",
            "stray trailing text",
        )
        assertEquals("", text)
        assertEquals("flashlight", call?.name)
    }

    @Test
    fun parserHandlesTypesEscapesAndGarbage() {
        val call = ToolCallParser.parse(
            "{\"name\": \"set_alarm\", \"arguments\": {\"hour\": 7, \"minute\": 30, " +
                "\"label\": \"say \\\"hi\\\"\", \"enabled\": true}}",
        )
        assertEquals("set_alarm", call?.name)
        assertEquals("7", call?.arguments?.get("hour"))
        assertEquals("30", call?.arguments?.get("minute"))
        assertEquals("say \"hi\"", call?.arguments?.get("label"))
        assertEquals("true", call?.arguments?.get("enabled"))
        assertNull(ToolCallParser.parse("not json at all"))
        assertNull(ToolCallParser.parse("{\"arguments\": {}}"))
    }
}
