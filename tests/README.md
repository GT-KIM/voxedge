# Tests

Tests are the feedback loop for this project.

Current coverage:

- Phase 0 model source manifest shape and safety flags.
- Phase 1 Qualcomm model preparation manifest and script readiness.
- Android controller boundary: the conversation state machine depends on ASR/LLM/TTS interfaces,
  not concrete runtime implementations.
- Android turn execution boundary: LLM->TTS->PCM playback is extracted from the controller, and
  streamed playback is behind an interface/factory seam.
- Android cancellation boundary: generation epoch/cancel checks are encapsulated, and active
  playback is cleared only by player identity.
- Android runner behavior: local JVM tests use fake LLM/TTS/input-builder/player to verify current
  and stale generation playback behavior.
- Android state transitions: speech-loop state changes go through a transition helper with JVM
  tests for happy path, barge-in, invalid transitions, and stop-to-idle.
- Android runtime initialization: concrete engine setup and asset probing are delegated out of
  MainActivity into RuntimeInitializer.
- Android screen rendering: MainActivity delegates Compose layout/buttons to ConversationScreen
  while retaining current action wiring.
- Android UI actions: MainActivity.Screen delegates long-running TTS/LLM/ASR work to private action
  handlers instead of embedding it inside the composable body.
- iOS feasibility: docs capture offline ASR gating, ANEMLL/MLX LLM choices, Core ML TTS path, and
  Mac/device testing constraints before broad implementation starts.
- Network-free Android runtime evidence: manifest has no network permission, source has no normal
  app-layer network API, and ASR/LLM/TTS load local model artifacts.
- Public README guardrails: top-level README leads with the network-free voice-agent proof point,
  links evidence docs, and states current limitations honestly.
- Runtime event logging: Android writes app-private JSONL events for ASR, LLM, TTS, playback, and
  turn-level latency evidence.
- MCP contract: shared fixtures are valid JSON-RPC 2.0 / MCP messages; Android and iOS pin the same
  protocol revision, methods, server and tool names; tool dispatch and the TTS clause handoff go
  through MCP; the external endpoint uses local sockets, is off by default, and is not persisted.
- Platform ASR option: on-device recognizer only, language-pack gated, selectable from diagnostics,
  never persisted; the owned engine stays the default and `OfflineAsr` is untouched.
- Tool-eval golden set: structure of `tools/llm/tool_eval_set.json`.
- Android JVM tests (`apps/android`, `gradlew :app:testDebugUnitTest`) cover the runner, tool
  loop, MCP codec/server/client/bridge/TTS server, platform-ASR logic, and settings.

Run:

```powershell
python -m unittest discover -s tests
```
