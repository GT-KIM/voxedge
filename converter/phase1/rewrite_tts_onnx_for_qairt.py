#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

import onnx
from onnx import shape_inference
from onnxsim import simplify


COMPONENTS = {
    "duration_predictor": {
        "file": "duration_predictor.onnx",
        "input_shapes": {
            "text_ids": ["batch_size", "text_length"],
            "style_dp": ["batch_size", 8, 16],
            "text_mask": ["batch_size", 1, "text_length"],
        },
    },
    "text_encoder": {
        "file": "text_encoder.onnx",
        "input_shapes": {
            "text_ids": ["batch_size", "text_length"],
            "style_ttl": ["batch_size", 50, 256],
            "text_mask": ["batch_size", 1, "text_length"],
        },
    },
    "vector_estimator": {
        "file": "vector_estimator.onnx",
        "input_shapes": {
            "noisy_latent": ["batch_size", 144, "latent_length"],
            "text_emb": ["batch_size", 256, "text_length"],
            "style_ttl": ["batch_size", 50, 256],
            "latent_mask": ["batch_size", 1, "latent_length"],
            "text_mask": ["batch_size", 1, "text_length"],
            "current_step": ["batch_size"],
            "total_step": ["batch_size"],
        },
    },
    "vocoder": {
        "file": "vocoder.onnx",
        "input_shapes": {
            "latent": ["batch_size", 144, "latent_length"],
        },
    },
}

DEFAULT_DIMS = {
    "batch_size": int(os.environ.get("TTS_BATCH_SIZE", "1")),
    "text_length": int(os.environ.get("TTS_TEXT_LENGTH", "256")),
    "latent_length": int(os.environ.get("TTS_LATENT_LENGTH", "1000")),
}

TARGET_OPSET = int(os.environ.get("TTS_QAIRT_TARGET_OPSET", "17"))
FORCE_TARGET_OPSET = os.environ.get("TTS_QAIRT_FORCE_OPSET", "1") == "1"


def materialize_shape(shape):
    return [DEFAULT_DIMS.get(dim, dim) for dim in shape]


def set_static_input_dims(model, input_shapes):
    for value_info in model.graph.input:
        if value_info.name not in input_shapes:
            continue

        dims = materialize_shape(input_shapes[value_info.name])
        tensor_type = value_info.type.tensor_type
        del tensor_type.shape.dim[:]
        for value in dims:
            dim = tensor_type.shape.dim.add()
            dim.dim_value = int(value)


def force_ai_onnx_opset(model, target_opset):
    for opset in model.opset_import:
        if opset.domain in ("", "ai.onnx"):
            previous = opset.version
            opset.version = target_opset
            return previous
    return None


def remove_value_info(model):
    del model.graph.value_info[:]


def rewrite_one(source_dir, output_dir, component_name, spec):
    source = source_dir / spec["file"]
    model = onnx.load(source)
    set_static_input_dims(model, spec["input_shapes"])
    model = shape_inference.infer_shapes(model)

    overwrite_input_shapes = {
        name: materialize_shape(shape) for name, shape in spec["input_shapes"].items()
    }
    simplified_model, check_ok = simplify(
        model,
        overwrite_input_shapes=overwrite_input_shapes,
        dynamic_input_shape=False,
        perform_optimization=True,
    )
    simplified_model = shape_inference.infer_shapes(simplified_model)

    previous_opset = None
    if FORCE_TARGET_OPSET:
        previous_opset = force_ai_onnx_opset(simplified_model, TARGET_OPSET)

    remove_value_info(simplified_model)
    output = output_dir / spec["file"]
    onnx.save(simplified_model, output)
    onnx.checker.check_model(output)

    return {
        "component": component_name,
        "source": str(source),
        "output": str(output),
        "onnxsim_check": bool(check_ok),
        "input_shapes": overwrite_input_shapes,
        "forced_opset": FORCE_TARGET_OPSET,
        "previous_opset": previous_opset,
        "target_opset": TARGET_OPSET if FORCE_TARGET_OPSET else previous_opset,
    }


def main():
    if len(sys.argv) != 3:
        print(
            "Usage: rewrite_tts_onnx_for_qairt.py SOURCE_ONNX_DIR OUTPUT_ONNX_DIR",
            file=sys.stderr,
        )
        return 2

    source_dir = Path(sys.argv[1])
    output_dir = Path(sys.argv[2])
    output_dir.mkdir(parents=True, exist_ok=True)

    report = [
        rewrite_one(source_dir, output_dir, name, spec)
        for name, spec in COMPONENTS.items()
    ]
    report_path = output_dir / "rewrite_report.json"
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
