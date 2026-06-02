package com.conversationalai.agent.ui

sealed interface ConversationAction {
    data class UpdateTypedText(val text: String) : ConversationAction
    data class ChangeLanguage(val language: String) : ConversationAction

    data object SubmitTypedTurn : ConversationAction
    data object StartHandsFree : ConversationAction
    data object StopHandsFree : ConversationAction
    data object StartPushToTalk : ConversationAction
    data object StopPushToTalk : ConversationAction
    data object CancelCurrentTurn : ConversationAction
    data object ToggleBargeIn : ConversationAction
    data object OpenDiagnostics : ConversationAction
    data object CloseDiagnostics : ConversationAction

    data object RunDebugSpeak : ConversationAction
    data object RunDebugAskLlm : ConversationAction
    data object RunDebugConverse : ConversationAction
    data object RunDebugAsrTest : ConversationAction
}
