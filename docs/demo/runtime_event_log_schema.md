# Runtime Event Log Schema

Status: Android implementation added on 2026-05-31.

The Android app writes a local JSONL event log for runtime evidence and latency forensics. The log is
app-private and is not uploaded.

Default path on device:

```text
filesDir/runtime_logs/turn_events.jsonl
```

The absolute path is emitted in the first `session.start` event as `log_path`.

## Format

Each line is one compact JSON object:

```json
{
  "schema_version": "runtime-log-v1",
  "seq": 0,
  "event": "turn.start",
  "t_wall_ms": 1780200000000,
  "t_mono_ms": 123456789,
  "generation_id": 1,
  "elapsed_ms": 0
}
```

Required base fields:

- `schema_version`: currently `runtime-log-v1`.
- `seq`: monotonically increasing event sequence within the app process.
- `event`: event name.
- `t_wall_ms`: wall-clock timestamp in milliseconds.
- `t_mono_ms`: monotonic timestamp in milliseconds.

Optional base fields:

- `generation_id`: turn cancel epoch from `GenerationEpoch`.
- `elapsed_ms`: elapsed milliseconds since the relevant turn start or stage start.

## Current Events

Session/runtime:

- `session.start`
- `session.end`
- `conversation.start`
- `conversation.stop`
- `state.changed`

ASR/prompt:

- `asr.final`
- `asr.no_speech`
- `turn.start`
- `prompt.assembled` — includes `session_mode` (`warm` incremental KV continuation vs `full`
  transcript re-prefill), `context_occupancy_pct`, and `lang` since 2026-06-10.

LLM:

- `llm.generate_start`
- `llm.first_token`

Tool use (agentic loop, 2026-06-10, additive):

- `tool.call` — the model requested a tool (`tool`, `step`, `args`).
- `tool.result` — dispatch outcome (`tool`, `ok`, `chars`).
- `tool.step_limit` — the per-turn generation-step cap stopped a tool chain.

TTS/playback:

- `tts.chunk_request`
- `tts.chunk_dropped`
- `tts.first_pcm`
- `tts.audio_chunk`
- `playback.start`
- `playback.end`

Turn/control:

- `turn.end` — includes `llm_result` (`OK`/`CONTEXT_EXCEEDED`/`ABORTED`/`ERROR`) since 2026-06-10.
- `control.barge_in`
- `control.llm_model_selected` — persisted LLM choice changed (`model_id`); applied next launch.

## Privacy Notes

The logger records timing and sizes by default, not audio buffers. Current turn events use character
counts such as `user_text_chars`, `reply_chars`, and `spoken_chars` instead of full transcript text.
This keeps the proof useful for latency evidence while avoiding unnecessary transcript persistence.

## Pulling A Demo Log

After an airplane-mode demo run:

```powershell
adb shell run-as com.conversationalai.agent ls files/runtime_logs
adb shell run-as com.conversationalai.agent cat files/runtime_logs/turn_events.jsonl
```

For public evidence, save the JSONL output next to the demo report and record the app commit hash,
device model, Android version, model bundle versions, and airplane-mode state.
