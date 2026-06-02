# Offline ASR Selection (R2)

Date: 2026-05-30. Requirements (confirmed with project leader): **Korean + English bilingual**,
**streaming** (real-time partials + fast endpoint), **balanced** accuracy/footprint, **fully
offline**, must run on both **Android (SM8750)** and **iOS**, owned (not OS-gated cloud ASR).

## Key finding: no off-the-shelf KO+EN *streaming* bilingual model exists

Research (sources below) shows the three requirements — streaming, Korean+English, single model —
do not co-exist in any ready model today:

| Candidate | KO | EN | Streaming | Size/latency | License | Cross-platform |
|---|---|---|---|---|---|---|
| sherpa-onnx **streaming Korean zipformer** (2024-06-16, KsponSpeech) | ✅ strong | ⚠️ weak | ✅ true | ~60 MB, ~160 ms | Apache-2.0 | ✅ Android+iOS |
| sherpa-onnx **SenseVoice** (zh/en/ja/ko/yue, 2025) | ✅ | ✅ | ❌ non-streaming (VAD-chunked) | small, fast | Apache-2.0 | ✅ |
| **Whisper** small/base (multilingual) | ✅ | ✅ (best code-switch) | ❌ (pseudo via chunking) | base ~140 MB / small ~470 MB, slower | MIT | ✅ (whisper.cpp/CoreML/sherpa) |
| sherpa-onnx streaming **zh-en** bilingual zipformer | ❌ | ✅ | ✅ | ~ | Apache-2.0 | ✅ |
| **Vosk** Korean | ✅ | separate model | ✅ | small | Apache-2.0 | ✅ but lower accuracy |
| NVIDIA Parakeet/Canary v3 (2025) | ❌ (25 EU langs, no KO) | ✅ | ✅(EN) | large | permissive | weak mobile |
| **Apple SpeechAnalyzer** (iOS 26, 2025) | ⚠️ "more to come" | ✅ | ✅ | OS model | Apple OS | ❌ iOS-only, OS-owned |

So **streaming + KO + EN in one owned model requires either training it or relaxing one axis.**

## Decision

### Engine (commit now): **sherpa-onnx** (k2-fsa, Apache-2.0)
This is the safe architectural commitment regardless of which model wins:
- Fully offline, runs ONNX models on-device; **official Android + iOS** bindings (Swift/Kotlin).
- Bundles **Silero VAD + endpointing** (covers latency stages 1–2) and streaming transducer decoding.
- Can run ALL candidate models (streaming zipformer, SenseVoice, Whisper, Paraformer) → we can
  swap the model without re-architecting. This puts an **owned offline ASR in the pipeline**
  (closes the R2 architectural hole) and keeps Android/iOS on the SAME runtime (helps R4 parity).
- iOS may *additionally* expose Apple SpeechAnalyzer later, but the owned sherpa-onnx model stays
  primary on both platforms for UX parity.

### Model — phased, because bilingual-streaming isn't off-the-shelf
- **Phase A (now, unblock the loop):** decide the v1 model along the fork below.
- **Phase B (bilingual-streaming ideal):** train a **KO+EN bilingual streaming Zipformer** via
  icefall (KsponSpeech + LibriSpeech, optional Korean-English code-switch data). This is the
  "owned" ideal but is real effort (data + training + export). It becomes the long-term model.

### The fork for Phase A (needs a project-leader call)
1. **Korean-primary streaming zipformer now** — true streaming, 60 MB, 160 ms, great Korean,
   weak English code-switching. Best if v1 is Korean-first and English is occasional.
2. **SenseVoice (KO+EN) chunked now** — genuine bilingual + small + fast, but NON-streaming:
   transcribe a VAD-segmented utterance after endpoint. Adds first-token latency (no live
   partials), in tension with the streaming budget (stages 1–2) but acceptable if endpoint is tight.
3. **Whisper-small chunked now** — best bilingual + code-switching quality, but largest/slowest
   and non-streaming; likely too heavy alongside the 1.18 GB LLM on mid-range devices.

