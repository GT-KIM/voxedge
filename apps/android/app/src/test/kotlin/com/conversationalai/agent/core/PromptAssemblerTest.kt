package com.conversationalai.agent.core

import com.conversationalai.agent.core.PromptAssembler.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerTest {

    @Test
    fun fullChatmlContainsSystemHistoryAndGenerationHeader() {
        val prompt = PromptAssembler.chatml(
            user = "and tomorrow?",
            history = listOf(PromptAssembler.Turn("weather today?", "It is sunny.")),
            lang = Lang.EN,
        )
        assertTrue(prompt.startsWith("<|im_start|>system\n"))
        assertTrue(prompt.contains("<|im_start|>user\nweather today?<|im_end|>\n"))
        assertTrue(prompt.contains("<|im_start|>assistant\nIt is sunny.<|im_end|>\n"))
        assertTrue(prompt.endsWith("<|im_start|>user\nand tomorrow?<|im_end|>\n<|im_start|>assistant\n"))
    }

    @Test
    fun incrementalChatmlIsOnlyTheNewTurn() {
        val prompt = PromptAssembler.chatmlIncremental("and tomorrow?")
        assertEquals("<|im_start|>user\nand tomorrow?<|im_end|>\n<|im_start|>assistant\n", prompt)
        assertFalse(prompt.contains("<|im_start|>system"))
    }

    @Test
    fun resolveLangAutoDetectsFromUserText() {
        // ASCII-only source (toolchain rule): Hangul sample via escapes (U+C548 U+B155 "annyeong").
        val hangul = "\uC548\uB155"
        assertEquals(Lang.KO, PromptAssembler.resolveLang(hangul))
        assertEquals(Lang.EN, PromptAssembler.resolveLang("hello there"))
        // Forced languages pass through untouched.
        assertEquals(Lang.KO, PromptAssembler.resolveLang("hello", Lang.KO))
        assertEquals(Lang.EN, PromptAssembler.resolveLang(hangul, Lang.EN))
    }

    @Test
    fun systemPromptSwitchesLanguageDirective() {
        assertTrue(PromptAssembler.systemPrompt(Lang.KO).contains("Reply ONLY in Korean"))
        assertTrue(PromptAssembler.systemPrompt(Lang.EN).contains("Reply ONLY in English"))
    }

    @Test
    fun savedFactsAreGroundedIntoTheSystemPrompt() {
        val withFacts = PromptAssembler.systemPrompt(
            Lang.EN, facts = "- name: Kwantae\n- seat preference: window",
        )
        assertTrue(withFacts.contains("name: Kwantae"))
        assertTrue(withFacts.contains("seat preference: window"))
        // No facts -> no facts module (no dangling header).
        assertFalse(PromptAssembler.systemPrompt(Lang.EN).contains("durable facts"))
    }
}
