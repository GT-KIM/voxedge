# Android app

The Android implementation of the offline speech-to-speech loop:
**mic → VAD → ASR → LLM → clause segmenter → TTS (SNPE, HTP) → playback**, hands-free, fully offline
at runtime.

- **LLM**: two switchable backends — Qwen3-4B via Qualcomm Genie (HTP, primary/measured) and
  Gemma 4 E2B via LiteRT-LM (GPU). Persistent KV, KV rewind, rolling summary, speculative turns,
  repetition penalty (Genie).
- **ASR**: per-language sherpa-onnx — Korean zipformer int8 (primary), SenseVoice for English; a
  GTCRN denoiser is wired but **disabled** in the main loop (it didn't improve CER).
- **Agentic**: 13 offline tools (clock/timer/alarm/battery/flashlight/calculate/memory×3/calendar/
  dial/SMS/navigate) + durable cross-session memory grounded into the prompt.
- **UI**: fully localized per conversation language (all-English / all-Korean), session drawer +
  persistence, settings sheet.

## Build

```powershell
# JVM unit tests
.\gradlew.bat :app:testDebugUnitTest

# debug APK (arm64-v8a)
.\gradlew.bat :app:assembleDebug
```

Build with the Android Studio JBR (set `JAVA_HOME`). The build currently emits an Android Gradle
Plugin warning because AGP is validated up to `compileSdk 34` while this project uses `compileSdk 35`
— harmless for the debug build.

> The APK builds, but **running** the speech loop needs models + the Qualcomm runtime, which are not
> in this repo. See [`../../MODELS.md`](../../MODELS.md) for what to fetch and how to provision it.

## Layout

```
app/                Kotlin/Compose app
  src/main/kotlin/com/conversationalai/agent/
    asr/            OfflineAsr (sherpa-onnx: Korean zipformer for KO, SenseVoice for EN)
    llm/            GenieLlm (JNI -> libGenie.so) + LiteRtLlm (Gemma 4 E2B) + LlmCatalog
    tts/            SupertonicTts (JNI -> resident SNPE engine), TtsInputBuilder
    audio/          MicStream/VAD, StreamingPcmPlayer, SpeechEnhancer
    core/           ConversationController, ClauseSegmenter, PromptAssembler, SpokenTextNormalizer
      memory/       MemoryStore (durable cross-session facts)
      tools/        ToolRegistry / ToolCallFilter (agentic loop)
    devicetools/    DeviceTools (the 13 on-device tools) + Arithmetic
    ui/             Compose UI (UiStrings = per-language localization)
native/             C++ JNI: genie_llm.cpp, supertonic_tts_engine.cpp + CMakeLists
prepare_jnilibs.ps1 copies the QAIRT/SNPE/Genie runtime .so set into jniLibs/ (not committed)
```

## Runtime notes (hard-won)

- The HTP/DSP only becomes available inside a packaged app with the right setup: declare
  `libcdsprpc.so` in the manifest, materialize the v79 DSP skels to a real dir at runtime, set a
  semicolon-separated `ADSP_LIBRARY_PATH` (+ unsigned-PD) before the runtime loads. See the teardown.
- Models live in app-private storage (dev provisioning via `adb`). `sherpa-onnx-*.aar` goes in
  `app/libs/` (gitignored).
