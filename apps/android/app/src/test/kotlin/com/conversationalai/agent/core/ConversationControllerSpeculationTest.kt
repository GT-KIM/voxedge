package com.conversationalai.agent.core

import com.conversationalai.agent.asr.AsrEngine
import com.conversationalai.agent.audio.PcmStreamPlayer
import com.conversationalai.agent.llm.LlmEngine
import com.conversationalai.agent.tts.ClauseInputBuilder
import com.conversationalai.agent.tts.TtsEngine
import com.conversationalai.agent.tts.TtsInputs
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Speculative early-prefill turns: the candidate checkpoint starts the turn (generation + TTS
 * synthesis) while audible playback stays gated; the real endpoint either COMMITS (same
 * transcript -> gate opens, one generation total) or cancels (mismatch / user resumed speaking).
 *
 * These tests drive the controller's internal entry points directly (candidateUtterance +
 * handleUtterance), standing in for the mic capture callbacks.
 */
class ConversationControllerSpeculationTest {

    private val samples = FloatArray(1600)

    /** ASR fake returning scripted transcripts in order. */
    private class ScriptedAsr(vararg texts: String) : AsrEngine {
        val queue = ArrayDeque(texts.toList())
        override fun name() = "scripted-asr"
        override fun transcribe(samples: FloatArray, sampleRate: Int): String =
            queue.removeFirstOrNull() ?: ""
    }

    private class GatedFakeLlm : LlmEngine {
        val prompts = mutableListOf<String>()
        val generateStarted = CompletableDeferred<Unit>()
        override fun name() = "fake-llm"
        override fun generate(prompt: String, onToken: (String) -> Unit): LlmEngine.Result {
            synchronized(prompts) { prompts += prompt }
            if (!generateStarted.isCompleted) generateStarted.complete(Unit)
            onToken("ok then.")
            return LlmEngine.Result.OK
        }
        override fun abort() = Unit
        override val sessionCapable: Boolean get() = true
        override fun sessionWarm() = false
        override fun resetSession() {}
    }

    private class RecordingPlayer : PcmStreamPlayer {
        val writes = ConcurrentLinkedQueue<Int>()
        override fun start() = Unit
        override fun write(pcm: FloatArray) { writes.add(pcm.size) }
        override fun interrupt() = Unit
        override fun stopAndRelease() = Unit
    }

    private fun controller(
        asr: AsrEngine,
        llm: LlmEngine,
        player: RecordingPlayer,
        onTurn: (TurnRecord) -> Unit = {},
    ): ConversationController {
        val c = ConversationController(
            vadModelPath = "unused",
            enhancer = null,
            asr = asr,
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
            onTurn = onTurn,
            playerFactory = { player },
        )
        return c
    }

    /** Drive the controller into the hands-free LISTENING state without a real mic. */
    private fun forceListening(c: ConversationController) {
        // start() would create a MicStream (Android); instead reach the state machine directly
        // through the public API surface used in tests: runTurn keeps IDLE, so use reflection-free
        // path: the state machine allows IDLE -> LISTENING only via start(). For tests we rely on
        // the internal test hook below.
        c.testEnterListening()
    }

    @Test
    fun matchingFinalTranscriptCommitsTheSpeculativeTurn() = runBlocking {
        val llm = GatedFakeLlm()
        val player = RecordingPlayer()
        val turns = mutableListOf<TurnRecord>()
        val c = controller(ScriptedAsr("hello there", "hello there"), llm, player) { turns += it }
        forceListening(c)

        c.candidateUtterance(samples)
        withTimeout(5000) { llm.generateStarted.await() }
        // Playback must stay gated while only the candidate exists.
        delay(150)
        assertTrue("playback leaked before commit", player.writes.isEmpty())

        withTimeout(5000) { c.handleUtterance(samples) }   // real endpoint, same transcript

        assertEquals(1, llm.prompts.size)                  // ONE generation total
        assertTrue(player.writes.isNotEmpty())             // audio released after commit
        assertEquals(1, turns.size)
        assertEquals("hello there", turns[0].userText)
        assertEquals("ok then.", turns[0].replyText)
    }

    @Test
    fun mismatchedFinalTranscriptRerunsWithTheRealText() = runBlocking {
        val llm = GatedFakeLlm()
        val player = RecordingPlayer()
        val turns = mutableListOf<TurnRecord>()
        val c = controller(
            ScriptedAsr("hello", "hello there friend"), llm, player,
        ) { turns += it }
        forceListening(c)

        c.candidateUtterance(samples)
        withTimeout(5000) { llm.generateStarted.await() }
        withTimeout(5000) { c.handleUtterance(samples) }   // endpoint hears MORE words

        assertEquals(2, llm.prompts.size)                  // speculative + real
        assertTrue(llm.prompts[1].contains("hello there friend"))
        assertEquals(1, turns.size)                        // only the real turn surfaces
        assertEquals("hello there friend", turns[0].userText)
    }

    @Test
    fun resumedSpeechCancelsSpeculationBeforeAnythingIsAudible() = runBlocking {
        val llm = GatedFakeLlm()
        val player = RecordingPlayer()
        val turns = mutableListOf<TurnRecord>()
        val c = controller(
            ScriptedAsr("well I was thinking", "well I was thinking about dinner"), llm, player,
        ) { turns += it }
        forceListening(c)

        c.candidateUtterance(samples)
        withTimeout(5000) { llm.generateStarted.await() }
        c.testSpeechOnset()                                // the user kept talking
        delay(100)
        assertTrue("cancelled speculation must stay silent", player.writes.isEmpty())

        withTimeout(5000) { c.handleUtterance(samples) }   // full utterance at the real endpoint

        assertEquals(2, llm.prompts.size)
        assertEquals(1, turns.size)
        assertEquals("well I was thinking about dinner", turns[0].userText)
        assertEquals("ok then.", turns[0].replyText)
    }
}
