import Foundation

enum SpeechLoopUiState: String, CaseIterable, Equatable {
    case idle
    case listening
    case capturing
    case transcribing
    case generating
    case speaking
    case recovering
}

enum RuntimeReadinessKind: String, CaseIterable, Equatable {
    case asr
    case llm
    case tts
    case vad
    case modelAssets
    case microphone
}

enum RuntimeReadinessStatus: String, CaseIterable, Equatable {
    case ready
    case initializing
    case missingAsset
    case failed
    case blocked
}

struct RuntimeReadiness: Identifiable, Equatable {
    var id: RuntimeReadinessKind { kind }
    let kind: RuntimeReadinessKind
    var status: RuntimeReadinessStatus
    var label: String
    var detail: String
}

enum TranscriptRole: String, CaseIterable, Equatable {
    case user
    case assistant
    case status
}

struct TranscriptItem: Identifiable, Equatable {
    let id: String
    var role: TranscriptRole
    var text: String
    var isStreaming: Bool
    var interrupted: Bool
    var spokenContent: String
}

struct LatencySummary: Equatable {
    var summaryText: String
    var asrMs: Int?
    var ttftMs: Int?
    var firstPcmMs: Int?
    var totalMs: Int?
}

struct ConversationUiState: Equatable {
    var typedText: String
    var busy: Bool
    var statusMessage: String
    var llmOutput: String
    var asrLanguage: String
    var recording: Bool
    var handsFree: Bool
    var bargeIn: Bool
    var diagnosticsOpen: Bool
    var loopState: SpeechLoopUiState
    var transcript: [TranscriptItem]
    var runtimeReadiness: [RuntimeReadiness]
    var latencySummary: LatencySummary
    var lastError: String?

    func isReady(_ kind: RuntimeReadinessKind) -> Bool {
        runtimeReadiness.first { $0.kind == kind }?.status == .ready
    }

    static func readyFixture() -> ConversationUiState {
        ConversationUiState(
            typedText: "Hello. Nice to meet you.",
            busy: false,
            statusMessage: "ready",
            llmOutput: "",
            asrLanguage: "ko",
            recording: false,
            handsFree: false,
            bargeIn: false,
            diagnosticsOpen: false,
            loopState: .idle,
            transcript: [
                TranscriptItem(
                    id: "u1",
                    role: .user,
                    text: "you: Hello",
                    isStreaming: false,
                    interrupted: false,
                    spokenContent: ""
                ),
                TranscriptItem(
                    id: "a1",
                    role: .assistant,
                    text: "Hello. How can I help?",
                    isStreaming: false,
                    interrupted: false,
                    spokenContent: ""
                ),
            ],
            runtimeReadiness: RuntimeReadiness.readyAll(),
            latencySummary: LatencySummary(summaryText: "", asrMs: nil, ttftMs: nil, firstPcmMs: nil, totalMs: nil),
            lastError: nil
        )
    }
}

extension RuntimeReadiness {
    static func readyAll() -> [RuntimeReadiness] {
        [
            RuntimeReadiness(kind: .asr, status: .ready, label: "ASR", detail: ""),
            RuntimeReadiness(kind: .llm, status: .ready, label: "LLM", detail: ""),
            RuntimeReadiness(kind: .tts, status: .ready, label: "TTS", detail: ""),
            RuntimeReadiness(kind: .vad, status: .ready, label: "VAD", detail: ""),
            RuntimeReadiness(kind: .modelAssets, status: .ready, label: "Models", detail: ""),
            RuntimeReadiness(kind: .microphone, status: .ready, label: "Mic", detail: ""),
        ]
    }
}
