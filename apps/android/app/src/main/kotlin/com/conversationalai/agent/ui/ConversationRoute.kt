package com.conversationalai.agent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.conversationalai.agent.core.ConvState

@Composable
fun ConversationRoute(
    status: String,
    convState: ConvState,
    sessionItems: List<TranscriptItem>,
    streamingReply: String,
    latencyLine: String,
    contextOccupancyPercent: Int?,
    activeModelName: String,
    sessions: List<SessionSummary>,
    currentSessionId: String,
    initOk: Boolean,
    llmOk: Boolean,
    asrOk: Boolean,
    vadOk: Boolean,
    micGranted: Boolean,
    initialAsrLanguage: String,
    initialBargeIn: Boolean,
    settingsController: SettingsController,
    onRequestMicPermission: () -> Unit,
    onNewSession: ((String) -> Unit) -> Unit,
    onSelectSession: (String, (String) -> Unit) -> Unit,
    onSpeak: (String, (Boolean) -> Unit, (String) -> Unit) -> Unit,
    onAskLlm: (String, () -> Unit, (String) -> Unit, (Boolean) -> Unit, (String) -> Unit) -> Unit,
    onConverse: (String, () -> Unit, (String) -> Unit, (Boolean) -> Unit, (String) -> Unit) -> Unit,
    onChangeLanguage: (String, (String) -> Unit, (Boolean) -> Unit, (String) -> Unit) -> Unit,
    onStartRecording: ((Boolean) -> Unit, (Boolean) -> Unit, () -> Unit, (String) -> Unit) -> Unit,
    onStopRecording: ((Boolean) -> Unit, (Boolean) -> Unit, (String) -> Unit, (String) -> Unit) -> Unit,
    onToggleConversation: (Boolean, (Boolean) -> Unit, () -> Unit) -> Unit,
    onToggleBargeIn: (Boolean, (Boolean) -> Unit) -> Unit,
    onCancelCurrentTurn: ((Boolean) -> Unit, (Boolean) -> Unit, (Boolean) -> Unit, (String) -> Unit) -> Unit,
    onAsrTest: ((Boolean) -> Unit, (String) -> Unit) -> Unit,
) {
    var text by remember { mutableStateOf("\uc548\ub155\ud558\uc138\uc694. \ub9cc\ub098\uc11c \ubc18\uac11\uc2b5\ub2c8\ub2e4.") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf(status) }
    var llmOut by remember { mutableStateOf("") }
    var asrLang by remember { mutableStateOf(initialAsrLanguage) }
    var recording by remember { mutableStateOf(false) }
    var conv by remember { mutableStateOf(false) }
    var bargeIn by remember { mutableStateOf(initialBargeIn) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var sessionsOpen by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(settingsController.uiState()) }

    LaunchedEffect(status) {
        msg = status
    }

    val loopState = SpeechLoopUiState.from(convState)
    val uiState = ConversationUiState(
        typedText = text,
        busy = busy,
        statusMessage = msg,
        llmOutput = llmOut,
        asrLanguage = asrLang,
        recording = recording,
        handsFree = conv,
        bargeIn = bargeIn,
        diagnosticsOpen = diagnosticsOpen,
        settingsOpen = settingsOpen,
        settings = settings,
        loopState = loopState,
        transcript = buildSessionTranscript(sessionItems, streamingReply, loopState),
        runtimeReadiness = buildRuntimeReadiness(
            ttsReady = initOk,
            llmReady = llmOk,
            asrReady = asrOk,
            vadReady = vadOk,
            micGranted = micGranted,
        ),
        latencySummary = LatencySummary(summaryText = latencyLine),
        lastError = msg.takeIf { it.contains("failed", ignoreCase = true) || it.contains("denied", ignoreCase = true) },
        contextOccupancyPercent = contextOccupancyPercent,
        activeModelName = activeModelName,
        sessions = sessions,
        currentSessionId = currentSessionId,
        sessionsOpen = sessionsOpen,
    )

    ConversationScreen(state = uiState) { action ->
        when (action) {
            is ConversationAction.UpdateTypedText -> text = action.text
            is ConversationAction.ChangeLanguage -> {
                onChangeLanguage(action.language, { asrLang = it }, { busy = it }, { msg = it })
            }
            ConversationAction.OpenSettings -> {
                settings = settingsController.uiState()
                settingsOpen = true
            }
            ConversationAction.CloseSettings -> settingsOpen = false
            is ConversationAction.SelectLlmModel -> {
                settings = settingsController.selectModel(action.modelId)
                if (settings.restartRequired) msg = "Restart the app to load the selected model."
            }
            is ConversationAction.SetSampling -> {
                settings = settingsController.setSampling(action.temp, action.topK, action.topP)
                msg = "sampling: temp=%.2f top-k=%d top-p=%.2f".format(action.temp, action.topK, action.topP)
            }
            is ConversationAction.SetMaxResponseTokens -> {
                settings = settingsController.setMaxResponseTokens(action.maxTokens)
                msg = "max response tokens: ${action.maxTokens}"
            }
            ConversationAction.ResetSampling -> {
                settings = settingsController.resetSampling()
                msg = "sampling reset to model defaults"
            }
            ConversationAction.ToggleTools -> {
                settings = settingsController.toggleTools()
            }
            ConversationAction.ToggleConfirmActions -> {
                settings = settingsController.toggleConfirmActions()
            }
            ConversationAction.SubmitTypedTurn -> {
                onConverse(text, { llmOut = "" }, { llmOut += it }, { busy = it }, { msg = it })
            }
            ConversationAction.NewSession -> {
                sessionsOpen = false
                onNewSession { msg = it }
            }
            ConversationAction.OpenSessions -> sessionsOpen = true
            ConversationAction.CloseSessions -> sessionsOpen = false
            is ConversationAction.SelectSession -> {
                sessionsOpen = false
                onSelectSession(action.sessionId) { msg = it }
            }
            ConversationAction.StartHandsFree -> {
                if (!micGranted) {
                    onRequestMicPermission()
                } else {
                    onToggleConversation(false, { conv = it }, { llmOut = "" })
                }
            }
            ConversationAction.StopHandsFree -> {
                onToggleConversation(true, { conv = it }, {})
            }
            ConversationAction.StartPushToTalk -> {
                if (!micGranted) {
                    onRequestMicPermission()
                } else {
                    onStartRecording({ recording = it }, { busy = it }, { llmOut = "" }, { msg = it })
                }
            }
            ConversationAction.StopPushToTalk -> {
                onStopRecording({ recording = it }, { busy = it }, { msg = it }, { llmOut += it })
            }
            ConversationAction.CancelCurrentTurn -> {
                onCancelCurrentTurn({ recording = it }, { conv = it }, { busy = it }, { msg = it })
            }
            ConversationAction.ToggleBargeIn -> {
                onToggleBargeIn(bargeIn) { bargeIn = it }
            }
            ConversationAction.OpenDiagnostics -> diagnosticsOpen = true
            ConversationAction.CloseDiagnostics -> diagnosticsOpen = false
            ConversationAction.RunDebugSpeak -> {
                onSpeak(text, { busy = it }, { msg = it })
            }
            ConversationAction.RunDebugAskLlm -> {
                onAskLlm(text, { llmOut = "" }, { llmOut += it }, { busy = it }, { msg = it })
            }
            ConversationAction.RunDebugConverse -> {
                onConverse(text, { llmOut = "" }, { llmOut += it }, { busy = it }, { msg = it })
            }
            ConversationAction.RunDebugAsrTest -> {
                onAsrTest({ busy = it }, { msg = it })
            }
        }
    }
}
