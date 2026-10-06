# On-device measurements (Snapdragon SM8750) — consolidated

Device: Galaxy Z Fold7 (SM-F966N), SoC SM8750 (Snapdragon 8 Elite), Android 16, QAIRT 2.46.
All numbers are measured on this project on this device; the component waterfall and the
methodology are in [`../design/latency_budget.md`](../design/latency_budget.md).

> **Note (current):** TTS ships **FP16** (short-chunk 64/128). TTS INT8/W8A16 were later built with a
> representative-calibration pipeline, tested, and **rejected** (audibly broken), so any INT8 TTS row
> below is a historical conversion measurement, not the shipped config. KO ASR is the **Korean
> zipformer int8** (~51 ms) now; a second LLM backend (Gemma 4 E2B / LiteRT-LM) ships alongside the
> Qwen3-4B Genie path and has its own section below (measured 2026-10-06).

## LLM — Qwen3-4B-Instruct-2507, Qualcomm Genie w4a16 (ctx 4096)

Runs on SM8750 via Genie (HTP).

| Metric | Value |
|---|---|
| Model load (GenieDialog_create) | 6.25 s cold / 4.57 s warm |
| TTFT (after load) | 63–74 ms |
| Prefill | 488–779 tok/s |
| Decode | 22.3–22.7 tok/s (stable over 914 tok, no throttle in 40 s) |
| Peak process RAM | ~1.18 GB |
| Thermal | idle 39 °C; init spike ~103 °C; steady generation ~70 °C |

LLM is not the conversational-latency bottleneck; load once at startup (never per turn).
Repro: `converter/phase1/run_qwen3_genie_device_spike.sh`.

## LLM — Gemma 4 E2B, LiteRT-LM 0.13.1 (GPU backend), in-app sustained run

Measured 2026-10-06 inside the app with the full loop resident (Gemma + Supertonic TTS + sherpa-onnx
ASR + VAD), 44 typed turns back to back over 10 min 09 s cycling the 7 chat prompts of
`tools/llm/eval_prompts.json` (4 EN, 3 KO), USB-powered, airplane mode **off**. Numbers are from
the app's own event log (`llm.generate_end`, `turn.end`) and `dumpsys meminfo/thermalservice/battery`.

| Metric | Value |
|---|---|
| TTFT, warm session (same language as the previous turn) | 112–127 ms (median 118 ms) |
| TTFT, language switch (EN→KO / KO→EN forces a re-prefill) | 1.1–1.4 s |
| First PCM (recognized text → first audio), warm | 462–565 ms (median 496 ms) |
| Decode | ~31 streamed chunks/s, flat over the run (first third 31.1, last third 30.7); one chunk per token on LiteRT-LM, not cross-checked against the tokenizer |
| Process memory, whole app | PSS 2.58 GB idle after load → 3.05–3.06 GB steady under turns (RSS 3.16 GB peak); plateau from turn ~6 on |
| Thermal | `thermalservice` status 0 (none) for the whole run; battery 27.9 → 32.0 °C. (This run read the stale "cached" sensor block, so no SKIN/AP trend; the harness now reads the HAL block, see the Qwen3 run below) |
| Failures | 0 of 44 turns (`llm_result` OK) |

Reading: no throttling or decode slowdown in 10 minutes of continuous turns on the GPU path. The
process figure is the whole resident pipeline, so it is not comparable to the Qwen3 row above (that
was the Genie LLM alone in a standalone spike). The per-turn total (~7–20 s) is dominated by playing
back the spoken answer, not compute.

Repro: `python tools/llm/sustained_run.py --label gemma4-e2b-litert --minutes 10` with the Gemma
backend selected in settings; report lands in `output/llm_eval/` (gitignored).

## LLM — Qwen3-4B Genie, in-app sustained run (same harness, 2026-10-06)

Same procedure as the Gemma run (38 turns, 10 min 13 s, USB-powered, airplane mode off), with the
harness reading the live HAL sensors and a guard that stops at SKIN 42 °C or thermal status 2
(neither was hit).

| Metric | Value |
|---|---|
| TTFT, warm | 104–107 ms (median 106 ms) |
| TTFT, language switch (re-prefill of the full prompt) | 1.1–1.5 s (median 1.22 s) |
| First PCM, warm | EN median 693 ms (618–1461); KO median 1128 ms |
| Decode | ~11.5 streamed chunks/s, flat (11.8 → 11.5); Genie batches ~3.9 chars per chunk, so this is **not** tok/s — the standalone 22 tok/s spike remains the token-rate reference |
| Process memory (app PSS) | 300 → 352 MB. The Genie weights live in HTP/DSP memory outside the app's PSS, so this is not comparable to the Gemma row (GPU buffers are counted in PSS) or to the 1.18 GB standalone figure |
| Thermal (HAL) | SKIN 32.3 → 40.2 °C (max 40.9), AP 38.8 → 41.4 °C (max 44.5), battery 29.1 → 35.3 °C; thermal status reached **1 (LIGHT)** at ~8.8 min. SKIN thresholds on this device: 38/40/42/45 °C |
| Failures | 0 of 38 |

Readings:
- The HTP path heats the device faster than the GPU path did (SKIN +8 °C in 10 min vs. no status
  change for Gemma), reaching the OS's first throttling step near the end. Decode did not slow
  down within the run.
- **Warm first-PCM is higher than the June figures** (EN 693 ms vs 576 ms on 2026-06-11; the
  README's 0.55–0.66 s was measured 2026-05-31 before the prompt deepening, prosody chunking, and
  tool policy landed). The language-switch TTFT also grew (1.2 s vs 0.5–0.65 s), consistent with a
  much longer system prompt to re-prefill. Not isolated to a cause yet; MCP encode/decode measured
  ~1 ms per leg, so it is not the MCP boundary. Isolation of the cause is open work.

