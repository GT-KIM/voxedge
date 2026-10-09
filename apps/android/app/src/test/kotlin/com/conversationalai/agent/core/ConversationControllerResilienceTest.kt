package com.conversationalai.agent.core

import com.conversationalai.agent.asr.AsrEngine
import com.conversationalai.agent.audio.PcmStreamPlayer
import com.conversationalai.agent.llm.LlmEngine
import com.conversationalai.agent.tts.ClauseInputBuilder
import com.conversationalai.agent.tts.TtsEngine
import com.conversationalai.agent.tts.TtsInputs
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A turn that throws must not kill the loop: the hands-free consumer recovers to LISTENING with
 * the engine session reset, and the typed entry point returns an empty record instead of
 * propagating (which would have crashed the activity's coroutine).
 */
class ConversationControllerResilienceTest {

    /** Throws on the first N generations, answers afterwards. */
    private class FlakyLlm(private var failures: Int) : LlmEngine {
        var resets = 0
        var aborts = 0
        val prompts = mutableListOf<String>()
        override fun name() = "flaky-llm"
        override fun generate(prompt: String, onToken: (String) -> Unit): LlmEngine.Result {
            prompts += prompt
            if (failures > 0) { failures -= 1; throw IllegalStateException("native decode crashed") }
            onToken("fine now.")
            return LlmEngine.Result.OK
        }
        override fun abort() { aborts += 1 }
        override val sessionCapable: Boolean get() = true
        override fun sessionWarm() = false
        override fun resetSession() { resets += 1 }
    }

    private fun controller(
        llm: LlmEngine,
        states: MutableList<ConvState>,
        turns: MutableList<TurnRecord>,
        notices: MutableList<AudibleFeedback.Cue>,
        logger: RuntimeEventLogger? = null,
    ) = ConversationController(
        vadModelPath = "unused",
        enhancer = null,
        asr = object : AsrEngine {
            override fun name() = "fake-asr"
            override fun transcribe(samples: FloatArray, sampleRate: Int) = "hello there"
        },
        llm = llm,
        tts = object : TtsEngine {
            override fun version() = "fake-tts"
            override fun synthesizeClause(inputs: TtsInputs, k: Int) = floatArrayOf(0f, 0f)
        },
        inputBuilder = object : ClauseInputBuilder {
            override fun build(text: String, lang: String, seed: Long) = TtsInputs(
                textIds = intArrayOf(0), textMask = floatArrayOf(1f), styleTtl = floatArrayOf(),
                styleDp = floatArrayOf(), noisyLatent = floatArrayOf(), latentMask = floatArrayOf(),
            )
        },
        scope = CoroutineScope(Dispatchers.Default),
        onState = { states += it },
        onTurn = { turns += it },
        playerFactory = {
            object : PcmStreamPlayer {
                override fun start() = Unit
                override fun write(pcm: FloatArray) = Unit
                override fun interrupt() = Unit
                override fun stopAndRelease() = Unit
            }
        },
        eventLogger = logger,
        onNotice = { notices += it },
    )

    @Test
    fun handsFreeLoopSurvivesAThrowingTurnAndAnswersTheNextOne() = runBlocking {
        val file = File.createTempFile("resilience", ".jsonl").also { it.deleteOnExit() }
        val logger = RuntimeEventLogger(file)
        val llm = FlakyLlm(failures = 1)
        val states = mutableListOf<ConvState>()
        val turns = mutableListOf<TurnRecord>()
        val notices = mutableListOf<AudibleFeedback.Cue>()
        val c = controller(llm, states, turns, notices, logger)
        c.testEnterListening()

        c.consumeUtterance(FloatArray(1600))   // generate() throws
        c.consumeUtterance(FloatArray(1600))   // must still run

        assertEquals(2, llm.prompts.size)
        assertEquals(1, turns.size)
        assertEquals("fine now.", turns[0].replyText)
        // The failed turn: engine session reset, failure cue voiced, loop back to LISTENING.
        assertTrue(llm.resets >= 1)
        assertTrue(llm.aborts >= 1)
        assertEquals(listOf(AudibleFeedback.Cue.GENERATION_FAILED), notices)
        assertEquals(ConvState.LISTENING, states.last())
        logger.flush()
        val joined = file.readText()
        assertTrue(joined.contains("\"event\":\"turn.failed\""))
        assertTrue(joined.contains("\"source\":\"hands_free\""))
        assertTrue(joined.contains("native decode crashed"))
    }

    @Test
    fun typedTurnReturnsAnEmptyRecordInsteadOfThrowing() = runBlocking {
        val llm = FlakyLlm(failures = 1)
        val turns = mutableListOf<TurnRecord>()
        val c = controller(llm, mutableListOf(), turns, mutableListOf())

        val failed = c.runTurn("hello") {}
        val ok = c.runTurn("hello again") {}

        assertEquals("", failed.replyText)
        assertEquals(0L, failed.firstPcmMs)
        assertEquals("fine now.", ok.replyText)
        // The second turn re-prefilled (full ChatML) because the failed one reset the session.
        assertTrue(llm.prompts[1].startsWith("<|im_start|>system\n"))
    }
}
