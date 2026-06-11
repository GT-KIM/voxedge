package com.conversationalai.agent.ui

import com.conversationalai.agent.asr.AsrEngine
import com.conversationalai.agent.audio.PcmStreamPlayer
import com.conversationalai.agent.core.ConversationController
import com.conversationalai.agent.llm.LlmCatalog
import com.conversationalai.agent.llm.LlmEngine
import com.conversationalai.agent.llm.LlmSampling
import com.conversationalai.agent.tts.ClauseInputBuilder
import com.conversationalai.agent.tts.TtsEngine
import com.conversationalai.agent.tts.TtsInputs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    @Test
    fun nullFieldsFallBackToModelDefaults() {
        val base = LlmSampling(temp = 0.6f, topK = 20, topP = 0.8f)
        assertEquals(base, AppSettings().effectiveSampling(base))
        assertFalse(AppSettings().hasSamplingOverride)
    }

    @Test
    fun overridesApplyOnTopOfModelDefaults() {
        val base = LlmSampling(temp = 0.6f, topK = 20, topP = 0.8f)
        val s = AppSettings(temp = 0.4f, topK = null, topP = null)
        assertEquals(LlmSampling(0.4f, 20, 0.8f), s.effectiveSampling(base))
        assertTrue(s.hasSamplingOverride)
    }
}

class SettingsControllerTest {

    private class MemoryStore(var stored: AppSettings = AppSettings()) : SettingsStore {
        var saves = 0
        override fun load(): AppSettings = stored
        override fun save(settings: AppSettings) { stored = settings; saves += 1 }
    }

    private class FakeLlm : LlmEngine {
        var sampling: LlmSampling? = null
        var resets = 0
        override fun name() = "fake"
        override fun generate(prompt: String, onToken: (String) -> Unit) = LlmEngine.Result.OK
        override fun abort() = Unit
        override val sessionCapable: Boolean get() = true
        override fun resetSession() { resets += 1 }
        override fun setSampling(sampling: LlmSampling): Boolean {
            this.sampling = sampling
            return true
        }
    }

    private fun conversationController(llm: LlmEngine) = ConversationController(
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
    )

    private fun controller(
        store: MemoryStore = MemoryStore(),
        llm: FakeLlm = FakeLlm(),
        provisioned: Set<String> = setOf(LlmCatalog.QWEN3_4B_GENIE.id),
    ) = SettingsController(
        store = store,
        activeModel = LlmCatalog.QWEN3_4B_GENIE,
        llm = llm,
        controller = conversationController(llm),
        isProvisioned = { spec -> spec.id in provisioned },
    )

    @Test
    fun uiStateMarksActiveSelectedAndProvisioned() {
        val state = controller().uiState()
        val qwen = state.models.first { it.id == LlmCatalog.QWEN3_4B_GENIE.id }
        val gemma = state.models.first { it.id == LlmCatalog.GEMMA4_E2B_LITERT.id }
        assertTrue(qwen.active)
        assertTrue(qwen.selected)
        assertTrue(qwen.provisioned)
        assertFalse(gemma.active)
        assertFalse(gemma.provisioned)
        assertFalse(state.restartRequired)
        assertEquals("Qwen3 4B (Genie NPU)", state.activeModelName)
    }

    @Test
    fun selectingAnotherModelPersistsAndFlagsRestart() {
        val store = MemoryStore()
        val c = controller(store = store, provisioned = LlmCatalog.ALL.map { it.id }.toSet())
        val state = c.selectModel(LlmCatalog.GEMMA4_E2B_LITERT.id)
        assertTrue(state.restartRequired)
        assertEquals(LlmCatalog.GEMMA4_E2B_LITERT.id, store.stored.llmModelId)
        assertTrue(state.models.first { it.id == LlmCatalog.GEMMA4_E2B_LITERT.id }.selected)
        // The active engine has NOT changed (resident until relaunch).
        assertTrue(state.models.first { it.id == LlmCatalog.QWEN3_4B_GENIE.id }.active)
    }

    @Test
    fun samplingAppliesLiveAndResets() {
        val store = MemoryStore()
        val llm = FakeLlm()
        val c = controller(store = store, llm = llm)

        val state = c.setSampling(temp = 0.4f, topK = 30, topP = 0.9f)
        assertEquals(LlmSampling(0.4f, 30, 0.9f), llm.sampling)
        assertTrue(state.samplingOverridden)
        assertEquals(0.4f, store.stored.temp)

        val reset = c.resetSampling()
        assertEquals(LlmCatalog.QWEN3_4B_GENIE.sampling, llm.sampling)
        assertFalse(reset.samplingOverridden)
        assertEquals(null, store.stored.temp)
    }

    @Test
    fun togglingToolsPersistsAndResetsTheSession() {
        val store = MemoryStore()
        val llm = FakeLlm()
        val c = controller(store = store, llm = llm)

        val off = c.toggleTools()
        assertFalse(off.toolsEnabled)
        assertFalse(store.stored.toolsEnabled)
        // Tool availability changes the system prompt -> the warm KV session must re-prefill.
        assertTrue(llm.resets >= 1)

        val on = c.toggleTools()
        assertTrue(on.toolsEnabled)
    }
}
