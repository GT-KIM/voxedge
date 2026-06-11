package com.conversationalai.agent.core

import com.conversationalai.agent.asr.AsrEngine
import com.conversationalai.agent.audio.PcmStreamPlayer
import com.conversationalai.agent.llm.LlmEngine
import com.conversationalai.agent.tts.ClauseInputBuilder
import com.conversationalai.agent.tts.TtsEngine
import com.conversationalai.agent.tts.TtsInputs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session-wise prompting through the controller: first turn re-prefills (full ChatML), later
 * turns continue the warm KV session incrementally, and language switches / overflow / high
 * occupancy force a re-prefill. Uses the one-shot [ConversationController.runTurn] path so no
 * Android mic/audio is touched.
 */
class ConversationControllerSessionTest {

    @Test
    fun firstTurnReprefillsThenWarmTurnsAreIncremental() = runBlocking {
        val llm = FakeSessionLlm()
        val controller = controller(llm)

        controller.runTurn("hello there") {}
        controller.runTurn("how are you") {}

        assertEquals(2, llm.prompts.size)
        assertTrue(llm.prompts[0].startsWith("<|im_start|>system\n"))
        assertTrue(llm.prompts[0].contains("Reply ONLY in English"))
        assertEquals(PromptAssembler.chatmlIncremental("how are you"), llm.prompts[1])
    }

    @Test
    fun languageSwitchForcesReprefillWithHistory() = runBlocking {
        val llm = FakeSessionLlm()
        val controller = controller(llm)
        val hangul = "\uC548\uB155"   // ASCII-only source (toolchain rule)

        controller.runTurn("hello there") {}
        controller.runTurn(hangul) {}

        val second = llm.prompts[1]
        assertTrue(second.startsWith("<|im_start|>system\n"))
        assertTrue(second.contains("Reply ONLY in Korean"))
        // The re-prefill replays the transcript so no context is lost across the session reset.
        assertTrue(second.contains("<|im_start|>user\nhello there<|im_end|>\n"))
    }

    @Test
    fun contextExceededColdsTheSessionForTheNextTurn() = runBlocking {
        val llm = FakeSessionLlm()
        val controller = controller(llm)

        controller.runTurn("turn one") {}
        llm.nextResult = LlmEngine.Result.CONTEXT_EXCEEDED
        controller.runTurn("turn two") {}
        controller.runTurn("turn three") {}

        assertEquals(PromptAssembler.chatmlIncremental("turn two"), llm.prompts[1])
        assertTrue(llm.prompts[2].startsWith("<|im_start|>system\n"))
    }

    @Test
    fun highOccupancyForcesReprefill() = runBlocking {
        val llm = FakeSessionLlm()
        val controller = controller(llm)

        controller.runTurn("turn one") {}
        llm.occupancy = LlmSessionPolicy.MAX_OCCUPANCY_PERCENT
        controller.runTurn("turn two") {}

        // The eviction path first distills a rolling summary on the warm session, then
        // re-prefills with the summary folded into the system prompt.
        assertEquals(3, llm.prompts.size)
        assertTrue(llm.prompts[1].contains("Summarize our conversation"))
        assertTrue(llm.prompts[2].startsWith("<|im_start|>system\n"))
        assertTrue(llm.prompts[2].contains("Summary of the conversation so far: ok."))
    }

    @Test
    fun rewindCapableEngineReprefillsViaRewindWithoutReset() = runBlocking {
        val llm = FakeRewindLlm()
        val controller = controller(llm)

        controller.runTurn("hello there") {}        // cold start, empty KV -> plain reset+prefill
        llm.coldNextTurn = true
        controller.runTurn("gets interrupted") {}   // warm incremental; ends ABORTED (session cold)
        controller.runTurn("after the abort") {}    // KV has content -> rewind re-prefill

        // The post-abort re-prefill goes through generateRewind with the FULL transcript and no
        // session reset (the engine prefix-matches its KV instead of prefilling from scratch).
        assertEquals(listOf("plain", "plain", "rewind"), llm.modes)
        assertTrue(llm.prompts[2].startsWith("<|im_start|>system\n"))
        assertEquals(1, llm.resets)   // only the cold start resets
    }

    @Test
    fun rawTemplateEngineGetsPlainTextAndSystemPromptSeparately() = runBlocking {
        val llm = FakeRawSessionLlm()
        val controller = controller(llm)

        controller.runTurn("hello there") {}
        controller.runTurn("how are you") {}

        // RAW engines receive plain user text in both modes; the system prompt travels via
        // setSystemPrompt at session configuration.
        assertEquals(listOf("hello there", "how are you"), llm.prompts)
        assertEquals(1, llm.systemPrompts.size)
        assertTrue(llm.systemPrompts.single().contains("Reply ONLY in English"))
    }

    @Test
    fun resetConversationStartsAFreshSession() = runBlocking {
        val llm = FakeSessionLlm()
        val controller = controller(llm)

        controller.runTurn("remember the number forty two") {}
        controller.runTurn("and the color blue") {}
        controller.resetConversation()
        controller.runTurn("hello again") {}

        // The post-reset turn re-prefills with NO carried history.
        val last = llm.prompts.last()
        assertTrue(last.startsWith("<|im_start|>system\n"))
        assertFalse(last.contains("forty two"))
        assertFalse(last.contains("the color blue"))
    }

