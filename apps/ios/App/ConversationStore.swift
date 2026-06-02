import Foundation
import Observation

@MainActor
@Observable
final class ConversationStore {
    var state: ConversationUiState
    private let backend: ConversationBackend

    init(
        state: ConversationUiState = .readyFixture(),
        backend: ConversationBackend = StubConversationBackend()
    ) {
        self.state = state
        self.backend = backend
    }

    func send(_ action: ConversationAction) {
        switch action {
        case .updateTypedText(let text):
            state.typedText = text
        case .changeLanguage(let language):
            state.asrLanguage = language
            run { try await backend.changeLanguage(language) }
        case .submitTypedTurn:
            state.loopState = .generating
            run { try await backend.submitTypedTurn(state.typedText) }
        case .startHandsFree:
            state.handsFree = true
            state.loopState = .listening
            run { try await backend.startHandsFree() }
        case .stopHandsFree:
            state.handsFree = false
            state.loopState = .idle
            Task { await backend.stopHandsFree() }
        case .startPushToTalk:
            state.recording = true
            state.loopState = .capturing
            run { try await backend.startPushToTalk() }
        case .stopPushToTalk:
            state.recording = false
            state.loopState = .transcribing
            run { try await backend.stopPushToTalk() }
        case .cancelCurrentTurn:
            state.recording = false
            state.handsFree = false
            state.busy = false
            state.loopState = .idle
            state.statusMessage = "cancelled"
            Task { await backend.cancelCurrentTurn() }
        case .toggleBargeIn:
            state.bargeIn.toggle()
        case .openDiagnostics:
            state.diagnosticsOpen = true
        case .closeDiagnostics:
            state.diagnosticsOpen = false
        case .runDebugSpeak:
            run { try await backend.runDebugSpeak(state.typedText) }
        case .runDebugAskLlm:
            run { try await backend.runDebugAskLlm(state.typedText) }
        case .runDebugConverse:
            run { try await backend.runDebugConverse(state.typedText) }
        case .runDebugAsrTest:
            run { try await backend.runDebugAsrTest() }
        }
    }

    private func run(_ operation: @escaping () async throws -> Void) {
        state.busy = true
        Task {
            do {
                try await operation()
                state.busy = false
                state.lastError = nil
            } catch {
                state.busy = false
                state.lastError = error.localizedDescription
                state.statusMessage = error.localizedDescription
            }
        }
    }
}
