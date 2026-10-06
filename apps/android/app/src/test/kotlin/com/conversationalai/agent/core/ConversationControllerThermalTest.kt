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
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thermal degrade through the controller: response cap, TTS flow steps, and the turn pause. */
class ConversationControllerThermalTest {

    private class FakeLlm : LlmEngine {
        val caps = mutableListOf<Int>()
        var generates = 0
        override fun name() = "fake-llm"
        override fun generate(prompt: String, onToken: (String) -> Unit): LlmEngine.Result {
            generates += 1
            onToken("Short answer.")
            return LlmEngine.Result.OK
        }
        override fun abort() = Unit
        override fun setMaxResponseTokens(maxTokens: Int): Boolean { caps += maxTokens; return true }
    }

    private class RecordingTts : TtsEngine {
        val ks = mutableListOf<Int>()
        override fun version() = "fake-tts"
        override fun synthesizeClause(inputs: TtsInputs, k: Int): FloatArray { ks += k; return floatArrayOf(0f) }
    }

    private fun controller(llm: LlmEngine, tts: TtsEngine, onThermal: (ThermalPolicy.Level, List<String>) -> Unit = { _, _ -> }) =
        ConversationController(
            vadModelPath = "unused",
            enhancer = null,
            asr = object : AsrEngine {
                override fun name() = "fake-asr"
                override fun transcribe(samples: FloatArray, sampleRate: Int) = ""
            },
            llm = llm,
            tts = tts,
            inputBuilder = object : ClauseInputBuilder {
                override fun build(text: String, lang: String, seed: Long) = TtsInputs(
                    textIds = intArrayOf(0), textMask = floatArrayOf(1f), styleTtl = floatArrayOf(),
                    styleDp = floatArrayOf(), noisyLatent = floatArrayOf(), latentMask = floatArrayOf(),
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
            onThermal = onThermal,
        )

    @Test
    fun elevatedCapsTheResponseTokensAndFlowStepsThenRestores() = runBlocking {
        val llm = FakeLlm()
        val tts = RecordingTts()
        val changes = mutableListOf<String>()
        val c = controller(llm, tts) { level, actions -> changes += "${level.wire}:${actions.joinToString("+")}" }
        c.setMaxResponseTokens(120)
        c.ttsFlowSteps = 6
        c.runTurn("hello") {}
        assertEquals(listOf(6), tts.ks)

        c.onThermalStatus(ThermalPolicy.STATUS_MODERATE, headroom = 0.8f)
        assertEquals(60, llm.caps.last())
        c.runTurn("hello again") {}
        assertEquals(5, tts.ks.last())
        assertEquals(listOf("elevated:reduce_flow_steps+shorten_response"), changes)

        c.onThermalStatus(ThermalPolicy.STATUS_NONE)
        assertEquals(120, llm.caps.last())
        c.runTurn("and again") {}
        assertEquals(6, tts.ks.last())
        assertEquals("nominal:", changes.last())
    }

    @Test
    fun criticalPausesTypedTurnsWithoutGenerating() = runBlocking {
        val llm = FakeLlm()
        val c = controller(llm, RecordingTts())
        c.onThermalStatus(ThermalPolicy.STATUS_SEVERE)
        val rec = c.runTurn("are you there") {}
        assertEquals(0, llm.generates)
        assertEquals("", rec.replyText)
        assertEquals(40, llm.caps.last())
        assertTrue(c.thermal.pauseNewTurns)

        c.onThermalStatus(ThermalPolicy.STATUS_NONE)
        c.runTurn("back") {}
        assertEquals(1, llm.generates)
    }

    @Test
    fun userSettingChangesGoThroughTheThermalCap() {
        val llm = FakeLlm()
        val c = controller(llm, RecordingTts())
        c.onThermalStatus(ThermalPolicy.STATUS_MODERATE)
        c.setMaxResponseTokens(200)
        assertEquals(100, llm.caps.last())
        c.setMaxResponseTokens(50)
        assertEquals(40, llm.caps.last())
    }
}
