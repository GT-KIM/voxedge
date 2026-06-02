#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

import onnx
from onnx import shape_inference, version_converter


DEFAULT_DIMS = {
    "batch_size": int(os.environ.get("TTS_BATCH_SIZE", "1")),
    "text_length": int(os.environ.get("TTS_TEXT_LENGTH", "256")),
    "latent_length": int(os.environ.get("TTS_LATENT_LENGTH", "1000")),
}

FORCE_OPSET_WITHOUT_CONVERSION = os.environ.get("TTS_FORCE_OPSET_WITHOUT_CONVERSION", "0") == "1"


def set_static_dims(model, dim_values):
    for value_info in model.graph.input:
        tensor_type = value_info.type.tensor_type
        for dim in tensor_type.shape.dim:
            if dim.HasField("dim_param") and dim.dim_param in dim_values:
                value = dim_values[dim.dim_param]
                dim.ClearField("dim_param")
                dim.dim_value = value


def current_ai_onnx_opset(model):
    for opset in model.opset_import:
        if opset.domain in ("", "ai.onnx"):
            return opset.version
    return None


def force_ai_onnx_opset(model, target_opset):
    for opset in model.opset_import:
        if opset.domain in ("", "ai.onnx"):
            opset.version = target_opset
            return


def prepare_one(source, output_dir, target_opset):
    model = onnx.load(source)
    original_opset = current_ai_onnx_opset(model)

    set_static_dims(model, DEFAULT_DIMS)
    model = shape_inference.infer_shapes(model)

    converted = False
    conversion_error = None
    if target_opset and original_opset and original_opset > target_opset:
        try:
            model = version_converter.convert_version(model, target_opset)
            converted = True
        except Exception as exc:
            conversion_error = str(exc)
            if FORCE_OPSET_WITHOUT_CONVERSION:
                force_ai_onnx_opset(model, target_opset)

    output = output_dir / source.name
    onnx.save(model, output)

    return {
        "source": str(source),
        "output": str(output),
        "original_opset": original_opset,
        "target_opset": target_opset,
        "converted_to_target_opset": converted,
        "forced_opset_without_conversion": bool(
            FORCE_OPSET_WITHOUT_CONVERSION and target_opset and not converted
        ),
        "conversion_error": conversion_error,
        "static_dims": DEFAULT_DIMS,
    }


def main():
    if len(sys.argv) != 3:
        print(
            "Usage: prepare_tts_static_onnx.py SOURCE_ONNX_DIR OUTPUT_ONNX_DIR",
            file=sys.stderr,
        )
        return 2

    source_dir = Path(sys.argv[1])
    output_dir = Path(sys.argv[2])
    target_opset = int(os.environ.get("TTS_TARGET_OPSET", "17"))

    output_dir.mkdir(parents=True, exist_ok=True)
    reports = []
    for name in ["duration_predictor.onnx", "text_encoder.onnx", "vector_estimator.onnx", "vocoder.onnx"]:
        reports.append(prepare_one(source_dir / name, output_dir, target_opset))

    report_path = output_dir / "static_onnx_report.json"
    report_path.write_text(json.dumps(reports, indent=2), encoding="utf-8")
    print(json.dumps(reports, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
