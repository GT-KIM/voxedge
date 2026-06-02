# Android App Architecture (Phase 3)

Status: design, pre-code. Maps the platform-neutral specs to concrete Android modules, threads,
and runtime integrations. Specs this implements: `speech_loop_state_machine.md` (states + power),
`latency_budget.md` (waterfall), `shared/mcp/` (event contract), `shared/config/` (typed config).
Verified runtime facts: `docs/demo/sm8750_measurements.md`.

## 1. Scope of the Phase 3 vertical slice
Offline speech-to-speech loop on SM8750: mic → ASR → prompt → LLM (Genie) → clause stream → TTS
(native SNPE engine) → playback → back to listening, with barge-in. Single screen. Korean+English.
NOT in scope yet: settings UI polish, multi-turn memory summarization UI, iOS (mirrored later).

## 2. Module / Gradle structure
Single Android Studio project, min SDK 34 (Android 14), target SDK 35; NDK r30; one app + a native lib.

```
apps/android/
  settings.gradle.kts, build.gradle.kts (root), gradle/ (wrapper)
  app/                         (Kotlin app module)
    src/main/AndroidManifest.xml   (RECORD_AUDIO, foreground service for the loop)
    src/main/kotlin/.../
      ui/            ConversationScreen (Compose): state chip + transcript + barge-in affordance
      core/          ConversationController (the state machine), ClauseSegmenter, PromptAssembler,
                     ConfigLoader (parses shared/config schema), EventBus (typed events)
      asr/           AsrEngine (interface) + PlatformAsr (Android on-device SpeechRecognizer)
      llm/           LlmEngine (interface) + GenieLlm (JNI/Genie or genie wrapper)
      tts/           TtsEngine (interface) + SupertonicTts (JNI -> libsupertonic_tts.so)
      audio/         AudioCapture (AudioRecord+VAD+AEC), AudioPlayer (AudioTrack), AudioFocus
  native/
    tts_engine/      supertonic_tts_engine.cpp  (existing resident engine; refactor to a class
                     + JNI entry points; build via CMake/externalNativeBuild instead of the
                     standalone build_tts_engine.sh)
    CMakeLists.txt   (links libSNPE; packages with the app's jniLibs)
```

Models/DLCs are NOT bundled in the APK (multi-GB). First-run asset provisioning copies them to app
storage (see §7). Keep `shared/` as the single source of the config + event contract; the app reads
the same JSON schema the conformance suite validates.

## 3. The native TTS engine → JNI
The existing `supertonic_tts_engine.cpp` is a CLI today. Refactor into:
- A C++ class `SupertonicTtsEngine { load(dlcDir); SynthResult synthesizeClause(inputs); }` holding
  the 3 resident SNPE instances + reusable UserBuffers (load once, ~24 ms/step, ~220 ms/clause).
- JNI bridge `Java_..._SupertonicTts_*`:
  - `nativeInit(dlcDir)` → returns a handle (loads DLCs once, on a dedicated TTS thread).
  - `nativeSynthesize(handle, textIds, textMask, styleTtl, styleDp, noisyLatent, latentMask, K)`
    → returns a `float[]`/`ByteBuffer` PCM (one clause).
  - `nativeRelease(handle)`.
- **Layout contract enforced at the JNI boundary** (the two verified gotchas):
  1. `text_ids` is Int_32 → JNI passes an `int[]`; engine uses `UserBufferEncodingIntN(32)`.
  2. Every multi-dim input is supplied in DLC layout (last two axes transposed vs ONNX:
     style_ttl [1,256,50], text_mask [1,64,1], noisy_latent [1,128,144], latent_mask [1,128,1]).
     The Kotlin input-prep (port of `tools/tts/prep_static_tts_inputs.py --layout dlc`) does the
     transpose; the engine memcpy's bytes as-is. Wrong layout = lost speaker identity (proven).
- Build via CMake `externalNativeBuild`; package `libSNPE.so` + HTP stubs/skel + `libc++_shared.so`
  into `jniLibs/arm64-v8a`. (genie-t2t / Genie LLM has its own libs; see §5.)

## 4. Threading & concurrency (maps to the statechart's concurrent regions)
Four long-lived workers behind the controller; communicate via the typed event bus (each event
carries `generation_id` + `seq` + `chunk_id` per the contract). No blocking on the main/UI thread.

| Region | Thread | Responsibility |
|---|---|---|
| R-listen | audio-capture thread | AudioRecord + VAD + AEC; emits speech-start / endpoint / barge-in |
| R-generate | llm thread | Genie decode → ClauseSegmenter → `tts.chunk_request` per clause |
| R-synth | tts thread | resident SupertonicTts.synthesizeClause → `tts.audio_chunk` (pcm_ref) |
| R-play | audio-play thread | AudioTrack queue; emits playback_start/end; honors cancel |

