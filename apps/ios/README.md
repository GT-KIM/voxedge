# iOS Feasibility Track

Purpose: iOS implementation of the offline speech-to-speech conversational loop.

Planned responsibilities:

- Speech framework integration for ASR.
- Prompt assembly from shared configurable templates.
- ANEMLL or MLX integration for LLM inference.
- Core ML integration for TTS inference.
- MCP-formatted exchange between LLM output and TTS input.
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
