# voxedge — fully-offline speech-to-speech voice agent on Snapdragon

> **Airplane mode on. You speak; a 4B LLM answers out loud — ASR, LLM, and TTS all running locally on
> one Snapdragon phone, no network at any point. First audio lands ~0.6 s after your words are
> recognized (the LLM→TTS path; the full speech-end→audio also includes the VAD endpoint + ASR).**

<p align="left">
  <img alt="Android" src="https://img.shields.io/badge/Android-Snapdragon%20SM8750-3DDC84?logo=android&logoColor=white">
  <img alt="Runtime" src="https://img.shields.io/badge/runtime-fully%20offline-0B7285">
  <img alt="ASR" src="https://img.shields.io/badge/ASR-sherpa--onnx-7950F2">
  <img alt="LLM" src="https://img.shields.io/badge/LLM-Qwen3--4B%20%C2%B7%20Genie%20HTP-1C7ED6">
  <img alt="TTS" src="https://img.shields.io/badge/TTS-Supertonic-FA5252">
  <img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue">
</p>

<!-- TODO(before launch): replace with the 30–60 s airplane-mode demo GIF/video -->
<!-- ![demo](docs/assets/demo.gif) -->

This is a **measured, honest reference** for what it takes to run a *full* speech loop —
ASR → LLM → TTS, hands-free, low-latency — entirely on-device on a current mobile SoC. It is not a
finished product and not trying to out-assistant Apple/Google/OpenAI. The interesting, under-served
thing is a **developer-inspectable, end-to-end offline speech loop on real silicon** — and the
engineering reality of making it run.

📖 **Full teardown (architecture, war stories, the bugs that ate days):**
[docs/teardown.md](docs/teardown.md)

## Architecture

```
mic → VAD (endpoint) → ASR → LLM (token stream) → clause segmenter → TTS → gapless playback
                                       └───────────────── half-duplex ─────────────────┘
```

The idea that makes it feel real-time is **clause streaming**: as the LLM streams tokens, a segmenter
cuts the stream into short clauses and hands each to TTS immediately — clause *N* synthesizes while
the LLM decodes *N+1* and the speaker plays *N−1*. First audio depends on the *first clause*, not the
whole answer. All three engines stay resident in one process (LLM + TTS on the NPU/HTP, ASR on CPU).

## Measured numbers (Galaxy Z Fold7 / Snapdragon SM8750, airplane mode)

| Stage | Measured |
|---|---|
| **Recognized text → first audio** (LLM→clause→TTS) | **~0.55–0.66 s** |
| LLM (Qwen3-4B, Qualcomm Genie w4a16, HTP) | TTFT ~65–90 ms · ~22 tok/s · ~1.18 GB · ~70 °C |
| TTS (Supertonic short-chunk, fp16, 6 flow steps) | ~220 ms / clause, resident |
| ASR decode (SenseVoice int8 / Dolphin CTC) | ~36 ms / ~27 ms |

The ~0.6 s is **first audio after the recognized text is available** — achieved by streaming TTS off
the first (short) clause rather than waiting for the whole answer. The full perceived latency from
when you *stop speaking* additionally includes the VAD endpoint (~0.6 s of trailing silence) and ASR
decode (~30 ms). Measured in-app with on-device timers, in airplane mode, on this project — not a
spec sheet. The component waterfall and the earlier (pre-pipelining, full-chunk) estimate are in
[`docs/design/latency_budget.md`](docs/design/latency_budget.md); methodology and the ASR eval
harness are in the [teardown](docs/teardown.md) and [`tools/asr/`](tools/asr/).

## What works / what doesn't (honest)

| Capability | Status |
|---|---|
| Fully offline (airplane-mode) Korean/English voice loop | ✅ works, measured |
| Hands-free VAD turn-taking | ✅ works |
| ~0.6 s first audio (clause streaming) | ✅ measured on SM8750 |
| On-device ASR + 4B LLM + TTS coexisting in one process | ✅ works |
| ASR under loud background music | ⚠️ improved (per-language ASR model), not solved |
| LLM answer quality | ⚠️ 4B-class on-device; prompt/history-tuned, not GPT-class |
| Barge-in (interrupt mid-answer) | 🧪 experimental, off by default (AEC self-interrupt) |
| iOS | ❌ not built (roadmap) |
| Portability across SoCs | ❌ single SoC (SM8750); per-SoC graph prep required |

## Models (bring your own)

This repo is **code, not model weights.** The models are large and under their own licenses, so they
are not redistributed here — you fetch them yourself and provision them to the device. Each model,
its license, and how to obtain it are documented in **[MODELS.md](MODELS.md)**:
Qwen3-4B (Qualcomm Genie bundle), Supertonic TTS, and the sherpa-onnx ASR/VAD models.

## Repository layout

```text
apps/android/        Android app + JNI bridges + native SNPE/Genie integration
converter/           model preparation / conversion scripts
docs/                teardown, architecture, design notes
shared/              cross-platform runtime event + config schemas
tools/asr/           host-side ASR/denoiser CER eval harness
tests/               contract tests
```

## Build (Android)

```powershell
# 1) provision models to the device (see MODELS.md)
# 2) build + install the debug APK
cd apps/android
.\gradlew.bat :app:assembleDebug
```

Requires the Qualcomm QAIRT/SNPE runtime libraries (not redistributed — see MODELS.md) and a
Snapdragon SM8750-class device. The app runs the whole conversation loop with no network access.

## Status

A single-device, research-grade proof — built to measure what's actually possible, and to be honest
about what isn't. Issues, critiques, and measurements on other devices are welcome.

## License

Code: **Apache-2.0** (see [LICENSE](LICENSE)). Model weights and the Qualcomm runtime are **not**
included and are governed by their own licenses (see [MODELS.md](MODELS.md)).

---

*Built by an on-device AI engineer. All numbers here are from this personal, open project.*
