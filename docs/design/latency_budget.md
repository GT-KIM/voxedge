# Latency Budget — first-audio waterfall

Status: spec grounded in 2026-05-30 SM8750 measurements. Targets are P50/P95, tracked as a
**waterfall of stages**, not one blended number. Tracked event timestamps (`t_mono_ms` per stage)
are in `shared/mcp/conversation_events.schema.json`.

## Definition of "first audio"
Track **first *semantic* audio** — the first PCM of the actual spoken reply — so the metric can't
be gamed by an early non-semantic filler. A short acknowledgement ("음…") MAY play earlier as a UX
bridge but is measured separately.

## Waterfall stages and targets

| # | Stage (event → event) | P50 | P95 | Measured / notes |
|---|---|---|---|---|
| 1 | speech end → endpoint detected | 150 ms | 300 ms | VAD/endpointing; OS-governed in Phase A (platform ASR), unmeasured |
| 2 | endpoint → ASR final text | 100 ms | 300 ms | depends on chosen ASR (Phase A = platform; unmeasured) |
| 3 | ASR final → LLM first token | 120 ms | 300 ms | TTFT 65 ms measured + history prefill (long history is the risk) |
| 4 | LLM first token → first TTS clause ready | 350 ms | 550 ms | first clause ~8–12 tok @ 22 tok/s ≈ 360–545 ms |
| 5 | first TTS clause → first PCM | 700 ms | 850 ms | FULL short-chunk clause synth: 96.6 + 144·K ms (fp16) ⇒ K=6 ≈ 0.96 s. **No intra-clause streaming credit** (Supertonic emits PCM only after all K flow steps + vocoder). INT8 ≈ halves. |
| 6 | first PCM → audible | 30 ms | 80 ms | warm audio path assumed |

### Aggregate targets (PROVISIONAL — first clause is a SUM, not overlapped)
Stage 4 and stage 5 do NOT overlap for the *first* clause (TTS can't start until clause text
exists; measured: no PCM until all flow steps + vocoder finish):
- **ASR-final → first semantic audio:** `65 + ~450 decode + ~960 TTS(K=6 fp16) + audio` ≈
  **~1.45 s**. Target **P50 ≤ 1.3 s, P95 ≤ 1.7 s** (fp16, K=6, full chunk). A small first clause
  (~32-frame) drops TTS to ~0.25 s → first audio ~0.75 s.
- **Physical speech-end → first semantic audio:** add stages 1–2 (ASR, unmeasured) ⇒
  **P50 ≤ 1.6 s, P95 ≤ 2.2 s**, provisional until ASR is measured.
- Sub-second from ASR-final is NOT reachable on the current measured path without a small first
  clause and/or INT8 and/or an acknowledgement bridge.

> Targets above are per-component sums (worst-case, full 128-latent chunk), not an end-to-end
> pipelined measurement. They predicted ~1.45 s for a full first clause and ~0.75 s for a small one.

### MEASURED end-to-end (in-app, SM8750, airplane mode, 2026-05-31)
The Android pipeline was instrumented and the real **recognized-text → first PCM** is
**~0.55–0.66 s** — i.e. the "small first clause" path above, and better than its ~0.75 s estimate.
Why it beats the full-chunk stage-5 estimate: the first clause is kept short by the segmenter, and
TTS runs on a **duration-predicted (dp-sized) latent**, so a clause synthesizes in **~220 ms**, not
the ~0.96 s full-chunk worst case. So the README's ~0.6 s is a *measured* number for this path, while
the ~1.45 s figure is the full-chunk component-sum estimate — both true, for different conditions.
The number **excludes stages 1–2** (VAD endpoint ~0.6 s + ASR ~30 ms); add those for speech-end →
audio (~1.2 s). Sub-second from recognized-text is real here; sub-second from *speech end* is not,
because of the VAD endpoint wait. Keep latency a measured value, not a hardcoded assumption.

## What blows the budget (priority order)
1. **Flow-matching step count K** — dominant TTS term (144 ms/step fp16). K=6 chosen. INT8 halves.
2. **TTS chunk size** — full 1000-frame shape ≈ 3.6 s (fails). Use short chunks; small first clause.
3. **LLM history prefill** — measured only at 31–58 tok; cap history (state machine). Measure at 256/512/1k/2k/4k.
4. **ASR finalization** (Phase A platform ASR, unmeasured) — could dominate stages 1–2.
5. **Cold loads** — load once (LLM ~6 s, TTS graph-prepared); never reload per turn. Non-graph-prepared TTS adds ~29 s on-load — forbidden.

## Rules
- Stream LLM → TTS by **clause**, not full sentence/response.
- Pipeline TTS synth of clause N with playback of clause N-1 and LLM decode of clause N+1 (clauses ≥1 only).
- Cancel fast on barge-in: stop audible playback mid-clause within 200 ms (not at clause boundary).
- Record the full per-turn waterfall into `TurnRecord.timing_ms` in dev builds.
