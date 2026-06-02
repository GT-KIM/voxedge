# Network-Free Runtime Evidence

Status: first evidence package, 2026-05-31.

This document records how the current Android runtime implements ASR, LLM, and TTS without a
runtime network dependency. Development-time asset acquisition/provisioning may require network
access; the claim here is narrower and testable: after models/assets are present on device, the
conversation runtime does not need network access.

## Claim

The Android speech loop is built from local engines and local model artifacts:

```text
microphone PCM
  -> OfflineAsr (sherpa-onnx, local ONNX models)
  -> PromptAssembler
  -> GenieLlm (local Genie bundle + libGenie.so)
  -> ClauseSegmenter
  -> SupertonicTts (local graph-prepared DLCs + SNPE)
  -> AudioTrack playback
```

The APK does **not** request `android.permission.INTERNET`, and the app source under
`apps/android/app/src/main` and `apps/android/native` does not call HTTP, socket, WebSocket, or
URL APIs.

## Static Evidence

### Android permissions

`apps/android/app/src/main/AndroidManifest.xml` requests only microphone/foreground-service/
notification permissions:

- `android.permission.RECORD_AUDIO`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_MICROPHONE`
- `android.permission.POST_NOTIFICATIONS`

It does not request:

- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.CHANGE_NETWORK_STATE`

### Source network scan

The contract test `tests/test_network_free_runtime_evidence.py` scans Android app/native source for
network entry points:

- `Socket`
- `URL(`
- `HttpURLConnection`
- `URLConnection`
- `OkHttp`
- `Retrofit`
- `WebSocket`
- `http://`
- `https://`

This is not a substitute for runtime packet capture, but it prevents accidental introduction of a
normal app-layer network path.

## ASR Evidence

Runtime class: `apps/android/app/src/main/kotlin/com/conversationalai/agent/asr/OfflineAsr.kt`

Implementation:

- Uses `sherpa-onnx` `OfflineRecognizer`.
- Loads model files from app-private storage:
  - `filesDir/asr/model.int8.onnx`
  - `filesDir/asr/tokens.txt`
  - optional Korean Dolphin backend under `filesDir/asr_dolphin/`
- Uses CPU provider in the recognizer config.
- Accepts local PCM samples and returns text synchronously through `transcribe(samples, sampleRate)`.

Network-free boundary:

- No Android `SpeechRecognizer`.
- No cloud ASR endpoint.
- No network fallback path.

Known limitation:

- Current ASR is chunked/offline rather than streaming partial ASR. Streaming partials can be added
  later behind the same `AsrEngine` boundary, but must remain owned/offline for the full offline
  product claim.

## LLM Evidence

Runtime classes:

- `apps/android/app/src/main/kotlin/com/conversationalai/agent/llm/GenieLlm.kt`
- `apps/android/native/llm/genie_llm.cpp`

Implementation:

- Loads `libgenie_llm.so`, which links local `libGenie.so`.
- Loads a local Genie bundle from app-private storage:
  - `filesDir/llm_bundle/genie_config.json`
  - `filesDir/llm_bundle/tokenizer.json`
  - `filesDir/llm_bundle/qwen3_4b_instruct_2507_w4a16_part_*_of_4.bin`
  - `filesDir/llm_bundle/htp_backend_ext_config.json`
- Native init rewrites bundle-relative filenames to absolute local filesystem paths before creating
  the Genie dialog.
- Generation runs through `GenieDialog_query` and streams text chunks back through a JNI callback.

Network-free boundary:

- No hosted LLM endpoint.
- No tokenizer/model fetch at runtime.
- No process working-directory mutation or remote path resolution.

## TTS Evidence

Runtime classes:

- `apps/android/app/src/main/kotlin/com/conversationalai/agent/tts/SupertonicTts.kt`
- `apps/android/native/tts_engine/supertonic_tts_engine.cpp`

Implementation:

- Loads `libsupertonic_tts.so`.
- Loads local graph-prepared DLC files from app-private storage:
  - `filesDir/tts_dlc/text_encoder.dlc`
  - `filesDir/tts_dlc/vector_estimator.dlc`
  - `filesDir/tts_dlc/vocoder.dlc`
- Runs SNPE on the device DSP/HTP using resident UserBuffers.
- Synthesizes one clause at a time and returns local PCM to AudioTrack playback.

Network-free boundary:

- No hosted TTS endpoint.
- No runtime voice/model download.
- No remote audio synthesis.

Correctness constraints already documented elsewhere:

- `text_ids` must be passed as int32 UserBuffer input.
- Multi-dimensional inputs must be supplied in DLC layout, not original ONNX layout.

## Runtime Reproduction Checklist

Use this checklist for the public demo evidence package:

1. Provision ASR, LLM, TTS, VAD, and DSP runtime assets into app-private storage.
2. Enable airplane mode on the target Android device.
3. Install and launch the debug APK.
4. Confirm runtime readiness strip shows ASR, LLM, TTS, VAD, models, and mic ready.
5. Run one Korean and one English turn.
6. Save logcat lines for:
   - ASR engine ready
   - LLM ready
   - TTS engine ready
   - ASR final text
   - LLM TTFT / token stream
   - TTS first PCM / playback start
7. Confirm the package still has no `INTERNET` permission:

```powershell
adb shell dumpsys package com.conversationalai.agent | findstr INTERNET
```

Expected result: no output.

## Remaining Evidence To Add

Static evidence is now covered. The next stronger proof is dynamic:

- Add an app-side latency/event JSON log for each turn. Initial Android JSONL schema:
  [`runtime_event_log_schema.md`](runtime_event_log_schema.md).
- Add a repeatable airplane-mode demo script and log bundle.
- Add optional packet-capture evidence showing no packets during the speech loop.
- Record a short public demo video with airplane mode visible before the turn starts.
