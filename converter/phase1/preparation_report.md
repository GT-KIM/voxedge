# Phase 1 Preparation Report

Date: 2026-05-28

## Prepared

- Confirmed QAIRT archive contains Linux host SNPE tools needed by the project direction.
- Recorded SM8750 target and burst runtime profile requirement in `model_preparation.json`.
- Ran `prepare_qairt_workspace.sh` in the PC's local `Ubuntu-22.04` WSL distro; QAIRT extracted and required tools validated.
- Ran `fetch_qairt_runtime_libs.sh` in local WSL; `libc++`, `libc++abi`, `libomp`, and `libunwind` runtime libraries were extracted into an ignored project-local directory because WSL sudo needs a password.
- Prepared WSL script for Supertonic 3 ONNX component conversion to float DLC.
- Added optional INT8 and graph-prepare stages gated by calibration input lists.
- Added a first-pass static TTS conversion profile: batch 1, text length 256, latent length 1000.
- Generated static-shape ONNX artifacts for Supertonic 3 components without modifying downloaded originals.
- Created a QAIRT Python 3.10 virtual environment under `$HOME/.venvs/qairt-2.46.0-py310`.
- Rewrote Supertonic 3 ONNX graphs with `onnxsim`, static dimensions, and forced opset 17 declaration.
- Generated float DLC files for all four Supertonic 3 components.
- Generated random calibration raw tensors and SNPE input lists for pipeline-only INT8 smoke testing.
- Generated INT8 DLC files and SM8750 graph-prepared DLC files for all four Supertonic 3 components.
- Selected `qualcomm/Qwen3-4B-Instruct-2507` as the primary Android Qualcomm LLM path using Qualcomm AI Hub Genie `w4a16` assets.
- Added WSL script and decision record for downloading and validating the Qwen3 Genie bundle.
- Downloaded the Qwen3 Snapdragon 8 Elite Genie bundle, extracted it, and validated required runtime files plus all four context-binary parts.
- Selected direct QNN generation from ONNX-community Gemma4 artifacts as the next Gemma 4 E2B Qualcomm path.
- Added WSL scripts for Gemma 4 ONNX export environment setup, text-only no-cache ONNX smoke export, ONNX-community artifact fetch, direct-QNN contract inspection, and direct-QNN plan/convert/context generation.
- Downloaded the ONNX-community Gemma4 tokenizer and ONNX external-data shards needed by `embed_tokens` and `decoder_model_merged`.
- Added and ran a Gemma4 QNN compatibility rewrite for external-data materialization, text-only embed path simplification, FP16 embed/decoder handoff, and `SimplifiedLayerNormalization` decomposition.
- Ran QAIRT `qnn-onnx-converter --dry_run info`; `embed_tokens` now reaches dry-run, while `decoder_model_merged` remains blocked by unsupported fused decoder operators.

## Model-specific state

### Qwen3-4B-Instruct-2507

Primary Android Qualcomm LLM from this point forward:

- Model id: `qualcomm/Qwen3-4B-Instruct-2507`
- Runtime: `GENIE`
- Precision: `w4a16`
- Target asset profile: `Snapdragon 8 Elite Mobile`
- Downloaded context length: 4096
- Shorter context length: requires custom export or another Qualcomm bundle
- Bundle path: `models/artifacts/android-qairt/llm/qwen3-4b-instruct-2507/genie_bundle`
- Extracted bundle path: `models/artifacts/android-qairt/llm/qwen3-4b-instruct-2507/extracted/qwen3_4b_instruct_2507-genie-w4a16-qualcomm_snapdragon_8_elite`
- Validation report: `models/artifacts/android-qairt/llm/qwen3-4b-instruct-2507/qwen3_genie_bundle_validation.json`

Prepared output:

- `models/artifacts/android-qairt/llm/qwen3-4b-instruct-2507/extracted/qwen3_4b_instruct_2507-genie-w4a16-qualcomm_snapdragon_8_elite/genie_config.json`

Prepare with:

- `converter/phase1/fetch_qwen3_4b_instruct_2507_genie_bundle.sh`
- `converter/phase1/validate_qwen3_genie_bundle.py`

The Android device smoke test is pending because `adb devices` currently reports no connected devices.

### Gemma 4 E2B Research Branch

Downloaded source is bf16 Safetensors. It is not a direct input to `snpe-*-to-dlc`; it needs a text-only ONNX or another QAIRT-compatible export first.

The research Qualcomm path uses ONNX-community artifacts:

- `models/original/llm/onnx-community__gemma-4-E2B-it-ONNX/onnx/embed_tokens_fp16.onnx`
- `models/original/llm/onnx-community__gemma-4-E2B-it-ONNX/onnx/decoder_model_merged_fp16.onnx`

