# LLM Qualcomm Export Plan

## Primary model

- Model id: `qualcomm/Qwen3-4B-Instruct-2507`
- Runtime: `GENIE`
- Precision: `w4a16`
- Target asset profile: `Snapdragon 8 Elite Mobile`
- Downloaded context length: 4096
- Shorter context length: requires custom export or another Qualcomm bundle

Use Qualcomm AI Hub pre-exported Genie assets for the Android product demo. The prepared artifact is:

- `models/artifacts/android-qairt/llm/qwen3-4b-instruct-2507/extracted/qwen3_4b_instruct_2507-genie-w4a16-qualcomm_snapdragon_8_elite/genie_config.json`

Prepared files:

- `fetch_qwen3_4b_instruct_2507_genie_bundle.sh`
- `validate_qwen3_genie_bundle.py`
- `qwen3_4b_instruct_2507_genie_plan.md`

Current status: host-side Qwen3 Genie bundle download and validation are complete. Android device smoke testing is pending because no Android device is connected to `adb`.

## Gemma 4 E2B research branch

## Source state

- Model id: `google/gemma-4-E2B-it`
- Local path: `models/original/llm/google__gemma-4-E2B-it`
- Weight format: Safetensors
- Source dtype: bfloat16
- Architecture: `Gemma4ForConditionalGeneration`
- Text layers: 35
- Text hidden size: 1536
- Text attention heads: 8
- Text KV heads: 1
- Vocabulary size: 262144
- Sliding window: 512

## Preparation decision

Use direct QNN generation from ONNX-community Gemma4 artifacts only as a research path. QAIRT Genie is not the selected Gemma4 path because the local builder does not expose a Gemma4-compatible preset and Gemma4's decoder contract requires custom runtime orchestration.

The text-only no-cache ONNX exporter remains available as a smoke test, not as the production generation graph. The project speech-to-speech pipeline only needs text input from ASR and text output to TTS.

## Required next artifact

The QAIRT/SNPE `snpe-*-to-dlc` commands need a supported model export such as ONNX, TensorFlow, TFLite, or a PyTorch trace. The downloaded Safetensors checkpoint is source material, not a direct DLC input.

Next concrete Gemma4 artifact:

- `models/artifacts/android-qairt/llm/gemma4-direct-qnn/gemma4_qnn_contract.json`

## Candidate paths

1. Download `onnx-community/gemma-4-E2B-it-ONNX` tokenizer, chat template, `embed_tokens_fp16.onnx`, and `decoder_model_merged_fp16.onnx`.
2. Inspect the ONNX IO contract and record the Android runtime responsibilities.
3. Run `qnn-onnx-converter --dry_run info` for `embed_tokens` and `decoder_model_merged`.
4. Generate QNN model libraries and HTP context binaries.
5. Implement the Android-side token-by-token loop around QNN.

## Blockers

- ONNX-community Gemma4 artifacts are downloaded in the PC's local WSL environment.
- QNN converter dry runs have been executed in the PC's local WSL environment. The rewritten `embed_tokens` graph reaches dry-run; `decoder_model_merged` is blocked by unsupported fused decoder operators.
- Android runtime packaging for tokenizer, chat template, sampling, and KV-cache tensors is not implemented yet.
- Logit parity against ONNX Runtime is still required after context binaries are generated.

## Prepared scripts

- `setup_gemma4_onnx_wsl_venv.sh`: creates a separate WSL Python environment for Gemma ONNX export dependencies.
- `export_gemma4_text_onnx.py`: exports a static text-only no-cache ONNX graph for first QAIRT compatibility validation.
- `fetch_gemma4_onnx_community.sh`: downloads the selected ONNX-community Gemma4 artifacts.
- `inspect_gemma4_qnn_contract.py`: records the direct-QNN IO contract.
- `rewrite_gemma4_onnx_for_qnn.py`: rewrites external-data layout, text-only embed path, FP16 handoff, and simplified layer normalization for QAIRT.
- `convert_gemma4_onnx_to_qnn_contexts.sh`: writes the direct-QNN plan, runs converter dry runs, and can generate HTP contexts.
- `gemma4_direct_qnn_plan.md`: documents the selected direct-QNN packaging flow.

## Current dry-run blocker

`decoder_model_merged` still needs deeper decomposition or custom-op support for `RotaryEmbedding`, `GroupQueryAttention`, `Gelu`, `Where`, `Expand`, `Split` `num_outputs`, Cast handling, and dynamic reshape constants before `qnn-model-lib-generator` or `qnn-context-binary-generator` should be run.

## Do not do

- Do not attempt to feed `model.safetensors` directly to `snpe-pytorch-to-dlc`.
- Do not remove tokenizer files or `chat_template.jinja`; they are runtime assets.
- Do not include vision/audio towers in the first Android LLM export unless required by the project leader.
- Do not treat a no-cache ONNX export as the production LLM runtime artifact.
