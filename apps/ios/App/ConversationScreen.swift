import SwiftUI

struct ConversationScreen: View {
    let state: ConversationUiState
    let send: (ConversationAction) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                readinessStrip

                VStack(alignment: .leading, spacing: 4) {
                    Text("Conversation")
                        .font(.title2)
                    Text("state: \(state.loopState.rawValue)")
                        .font(.caption)
                }

                transcriptTimeline

                TextField(
                    "message",
                    text: Binding(
                        get: { state.typedText },
                        set: { send(.updateTypedText($0)) }
                    ),
                    axis: .vertical
                )
                .textFieldStyle(.roundedBorder)
                .disabled(state.recording)

                HStack {
                    Button("Send") { send(.submitTypedTurn) }
                        .disabled(!state.isReady(.llm) || !state.isReady(.tts) || state.busy)

                    Button(state.handsFree ? "Stop listening" : "Start listening") {
                        send(state.handsFree ? .stopHandsFree : .startHandsFree)
                    }
                    .disabled(!state.handsFree && (!state.isReady(.asr) || !state.isReady(.llm) || !state.isReady(.tts)))
                }

                HStack {
                    Button(state.recording ? "Stop & answer" : "Push to talk") {
                        send(state.recording ? .stopPushToTalk : .startPushToTalk)
                    }
                    .disabled(!state.isReady(.asr) || !state.isReady(.llm) || !state.isReady(.tts))

                    Button("Barge-in: \(state.bargeIn ? "ON" : "OFF")") {
                        send(.toggleBargeIn)
                    }

                    Button("ASR: \(state.asrLanguage.uppercased())") {
                        send(.changeLanguage(state.asrLanguage == "ko" ? "en" : "ko"))
                    }
                    .disabled(!state.isReady(.asr))
                }

                if state.busy || state.handsFree || state.recording {
                    Button("Cancel") { send(.cancelCurrentTurn) }
                }

                if !state.latencySummary.summaryText.isEmpty {
                    Text(state.latencySummary.summaryText)
                        .font(.caption)
                }

                if let lastError = state.lastError {
                    Text(lastError)
                        .foregroundStyle(.red)
                }

                Text(state.statusMessage)
                    .font(.body)

                Button(state.diagnosticsOpen ? "Close diagnostics" : "Diagnostics") {
                    send(state.diagnosticsOpen ? .closeDiagnostics : .openDiagnostics)
                }

                if state.diagnosticsOpen {
                    diagnosticsPanel
                }
            }
            .padding(24)
        }
    }

    private var readinessStrip: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(state.runtimeReadiness) { item in
                Text("\(item.label): \(item.status.rawValue)")
                    .font(.caption)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(.thinMaterial)
            }
        }
    }

    private var transcriptTimeline: some View {
        VStack(alignment: .leading, spacing: 8) {
            if state.transcript.isEmpty {
                Text("No turns yet.")
            } else {
                ForEach(state.transcript) { item in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.role.rawValue)
                            .font(.caption)
                        Text(item.text)
                        if item.interrupted && !item.spokenContent.isEmpty {
                            Text("spoken: \(item.spokenContent)")
                                .font(.caption)
                        } else if item.isStreaming {
                            Text("streaming")
                                .font(.caption)
                        }
                    }
                    .padding(12)
                    .background(.thinMaterial)
                }
            }
        }
    }

    private var diagnosticsPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Runtime diagnostics")
                .font(.headline)
            HStack {
                Button("Speak") { send(.runDebugSpeak) }
                    .disabled(!state.isReady(.tts) || state.busy)
                Button("Ask LLM") { send(.runDebugAskLlm) }
                    .disabled(!state.isReady(.llm) || state.busy)
            }
            HStack {
                Button("Converse") { send(.runDebugConverse) }
                    .disabled(!state.isReady(.tts) || !state.isReady(.llm) || state.busy)
                Button("ASR test (wav)") { send(.runDebugAsrTest) }
                    .disabled(!state.isReady(.asr) || state.busy)
            }
            if !state.llmOutput.isEmpty {
                Text(state.llmOutput)
                    .font(.caption)
            }
        }
    }
}

#Preview {
    ConversationScreen(state: .readyFixture(), send: { _ in })
}
