# Model Pipeline Skeleton

> **LLM status:** the **current Android demo path uses Qwen3-4B-Instruct-2507** via the Qualcomm
> Genie `w4a16` bundle (runs on the HTP — this is what the app actually ships). **Gemma-4-E2B was the
> original Phase-0 plan** and is kept below as a research branch only (direct-QNN export remains
> blocked on unsupported decoder ops — see `converter/phase1/gemma4_direct_qnn_plan.md`). For how to
> obtain and provision the models that the app uses, see [`../MODELS.md`](../MODELS.md).

## Phase 0: source models

- LLM (current): `Qwen3-4B-Instruct-2507` Genie `w4a16` bundle (Qualcomm AI Hub) — see `MODELS.md`.
- LLM (original plan / research branch): `google/gemma-4-E2B-it`.
- TTS: `Supertone/supertonic-3` downloaded to `models/original/tts/supertonic-3`.
- Record source URL, license, checksum, local path, precision, and tokenizer/config metadata.

Phase 0 local artifacts:

- Source intake runbook: `converter/phase0/README.md`
- Source manifest: `converter/phase0/model_sources.json`
- WSL fetch script: `converter/phase0/fetch_sources.sh`
- Download report: `converter/phase0/download_report.md`
- Checksums: `converter/phase0/checksums.sha256`

## Phase 1: Android Qualcomm QAIRT/SNPE

Target device: SM8750.

Expected stages:

1. Export source model to a QAIRT/SNPE-compatible intermediate format.
2. Convert with the appropriate `snpe-*-to-dlc` tool.
3. Quantize with `snpe-dlc-quantize` using INT8 settings.
4. Prepare graph with `snpe-dlc-graph-prepare`.
5. Use burst power profile and highest-performance runtime settings where available.
6. Benchmark on target hardware and save reports.

Prepared Phase 1 files:

- Preparation runbook: `converter/phase1/README.md`
- Preparation manifest: `converter/phase1/model_preparation.json`
- QAIRT workspace script: `converter/phase1/prepare_qairt_workspace.sh`
- QAIRT runtime dependency script: `converter/phase1/fetch_qairt_runtime_libs.sh`
- TTS static ONNX script: `converter/phase1/prepare_tts_static_onnx.py`
- TTS QAIRT rewrite script: `converter/phase1/rewrite_tts_onnx_for_qairt.py`
- TTS conversion script: `converter/phase1/convert_tts_onnx_to_dlc.sh`
- LLM export plan: `converter/phase1/llm_export_plan.md`
- Calibration notes: `converter/phase1/calibration/README.md`

## Phase 2: iOS

LLM candidates:

- ANEMLL
- MLX

TTS path:

- Core ML Tools, starting with FP16.
- Consider INT8 quantization only if latency, memory, or thermal behavior requires it.

Selection criterion:

- Compare throughput, memory, startup latency, implementation risk, and app integration effort before choosing the default iOS LLM runtime.
