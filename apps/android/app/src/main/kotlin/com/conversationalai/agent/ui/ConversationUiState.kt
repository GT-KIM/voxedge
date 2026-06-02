package com.conversationalai.agent.ui

import com.conversationalai.agent.core.ConvState

enum class SpeechLoopUiState {
    IDLE,
    LISTENING,
    CAPTURING,
    TRANSCRIBING,
    GENERATING,
    SPEAKING,
    RECOVERING,
    ;

    companion object {
        fun from(convState: ConvState): SpeechLoopUiState = when (convState) {
            ConvState.IDLE -> IDLE
            ConvState.LISTENING -> LISTENING
            ConvState.CAPTURING -> CAPTURING
            ConvState.TRANSCRIBING -> TRANSCRIBING
            ConvState.GENERATING -> GENERATING
            ConvState.SPEAKING -> SPEAKING
            ConvState.RECOVERING -> RECOVERING
        }
    }
}

enum class RuntimeReadinessKind {
    ASR,
    LLM,
    TTS,
    VAD,
    MODEL_ASSETS,
    MICROPHONE,
}

enum class RuntimeReadinessStatus {
    READY,
    INITIALIZING,
    MISSING_ASSET,
    FAILED,
    BLOCKED,
}

data class RuntimeReadiness(
    val kind: RuntimeReadinessKind,
    val status: RuntimeReadinessStatus,
    val label: String,
    val detail: String = "",
)

enum class TranscriptRole {
    USER,
    ASSISTANT,
    STATUS,
}

data class TranscriptItem(
    val id: String,
    val role: TranscriptRole,
    val text: String,
    val isStreaming: Boolean = false,
    val interrupted: Boolean = false,
    val spokenContent: String = "",
)

data class LatencySummary(
    val summaryText: String = "",
    val asrMs: Long? = null,
    val ttftMs: Long? = null,
    val firstPcmMs: Long? = null,
    val totalMs: Long? = null,
)

data class ConversationUiState(
    val typedText: String = "",
    val busy: Boolean = false,
    val statusMessage: String = "",
    val llmOutput: String = "",
    val asrLanguage: String = "ko",
    val recording: Boolean = false,
    val handsFree: Boolean = false,
    val bargeIn: Boolean = false,
    val diagnosticsOpen: Boolean = false,
    val loopState: SpeechLoopUiState = SpeechLoopUiState.IDLE,
    val transcript: List<TranscriptItem> = emptyList(),
    val runtimeReadiness: List<RuntimeReadiness> = emptyList(),
    val latencySummary: LatencySummary = LatencySummary(),
    val lastError: String? = null,
) {
    fun readiness(kind: RuntimeReadinessKind): RuntimeReadinessStatus =
        runtimeReadiness.firstOrNull { it.kind == kind }?.status ?: RuntimeReadinessStatus.INITIALIZING

    fun isReady(kind: RuntimeReadinessKind): Boolean = readiness(kind) == RuntimeReadinessStatus.READY
}

fun buildRuntimeReadiness(
    ttsReady: Boolean,
    llmReady: Boolean,
    asrReady: Boolean,
    vadReady: Boolean,
    micGranted: Boolean,
): List<RuntimeReadiness> {
    val allAssetsReady = ttsReady && llmReady && asrReady && vadReady
    return listOf(
        RuntimeReadiness(RuntimeReadinessKind.ASR, readinessFromFlag(asrReady), "ASR"),
        RuntimeReadiness(RuntimeReadinessKind.LLM, readinessFromFlag(llmReady), "LLM"),
        RuntimeReadiness(RuntimeReadinessKind.TTS, readinessFromFlag(ttsReady), "TTS"),
        RuntimeReadiness(RuntimeReadinessKind.VAD, readinessFromFlag(vadReady), "VAD"),
        RuntimeReadiness(RuntimeReadinessKind.MODEL_ASSETS, readinessFromFlag(allAssetsReady), "Models"),
        RuntimeReadiness(
            RuntimeReadinessKind.MICROPHONE,
            if (micGranted) RuntimeReadinessStatus.READY else RuntimeReadinessStatus.BLOCKED,
            "Mic",
            if (micGranted) "" else "Permission required",
        ),
    )
}

fun buildTranscriptItems(
    convLine: String,
    convReply: String,
    llmOutput: String,
    statusMessage: String,
    loopState: SpeechLoopUiState,
): List<TranscriptItem> {
    val items = mutableListOf<TranscriptItem>()
    if (convLine.isNotBlank()) {
        items += TranscriptItem(id = "user-current", role = TranscriptRole.USER, text = convLine)
    }
    if (convReply.isNotBlank()) {
        items += TranscriptItem(
            id = "assistant-current",
            role = TranscriptRole.ASSISTANT,
            text = convReply,
            isStreaming = loopState == SpeechLoopUiState.GENERATING || loopState == SpeechLoopUiState.SPEAKING,
        )
    }
    if (llmOutput.isNotBlank() && convReply.isBlank()) {
        items += TranscriptItem(id = "assistant-debug", role = TranscriptRole.ASSISTANT, text = llmOutput)
    }
    if (items.isEmpty() && statusMessage.isNotBlank()) {
        items += TranscriptItem(id = "status", role = TranscriptRole.STATUS, text = statusMessage)
    }
    return items
}

private fun readinessFromFlag(ok: Boolean): RuntimeReadinessStatus =
    if (ok) RuntimeReadinessStatus.READY else RuntimeReadinessStatus.MISSING_ASSET
