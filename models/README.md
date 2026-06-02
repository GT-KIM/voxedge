# Models

Large model files and compiled artifacts should not be committed.

Expected local staging areas:

- `models/original/`: source FP16/FP32 model checkpoints, tokenizers, and manifests.
- `models/artifacts/android-qairt/`: Qualcomm `.dlc` outputs, quantized outputs, graph-prepared outputs, and benchmark reports.
- `models/artifacts/ios/`: `.mlpackage`, MLX artifacts, ANEMLL outputs, and benchmark reports.

Record reproducible preparation steps in `docs/MODEL_PIPELINE.md` and tool-specific files under `tools/model_compile/`.
