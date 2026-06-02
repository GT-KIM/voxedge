# Supertonic 3 QAIRT Conversion Report

Date: 2026-05-28

## Result

Float DLC conversion, random-input INT8 quantization, and SM8750 HTP graph preparation succeeded for all Supertonic 3 TTS components in the PC's local `Ubuntu-22.04` WSL distro.

The successful run used:

- QAIRT: `tools/model_compile/qairt/v2.46.0.260424.zip`
- Python venv: `$HOME/.venvs/qairt-2.46.0-py310`
- Rewritten ONNX path: `models/artifacts/android-qairt/tts/rewritten_onnx_venv_l1000`
- Calibration manifest: `models/artifacts/android-qairt/calibration/tts/random_calibration_manifest.json`
- Calibration samples: `8` random samples per component
- Batch size: `1`
- Text length: `256`
- Latent length: `1000`
- Forced ONNX opset declaration: `17`
- Graph prepare options: `--htp_socs=sm8750 --optimization_level=3 --vtcm_override=0 --overwrite_cache_records`

The random calibration data is for pipeline validation only. Replace it with representative TTS calibration samples before making quality or accuracy claims.

`snpe-dlc-graph-prepare` in this QAIRT release does not expose a burst power-profile flag. Burst remains a runtime/benchmark setting, for example `snpe-net-run --perf_profile burst`.

## DLC outputs

| Component | Float DLC size | INT8 DLC size | SM8750 prepared DLC size |
| --- | ---: | ---: | ---: |
| Duration predictor | 4,043,188 bytes | 1,268,164 bytes | 2,907,247 bytes |
| Text encoder | 37,040,468 bytes | 9,640,476 bytes | 27,285,643 bytes |
| Vector estimator | 256,739,988 bytes | 65,095,332 bytes | 143,463,083 bytes |
| Vocoder | 101,590,412 bytes | 25,589,292 bytes | 55,339,567 bytes |

## Output paths

- Float DLCs: `models/artifacts/android-qairt/tts/float/`
- INT8 DLCs: `models/artifacts/android-qairt/tts/int8/`
- SM8750 prepared DLCs: `models/artifacts/android-qairt/tts/prepared/`
- Conversion log: `models/artifacts/android-qairt/logs/convert_tts_int8_random_calibration_graph_prepare_20260528.log`
- Calibration generation log: `models/artifacts/android-qairt/logs/generate_random_tts_calibration_20260528.log`

## Important findings

- The initial converter failures were caused by running QAIRT with the wrong WSL Python package stack. A minimal rank-3 `Transpose` ONNX failed under the user-site Python packages but converted successfully inside the QAIRT venv with tested dependency versions.
- SNPE converted ONNX `INT64` token IDs into DLC `Int_32` inputs, so calibration raw files for `text_ids` must be written as `int32`.

## Remaining work

- Replace random calibration with representative TTS samples.
- Run target-device validation and benchmarks with burst runtime profile.