The next planned output is:

- `models/artifacts/android-qairt/llm/gemma4-direct-qnn/gemma4_qnn_contract.json`

Current local artifacts include:

- `models/artifacts/android-qairt/llm/gemma4-direct-qnn/rewritten_onnx/`
- `models/artifacts/android-qairt/llm/gemma4-direct-qnn/rewrite_report.json`
- `models/artifacts/android-qairt/logs/gemma4_direct_qnn_rewritten_dryrun_20260528.log`

The old text-only no-cache ONNX export validates Safetensors-to-ONNX and QAIRT converter compatibility only. Gemma4 direct QNN generation targets context binaries with Android-side tokenizer, chat-template, sampling, and KV-cache orchestration. Context generation is blocked until decoder fused attention/rotary/activation patterns are decomposed or implemented as QNN custom ops. This branch must not block the Qwen3 Android demo path.

### Supertonic 3

Downloaded source already includes ONNX components:

- `duration_predictor.onnx`
- `text_encoder.onnx`
- `vector_estimator.onnx`
- `vocoder.onnx`

Static-shape and rewritten ONNX copies were generated under ignored artifact storage:

- `models/artifacts/android-qairt/tts/static_onnx/`
- `models/artifacts/android-qairt/tts/static_onnx_opset17_forced/`
- `models/artifacts/android-qairt/tts/rewritten_onnx_venv_l1000/`

Float DLC outputs were generated:

- `models/artifacts/android-qairt/tts/float/duration_predictor.dlc`
- `models/artifacts/android-qairt/tts/float/text_encoder.dlc`
- `models/artifacts/android-qairt/tts/float/vector_estimator.dlc`
- `models/artifacts/android-qairt/tts/float/vocoder.dlc`

Random-calibrated INT8 DLC outputs were generated:

- `models/artifacts/android-qairt/tts/int8/duration_predictor_int8.dlc`
- `models/artifacts/android-qairt/tts/int8/text_encoder_int8.dlc`
- `models/artifacts/android-qairt/tts/int8/vector_estimator_int8.dlc`
- `models/artifacts/android-qairt/tts/int8/vocoder_int8.dlc`

SM8750 graph-prepared DLC outputs were generated:

- `models/artifacts/android-qairt/tts/prepared/duration_predictor_sm8750.dlc`
- `models/artifacts/android-qairt/tts/prepared/text_encoder_sm8750.dlc`
- `models/artifacts/android-qairt/tts/prepared/vector_estimator_sm8750.dlc`
- `models/artifacts/android-qairt/tts/prepared/vocoder_sm8750.dlc`

Important observed failures before the fix:

- `snpe-onnx-to-dlc` on `duration_predictor`: `Transpose` shape inference error.
- `snpe-onnx-to-dlc` on static/forced-opset `duration_predictor`: same `Transpose` shape inference error.
- `snpe-onnx-to-dlc` on `vocoder`: `Reshape` shape inference error.
- `qnn-onnx-converter` dry run reports unsupported opset 19 operator versions, including `PRelu` and `Equal`.
- `qnn-onnx-converter` full conversion on forced-opset `vocoder`: `Reshape` shape inference error.
- Minimal rank-3 `Transpose` probe failed under the wrong WSL Python package stack and succeeded inside the QAIRT venv.

The next model-preparation step is representative calibration data and target-device benchmarking. The current INT8 artifacts use random inputs only for pipeline validation.

## Logs

- `models/artifacts/android-qairt/logs/tts_onnx_inputs.json`
- `models/artifacts/android-qairt/logs/duration_predictor_symbol_test.log`
- `models/artifacts/android-qairt/logs/convert_tts_static_onnx_to_dlc.log`
- `models/artifacts/android-qairt/logs/convert_tts_forced_opset17_to_dlc.log`
- `models/artifacts/android-qairt/logs/vocoder_to_dlc.log`
- `models/artifacts/android-qairt/logs/vocoder_qnn_convert.log`
- `models/artifacts/android-qairt/logs/rewrite_tts_onnx_for_qairt_venv_l1000.log`
- `models/artifacts/android-qairt/logs/convert_tts_rewritten_onnx_venv_l1000_to_dlc.log`
- `models/artifacts/android-qairt/logs/generate_random_tts_calibration_20260528.log`
- `models/artifacts/android-qairt/logs/convert_tts_int8_random_calibration_graph_prepare_20260528.log`
- `models/artifacts/android-qairt/logs/qwen3_4b_instruct_2507_genie_download_20260528.log`
