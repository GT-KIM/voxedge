#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto


COMPONENTS = (
    "duration_predictor",
    "text_encoder",
    "vector_estimator",
    "vocoder",
)

ONNX_TO_NUMPY = {
    TensorProto.FLOAT: np.float32,
    TensorProto.FLOAT16: np.float16,
    TensorProto.DOUBLE: np.float64,
    TensorProto.INT64: np.int64,
    TensorProto.INT32: np.int32,
    TensorProto.INT16: np.int16,
    TensorProto.INT8: np.int8,
    TensorProto.UINT64: np.uint64,
    TensorProto.UINT32: np.uint32,
    TensorProto.UINT16: np.uint16,
    TensorProto.UINT8: np.uint8,
    TensorProto.BOOL: np.bool_,
}


def dim_to_int(dim):
    if dim.HasField("dim_value"):
        return int(dim.dim_value)
    raise ValueError(f"Calibration graph still has a dynamic dimension: {dim}")


def model_inputs(path):
    model = onnx.load(path)
    initializers = {initializer.name for initializer in model.graph.initializer}
    inputs = []

    for value_info in model.graph.input:
        if value_info.name in initializers:
            continue

        tensor_type = value_info.type.tensor_type
        elem_type = tensor_type.elem_type
        if elem_type not in ONNX_TO_NUMPY:
            dtype_name = TensorProto.DataType.Name(elem_type)
            raise ValueError(f"Unsupported ONNX input dtype {dtype_name}: {value_info.name}")

        shape = [dim_to_int(dim) for dim in tensor_type.shape.dim]
        numpy_dtype = np.dtype(ONNX_TO_NUMPY[elem_type])
        if elem_type == TensorProto.INT64:
            # SNPE DLC conversion stores ONNX INT64 token ids as Int_32 inputs.
            numpy_dtype = np.dtype(np.int32)

        inputs.append(
            {
                "name": value_info.name,
                "shape": shape,
                "onnx_dtype": TensorProto.DataType.Name(elem_type),
                "numpy_dtype": numpy_dtype,
            }
        )

    return inputs


def random_input(name, shape, dtype, rng):
    if np.issubdtype(dtype, np.integer):
        values = rng.integers(1, 1024, size=shape, dtype=dtype)
    elif np.issubdtype(dtype, np.bool_):
        values = np.ones(shape, dtype=dtype)
    elif "mask" in name:
        values = np.ones(shape, dtype=dtype)
    elif name == "current_step":
        values = np.array([rng.integers(0, 32)], dtype=dtype)
    elif name == "total_step":
        values = np.array([32], dtype=dtype)
    else:
        values = rng.normal(loc=0.0, scale=0.5, size=shape).astype(dtype)

    return np.ascontiguousarray(values)


def write_component_calibration(component, inputs, output_root, samples, rng):
    component_root = output_root / component
    component_root.mkdir(parents=True, exist_ok=True)
    input_list_path = output_root / f"{component}_input_list.txt"
    list_lines = []
    sample_reports = []

    for sample_index in range(samples):
        sample_root = component_root / f"sample_{sample_index:03d}"
        sample_root.mkdir(parents=True, exist_ok=True)
        entries = []

        for input_info in inputs:
            name = input_info["name"]
            dtype = input_info["numpy_dtype"]
            shape = input_info["shape"]
            raw_path = sample_root / f"{name}.raw"
            data = random_input(name, shape, dtype, rng)
            data.tofile(raw_path)
            entries.append(f"{name}:={raw_path.resolve()}")

        list_lines.append(" ".join(entries))
        sample_reports.append(
            {
                "index": sample_index,
                "files": [entry.split(":=", 1)[1] for entry in entries],
            }
        )

    input_list_path.write_text("\n".join(list_lines) + "\n", encoding="utf-8")
    return {
        "component": component,
        "input_list": str(input_list_path.resolve()),
        "inputs": [
            {
                "name": input_info["name"],
                "shape": input_info["shape"],
                "onnx_dtype": input_info["onnx_dtype"],
                "raw_dtype": str(input_info["numpy_dtype"]),
            }
            for input_info in inputs
        ],
        "samples": sample_reports,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--onnx-dir",
        default="models/artifacts/android-qairt/tts/rewritten_onnx_venv_l1000",
    )
    parser.add_argument(
        "--output-root",
        default="models/artifacts/android-qairt/calibration/tts",
    )
    parser.add_argument("--samples", type=int, default=8)
    parser.add_argument("--seed", type=int, default=20260528)
    args = parser.parse_args()

    if args.samples < 1:
        raise ValueError("--samples must be at least 1")

    onnx_dir = Path(args.onnx_dir)
    output_root = Path(args.output_root)
    output_root.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(args.seed)

    reports = []
    for component in COMPONENTS:
        onnx_path = onnx_dir / f"{component}.onnx"
        if not onnx_path.is_file():
            raise FileNotFoundError(f"Missing rewritten ONNX graph: {onnx_path}")
        reports.append(
            write_component_calibration(
                component=component,
                inputs=model_inputs(onnx_path),
                output_root=output_root,
                samples=args.samples,
                rng=rng,
            )
        )

    report = {
        "kind": "random_tts_calibration",
        "purpose": "pipeline_smoke_test_only",
        "seed": args.seed,
        "samples_per_component": args.samples,
        "onnx_dir": str(onnx_dir.resolve()),
        "output_root": str(output_root.resolve()),
        "components": reports,
    }
    report_path = output_root / "random_calibration_manifest.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
