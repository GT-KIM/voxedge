# iOS Feasibility Plan

Status: feasibility track, pre-Xcode-project.
Date: 2026-05-31.

## Goal

Decide whether the current on-device speech-to-speech loop can be mirrored on iOS without weakening
the runtime-offline requirement.

Target loop:

```text
App launch -> ASR listening -> user speech -> prompt assembly -> LLM -> MCP event stream
-> TTS -> audio playback -> ASR listening
```

## Current constraints

- Runtime must not require network access after assets/models are provisioned.
- There is no Mac build server in this environment yet, so iOS work must stay build-ready by design
  and cannot be compiled here.
- The project leader has an iPad. Useful paths:
  - Normal iOS app/device testing still needs Xcode on a Mac or a cloud CI/signing path.
  - iPad alone can help with manual TestFlight/device validation later, but it is not enough for the
    Core ML conversion/build/signing pipeline.
  - Swift Playgrounds on iPad may be useful for tiny Swift experiments, not for this full app.
- Keep Android and iOS user experience aligned through shared state names, prompt/config contracts,
  and MCP event/turn schemas.

## Feasibility verdict

Proceed with iOS feasibility in two lanes:

1. Build-ready iOS architecture skeleton in this repo.
2. Model-runtime spike outside the app once a Mac or macOS CI is available.

Do not start a full iOS app implementation until the model-runtime decision is measured on a real
Apple device.

## ASR

Primary candidate: Apple Speech framework.

Feasibility:

- Use `SFSpeechAudioBufferRecognitionRequest` for live microphone audio.
- Set `requiresOnDeviceRecognition = true`.
- Gate offline claims on `SFSpeechRecognizer.supportsOnDeviceRecognition == true` for the chosen
  locale/device.
- Enable partial results for responsiveness, but treat final endpoint behavior as a product risk.

Risk:

- On-device recognition is locale/device dependent. If `supportsOnDeviceRecognition` is false,
  Apple Speech may require network. That is incompatible with this project unless we block the flow
  or switch to an owned Core ML/MLX ASR model.

Fallback candidate:

- Owned ASR model converted to Core ML or run through MLX if Apple Speech fails the offline gate for
  Korean/English on target devices.

## LLM

Candidates:

1. ANEMLL / Core ML / ANE path.
2. MLX Swift path.

Recommended first spike:

- ANEMLL with a known-supported small model family first, because it directly targets iOS/macOS
  sample apps and ANE-oriented Core ML inference.
- MLX Swift as a parallel fallback if ANEMLL conversion or runtime integration blocks.

Important model risk:

- The project began with Gemma4-E2B as the requested LLM source, but current ANEMLL public support
  signals are stronger for Gemma 3, Qwen, LLaMA-family, and smaller Apple-device-oriented models.
- Do not silently replace Gemma4-E2B as the product target. For feasibility, benchmark a supported
  small model first to validate the iOS pipeline, then decide whether Gemma4-E2B is feasible or
  should remain research-only.

Acceptance criteria:

- Resident model load works on target Apple device.
- Streaming tokens are available or can be polled without blocking UI/audio.
- Measured token/sec, peak memory, startup/load time, and thermal behavior are recorded.
- The runtime path works with airplane mode enabled after model provisioning.

## TTS

Primary candidate: Core ML Tools conversion of Supertonic 3 ONNX components.

Recommended first spike:

1. Convert the existing Supertonic 3 ONNX subgraphs to FP16 Core ML `.mlpackage`.
2. Run host-side shape validation on macOS.
3. Build a tiny Swift runner that feeds one short Korean clause and writes PCM/wav.
4. Only then attempt INT8 or palettization/compression.

Risks:

- Supertonic 3 has multiple subgraphs and layout-sensitive inputs. Android already proved that input
  layout mistakes can preserve shape while destroying quality.
- Flow/diffusion-style TTS may be expensive on iOS if the whole path does not land on efficient
  Core ML compute units.
- INT8 quality/performance needs representative calibration; do not make it the first spike.

Acceptance criteria:

- One-clause FP16 Core ML TTS produces audible PCM with comparable duration/amplitude to the host
  reference.
- First PCM timing is measured.
- Runtime memory and thermal behavior are recorded for at least a short sustained loop.

## App architecture skeleton

Keep the iOS code split into the same boundaries as Android:

```text
apps/ios/
  App/
    ConversationApp.swift
    ConversationScreen.swift
  Core/
    ConversationController.swift
    SpeechLoopState.swift
    GenerationEpoch.swift
    PromptAssembler.swift
    McpEvents.swift
  ASR/
    AsrEngine.swift
    AppleSpeechAsr.swift
  LLM/
    LlmEngine.swift
    AnemllLlm.swift
    MlxLlm.swift
  TTS/
    TtsEngine.swift
    CoreMlSupertonicTts.swift
  Audio/
    AudioCapture.swift
    PcmPlayer.swift
```

Initial Swift code should compile once opened in Xcode, but it should be allowed to have runtime
stubs for ANEMLL/MLX/Core ML assets until model artifacts exist.

## Decision matrix

| Area | Default feasibility path | Fallback | Blocker to watch |
|---|---|---|---|
| ASR | Apple Speech with `requiresOnDeviceRecognition` | Owned Core ML/MLX ASR | Locale/device does not support offline recognition |
| LLM | ANEMLL on ANE/Core ML | MLX Swift | Model support, memory, token/sec, conversion complexity |
| TTS | Core ML FP16 Supertonic 3 | Smaller/alternate Core ML TTS | Graph conversion, layout fidelity, latency |
| UI/state | SwiftUI mirror of Android controller | Minimal debug UI first | Divergence from Android UX |
| Build/test | Xcode/macOS CI | build-ready sources only | No local Mac in current environment |

## Next steps

1. Create a build-ready iOS source skeleton matching the architecture above.
2. Add shared-contract Swift models for MCP event names, generation IDs, and speech-loop state.
3. Prepare a macOS-required model spike checklist for:
   - Apple Speech offline gate for KO/EN.
   - ANEMLL small-model load + streaming.
   - MLX Swift small-model load + streaming.
   - Supertonic 3 Core ML FP16 one-clause PCM.
4. When Mac/Xcode access exists, run the spike on a real iPad/iPhone with airplane mode enabled.

## References

- Apple Speech `supportsOnDeviceRecognition` and `requiresOnDeviceRecognition`.
- Apple MLX project page and Swift/C/C++ availability.
- Apple Core ML Tools optimization docs for palettization/quantization availability.
- ANEMLL project and ANE-first iOS/macOS/visionOS app support.
