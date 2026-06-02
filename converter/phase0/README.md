# Phase 0 Source Model Intake

Phase 0 prepares original FP16/FP32 source assets for later Android Qualcomm and iOS conversion work.

## Current status

- LLM source is downloaded: `google/gemma-4-E2B-it`.
- TTS source is downloaded: `Supertone/supertonic-3`.
- Checksums are recorded in `converter/phase0/checksums.sha256`.

## Verified source notes

- `google/gemma-4-31B-it` is a Hugging Face model from Google with Apache 2.0 license and Safetensors weights.
- The Gemma 4 model card describes E2B and E4B as the mobile/edge-oriented smaller models, and the card also references `google/gemma-4-E2B-it` in audio examples.
- `Supertone/supertonic-3` is a Hugging Face model from Supertone with OpenRAIL-M model terms and ONNX assets.
- Supertonic 3 is intended for local ONNX Runtime inference and supports 31 languages.

## Source decision

The project leader selected `google/gemma-4-E2B-it` for Phase 0. The original supplied URL still resolves to `google/gemma-4-31B-it`, so keep that mismatch documented for traceability.

## Local storage

Large model assets must be stored under ignored directories:

```text
models/original/llm/
models/original/tts/
```

Do not commit checkpoints, ONNX files, Safetensors files, or converted artifacts.

## Fetch workflow

Run from WSL after the LLM model id is confirmed and model terms are accepted:

```bash
cd <repo-root>
ACCEPT_MODEL_LICENSES=1 bash converter/phase0/fetch_sources.sh
```

To override the default LLM, set `LLM_MODEL_ID` explicitly.

The script only downloads after `ACCEPT_MODEL_LICENSES=1` is set.
