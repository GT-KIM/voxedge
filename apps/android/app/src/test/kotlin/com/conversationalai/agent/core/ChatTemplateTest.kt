package com.conversationalai.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTemplateTest {

    private val history = listOf(PromptAssembler.Turn("first question", "first answer"))

    @Test
    fun chatmlMatchesPromptAssemblerOutput() {
        assertEquals(
            PromptAssembler.chatml("hi", history, PromptAssembler.Lang.EN),
            ChatTemplate.CHATML.full(PromptAssembler.systemPrompt(PromptAssembler.Lang.EN), history, "hi"),
        )
        assertEquals(PromptAssembler.chatmlIncremental("hi"), ChatTemplate.CHATML.incremental("hi"))
    }

    @Test
    fun gemmaFoldsSystemIntoFirstUserTurnOnly() {
        val full = ChatTemplate.GEMMA.full("SYSTEM RULES", history, "second question")
        assertEquals(
            "<start_of_turn>user\nSYSTEM RULES\n\nfirst question<end_of_turn>\n" +
                "<start_of_turn>model\nfirst answer<end_of_turn>\n" +
                "<start_of_turn>user\nsecond question<end_of_turn>\n" +
                "<start_of_turn>model\n",
            full,
        )
        // No history: the system folds into the current user turn instead.
        val noHistory = ChatTemplate.GEMMA.full("SYSTEM RULES", emptyList(), "only question")
        assertTrue(noHistory.startsWith("<start_of_turn>user\nSYSTEM RULES\n\nonly question<end_of_turn>\n"))
        // Never more than one fold.
        assertEquals(1, Regex("SYSTEM RULES").findAll(full).count())
    }

    @Test
    fun gemmaIncrementalIsOnlyTheNewTurn() {
        assertEquals(
            "<start_of_turn>user\nhi<end_of_turn>\n<start_of_turn>model\n",
            ChatTemplate.GEMMA.incremental("hi"),
        )
    }

    @Test
    fun rawPassesUserTextThrough() {
        assertEquals("hi", ChatTemplate.RAW.full("SYSTEM RULES", history, "hi"))
        assertEquals("hi", ChatTemplate.RAW.incremental("hi"))
        assertFalse(ChatTemplate.RAW.full("SYSTEM", history, "hi").contains("SYSTEM"))
    }

    @Test
    fun fromIdResolvesAndFallsBackToChatml() {
        assertEquals(ChatTemplate.CHATML, ChatTemplate.fromId("chatml"))
        assertEquals(ChatTemplate.GEMMA, ChatTemplate.fromId("gemma"))
        assertEquals(ChatTemplate.RAW, ChatTemplate.fromId("raw"))
        assertEquals(ChatTemplate.CHATML, ChatTemplate.fromId("unknown-model"))
    }
}
