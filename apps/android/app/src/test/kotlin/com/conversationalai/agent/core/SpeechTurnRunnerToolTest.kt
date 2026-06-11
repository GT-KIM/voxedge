package com.conversationalai.agent.core

import com.conversationalai.agent.audio.PcmStreamPlayer
import com.conversationalai.agent.core.tools.Tool
import com.conversationalai.agent.core.tools.ToolParam
import com.conversationalai.agent.core.tools.ToolRegistry
import com.conversationalai.agent.core.tools.ToolResult
import com.conversationalai.agent.core.tools.ToolSpec
import com.conversationalai.agent.llm.LlmEngine
import com.conversationalai.agent.tts.ClauseInputBuilder
import com.conversationalai.agent.tts.TtsEngine
import com.conversationalai.agent.tts.TtsInputs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agentic tool-use loop: tool call -> execute -> tool_response continuation -> spoken answer. */
class SpeechTurnRunnerToolTest {

    @Test
    fun toolCallIsExecutedAndAnswerComesFromTheNextStep() = runBlocking {
        // Step 1 emits a tool call (with spoken preamble); step 2 answers in plain text.
        val llm = ScriptedSessionLlm(
            listOf(
                "Let me check. [TOOL_CALL]{\"name\":\"get_datetime\",\"arguments\":{}}[/TOOL_CALL]",
                "It is three in the afternoon.",
            ),
        )
        val clock = FakeClockTool()
        val inputBuilder = RecordingInputBuilder()
        val epoch = GenerationEpoch()
        val record = runner(llm, ToolRegistry(listOf(clock)), inputBuilder, epoch).run(
            gid = epoch.next(),
            prompt = "full prompt",
            userText = "what time is it",
            asrMs = 0L,
            onDelta = {},
        )

        assertEquals(1, clock.calls)
        assertEquals(2, llm.prompts.size)
        // The second step is the tool response rendered for the warm session.
        assertEquals(ChatTemplate.CHATML.toolResponse("2026-06-10 15:00"), llm.prompts[1])
        // Spoken/visible text: preamble + final answer, NO JSON.
        assertEquals("Let me check. It is three in the afternoon.", record.replyText)
        assertFalse(record.replyText.contains("tool_call"))
        // TTS got both the preamble clause and the answer clause.
        assertTrue(inputBuilder.clauses.any { it.contains("Let me check.") })
        assertTrue(inputBuilder.clauses.any { it.contains("three in the afternoon") })
    }

    @Test
    fun stepLimitStopsRunawayToolChains() = runBlocking {
        val toolCall = "[TOOL_CALL]{\"name\":\"get_datetime\",\"arguments\":{}}[/TOOL_CALL]"
        val llm = ScriptedSessionLlm(listOf(toolCall, toolCall, toolCall, toolCall))
        val clock = FakeClockTool()
        val epoch = GenerationEpoch()

        runner(llm, ToolRegistry(listOf(clock)), RecordingInputBuilder(), epoch).run(
            gid = epoch.next(),
            prompt = "full prompt",
            userText = "loop forever",
            asrMs = 0L,
            onDelta = {},
        )

        // MAX_TOOL_STEPS generations -> at most MAX_TOOL_STEPS - 1 dispatches.
        assertEquals(SpeechTurnRunner.MAX_TOOL_STEPS, llm.prompts.size)
        assertEquals(SpeechTurnRunner.MAX_TOOL_STEPS - 1, clock.calls)
    }

    @Test
    fun bargedInTurnNeverExecutesTools() = runBlocking {
        val epoch = GenerationEpoch()
        val gid = epoch.next()
        epoch.cancel()   // barge-in before/while the model streams
        val llm = ScriptedSessionLlm(
            listOf("[TOOL_CALL]{\"name\":\"get_datetime\",\"arguments\":{}}[/TOOL_CALL]"),
        )
        val clock = FakeClockTool()

        runner(llm, ToolRegistry(listOf(clock)), RecordingInputBuilder(), epoch).run(
            gid = gid,
            prompt = "full prompt",
            userText = "set something dangerous",
            asrMs = 0L,
            onDelta = {},
        )

        assertEquals(0, clock.calls)
    }

