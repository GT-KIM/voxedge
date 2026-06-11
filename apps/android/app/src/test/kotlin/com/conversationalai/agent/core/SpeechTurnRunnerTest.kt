package com.conversationalai.agent.core

import com.conversationalai.agent.audio.PcmStreamPlayer
import com.conversationalai.agent.llm.LlmEngine
import com.conversationalai.agent.tts.ClauseInputBuilder
import com.conversationalai.agent.tts.TtsEngine
import com.conversationalai.agent.tts.TtsInputs
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTurnRunnerTest {
    @Test
    fun currentGenerationSynthesizesAndWritesPcm() = runBlocking {
        val epoch = GenerationEpoch()
        val gid = epoch.next()
        val llm = FakeLlm("hello.")
        val tts = FakeTts()
        val inputBuilder = FakeInputBuilder()
        val player = FakePlayer()
        val states = mutableListOf<ConvState>()

        val record = runner(epoch, llm, tts, inputBuilder, player, states).run(
            gid = gid,
            prompt = "prompt",
            userText = "user",
            asrMs = 12L,
            onDelta = {},
        )

        assertFalse(record.bargedIn)
        assertEquals("hello.", record.replyText)
        assertEquals("hello.", inputBuilder.clauses.single())
        assertEquals(1, tts.synthesizeCount)
        assertEquals(1, player.writeCount)
        assertTrue(player.started)
        assertTrue(player.stopped)
        assertTrue(ConvState.GENERATING in states)
        assertTrue(ConvState.SPEAKING in states)
    }

    @Test
    fun staleGenerationDoesNotSynthesizeOrWritePcm() = runBlocking {
        val epoch = GenerationEpoch()
        val gid = epoch.next()
        epoch.cancel()
        val llm = FakeLlm("stale.")
        val tts = FakeTts()
        val inputBuilder = FakeInputBuilder()
        val player = FakePlayer()

        val record = runner(epoch, llm, tts, inputBuilder, player, mutableListOf()).run(
            gid = gid,
            prompt = "prompt",
            userText = "user",
            asrMs = 12L,
            onDelta = {},
        )

        assertTrue(record.bargedIn)
        assertEquals("", record.replyText)
        assertEquals(emptyList<String>(), inputBuilder.clauses)
        assertEquals(0, tts.synthesizeCount)
        assertEquals(0, player.writeCount)
        assertTrue(player.started)
        assertTrue(player.stopped)
    }

    @Test
    fun logsTurnLatencyEvents() = runBlocking {
        val file = File.createTempFile("turn-events", ".jsonl").also { it.deleteOnExit() }
        file.writeText("")
        val logger = RuntimeEventLogger(file)
        val epoch = GenerationEpoch()
        val gid = epoch.next()
        val llm = FakeLlm("hello.")
        val tts = FakeTts()
        val inputBuilder = FakeInputBuilder()
        val player = FakePlayer()

        runner(epoch, llm, tts, inputBuilder, player, mutableListOf(), logger).run(
            gid = gid,
            prompt = "prompt",
            userText = "user",
            asrMs = 12L,
            onDelta = {},
        )

        val joined = file.readText()
        for (event in listOf(
            "llm.generate_start",
            "llm.first_token",
            "tts.chunk_request",
            "tts.first_pcm",
            "tts.audio_chunk",
            "playback.start",
            "playback.end",
            "turn.end",
        )) {
            assertTrue("missing $event", joined.contains("\"event\":\"$event\""))
        }
        assertTrue(joined.contains("\"generation_id\":$gid"))
        assertTrue(joined.contains("\"asr_ms\":12"))
        assertTrue(joined.contains("\"first_pcm_ms\""))
    }

    private fun runner(
        epoch: GenerationEpoch,
        llm: LlmEngine,
        tts: TtsEngine,
        inputBuilder: ClauseInputBuilder,
        player: FakePlayer,
        states: MutableList<ConvState>,
        eventLogger: RuntimeEventLogger? = null,
    ) = SpeechTurnRunner(
        llm = llm,
        tts = tts,
        inputBuilder = inputBuilder,
        playerFactory = { player },
        generationEpoch = epoch,
        onPlayerStarted = {},
        onPlayerStopped = {},
        onState = { states += it },
        onSpeakingStarted = {},
        eventLogger = eventLogger,
    )

    private class FakeLlm(private val response: String) : LlmEngine {
        override fun name(): String = "fake-llm"
        override fun generate(prompt: String, onToken: (String) -> Unit): LlmEngine.Result {
            onToken(response)
            return LlmEngine.Result.OK
        }
        override fun abort() = Unit
    }

    private class FakeTts : TtsEngine {
        var synthesizeCount = 0
        override fun version(): String = "fake-tts"
        override fun synthesizeClause(inputs: TtsInputs, k: Int): FloatArray {
            synthesizeCount += 1
            return floatArrayOf(0.1f, 0.2f)
        }
    }

    private class FakeInputBuilder : ClauseInputBuilder {
        val clauses = mutableListOf<String>()
        override fun build(text: String, lang: String, seed: Long): TtsInputs {
            clauses += text
            return TtsInputs(
                textIds = intArrayOf(text.length),
                textMask = floatArrayOf(1f),
                styleTtl = floatArrayOf(),
                styleDp = floatArrayOf(),
                noisyLatent = floatArrayOf(),
                latentMask = floatArrayOf(),
            )
        }
    }

    private class FakePlayer : PcmStreamPlayer {
        var started = false
        var stopped = false
        var writeCount = 0
        override fun start() { started = true }
        override fun write(pcm: FloatArray) { writeCount += 1 }
        override fun interrupt() = Unit
        override fun stopAndRelease() { stopped = true }
    }
}
