# iOS Feasibility Track

Purpose: iOS implementation of the offline speech-to-speech conversational loop.

Planned responsibilities (to mirror the now-shipping Android app):

- ASR (owned/offline preferred, e.g. sherpa-onnx, to match Android's Korean-zipformer / SenseVoice
  choice rather than OS cloud ASR).
- Prompt assembly from the shared composable modules (see `shared/prompts/base_system_prompt.md`).
- LLM inference — runtime TBD pending on-device measurement on Apple silicon (Core ML / MLX / ANEMLL
  / a LiteRT path); Android ships two backends (Qwen3-4B Genie + Gemma 4 E2B LiteRT-LM).
- Core ML integration for TTS inference (Supertonic).
- The streaming-event-stream + durable turn-record contract (`shared/mcp/`), not an "MCP-formatted"
  audio boundary (see that README's resolved decision).
- The agentic layer (on-device tools + durable memory) and per-language UI localization, mirroring
  Android.
- UI aligned with the Android application.

No Xcode project is generated yet. The repository now includes build-ready Swift source skeletons
for the cross-platform UI/backend bridge:

- `App/ConversationApp.swift`
- `App/ConversationScreen.swift`
- `App/ConversationStore.swift`
- `Core/ConversationUiState.swift`
- `Core/ConversationAction.swift`
- replaceable ASR/LLM/TTS/Audio protocols and stubs

The iOS UI mirrors the Android contract with SwiftUI + Observation. Runtime implementations remain
stubbed until ASR/LLM/TTS measurements are possible on a real Apple device or macOS CI/Xcode setup.

Current feasibility plan: `docs/design/ios_feasibility.md`.

Near-term rule: do not start broad iOS app implementation until the ASR/LLM/TTS runtime choices are measured on a real Apple device or macOS CI/Xcode environment.
