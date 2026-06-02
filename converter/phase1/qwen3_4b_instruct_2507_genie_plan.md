# Qwen3-4B-Instruct-2507 Genie Plan

## Decision

Use `qualcomm/Qwen3-4B-Instruct-2507` as the primary Android Qualcomm LLM from this point forward.

Gemma4 remains a research branch because its ONNX-community decoder still requires deep decomposition or QNN custom-op work before it can produce QNN context binaries. Qwen3-4B-Instruct-2507 has ready-made Qualcomm AI Hub Genie assets, which is the better path for the first on-device speech-to-speech demo.

## Target

- Runtime: `GENIE`
- Precision: `w4a16`
- Target SoC: `SM8750`
- Preferred device asset: `Snapdragon 8 Elite Mobile`
- Minimum QNN SDK listed by Qualcomm AI Hub: `2.45.0`
- Local QAIRT: `2.46.0.260424`
- Downloaded context length: `4096`
- Shorter context length: requires custom export or another Qualcomm bundle

## Asset Preparation

```bash
cd <repo-root>
bash converter/phase1/fetch_qwen3_4b_instruct_2507_genie_bundle.sh
```

The script downloads the Qualcomm AI Hub pre-exported Genie bundle and exposes it at:

```text
models/artifacts/android-qairt/llm/qwen3-4b-instruct-2507/extracted/qwen3_4b_instruct_2507-genie-w4a16-qualcomm_snapdragon_8_elite
```

Validate it with:

```bash
python3 converter/phase1/validate_qwen3_genie_bundle.py
```

## Runtime Prompt Format

Qwen uses ChatML-style turns. Use real line feeds in prompt files instead of escaped `\n` text.

```text
<|im_start|>system
You are a helpful AI assistant.<|im_end|>
<|im_start|>user
Hello.<|im_end|>
<|im_start|>assistant
```

## Validation Order

1. Download and extract the Genie bundle.
2. Verify `genie_config.json` exists in the bundle.
3. Push the bundle to Android with `adb`.
4. Run `genie-t2t-run` with a prompt file and `--profile`.
5. Record TTFT, token rate, memory, and context length.
6. Integrate the Android app through Genie SDK after command-line smoke testing passes.

## Current Status

The Snapdragon 8 Elite Genie bundle has been downloaded and validated host-side. The bundle's `genie_config.json` uses context size 4096. The Android device smoke test is pending because `adb devices` currently reports no connected devices.
