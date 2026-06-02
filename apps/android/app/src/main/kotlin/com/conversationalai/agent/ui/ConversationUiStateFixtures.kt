package com.conversationalai.agent.ui

object ConversationUiStateFixtures {
    fun ready(): ConversationUiState = base(
        transcript = listOf(
            TranscriptItem("u1", TranscriptRole.USER, "you: Hello"),
            TranscriptItem("a1", TranscriptRole.ASSISTANT, "Hello. How can I help?"),
        ),
    )

    fun missingAssets(): ConversationUiState = base(
        statusMessage = "models missing",
        runtimeReadiness = listOf(
            RuntimeReadiness(RuntimeReadinessKind.ASR, RuntimeReadinessStatus.MISSING_ASSET, "ASR"),
            RuntimeReadiness(RuntimeReadinessKind.LLM, RuntimeReadinessStatus.MISSING_ASSET, "LLM"),
            RuntimeReadiness(RuntimeReadinessKind.TTS, RuntimeReadinessStatus.MISSING_ASSET, "TTS"),
            RuntimeReadiness(RuntimeReadinessKind.VAD, RuntimeReadinessStatus.MISSING_ASSET, "VAD"),
            RuntimeReadiness(RuntimeReadinessKind.MODEL_ASSETS, RuntimeReadinessStatus.MISSING_ASSET, "Models"),
            RuntimeReadiness(RuntimeReadinessKind.MICROPHONE, RuntimeReadinessStatus.BLOCKED, "Mic"),
        ),
    )

    fun listening(): ConversationUiState = base(loopState = SpeechLoopUiState.LISTENING, handsFree = true)

    fun generating(): ConversationUiState = base(
        busy = true,
        loopState = SpeechLoopUiState.GENERATING,
        transcript = listOf(
            TranscriptItem("u1", TranscriptRole.USER, "you: Tell me a short joke."),
            TranscriptItem("a1", TranscriptRole.ASSISTANT, "Sure,", isStreaming = true),
        ),
    )

    fun speaking(): ConversationUiState = generating().copy(loopState = SpeechLoopUiState.SPEAKING)

    fun bargeIn(): ConversationUiState = speaking().copy(bargeIn = true)

    fun error(): ConversationUiState = base(
        statusMessage = "ASR language switch failed",
        lastError = "ASR language switch failed",
    )

    private fun base(
        typedText: String = "Hello. Nice to meet you.",
        busy: Boolean = false,
        statusMessage: String = "ready",
        loopState: SpeechLoopUiState = SpeechLoopUiState.IDLE,
        transcript: List<TranscriptItem> = emptyList(),
        runtimeReadiness: List<RuntimeReadiness> = buildRuntimeReadiness(
            ttsReady = true,
            llmReady = true,
            asrReady = true,
            vadReady = true,
            micGranted = true,
        ),
        handsFree: Boolean = false,
        lastError: String? = null,
    ): ConversationUiState = ConversationUiState(
        typedText = typedText,
        busy = busy,
        statusMessage = statusMessage,
        loopState = loopState,
        transcript = transcript,
        runtimeReadiness = runtimeReadiness,
        handsFree = handsFree,
        lastError = lastError,
    )
}
