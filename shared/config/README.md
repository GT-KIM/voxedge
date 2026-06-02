# Shared Configuration Contract

Concrete, typed configuration shared by Android and iOS:
[`config.schema.json`](config.schema.json) (schema_version `1.0.0`). Platforms may store values
differently, but key names and meaning are equivalent and validated against this schema.

## Groups

- **asr** — language, model path, endpoint silence threshold, offline-required flag.
- **prompt** — template id and system prompt path (see `shared/prompts`).
- **llm** — model/tokenizer paths, `context_size` (4096), `max_response_tokens` cap, history
  window + rolling-summary budgets, sampler settings.
- **tts** — voice, model dir, `precision` (fp16/int8), `flow_steps` (K — dominant latency knob),
  `chunk_latent_frames` (short-chunk size for first-audio latency), speed.
- **runtime** — `power_profile` (auto = state-driven), first-audio P95 target, barge-in toggle,
  thermal thresholds.
- **mcp_schema_version** — pins the conversation contract (`shared/mcp`).

## Defaults reflect measured reality (2026-05-30, SM8750)

- `llm.context_size = 4096`, `max_response_tokens = 120` (spoken UX; 256 was a long monologue
  at 22 tok/s).
- `asr.endpoint_silence_ms = 350` — directly sets latency stage 1 (trade-off vs clipping).
- `tts.flow_steps = 6` (chosen by listening; K=4 under-converged) and `tts.chunk_latent_frames =
  128` — short-chunk fp16 first-clause TTS ≈ 0.96 s at K=6 (see
  `docs/demo/sm8750_measurements.md`).
- `runtime.use_os_thermal_state = true` (primary), `thermal_elevated_c = 80` with
  `thermal_hysteresis_c = 6` — LLM steady ~70 °C, init spike ~103 °C; hysteresis prevents flapping.
- `runtime.barge_in_stop_ms = 200` — audible playback stops mid-clause this fast.
- `tts.precision` defaults to `fp16`; switch to `int8` only after representative calibration (R6).

Keep this contract platform-neutral. Both platforms load and validate it; the conformance suite
(`tests/`) asserts equivalent interpretation.
