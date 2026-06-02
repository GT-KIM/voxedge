# Cross-Platform Frontend + Backend Bridge Plan

Status: implemented as a first contract/skeleton pass on 2026-05-31.

## Decision

Use **Native Mirror** for the product UI:

- Android: Jetpack Compose + Material 3.
- iOS: SwiftUI + Observation.
- Shared UX is enforced by matching state/action names, shared readiness concepts, MCP event names,
  prompt/config contracts, and conformance tests.

The frontend/backend bridge is an **in-process typed event/action bridge**. Do not add a local
HTTP server, localhost API, WebSocket bridge, or runtime network dependency for the conversation
loop.

## UI Contract

Both platforms expose these frontend types:

- Android: `ConversationUiState`, `ConversationAction`, `ConversationRoute`.
- iOS: `ConversationUiState`, `ConversationAction`, `ConversationStore`.

`ConversationUiState` includes:

- `SpeechLoopUiState`: `IDLE/LISTENING/CAPTURING/TRANSCRIBING/GENERATING/SPEAKING/RECOVERING`
  on Android and the same lower-case cases on iOS.
- Transcript rows for user, assistant, status, streaming, interrupted, and spoken-content display.
- Runtime readiness for `ASR`, `LLM`, `TTS`, `VAD`, `MODEL_ASSETS`, and `MICROPHONE`.
- Controls for typed input, hands-free mic, push-to-talk, cancel, barge-in, ASR language, and
  diagnostics.
- Diagnostics fields for latency summary, last error, LLM debug output, and existing runtime
  debug actions.

`ConversationAction` includes:

- `UpdateTypedText`
- `SubmitTypedTurn`
- `StartHandsFree` / `StopHandsFree`
- `StartPushToTalk` / `StopPushToTalk`
- `CancelCurrentTurn`
- `ToggleBargeIn`
- `ChangeLanguage`
- `OpenDiagnostics` / `CloseDiagnostics`
- `RunDebugSpeak`, `RunDebugAskLlm`, `RunDebugConverse`, `RunDebugAsrTest`

## Backend Bridge

Android:

- `ConversationRoute` owns local Compose UI state, maps runtime readiness into
  `ConversationUiState`, and dispatches `ConversationAction`.
- `ConversationController` remains the backend owner for ASR -> LLM -> TTS -> playback.
- Existing debug actions remain reachable only through `DebugRuntimePanel`.

iOS:

- `ConversationStore` is a root-owned `@Observable` state holder.
- It maps `ConversationAction` into a replaceable `ConversationBackend` protocol.
- Apple Speech/owned ASR, ANEMLL/MLX LLM, and Core ML TTS remain behind runtime interfaces until
  real Apple-device measurements are available.

Shared contracts:

- `shared/mcp/conversation_events.schema.json`
- `shared/config/config.schema.json`
- `shared/prompts/base_system_prompt.md`

## Event Mapping

Both platforms define the same `McpEventType` wire names from the shared schema:

`session.start`, `session.end`, `asr.partial`, `asr.final`, `prompt.assembled`,
`llm.text_delta`, `llm.turn_end`, `tts.chunk_request`, `tts.audio_chunk`,
`tts.playback_start`, `tts.playback_end`, `control.barge_in`, `control.cancel`,
`runtime.thermal`, `runtime.degrade`, and `error`.

## Testing

- Python conformance tests compare Android/iOS state names, action names, readiness kinds, and MCP
  event wire names.
- Android contract tests verify `ConversationScreen` consumes one `ConversationUiState` plus one
  `ConversationAction` handler, and that diagnostics still expose the current debug actions.
- The iOS skeleton remains build-ready source only until a Mac/Xcode or macOS CI path is available.
