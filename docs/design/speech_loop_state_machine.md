# Speech-Loop State Machine + Power Policy

Status: spec grounded in 2026-05-30 SM8750 measurements. This is the platform-neutral behavior
both Android and iOS must implement identically (enforced by the conformance suite, R4).

## States

```text
        ┌──────────────┐
        │   STARTING   │  load models once (LLM ~6s, TTS graph-prepared <1s); warm audio
        └──────┬───────┘
               ▼
        ┌──────────────┐   user speech detected (VAD)
        │  LISTENING   │ ───────────────────────────────▶ CAPTURING
        │ (low power)  │ ◀─────────────────────────────── (endpoint + empty / timeout)
        └──────┬───────┘
               │ (optional wake word)
               ▼
        ┌──────────────┐  endpoint detected (VAD silence)
        │  CAPTURING   │ ─────────────────────────────▶ TRANSCRIBING
        │ (ASR active) │
        └──────────────┘
               ▼
        ┌──────────────┐  asr.final
        │ TRANSCRIBING │ ─────────────────────────────▶ GENERATING
        └──────────────┘
               ▼
        ┌──────────────┐  llm.text_delta → clause → tts.chunk_request (stream)
        │  GENERATING  │ ───────────────────────────────▶ SPEAKING (first clause ready)
        │ (LLM + TTS)  │
        └──────────────┘
               ▼
        ┌──────────────┐  tts.playback_end (last clause) → back to LISTENING
        │   SPEAKING   │
        │ (playback +  │   user speech during playback ─▶ INTERRUPTED (barge-in)
        │  AEC + VAD)  │
        └──────┬───────┘
               ▼
        ┌──────────────┐  cancel pending text+PCM within one clause boundary
        │ INTERRUPTED  │ ─────────────────────────────▶ CAPTURING (new user turn)
        └──────────────┘

   any state ──error──▶ RECOVERING ──▶ LISTENING (degrade gracefully)
```

## Concurrent regions (not one linear state)

The diagram above is a simplification. From the moment a turn starts generating, **four regions
run concurrently** and must be modeled as orthogonal regions of a statechart, not one linear
state — otherwise overlapping LLM / TTS / playback / listening produce races:

- **R-listen:** mic + VAD + AEC stay active *even during SPEAKING* (for barge-in).
- **R-generate:** LLM decoding → clause segmentation → `tts.chunk_request` per clause.
- **R-synth:** TTS synthesizing clause N (queue of chunk_ids).
- **R-play:** audio playback of completed clauses.

Every event in flight carries the turn's `generation_id` (cancel epoch). A worker in any region
MUST drop events whose `generation_id` is stale. This is the race guard.

## Key transitions and rules

- **Pipelining (later clauses only).** Clause 0's first PCM is a serial cost (R-generate then
  R-synth then R-play — see `latency_budget.md`); from clause 1 on, R-synth(N) overlaps
  R-play(N-1) and R-generate(N+1).
- **Barge-in (INTERRUPTED):** R-listen stays active with AEC during SPEAKING. On confirmed user
  speech: (1) bump `generation_id` via `control.barge_in` (`cancel.new_generation_id`); (2) **stop
  audible playback mid-clause within 200 ms** (`cancel.stop_playback_within_ms`) — do not wait for
  a clause boundary; (3) flush queued + in-flight TTS chunks and pending LLM text for the old
  epoch; (4) start a new CAPTURING turn. Stale events from the old epoch are dropped by every
  region. Record `barged_in=true` and `spoken_content` (≠ full `content`) in the `TurnRecord`.
- **Endpointing:** VAD silence threshold drives CAPTURING → TRANSCRIBING. Tune to balance
  responsiveness (stage 1 budget 150/300 ms) against clipping the user.
- **Phase A ASR = platform OS recognizer behind an `AsrEngine` interface** (Android
  on-device `SpeechRecognizer` / iOS `SpeechAnalyzer`): streams `asr.partial` live, `asr.final` on
  endpoint. ⚠️ Not guaranteed fully offline — gate on OS on-device availability
  (`asr.platform.require_on_device`); if unavailable, block or network-fallback per
  `allow_network_fallback`. Phase B swaps to the owned sherpa-onnx model (no pipeline rework).
  Latency stages 1–2 are OS-governed (measure in-app, Phase 3). See `docs/design/asr_selection.md`.
- **History cap:** before GENERATING, assemble the prompt within the 4096-token budget
  (system/contract < 700, recent window ~2000–2500, rolling summary ~200–500, current turn +
  headroom ~700–1000). When used context exceeds ~2500–3000 tokens, fold oldest turns into the
  rolling summary. Never re-prefill unbounded history (prefill latency, stage 3).
- **Response caps:** hard cap on generated tokens per turn and on queued TTS audio, so a runaway
  generation can't overheat or monopolize the device.

## Power / thermal policy

Measured: LLM steady generation ~70 °C (init spike ~103 °C); TTS thermally light (~39 °C). Burst
is fine for short cool-device turns but unsafe as a permanent default for an always-listening loop.

| State | Power profile | Notes |
|---|---|---|
| LISTENING | low power | VAD/wake only; no heavy ASR/LLM/HTP load |
| CAPTURING / TRANSCRIBING | balanced | ASR active only during speech windows |
| GENERATING (short turn, thermal nominal) | burst | fast TTFT/decode |
| GENERATING (long turn or thermal elevated) | balanced/sustained | avoid sustained burst heat |
| SPEAKING | balanced | TTS is light; keep headroom for barge-in ASR |

- Subscribe to `runtime.thermal`. On `elevated`: drop GENERATING from burst to balanced and
  reduce TTS flow steps K / response length. On `critical`: pause new turns, surface a UX notice.
- **Never reload models per turn** (init spike + multi-second/30-s cost). Load once in STARTING,
  keep LLM dialog and TTS graphs resident for the session.

## Cross-platform equivalence (R4)

The state names, transitions, endpointing/barge-in rules, history-cap policy, and power-profile
mapping above are the contract. Android (Genie/QNN + Jetpack/owned ASR) and iOS (MLX/ANEMLL +
Core ML + Speech/owned ASR) implement the same machine; the conformance suite (R5) drives a canned
event stream through each and asserts identical state transitions and aggregated `TurnRecord`s.