    @Test
    fun useToolsFalseDisablesTheLoopEvenWithARegistry() = runBlocking {
        val llm = ScriptedSessionLlm(
            listOf("[TOOL_CALL]{\"name\":\"get_datetime\",\"arguments\":{}}[/TOOL_CALL]"),
        )
        val clock = FakeClockTool()
        val epoch = GenerationEpoch()

        runner(llm, ToolRegistry(listOf(clock)), RecordingInputBuilder(), epoch).run(
            gid = epoch.next(),
            prompt = "full prompt",
            userText = "what time is it",
            asrMs = 0L,
            onDelta = {},
            useTools = false,
        )

        assertEquals(0, clock.calls)
        assertEquals(1, llm.prompts.size)
    }

    @Test
    fun sessionIncapableEngineSkipsTheToolLoop() = runBlocking {
        val llm = PlainLlm("plain answer.")
        val clock = FakeClockTool()
        val epoch = GenerationEpoch()

        val record = runner(llm, ToolRegistry(listOf(clock)), RecordingInputBuilder(), epoch).run(
            gid = epoch.next(),
            prompt = "full prompt",
            userText = "hello",
            asrMs = 0L,
            onDelta = {},
        )

        assertEquals("plain answer.", record.replyText)
        assertEquals(0, clock.calls)
    }

    // --- fixtures ---

    private fun runner(
        llm: LlmEngine,
        tools: ToolRegistry,
        inputBuilder: ClauseInputBuilder,
        epoch: GenerationEpoch = GenerationEpoch(),
    ) = SpeechTurnRunner(
        llm = llm,
        tts = object : TtsEngine {
            override fun version() = "fake-tts"
            override fun synthesizeClause(inputs: TtsInputs, k: Int) = floatArrayOf(0f)
        },
        inputBuilder = inputBuilder,
        playerFactory = {
            object : PcmStreamPlayer {
                override fun start() = Unit
                override fun write(pcm: FloatArray) = Unit
                override fun interrupt() = Unit
                override fun stopAndRelease() = Unit
            }
        },
        generationEpoch = epoch,
        onPlayerStarted = {},
        onPlayerStopped = {},
        onState = {},
        onSpeakingStarted = {},
        tools = tools,
    )

    /** Session-capable engine that replays scripted step outputs and records step prompts. */
    private class ScriptedSessionLlm(private val steps: List<String>) : LlmEngine {
        val prompts = mutableListOf<String>()
        override fun name() = "scripted-llm"
        override fun generate(prompt: String, onToken: (String) -> Unit): LlmEngine.Result {
            prompts += prompt
            val out = steps.getOrNull(prompts.size - 1) ?: ""
            // Stream in small chunks to exercise the filter's split-marker handling.
            out.chunked(7).forEach(onToken)
            return LlmEngine.Result.OK
        }
        override fun abort() = Unit
        override val sessionCapable: Boolean get() = true
        override fun sessionWarm() = true
        override fun resetSession() = Unit
    }

    private class PlainLlm(private val response: String) : LlmEngine {
        override fun name() = "plain-llm"
        override fun generate(prompt: String, onToken: (String) -> Unit): LlmEngine.Result {
            onToken(response)
            return LlmEngine.Result.OK
        }
        override fun abort() = Unit
    }

    private class FakeClockTool : Tool {
        var calls = 0
        override val spec = ToolSpec("get_datetime", "current time", listOf(ToolParam("none", "unused", false)))
        override fun execute(args: Map<String, String>): ToolResult {
            calls += 1
            return ToolResult(true, "2026-06-10 15:00")
        }
    }

    private class RecordingInputBuilder : ClauseInputBuilder {
        val clauses = mutableListOf<String>()
        override fun build(text: String, lang: String, seed: Long): TtsInputs {
            clauses += text
            return TtsInputs(
                textIds = intArrayOf(0),
                textMask = floatArrayOf(1f),
                styleTtl = floatArrayOf(),
                styleDp = floatArrayOf(),
                noisyLatent = floatArrayOf(),
                latentMask = floatArrayOf(),
            )
        }
    }
}
