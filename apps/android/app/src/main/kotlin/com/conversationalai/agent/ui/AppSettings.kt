package com.conversationalai.agent.ui

import com.conversationalai.agent.llm.LlmSampling

/**
 * Persisted user configuration. Null sampling fields mean "use the active model's catalog
 * default" — so switching models keeps each model's tuned defaults unless the user explicitly
 * overrode them. Pure data + merge logic, JVM-tested in AppSettingsTest.
 */
data class AppSettings(
    /** LlmCatalog id; null = catalog default. Applied on next launch (engines are resident). */
    val llmModelId: String? = null,
    val temp: Float? = null,
    val topK: Int? = null,
    val topP: Float? = null,
    /** Per-turn generation cap; null = LlmEngine.DEFAULT_MAX_RESPONSE_TOKENS. */
    val maxResponseTokens: Int? = null,
    val bargeIn: Boolean = false,
    val toolsEnabled: Boolean = true,
) {
    val hasSamplingOverride: Boolean get() = temp != null || topK != null || topP != null

    /** The model's catalog sampling with any user overrides applied on top. */
    fun effectiveSampling(base: LlmSampling): LlmSampling = LlmSampling(
        temp = temp ?: base.temp,
        topK = topK ?: base.topK,
        topP = topP ?: base.topP,
    )
}