Repro: `python tools/llm/sustained_run.py --label qwen3-4b-genie --minutes 10` (Qwen3 selected).

### Airplane-mode re-runs (2026-10-06 evening, same harness, USB still attached for adb)

| | Gemma 4 E2B (GPU) | Qwen3-4B Genie (HTP) |
|---|---|---|
| Turns / duration | 43 / 10 min 01 s, 0 failures | 14 / 3 min 52 s, 0 failures — **stopped by the guard at thermal status 2 (MODERATE)** |
| TTFT warm | 115 ms | 106 ms |
| First PCM warm | 494 ms | EN 761 ms, KO 1117 ms |
| Decode | 31.9 chunks/s, flat (32.1 → 31.8) | 11.2 → 9.1 chunks/s (**−18 %** once status 2 was reached) |
| SKIN | 33.5 → 38.0 °C, status 0 throughout | 33.5 → 41.6 °C in under 4 min; status 1 at ~2 min, status 2 at ~3.9 min |
| AP | max 42.5 °C | max 49.0 °C |

Readings:
- Airplane mode changed nothing material for the GPU path: the numbers match the morning run within
  noise. The GPU path is the one that survives 10 minutes of continuous turns without throttling.
- The HTP path's thermal margin depends on the starting state. In the morning (cool device) it took
  ~9 min to reach status 1; in the evening, after a day of runs and a 3-min cool-down that had only
  brought SKIN back to 33 °C (AP still 41.7 °C), it reached status 2 in under 4 minutes and decode
  slowed measurably. A first attempt straight after the Gemma run (SKIN 36 °C at start) hit the
  42 °C guard after 2 minutes. Continuous HTP use therefore needs the thermal/degrade policy that the
  event schema already defines (`runtime.thermal` / `runtime.degrade`) and the app does not yet
  implement.
- Charging was present in all runs (adb needs USB), so battery heat is in every figure.

## ASR — owned, offline (sherpa-onnx, per-language)

| Model | Role | Decode | Notes |
|---|---|---|---|
| Dolphin base CTC | Korean | ~27 ms | picked by measured CER on real captured audio |
| SenseVoice int8 | English | ~36 ms | Dolphin can't do English |

CPU/ONNX, no NPU. Model selection was data-driven — see the eval harness in [`../../tools/asr/`](../../tools/asr/).

## TTS — Supertonic on the HTP/DSP

Per-component (snpe-net-run, `--use_dsp --perf_profile burst`, load-isolated):

| Component | full-size 256/1000 INT8 | short-chunk 64/128 fp16 |
|---|---|---|
| duration_predictor | 8.6 ms | negligible |
| text_encoder | 158 ms | 3.8 ms |
| vector_estimator (per flow step) | 409.6 ms | 144 ms |
| vocoder | 161 ms | 88 ms |

- **Critical finding:** DLCs are statically sized; the original 1000-frame shape made first-clause
  TTS ~3.6 s. **Re-converting to a short chunk (text 64 / latent 128) fixed it.**
- 1 latent frame ≈ 3072 samples (~70 ms); a 128-frame chunk holds ONE clause — long text must be
  clause-split before TTS.
- **graph-prepare is MANDATORY** (non-prepared float runs wrong on HTP / ~29 s on-load).
- **K (flow steps) = 6**, chosen by listening (K=4 under-converged; K=6 ≈ K=8).

### TTS — resident native engine (the shippable runtime), K=6 short-chunk fp16, one Korean clause

| Metric | Value |
|---|---|
| model load (3 DLCs, once, warm) | ~250–490 ms |
| text_encoder | ~3 ms |
| vector_estimator | ~24 ms/step (×6 ≈ 145 ms) |
| vocoder | ~70 ms |
| **synth per clause** | **~220 ms** |

The earlier ~790 ms/step was per-call DLC reload, not compute — confirmed by the resident engine.

#### Correctness gotchas (must hold in the app/JNI)
1. **int32 text_ids:** the DLC takes `text_ids` as `Int_32`; SNPE `ITensor` is float32-only. Use the
   UserBuffer path with `UserBufferEncodingIntN(32)` for `text_ids`, float for the rest.
2. **Input layout:** QAIRT (`axes_to_spatial_first_order`) transposes the last two axes of every
   multi-dim input. Inputs must be produced in DLC layout (`prep_static_tts_inputs.py --layout dlc`),
   else speaker identity / quality is destroyed. After the fix, device fp16 matches host fp32
   (waveform corr 0.95, rms ratio 1.0).

## End-to-end first-audio (in-app, single process, airplane mode)

Measured **recognized-text → first PCM: ~0.55–0.66 s** (TTFT ~67 ms + a short first clause + dp-sized
TTS ~220 ms + audio). This realizes the "small first clause" path of the latency budget and beats its
full-chunk estimate. It **excludes** the VAD endpoint (~0.6 s of trailing silence) and ASR decode;
add those for speech-end → audio (~1.2 s). Later clauses pipeline (synth N ∥ play N−1 ∥ decode N+1).

## Open / next
INT8 short-chunk + representative calibration · barge-in with reference-signal AEC · iOS feasibility
(Core ML / MLX) · multi-SoC portability · sustained full-loop thermal on the Qwen3 Genie path (the
Gemma path has its 10-min run above) · Supertonic OpenRAIL-M license review for any redistribution.
