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
    /** Tools the agent ran for this turn, e.g. "set_timer(ok)". */
    val tools: List<String> = emptyList(),
    /** Muted per-turn meta line (latency breakdown), rendered Codex-style under the turn. */
    val meta: String = "",
)

data class LatencySummary(
    val summaryText: String = "",
    val asrMs: Long? = null,
    val ttftMs: Long? = null,
    val firstPcmMs: Long? = null,
    val totalMs: Long? = null,
)

/** One selectable LLM in the settings sheet. [active] = loaded in this process;
 *  [selected] = persisted choice (loads on next launch when it differs from active). */
data class LlmModelOption(
    val id: String,
    val displayName: String,
    val provisioned: Boolean,
    val active: Boolean,
    val selected: Boolean,
)

data class SettingsUiState(
    val activeModelName: String = "",
    val models: List<LlmModelOption> = emptyList(),
    val temp: Float = 0.6f,
    val topK: Int = 20,
    val topP: Float = 0.8f,
    val maxResponseTokens: Int = 120,
    val samplingOverridden: Boolean = false,
    val toolsEnabled: Boolean = true,
    val confirmActions: Boolean = false,
    val restartRequired: Boolean = false,
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
    val settingsOpen: Boolean = false,
    val settings: SettingsUiState = SettingsUiState(),
    val loopState: SpeechLoopUiState = SpeechLoopUiState.IDLE,
    val transcript: List<TranscriptItem> = emptyList(),
    val runtimeReadiness: List<RuntimeReadiness> = emptyList(),
    val latencySummary: LatencySummary = LatencySummary(),
    val lastError: String? = null,
    /** Session header info: LLM context fill (null until the first turn) + active model name. */
    val contextOccupancyPercent: Int? = null,
    val activeModelName: String = "",
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

/** SESSION timeline: the accumulated turns plus the currently-streaming assistant reply. */
fun buildSessionTranscript(
    sessionItems: List<TranscriptItem>,
    streamingReply: String,
    loopState: SpeechLoopUiState,
): List<TranscriptItem> {
    if (streamingReply.isBlank()) return sessionItems
    return sessionItems + TranscriptItem(
        id = "streaming",
        role = TranscriptRole.ASSISTANT,
        text = streamingReply,
        isStreaming = loopState == SpeechLoopUiState.GENERATING || loopState == SpeechLoopUiState.SPEAKING,
    )
}

private fun readinessFromFlag(ok: Boolean): RuntimeReadinessStatus =
    if (ok) RuntimeReadinessStatus.READY else RuntimeReadinessStatus.MISSING_ASSET
