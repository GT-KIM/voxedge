# Phase 1 Qualcomm Model Preparation

Phase 1 prepares Android Qualcomm artifacts for SM8750-class devices using QAIRT/SNPE.

## Current status

- QAIRT archive is present at `tools/model_compile/qairt/v2.46.0.260424.zip`.
- QAIRT archive contains Linux host tools for `snpe-onnx-to-dlc`, `snpe-dlc-quantize`, and `snpe-dlc-graph-prepare`.
- QAIRT was extracted and validated in the PC's local `Ubuntu-22.04` WSL distro.
- Supertonic 3 TTS is split into ONNX components; float DLC conversion, random-calibrated INT8 quantization, and SM8750 graph preparation now succeed through the rewritten ONNX path.
- Qwen3-4B-Instruct-2507 is selected as the primary Android Qualcomm LLM path using Qualcomm AI Hub pre-exported Genie `w4a16` assets.
- Gemma 4 E2B is kept as a research branch. Direct QNN generation from ONNX-community Gemma4 FP16 artifacts reaches QAIRT dry-run for embedding, but the decoder remains blocked by unsupported fused decoder operators.

## Prepared files

- `model_preparation.json`: source metadata, target platform, QAIRT tool paths, and per-model preparation state.
- `prepare_qairt_workspace.sh`: WSL script to extract QAIRT and validate expected host tools.
- `fetch_qairt_runtime_libs.sh`: WSL script to fetch project-local QAIRT runtime library dependencies when sudo package installation is unavailable.
- `setup_qairt_wsl_venv.sh`: WSL script to create a QAIRT Python 3.10 virtual environment under `$HOME` with tested converter dependency versions.
- `prepare_tts_static_onnx.py`: WSL/Python script to create static-shape Supertonic ONNX copies in the ignored artifact directory.
- `rewrite_tts_onnx_for_qairt.py`: WSL/Python script that runs ONNX simplification, fixes static shapes, and emits QAIRT-targeted ONNX copies without touching downloaded originals.
- `generate_random_tts_calibration.py`: WSL/Python script to generate reproducible random raw tensors and SNPE input lists for pipeline-only INT8 calibration smoke tests.
- `convert_tts_onnx_to_dlc.sh`: WSL script to convert Supertonic 3 ONNX components to DLC, then optionally quantize and graph-prepare when calibration inputs are available.
- `fetch_qwen3_4b_instruct_2507_genie_bundle.sh`: WSL script to download and extract the Qualcomm AI Hub Qwen3 Genie bundle for Snapdragon 8 Elite.
- `validate_qwen3_genie_bundle.py`: validates the extracted Qwen3 Genie bundle structure and records the result.
- `qwen3_4b_instruct_2507_genie_plan.md`: primary LLM decision record and Genie validation plan.
- `setup_gemma4_onnx_wsl_venv.sh`: WSL script to create a separate Gemma 4 ONNX export environment.
- `fetch_gemma4_onnx_community.sh`: WSL script to download the ONNX-community Gemma4 tokenizer, chat template, `embed_tokens`, and merged decoder artifacts.
- `export_gemma4_text_onnx.py`: WSL/Python script to export a text-only no-cache ONNX graph for QAIRT converter smoke testing.
- `inspect_gemma4_qnn_contract.py`: WSL/Python script to record the ONNX-community Gemma4 IO contract needed by the Android QNN loop.
- `rewrite_gemma4_onnx_for_qnn.py`: WSL/Python script to materialize external-data shards and rewrite `SimplifiedLayerNormalization` into primitive ONNX ops for QAIRT.
- `convert_gemma4_onnx_to_qnn_contexts.sh`: WSL script to plan, dry-run, convert, and context-serialize Gemma4 QNN components for SM8750.
- `gemma4_direct_qnn_plan.md`: direct-QNN decision record and Android runtime contract.
- `llm_export_plan.md`: decision record for Gemma 4 E2B export before Qualcomm conversion.
- `calibration/README.md`: calibration input requirements for INT8 quantization.

## Execution order in WSL

```bash
cd <repo-root>
bash converter/phase1/prepare_qairt_workspace.sh
bash converter/phase1/fetch_qairt_runtime_libs.sh
bash converter/phase1/setup_qairt_wsl_venv.sh
source models/artifacts/android-qairt/env.sh
python3 converter/phase1/prepare_tts_static_onnx.py \
  models/original/tts/supertonic-3/onnx \
  models/artifacts/android-qairt/tts/static_onnx
python3 converter/phase1/rewrite_tts_onnx_for_qairt.py \
  models/original/tts/supertonic-3/onnx \
  models/artifacts/android-qairt/tts/rewritten_onnx
python3 converter/phase1/generate_random_tts_calibration.py
bash converter/phase1/convert_tts_onnx_to_dlc.sh
bash converter/phase1/fetch_qwen3_4b_instruct_2507_genie_bundle.sh
python3 converter/phase1/validate_qwen3_genie_bundle.py
bash converter/phase1/setup_gemma4_onnx_wsl_venv.sh
bash converter/phase1/fetch_gemma4_onnx_community.sh
python3 converter/phase1/inspect_gemma4_qnn_contract.py
python3 converter/phase1/rewrite_gemma4_onnx_for_qnn.py
GEMMA4_QNN_MODE=plan bash converter/phase1/convert_gemma4_onnx_to_qnn_contexts.sh
GEMMA4_QNN_MODE=dry-run bash converter/phase1/convert_gemma4_onnx_to_qnn_contexts.sh
```

