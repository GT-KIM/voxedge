# Models & runtime (bring your own)

This repository contains **code, not model weights or vendor runtimes.** The models are large and
each is governed by its own license, so they are **not redistributed here.** You fetch them yourself,
(convert where noted), and provision them to the device's app-private storage.

> ⚠️ **Verify every license yourself before redistributing or using commercially.** The notes below
> are a starting point, not legal advice. Licenses change.

## What you need

| Component | Model | Where it goes (device app storage) |
|---|---|---|
| LLM (default) | Qwen3-4B-Instruct-2507, Qualcomm Genie `w4a16` bundle | `files/llm_bundle/` |
| LLM (optional) | Gemma 4 E2B `.litertlm` (LiteRT-LM) | `files/llm_litert/gemma-4-E2B-it.litertlm` |
| TTS | Supertonic (short-chunk, graph-prepared DLCs) | `files/tts_dlc/` |
| ASR (KO) | sherpa-onnx Dolphin base CTC | `files/asr_dolphin/` |
| ASR (EN) | sherpa-onnx SenseVoice int8 | `files/asr/` |
| VAD | Silero VAD (sherpa-onnx) | `files/vad/` |
| Runtime | Qualcomm QAIRT / SNPE / Genie libraries (`libGenie.so`, `libSNPE.so`, HTP v79 skels) | app `jniLibs/` + `assets/` |

## LLM — Qwen3-4B-Instruct-2507 (Qualcomm Genie)

- **Model license:** Qwen3 base model is Apache-2.0 (Alibaba/Qwen). The prebuilt **Genie `w4a16`
  bundle** is distributed via Qualcomm AI Hub under Qualcomm's terms — review them.
- **How to get it:** Qualcomm AI Hub provides the Genie bundle for Snapdragon 8 Elite-class targets.
  See `converter/phase1/fetch_qwen3_4b_instruct_2507_genie_bundle.sh` and
  `converter/phase1/validate_qwen3_genie_bundle.py`.
- The bundle is ~3 GB (4 context binaries + tokenizer + `genie_config.json`).

## LLM (optional) — Gemma 4 E2B (LiteRT-LM)

- **Model license:** Gemma Terms of Use (Google). Review before redistribution/commercial use.
- **How to get it:** the official `.litertlm` bundle is published at
  `litert-community/gemma-4-E2B-it-litert-lm` on Hugging Face — see
  `converter/phase1/fetch_gemma4_litert_lm.sh`. Mixed 2/4/8-bit quantization, ~0.8 GB text-only
  weight footprint.
- **Runtime:** LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`, Apache-2.0) is pulled by
  Gradle — no vendor SDK needed. The app runs it on the GPU backend by default; NPU (QNN) is a
  follow-up measurement.
- **Selection:** the in-app `LLM:` button cycles the persisted model choice (applied on next
  launch); if the selected model is not provisioned the app falls back to whichever one is.

## TTS — Supertonic

- **Model license:** Supertonic weights are released under **OpenRAIL-M**; sample code MIT. Review
  the OpenRAIL use restrictions before any product use.
- **How to get it:** download from the official Supertonic release, then convert ONNX → SNPE DLC and
  graph-prepare for the target SoC. Conversion scripts and the short-chunk (text64/latent128) profile
  are in `tools/tts/` and `converter/phase1/`. Conversion must run in the tested QAIRT venv
  (onnx 1.19.1) — see `docs/MODEL_PIPELINE.md`.

## ASR & VAD — sherpa-onnx

All run on CPU via ONNX Runtime (no vendor NPU needed).

- **Korean zipformer transducer** (`sherpa-onnx-zipformer-korean-2024-06-24`, k2-fsa GitHub
  release `asr-models`) — the KO default since 2026-06-12: clean CER 0.008 @ ~51 ms vs Dolphin
  base 0.136 (tools/asr/eval_live.py, labeled set + live device captures). Provision the int8
  encoder/decoder/joiner + tokens.txt to `files/asr_zipformer_ko/`.
- **Dolphin base CTC** (Korean-strong, multi East-Asian) — KO fallback when the zipformer is not
  provisioned — `k2-fsa` / `csukuangfj` on Hugging Face,
  Apache-2.0 runtime; verify the model card's license.
- **SenseVoice int8** (zh/en/ja/ko/yue) — `csukuangfj/sherpa-onnx-sense-voice-...`, Apache-2.0.
- **Silero VAD** — `silero_vad.onnx` from the sherpa-onnx `asr-models` release.
- The sherpa-onnx **Android AAR** (`sherpa-onnx-*.aar`, Apache-2.0) provides the JNI + Kotlin API;
  drop it in `apps/android/app/libs/` (gitignored).

This project selected the ASR models **by measured CER** on real captured audio — see the harness in
[`tools/asr/`](tools/asr/) (`eval_models.py`, `eval_captured.py`).

## Qualcomm runtime (QAIRT / SNPE / Genie)

- **Not redistributable.** Download the QAIRT SDK from Qualcomm (license required). The app needs
  `libGenie.so`, `libSNPE.so`, the QNN HTP backend + **v79** DSP skels, and `libc++_shared.so`.
- `apps/android/prepare_jnilibs.ps1` copies the right set into `jniLibs/` and `assets/qairt_dsp/`
  (both gitignored). The DSP skels must match the SoC: **v79 for SM8750** (do not use v81).

## Provisioning to the device

Models are pushed to app-private storage (dev provisioning), e.g.:

```powershell
# push to /data/local/tmp first, then copy into the app sandbox via run-as
adb push <model> /data/local/tmp/<name>
adb shell run-as <app.package> cp -r /data/local/tmp/<name> files/<dest>
```

`run-as` cannot write to `/data/local/tmp`, so always push as shell → `run-as cp`. See the per-step
notes in `docs/`.

## Portability note

The LLM/TTS are compiled and **graph-prepared for one specific SoC (SM8750 / v79)**. Moving to
another Snapdragon — let alone another vendor — is real per-device conversion work, not a recompile.
