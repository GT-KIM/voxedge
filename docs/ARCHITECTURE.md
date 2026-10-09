# Architecture Skeleton

## Runtime pipeline

```text
App launch
  -> ASR listening
  -> user speech
  -> transcript normalization
  -> configurable prompt assembly
  -> on-device LLM inference
  -> MCP-formatted assistant output
  -> MCP-formatted TTS input
  -> on-device TTS inference
  -> audio playback
  -> ASR listening
```

## Cross-platform boundaries

- `apps/android`: Android UI, owned offline ASR (sherpa-onnx; platform recognizer as a test option), Qualcomm/LiteRT-LM runtime integration, audio playback, and platform permissions.
- `apps/ios`: iOS UI, Speech framework integration, ANEMLL/MLX/Core ML runtime integration, audio playback, and platform permissions.
- `shared/mcp`: the MCP profile (JSON-RPC 2.0 servers for the device tools, for TTS `speak`, and for ASR `transcribe`), runtime event schema, and turn record used between LLM output and TTS input.
- `shared/prompts`: prompt templates that both platforms load or embed consistently.
- `shared/config`: configuration keys shared by Android and iOS.
- `tools/model_compile`: host-side conversion and benchmark workflows.

## Detailed specs

- MCP boundaries (device tools + LLM->TTS clause handoff + utterance->ASR handoff), runtime streaming events, turn record:
  `shared/mcp/README.md`, `shared/mcp/conversation_events.schema.json`, `docs/design/mcp_boundary.md`.
- Typed shared configuration: `shared/config/config.schema.json`.
- First-audio latency waterfall + P50/P95 targets: `docs/design/latency_budget.md`.
- Speech-loop state machine + power/thermal policy: `docs/design/speech_loop_state_machine.md`.
- On-device measurements: `docs/demo/sm8750_measurements.md`.

## Offline runtime rule

Network access may be used during development for model/tool acquisition if explicitly approved. The delivered mobile runtime should not require network access for the conversational loop.
