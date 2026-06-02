# QAIRT/SNPE Compile Pipeline

Target: Qualcomm SM8750 Android devices.

Planned scripts:

- Environment validation for the local QAIRT/SNPE installation.
- Source model export to a supported intermediate format.
- DLC conversion through the appropriate `snpe-*-to-dlc` command.
- INT8 quantization with `snpe-dlc-quantize`.
- Graph preparation with `snpe-dlc-graph-prepare`.
- Device benchmark capture and report generation.

The local QAIRT archive is currently staged under `tools/model_compile/qairt/`.

Project-specific Phase 1 preparation scripts live under `converter/phase1/`.
