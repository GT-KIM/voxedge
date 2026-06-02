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

- `apps/android`: Android UI, Android ASR integration, Qualcomm runtime integration, audio playback, and platform permissions.
- `apps/ios`: iOS UI, Speech framework integration, ANEMLL/MLX/Core ML runtime integration, audio playback, and platform permissions.
- `shared/mcp`: message envelope and schema definitions used between LLM output and TTS input.
- `shared/prompts`: prompt templates that both platforms load or embed consistently.
- `shared/config`: configuration keys shared by Android and iOS.
- `tools/model_compile`: host-side conversion and benchmark workflows.

## Detailed specs (measured, 2026-05-30)

- Conversation contract (runtime streaming events + durable MCP-style turn record):
  `shared/mcp/README.md` + `shared/mcp/conversation_events.schema.json`.
- Typed shared configuration: `shared/config/config.schema.json`.
- First-audio latency waterfall + P50/P95 targets: `docs/design/latency_budget.md`.
- Speech-loop state machine + power/thermal policy: `docs/design/speech_loop_state_machine.md`.
- Device measurements + design review: `docs/review/`.

## Offline runtime rule

Network access may be used during development for model/tool acquisition if explicitly approved. The delivered mobile runtime should not require network access for the conversational loop.
