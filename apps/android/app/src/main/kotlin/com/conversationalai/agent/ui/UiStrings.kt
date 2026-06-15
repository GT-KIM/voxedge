package com.conversationalai.agent.ui

/**
 * UI string localization. The whole on-screen chrome follows the conversation language (the same
 * "ko"/"en" the ASR-language toggle controls), so an English session shows an all-English UI and a
 * Korean session shows an all-Korean UI — no half-translated demo. Pick with [uiStrings]; dynamic
 * labels (loop state, readiness status, tool action rows) are built by the helper methods so they
 * localize too.
 *
 * Raw Hangul literals are used for the Korean strings (they compile on this toolchain, as
 * SpokenTextNormalizer/PromptAssembler already rely on).
 */
data class UiStrings(
    val sessionsTitle: String,
    val newSessionPlus: String,
    val noSavedSessions: String,
    val noTurnsYet: String,
    val messageHint: String,
    val send: String,
    val working: String,
    val cancel: String,
    val pushToTalk: String,
    val stopAndAnswer: String,
    val settings: String,
    val closeSettings: String,
    val diag: String,
    val closeDiag: String,
    val sessionHeader: String,
    val newSession: String,
    val startListening: String,
    val stopListening: String,
    val asrPrefix: String,
    val generating: String,
    val interrupted: String,
    val interruptedSpokenPrefix: String,   // followed by the spoken text in quotes
    val modelUnknown: String,
    val ctxPrefix: String,                 // " - ctx" then "NN%"
    private val loopWords: Map<SpeechLoopUiState, String>,
    private val statusWords: Map<RuntimeReadinessStatus, String>,
    private val readinessLabels: Map<RuntimeReadinessKind, String>,
    private val ranWord: String,
    private val failedSuffix: String,
) {
    fun loopWord(s: SpeechLoopUiState): String = loopWords[s] ?: s.name.lowercase()

    fun statusWord(s: RuntimeReadinessStatus): String = statusWords[s] ?: s.name.lowercase()

    /** Readiness chip label: technical acronyms (ASR/LLM/TTS/VAD) pass through; the rest localize. */
    fun readinessLabel(kind: RuntimeReadinessKind, fallback: String): String =
        readinessLabels[kind] ?: fallback

    /** "working" status row under the feed. */
    fun workingLabel(s: SpeechLoopUiState): String = when (s) {
        SpeechLoopUiState.TRANSCRIBING -> loopWords[SpeechLoopUiState.TRANSCRIBING] ?: "transcribing..."
        SpeechLoopUiState.SPEAKING -> loopWords[SpeechLoopUiState.SPEAKING] ?: "speaking..."
        else -> loopWords[SpeechLoopUiState.GENERATING] ?: "thinking..."
    }

    /** Tool action row, e.g. EN "ran calculate" / KO "calculate 실행". [ok] appends the failed suffix. */
    fun actionRow(name: String, ok: Boolean): String =
        ranWord.format(name) + if (ok) "" else failedSuffix

    companion object {
        fun of(lang: String): UiStrings = if (lang == "ko") KO else EN

        private val EN = UiStrings(
            sessionsTitle = "Sessions",
            newSessionPlus = "+ New session",
            noSavedSessions = "No saved sessions yet.",
            noTurnsYet = "No turns yet - speak or type to start the session.",
            messageHint = "message",
            send = "Send",
            working = "Working...",
            cancel = "Cancel",
            pushToTalk = "Push to talk",
            stopAndAnswer = "Stop & answer",
            settings = "Settings",
            closeSettings = "Close settings",
            diag = "Diag",
            closeDiag = "Close diag",
            sessionHeader = "Session",
            newSession = "New session",
            startListening = "Start listening",
            stopListening = "Stop listening",
            asrPrefix = "ASR: ",
            generating = "generating...",
            interrupted = "interrupted",
            interruptedSpokenPrefix = "interrupted - spoken: ",
            modelUnknown = "model unknown",
            ctxPrefix = " - ctx",
            loopWords = mapOf(
                SpeechLoopUiState.IDLE to "idle",
                SpeechLoopUiState.LISTENING to "listening",
                SpeechLoopUiState.CAPTURING to "capturing",
                SpeechLoopUiState.TRANSCRIBING to "transcribing...",
                SpeechLoopUiState.GENERATING to "thinking...",
                SpeechLoopUiState.SPEAKING to "speaking...",
                SpeechLoopUiState.RECOVERING to "recovering",
            ),
            statusWords = mapOf(
                RuntimeReadinessStatus.READY to "ready",
                RuntimeReadinessStatus.INITIALIZING to "initializing",
                RuntimeReadinessStatus.MISSING_ASSET to "missing asset",
                RuntimeReadinessStatus.FAILED to "failed",
                RuntimeReadinessStatus.BLOCKED to "blocked",
            ),
            readinessLabels = mapOf(
                RuntimeReadinessKind.MODEL_ASSETS to "Models",
                RuntimeReadinessKind.MICROPHONE to "Mic",
            ),
            ranWord = "ran %s",
            failedSuffix = " (failed)",
        )

        private val KO = UiStrings(
            sessionsTitle = "세션",
            newSessionPlus = "+ 새 세션",
            noSavedSessions = "저장된 세션이 없어요.",
            noTurnsYet = "아직 대화가 없어요 - 말하거나 입력해서 시작하세요.",
            messageHint = "메시지",
            send = "보내기",
            working = "처리 중...",
            cancel = "취소",
            pushToTalk = "눌러서 말하기",
            stopAndAnswer = "멈추고 답하기",
            settings = "설정",
            closeSettings = "설정 닫기",
            diag = "진단",
            closeDiag = "진단 닫기",
            sessionHeader = "세션",
            newSession = "새 세션",
            startListening = "듣기 시작",
            stopListening = "듣기 멈춤",
            asrPrefix = "음성인식: ",
            generating = "생성 중...",
            interrupted = "중단됨",
            interruptedSpokenPrefix = "중단됨 - 말한 내용: ",
            modelUnknown = "모델 미상",
            ctxPrefix = " - 컨텍스트",
            loopWords = mapOf(
                SpeechLoopUiState.IDLE to "대기 중",
                SpeechLoopUiState.LISTENING to "듣는 중",
                SpeechLoopUiState.CAPTURING to "녹음 중",
                SpeechLoopUiState.TRANSCRIBING to "받아쓰는 중...",
                SpeechLoopUiState.GENERATING to "생각 중...",
                SpeechLoopUiState.SPEAKING to "말하는 중...",
                SpeechLoopUiState.RECOVERING to "복구 중",
            ),
            statusWords = mapOf(
                RuntimeReadinessStatus.READY to "준비됨",
                RuntimeReadinessStatus.INITIALIZING to "초기화 중",
                RuntimeReadinessStatus.MISSING_ASSET to "에셋 없음",
                RuntimeReadinessStatus.FAILED to "실패",
                RuntimeReadinessStatus.BLOCKED to "차단됨",
            ),
            readinessLabels = mapOf(
                RuntimeReadinessKind.MODEL_ASSETS to "모델",
                RuntimeReadinessKind.MICROPHONE to "마이크",
            ),
            ranWord = "%s 실행",
            failedSuffix = " (실패)",
        )
    }
}