    @Test
    fun restoreHistoryWithSummarySeedsContextAndSystemPrompt() = runBlocking {
        val llm = FakeSessionLlm()
        val controller = controller(llm)

        controller.restoreHistory(
            turns = listOf(PromptAssembler.Turn("earlier question", "earlier answer")),
            summary = "the user is planning a hiking trip",
        )
        controller.runTurn("so what should I pack?") {}

        val prompt = llm.prompts.last()
        assertTrue(prompt.contains("Summary of the conversation so far: the user is planning a hiking trip"))
        assertTrue(prompt.contains("<|im_start|>user\nearlier question<|im_end|>\n"))
    }

    @Test
    fun sessionUnawareEngineAlwaysGetsTheFullPrompt() = runBlocking {
        val llm = FakePlainLlm()
        val controller = controller(llm)

        controller.runTurn("hello there") {}
        controller.runTurn("how are you") {}

        assertTrue(llm.prompts.all { it.startsWith("<|im_start|>system\n") })
    }

    // --- fakes ---

    private fun controller(llm: LlmEngine) = ConversationController(
        vadModelPath = "unused",
        enhancer = null,
        asr = object : AsrEngine {
            override fun name() = "fake-asr"
            override fun transcribe(samples: FloatArray, sampleRate: Int) = ""
        },
        llm = llm,
        tts = object : TtsEngine {
            override fun version() = "fake-tts"
            override fun synthesizeClause(inputs: TtsInputs, k: Int) = floatArrayOf(0f)
        },
        inputBuilder = object : ClauseInputBuilder {
            override fun build(text: String, lang: String, seed: Long) = TtsInputs(
                textIds = intArrayOf(0),
                textMask = floatArrayOf(1f),
                styleTtl = floatArrayOf(),
                styleDp = floatArrayOf(),
                noisyLatent = floatArrayOf(),
                latentMask = floatArrayOf(),
            )
        },
        scope = CoroutineScope(Dispatchers.Default),
        playerFactory = {
            object : PcmStreamPlayer {
                override fun start() = Unit
                override fun write(pcm: FloatArray) = Unit
                override fun interrupt() = Unit
                override fun stopAndRelease() = Unit
            }
        },
    )

    private class FakeSessionLlm : LlmEngine {
        val prompts = mutableListOf<String>()
        var occupancy = 10
        var nextResult = LlmEngine.Result.OK
        private var warm = false

        override fun name() = "fake-session-llm"
        override fun generate(prompt: String, onToken: (String) -> Unit): LlmEngine.Result {
            prompts += prompt
            onToken("ok.")
            val result = nextResult
            nextResult = LlmEngine.Result.OK
            warm = result == LlmEngine.Result.OK
            return result
        }
        override fun abort() { warm = false }
        override val sessionCapable: Boolean get() = true
        override fun sessionWarm() = warm
        override fun resetSession() { warm = false }
        override fun contextOccupancyPercent() = occupancy
    }

    private class FakeRewindLlm : LlmEngine {
        val prompts = mutableListOf<String>()
        val modes = mutableListOf<String>()
        var resets = 0
        var coldNextTurn = false
        private var warm = false

        override fun name() = "fake-rewind-llm"
        override fun generate(prompt: String, onToken: (String) -> Unit): LlmEngine.Result {
            prompts += prompt; modes += "plain"; onToken("ok."); return finish()
        }
        override fun generateRewind(prompt: String, onToken: (String) -> Unit): LlmEngine.Result {
            prompts += prompt; modes += "rewind"; onToken("ok."); return finish()
        }
        private fun finish(): LlmEngine.Result {
            warm = !coldNextTurn
            coldNextTurn = false
            return if (warm) LlmEngine.Result.OK else LlmEngine.Result.ABORTED
        }
        override fun abort() { warm = false }
        override val sessionCapable: Boolean get() = true
        override val supportsRewind: Boolean get() = true
        override fun sessionWarm() = warm
        override fun resetSession() { resets += 1; warm = false }
        // Empty before the first turn; afterwards the KV holds content rewind can match against.
        override fun contextOccupancyPercent() = if (prompts.isEmpty()) 0 else 10
    }

    private class FakeRawSessionLlm : LlmEngine {
        val prompts = mutableListOf<String>()
        val systemPrompts = mutableListOf<String>()
        private var warm = false

        override fun name() = "fake-raw-llm"
        override fun generate(prompt: String, onToken: (String) -> Unit): LlmEngine.Result {
            prompts += prompt
            onToken("ok.")
            warm = true
            return LlmEngine.Result.OK
        }
        override fun abort() { warm = false }
        override val sessionCapable: Boolean get() = true
        override fun sessionWarm() = warm
        override fun resetSession() { warm = false }
        override fun setSystemPrompt(systemPrompt: String) { systemPrompts += systemPrompt }
        override fun chatTemplateId() = "raw"
    }

    private class FakePlainLlm : LlmEngine {
        val prompts = mutableListOf<String>()
        override fun name() = "fake-plain-llm"
        override fun generate(prompt: String, onToken: (String) -> Unit): LlmEngine.Result {
            prompts += prompt
            onToken("ok.")
            return LlmEngine.Result.OK
        }
        override fun abort() = Unit
    }
}
