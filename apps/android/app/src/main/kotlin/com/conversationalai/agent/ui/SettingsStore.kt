package com.conversationalai.agent.ui

import android.content.Context

/** Persistence boundary for [AppSettings]; in-memory fakes implement this in JVM tests. */
interface SettingsStore {
    fun load(): AppSettings
    fun save(settings: AppSettings)
}

/** SharedPreferences-backed store (single source of truth for the pref file + key names). */
class PrefsSettingsStore(context: Context) : SettingsStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): AppSettings = AppSettings(
        llmModelId = prefs.getString(KEY_LLM_MODEL_ID, null),
        temp = if (prefs.contains(KEY_TEMP)) prefs.getFloat(KEY_TEMP, 0f) else null,
        topK = if (prefs.contains(KEY_TOP_K)) prefs.getInt(KEY_TOP_K, 0) else null,
        topP = if (prefs.contains(KEY_TOP_P)) prefs.getFloat(KEY_TOP_P, 0f) else null,
        maxResponseTokens = if (prefs.contains(KEY_MAX_TOKENS)) prefs.getInt(KEY_MAX_TOKENS, 0) else null,
        bargeIn = prefs.getBoolean(KEY_BARGE_IN, false),
        toolsEnabled = prefs.getBoolean(KEY_TOOLS_ENABLED, true),
    )

    override fun save(settings: AppSettings) {
        prefs.edit().apply {
            if (settings.llmModelId != null) putString(KEY_LLM_MODEL_ID, settings.llmModelId)
            else remove(KEY_LLM_MODEL_ID)
            if (settings.temp != null) putFloat(KEY_TEMP, settings.temp) else remove(KEY_TEMP)
            if (settings.topK != null) putInt(KEY_TOP_K, settings.topK) else remove(KEY_TOP_K)
            if (settings.topP != null) putFloat(KEY_TOP_P, settings.topP) else remove(KEY_TOP_P)
            if (settings.maxResponseTokens != null) putInt(KEY_MAX_TOKENS, settings.maxResponseTokens)
            else remove(KEY_MAX_TOKENS)
            putBoolean(KEY_BARGE_IN, settings.bargeIn)
            putBoolean(KEY_TOOLS_ENABLED, settings.toolsEnabled)
        }.apply()
    }

    companion object {
        const val PREFS_NAME = "voxedge_settings"
        const val KEY_LLM_MODEL_ID = "llm_model_id"
        const val KEY_TEMP = "llm_temp"
        const val KEY_TOP_K = "llm_top_k"
        const val KEY_TOP_P = "llm_top_p"
        const val KEY_MAX_TOKENS = "llm_max_response_tokens"
        const val KEY_BARGE_IN = "barge_in"
        const val KEY_TOOLS_ENABLED = "tools_enabled"
    }
}
