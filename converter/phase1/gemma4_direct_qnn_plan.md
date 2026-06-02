# Gemma4 Direct QNN Packaging Plan

## Decision

Use direct QNN generation for Gemma4 instead of QAIRT Genie packaging unless a later Genie release provides a compatible Gemma4 preset.

Gemma4's ONNX-community decoder is not a simple text-generation graph. It expects embedding vectors, per-layer inputs, explicit position and attention tensors, and explicit KV-cache tensors. That contract is acceptable for QNN, but it means the Android app must own the generation loop.

## Components

- `embed_tokens_fp16.onnx`: converts token ids into `inputs_embeds`.
- `decoder_model_merged_fp16.onnx`: consumes `inputs_embeds`, `per_layer_inputs`, `position_ids`, `attention_mask`, and `past_key_values`; emits logits and updated KV-cache tensors.

## Android runtime responsibilities

- Load tokenizer, special tokens, and chat template from the ONNX-community artifact.
- Maintain the prompt template and conversation state.
- Run `embed_tokens` for new token ids.
- Construct decoder inputs for prefill and decode steps.
- Maintain KV-cache tensors across decode steps.
- Run sampling, stopping criteria, and MCP formatting outside QNN.

## Qualcomm build flow

```bash
cd <repo-root>
bash converter/phase1/setup_gemma4_onnx_wsl_venv.sh
bash converter/phase1/fetch_gemma4_onnx_community.sh
python3 converter/phase1/inspect_gemma4_qnn_contract.py
python3 converter/phase1/rewrite_gemma4_onnx_for_qnn.py
GEMMA4_QNN_MODE=dry-run bash converter/phase1/convert_gemma4_onnx_to_qnn_contexts.sh
GEMMA4_QNN_MODE=context bash converter/phase1/convert_gemma4_onnx_to_qnn_contexts.sh
```

The default script mode is `plan`, which only writes the intended QNN config and artifact manifest. Use `dry-run` first after downloading and rewriting the ONNX files. Use `context` only after the dry run shows the converter accepts both graphs.

## Target settings

- Target SoC: `SM8750`
- HTP architecture: `v79`
- QNN context `soc_model`: `69`
- Runtime performance profile: `burst`

## Validation order

1. Inspect ONNX IO contract and record it in `gemma4_qnn_contract.json`.
2. Run `qnn-onnx-converter --dry_run info` for both graphs.
3. Generate QNN model libraries.
4. Generate HTP context binaries.
5. Add an Android-side token-by-token QNN runner test with fixed prompt and deterministic sampler.
6. Compare logits from the QNN runner against ONNX Runtime on the same prompt bucket before tuning performance.

## Current Dry-Run Result

The first rewritten dry-run reached the decoder but did not produce QNN context-ready graphs.

- `embed_tokens`: text-only rewrite removes the multimodal `Where` path and FP32 output Casts; QAIRT dry-run reaches graph evaluation with shape warnings.
- `decoder_model_merged`: still blocked by unsupported fused decoder patterns: `RotaryEmbedding`, `GroupQueryAttention`, `Gelu`, `Where`, `Expand`, `Split` `num_outputs`, Cast handling, and dynamic reshape constants.

Next implementation work is not `qnn-context-binary-generator`; it is decoder graph decomposition or QNN custom-op packaging for the fused attention/rotary path.