`ConversationController` owns the current `generation_id`. On barge-in it bumps the epoch; every
worker drops stale-epoch events; R-play stops mid-clause within 200 ms (AudioTrack.pause+flush).
LLM and TTS pipeline: clause N synthesizes while LLM decodes clause N+1 and R-play plays clause N-1.

## 5. LLM integration (Genie)
Two viable routes (decide during Stage A coding):
- (a) Genie SDK / `libGenie.so` via JNI (token streaming callback) — preferred for clause streaming.
- (b) Wrap the `genie-t2t-run` flow in-process.
Resident: load once in STARTING (~6 s, on a splash/warm-up); never reload per turn. Apply the
history cap from the state machine (window + rolling summary within ctx 4096). Stream tokens →
ClauseSegmenter (split on sentence/clause punctuation + max ~10–12 tokens) → emit `tts.chunk_request`
with the first clause kept short to minimize first-audio.

## 6. ASR integration (Phase A = platform)
`PlatformAsr implements AsrEngine` using Android on-device `SpeechRecognizer`:
- Gate on `SpeechRecognizer.createOnDeviceSpeechRecognizer` availability (config
  `asr.platform.require_on_device`); if unavailable, block or network-fallback per
  `allow_network_fallback` with a visible indicator (offline claim qualified — see asr_selection.md).
- Streams partials → `asr.partial`; endpoint → `asr.final`. Phase B swaps to owned sherpa-onnx
  behind the same `AsrEngine` interface (no controller change).

## 7. Models / assets at runtime
- DLCs (Qwen3 Genie bundle ~3 GB; Supertonic short-chunk DLCs ~0.6 GB) live in app-private storage,
  provisioned on first run (adb push for dev; Play Asset Delivery / on-demand later). Checksum-verify.
- Voice style JSON (F1) + unicode_indexer.json shipped as assets (small).
- All runtime is offline once provisioned (the core promise; ASR caveat in §6).

## 8. Build & run (dev)
- Gradle wrapper; build with Android Studio JBR (JDK present) — no global gradle needed.
- `adb` at `<SDK>/platform-tools/adb.exe`. Install debug APK, push models to app storage, run.
- Native engine already builds standalone (`build_tts_engine.sh`); CMake reuses the same source.

## 9. Build order (next coding steps)
1. ✅ DONE (2026-05-30): Gradle/NDK skeleton builds. `apps/android` = root +`:app` (Kotlin 2.0.20,
   Compose, minSDK 34/target 35, Kotlin compose-compiler plugin) + `native/CMakeLists.txt`
   compiling `libsupertonic_tts.so` (engine guarded by `TTS_ENGINE_AS_LIBRARY`, + `tts_jni.cpp`,
   linking imported libSNPE). `:app:assembleDebug` → `app-debug.apk` (~30 MB), arm64-v8a only.
   App theme = `Theme.ConversationalAi` (platform Material, no Material-Components XML dep).
   Gradle wrapper 8.14, build with Android Studio JBR (JAVA_HOME).
   ⚠️ KNOWN GAP (handled at install time, not build): the APK has `libsupertonic_tts.so` but NOT
   its dependency `libSNPE.so` + HTP stub/skel + `libc++_shared.so` — those are copied into
   `app/src/main/jniLibs/arm64-v8a/` by a prepare step (gitignored). Until then the lib loads
   but `nativeVersion()` would fail at runtime with UnsatisfiedLinkError. Add the jniLibs copy
   when wiring real synthesis (step 2).
2. **JNI-wrap the TTS engine** + Kotlin input-prep (DLC layout) → unit "text → PCM → AudioTrack"
   demo (no ASR/LLM yet). This is the first runnable slice and validates the JNI + layout contract.
   Includes: copy SNPE runtime .so set into `jniLibs/arm64-v8a/` (a prepare gradle task / script),
   `nativeInit/nativeSynthesize/nativeRelease`, push DLCs via adb to app storage.
3. Genie LLM JNI + ClauseSegmenter → "typed text → streamed clauses → TTS → audio".
4. PlatformAsr + AudioCapture(VAD/AEC) → full mic→…→playback loop with barge-in.
5. ConversationController state machine + power policy + EventBus wired to the real workers.
6. Measure the real single-process first-PCM waterfall in-app (replaces the component-sum estimate).

## 10. Decisions (confirmed 2026-05-30)
- **LLM route: (a) Genie SDK via JNI** (`libGenie.so`, token-streaming callback) — best fit for
  clause streaming.
- **minSDK 34, targetSDK 35; the always-listening loop runs in a foreground service** (mic +
  ongoing notification; survives backgrounding).
- **Dev asset provisioning: `adb push`** DLCs/models into app-private storage (Play Asset Delivery
  deferred to release).
