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
