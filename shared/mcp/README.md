# Conversation Contract (runtime events + durable MCP-style turn record)

This contract is the boundary between pipeline stages (ASR → prompt → LLM → TTS → playback)
and between the two platforms. It replaces the earlier ad-hoc envelope with a concrete,
versioned schema: [`conversation_events.schema.json`](conversation_events.schema.json)
(schema_version `1.0.0`).

## Why two layers

On-device measurement (see `docs/demo/sm8750_measurements.md`) showed that first-audio
latency requires **streaming LLM output into TTS clause-by-clause** — first-clause TTS is ~0.67 s
only because we synthesize a short clause immediately, not after a full response. Gating TTS on a
single complete "MCP object" would add seconds of dead air. So the contract is split:

1. **Runtime streaming event channel** (low-latency, in-process / IPC). Ordered `Event`s flow
   between stages as soon as data exists: `asr.partial/final`, `llm.text_delta`,
   `tts.chunk_request` (one per clause), `tts.audio_chunk`, `control.barge_in/cancel`,
   `runtime.thermal/degrade`, `error`. Audio is referenced by handle (`pcm_ref`), never inlined.
   Each event carries `seq` (ordering) and `t_mono_ms` (for the latency waterfall).

2. **Durable conversation turn record** (`TurnRecord`). Aggregated AFTER each turn from the
   stream: `conversation_id`, `turn_id`, `generation_id`, `role`, `content`, `spoken_content`
   (may differ from `content` on barge-in), measured `timing_ms`, and `metadata`. This is a
   replay / analytics / eval log — **it is NOT MCP itself.** Calling a plain turn log
   "MCP-formatted" would be ceremony. The `tool_calls` array is a forward hook: a real MCP
   tool/resource bridge (call ids, tool req/result, status) is added later **only at actual tool
   boundaries**, not at the LLM↔TTS audio boundary.

> **Decision (RESOLVED, and now implemented).** The original brief's "MCP-formatted LLM↔TTS
> boundary" was reconciled as proposed: the **runtime boundary is the streaming event stream**, and
> the **durable layer is the conversation turn record** with a `tool_calls` hook for real tools. Real
> tools now exist — the on-device agentic layer has 13 tools + durable memory — but they sit at the
> **LLM↔tool** boundary (executed between LLM steps and fed back as a tool-response continuation),
> not at the audio boundary, exactly as designed. The Android runtime log (`runtime-log-v1`,
> `RuntimeEventLogger`) is the concrete realization of the event channel; `tool.call`/`tool.result`
> events were added for the agentic loop.

## Cancellation / barge-in (`generation_id`)

Every `Event` carries a `generation_id` (cancel epoch). On barge-in/cancel, a `control.*` event
supplies `cancel.new_generation_id`; all stages switch to it and **drop any event with a stale
`generation_id`** so superseded `llm.text_delta` / `tts.audio_chunk` / playback can't leak into
the new turn. Audible playback must stop mid-clause within `cancel.stop_playback_within_ms`
(default 200 ms) — clause-boundary discard alone is too slow for the user.

## Versioning

- `schema_version` required on every `Event` and `TurnRecord`. Semver; both platforms pin the
  major. Unknown event `type`s and unknown `metadata` keys are ignored, never rejected. A message
  is EITHER an `event` OR a `turn_record` (`oneOf`), and per-type payloads are required (e.g.
  `tts.audio_chunk` requires `audio`; `control.*` requires `cancel`; `asr.final` requires `asr_detail`).

## Conformance (R5) — invariants, not identical text

Both platforms pass a shared suite built from fixtures under `tests/`. It must assert
**invariants**, not identical model output:
- Schema validation of every emitted event/record.
- Canned event stream → identical state transitions + aggregated `TurnRecord` shape.
- **Timed/latency** fixtures (waterfall assertions), **barge-in** (mid-clause stop < 200 ms),
  **stale-event / cancel-epoch races**, **no-speech timeout**, **thermal degrade**, prompt
  normalization, and clause-segmentation fixtures.
Offline ASR is now selected and measured (R2 resolved: sherpa-onnx Korean zipformer int8 ~51 ms /
SenseVoice for English), so the `docs/design/latency_budget.md` figures are measured, not provisional.

## Related specs

- Latency waterfall + targets: `docs/design/latency_budget.md`.
- Conversation state machine + power policy: `docs/design/speech_loop_state_machine.md`.
- Typed configuration: `shared/config/`.
