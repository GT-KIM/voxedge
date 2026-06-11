package com.conversationalai.agent.ui

sealed interface ConversationAction {
    data class UpdateTypedText(val text: String) : ConversationAction
    data class ChangeLanguage(val language: String) : ConversationAction

    data object SubmitTypedTurn : ConversationAction
    /** Start a fresh session: clears the transcript timeline, history, summary, and KV cache. */
    data object NewSession : ConversationAction
    /** Session drawer (left): open/close, and switch to a persisted session. */
    data object OpenSessions : ConversationAction
    data object CloseSessions : ConversationAction
    data class SelectSession(val sessionId: String) : ConversationAction
    data object StartHandsFree : ConversationAction
    data object StopHandsFree : ConversationAction
    data object StartPushToTalk : ConversationAction
    data object StopPushToTalk : ConversationAction
    data object CancelCurrentTurn : ConversationAction
    data object ToggleBargeIn : ConversationAction
    data object OpenDiagnostics : ConversationAction
    data object CloseDiagnostics : ConversationAction

    // Settings sheet (model selection applies on next launch; the rest applies live + persists).
    data object OpenSettings : ConversationAction
    data object CloseSettings : ConversationAction
    data class SelectLlmModel(val modelId: String) : ConversationAction
    data class SetSampling(val temp: Float, val topK: Int, val topP: Float) : ConversationAction
    data class SetMaxResponseTokens(val maxTokens: Int) : ConversationAction
    data object ResetSampling : ConversationAction
    data object ToggleTools : ConversationAction
    data object ToggleConfirmActions : ConversationAction

    data object RunDebugSpeak : ConversationAction
    data object RunDebugAskLlm : ConversationAction
    data object RunDebugConverse : ConversationAction
    data object RunDebugAsrTest : ConversationAction
}