## Impact on existing specs
- Add an offline ASR model to Phase 0/1 model prep (currently only LLM + TTS are prepared) — this
  is the missing pipeline piece. For sherpa-onnx the model ships as ONNX (no QAIRT/DLC needed; runs
  on CPU/NNAPI), so it does not need the Qualcomm graph-prepare path.
- Latency stages 1–2 stay **provisional** until the chosen model is measured in airplane mode on
  the SM8750 device (and on iOS).
- The streaming-vs-chunked choice changes the state machine's CAPTURING/TRANSCRIBING behavior:
  streaming emits `asr.partial` continuously; chunked emits only `asr.final` after endpoint.

## DECISION (2026-05-30, project leader) — REVISED

The leader chose to **use the platform's built-in ASR for Phase A** and adopt an owned model
later, so app development can proceed without the ASR build/benchmark detour.

- **Phase A ASR: platform/OS built-in ASR** behind a shared `AsrEngine` interface.
  - Android: `SpeechRecognizer` with `createOnDeviceSpeechRecognizer()` (Samsung devices add their
    own on-device engine); supports live partial results.
  - iOS: `SpeechAnalyzer`/`SpeechTranscriber` (iOS 26) or `SFSpeechRecognizer` with
    `supportsOnDeviceRecognition`; supports streaming partials.
  - Both give KO+EN + streaming partials "for free" with zero model shipping.
- **Phase B (post-app): swap to the owned sherpa-onnx model** — Whisper-small chunked first, then
  the KO+EN streaming Zipformer (icefall) — to restore the guarantee and cross-platform parity.
  The engine analysis above is the Phase B plan. (Note: sherpa-onnx ships an Android AAR/jniLibs
  but no prebuilt CLI; a device benchmark needs an NDK build. Whisper-small ONNX ≈ int8 enc 112 MB
  + dec 262 MB. The prep/bench scripting is a Phase B task — not written yet.)

### ⚠️ Trade-off flagged (must be owned by the project leader)
The core promise is "fully on-device, no network at runtime." Platform ASR can route audio to a
server depending on device/locale, so **Phase A is not guaranteed fully offline.** Required Phase A
guardrails:
1. **Gate on on-device availability** (`createOnDeviceSpeechRecognizer` / `supportsOnDeviceRecognition`);
   if unavailable for the locale/device, either block ASR or explicitly fall back to network with a
   user-visible indicator.
2. Qualify the "fully offline" claim until Phase B lands the owned model.
3. Keep ASR behind a thin `AsrEngine` interface so swapping platform→owned model needs no
   pipeline rework (and so the conformance contract treats both identically).

### Phase A implications (applied to specs)
- State machine: platform ASR streams partials → `asr.partial` IS available in Phase A (unlike the
  earlier chunked-Whisper plan). CAPTURING emits partials; `asr.final` on endpoint.
- Latency stages 1–2 are governed by the OS recognizer (not separately benchmarkable via CLI);
  measure in-app during Phase 3. Targets stay provisional.
- No ASR model is added to Phase 0/1 prep for Phase A (OS provides it); the owned-model prep is a
  Phase B item.
- Cross-platform parity risk (R4): Android and iOS OS recognizers differ in accuracy, partial
  cadence, endpointing, and language behavior — the `AsrEngine` interface + conformance fixtures
  must absorb this, and Phase B's shared owned model is what ultimately equalizes UX.

## Sources
- sherpa-onnx pretrained models / Korean streaming zipformer:
  https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16 ,
  https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html
- KsponSpeech icefall recipe: https://huggingface.co/johnBamma/icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12
- SenseVoice (zh/en/ja/ko/yue) via sherpa-onnx: https://k2-fsa.github.io/sherpa/onnx/index.html
- Apple SpeechAnalyzer (iOS 26): https://developer.apple.com/documentation/speech/speechanalyzer
- NVIDIA Parakeet/Canary v3 (no Korean): https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3 , https://huggingface.co/nvidia/canary-1b-v2
- Vosk offline STT: https://www.videosdk.live/developer-hub/stt/vosk-speech-recognition
