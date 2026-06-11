package com.conversationalai.agent.ui

import com.conversationalai.agent.core.ConversationController
import com.conversationalai.agent.llm.LlmCatalog
import com.conversationalai.agent.llm.LlmEngine
import com.conversationalai.agent.llm.LlmModelSpec

/**
 * Mediates the settings sheet: holds the persisted [AppSettings], applies what can apply LIVE
 * (sampling via [LlmEngine.setSampling]; tool/barge-in toggles on the conversation controller),
 * and reports what needs a relaunch (model selection — engines are resident for the process
 * lifetime). Every mutation persists through [store] and returns the fresh [SettingsUiState].
 */
class SettingsController(
    private val store: SettingsStore,
    private val activeModel: LlmModelSpec,
    private val llm: LlmEngine,
    private val controller: ConversationController,
    private val isProvisioned: (LlmModelSpec) -> Boolean,
) {
    private var settings: AppSettings = store.load()

    /** Apply the persisted, non-model settings at startup (model + sampling are applied by
     *  RuntimeInitializer before the engines warm up). */
    fun applyStartupSettings() {
        controller.bargeInEnabled = settings.bargeIn
        controller.setToolsEnabled(settings.toolsEnabled)
        controller.setConfirmActions(settings.confirmActions)
    }

    fun uiState(): SettingsUiState {
        val selectedId = settings.llmModelId ?: activeModel.id
        val sampling = settings.effectiveSampling(activeModel.sampling)
        return SettingsUiState(
            activeModelName = activeModel.displayName,
            models = LlmCatalog.ALL.map { spec ->
                LlmModelOption(
                    id = spec.id,
                    displayName = spec.displayName,
                    provisioned = isProvisioned(spec),
                    active = spec.id == activeModel.id,
                    selected = spec.id == selectedId,
                )
            },
            temp = sampling.temp,
            topK = sampling.topK,
            topP = sampling.topP,
            maxResponseTokens = settings.maxResponseTokens ?: LlmEngine.DEFAULT_MAX_RESPONSE_TOKENS,
            samplingOverridden = settings.hasSamplingOverride,
            toolsEnabled = settings.toolsEnabled,
            confirmActions = settings.confirmActions,
            restartRequired = selectedId != activeModel.id,
        )
    }

    /** Persist a model choice; the engine loads it on the NEXT app launch. */
    fun selectModel(id: String): SettingsUiState {
        update(settings.copy(llmModelId = id))
        return uiState()
    }

    /** Persist + apply sampling to the live engine (Genie applies in place; LiteRT-LM recreates
     *  its conversation, which also restarts that session). */
    fun setSampling(temp: Float, topK: Int, topP: Float): SettingsUiState {
        update(settings.copy(temp = temp, topK = topK, topP = topP))
        llm.setSampling(settings.effectiveSampling(activeModel.sampling))
        return uiState()
    }

    /** Persist + apply the per-turn generation cap to the live engine. */
    fun setMaxResponseTokens(maxTokens: Int): SettingsUiState {
        update(settings.copy(maxResponseTokens = maxTokens))
        llm.setMaxResponseTokens(maxTokens)
        return uiState()
    }

    /** Drop overrides and return to the active model's catalog defaults. */
    fun resetSampling(): SettingsUiState {
        update(settings.copy(temp = null, topK = null, topP = null))
        llm.setSampling(activeModel.sampling)
        return uiState()
    }

    fun toggleTools(): SettingsUiState {
        update(settings.copy(toolsEnabled = !settings.toolsEnabled))
        controller.setToolsEnabled(settings.toolsEnabled)
        return uiState()
    }

    fun toggleConfirmActions(): SettingsUiState {
        update(settings.copy(confirmActions = !settings.confirmActions))
        controller.setConfirmActions(settings.confirmActions)
        return uiState()
    }

    /** Barge-in default persists here; the live flag is owned by the conversation controller. */
    fun setBargeIn(enabled: Boolean) {
        update(settings.copy(bargeIn = enabled))
        controller.bargeInEnabled = enabled
    }

    private fun update(next: AppSettings) {
        settings = next
        store.save(next)
    }
}
