# On-device measurements (Snapdragon SM8750) — consolidated

Device: Galaxy Z Fold7 (SM-F966N), SoC SM8750 (Snapdragon 8 Elite), Android 16, QAIRT 2.46.
All numbers are measured on this project on this device; the component waterfall and the
methodology are in [`../design/latency_budget.md`](../design/latency_budget.md).

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
(Core ML / MLX) · multi-SoC portability · sustained full-loop thermal · Supertonic OpenRAIL-M license
review for any redistribution.