The TTS script produces float DLC files, then INT8 DLCs and SM8750 graph-prepared DLCs when component-specific calibration input lists are present.

Default TTS static conversion profile:

- `TTS_BATCH_SIZE=1`
- `TTS_TEXT_LENGTH=256`
- `TTS_LATENT_LENGTH=1000`

Override these environment variables before running `convert_tts_onnx_to_dlc.sh` if the product utterance limits change.

If QAIRT rejects ONNX opset 19 operator versions, an experimental static export can force the opset declaration after shape inference:

```bash
TTS_FORCE_OPSET_WITHOUT_CONVERSION=1 python3 converter/phase1/prepare_tts_static_onnx.py \
  models/original/tts/supertonic-3/onnx \
  models/artifacts/android-qairt/tts/static_onnx_opset17_forced
```

Use this only as a compatibility experiment; it does not perform semantic operator rewriting.

## Previous TTS blocker

Initial QAIRT conversion attempts failed before DLC output:

- `duration_predictor`: SNPE converter fails at `/sentence_encoder/text_embedder/Transpose`.
- `vocoder`: SNPE/QNN converter fails at `/Reshape`.
- QNN dry runs report unsupported opset 19 operator versions such as `PRelu` and `Equal`.

Working path: run the QAIRT venv setup, rewrite Supertonic 3 ONNX with latent length 1000, then convert the rewritten ONNX files.

Random calibration inputs have been generated and used for an INT8 pipeline smoke test. Next step: replace random calibration with representative samples and run target-device benchmarks.

## Primary LLM Genie path

The selected product LLM is `qualcomm/Qwen3-4B-Instruct-2507` with Qualcomm AI Hub Genie `w4a16` assets.

Default settings:

- Runtime: `GENIE`
- Precision: `w4a16`
- Target SoC: `SM8750`
- Target asset profile: `Snapdragon 8 Elite Mobile`
- Minimum QNN SDK listed by Qualcomm AI Hub: `2.45.0`
- Local QAIRT: `2.46.0.260424`
- Downloaded context length: `4096`
- Shorter context length: requires custom export or another Qualcomm bundle

Prepare the bundle:

```bash
cd <repo-root>
bash converter/phase1/fetch_qwen3_4b_instruct_2507_genie_bundle.sh
```

The downloaded bundle is extracted at:

```text
models/artifacts/android-qairt/llm/qwen3-4b-instruct-2507/extracted/qwen3_4b_instruct_2507-genie-w4a16-qualcomm_snapdragon_8_elite
```

Validated host-side contents:

- `genie_config.json`
- `text-generator.json`
- `tokenizer.json`
- `tokenizer_config.json`
- `htp_backend_ext_config.json`
- `metadata.json`
- four `qwen3_4b_instruct_2507_w4a16_part_*_of_4.bin` context binaries

Use `genie-t2t-run` with prompt files containing real line feeds for command-line smoke testing before Android SDK integration. Device smoke testing is pending because no Android device is currently attached to `adb`.

## Gemma4 research direct-QNN path

Gemma4 conversion should not start from the downloaded Safetensors file alone. The research path is to use `onnx-community/gemma-4-E2B-it-ONNX` artifacts as QNN converter input:

- `embed_tokens_fp16.onnx`
- `decoder_model_merged_fp16.onnx`

These artifacts still are not drop-in Genie inputs. The Android app must own the Gemma4 generation loop: tokenizer, chat template, prompt state, `inputs_embeds`, `per_layer_inputs`, `position_ids`, `attention_mask`, KV-cache tensors, sampling, and stopping criteria.

The old text-only no-cache ONNX target remains available at `models/artifacts/android-qairt/llm/gemma4_text_prefill_no_cache.onnx` for converter smoke testing only. It is not the production generation artifact because it does not expose KV-cache inputs and outputs.

Default direct-QNN compile settings:

- `TARGET_SOC_MODEL=SM8750`
- `QNN_HTP_ARCH=v79`
- `QNN_CONTEXT_SOC_MODEL=69`
- `QNN_PERF_PROFILE=burst`

Run `GEMMA4_QNN_MODE=plan` first; it writes the QNN backend config and artifact plan without requiring large ONNX files. Run `dry-run` only after the ONNX-community artifacts are downloaded and rewritten. Run `context` after both converter dry runs pass.

Current dry-run result:

- `embed_tokens`: reaches QAIRT dry-run after text-only and FP16 handoff rewrites, with remaining shape warnings.
- `decoder_model_merged`: blocked by unsupported fused decoder patterns in QAIRT dry-run, including `RotaryEmbedding`, `GroupQueryAttention`, `Gelu`, `Where`, `Expand`, `Split` with `num_outputs`, and Cast/layout handling.

This means direct QNN remains the right packaging direction, but the decoder still needs either deeper ONNX decomposition or QNN custom-op/plugin work before context-binary generation can succeed.
