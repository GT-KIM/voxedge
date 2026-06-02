#!/usr/bin/env python3
import json
import sys
from pathlib import Path

import onnx


def dim_to_value(dim):
    if dim.HasField("dim_value"):
        return dim.dim_value
    if dim.HasField("dim_param"):
        return dim.dim_param
    return None


def inspect_model(path):
    model = onnx.load(path)
    initializers = {initializer.name for initializer in model.graph.initializer}
    inputs = []

    for value_info in model.graph.input:
        if value_info.name in initializers:
            continue

        tensor_type = value_info.type.tensor_type
        shape = [dim_to_value(dim) for dim in tensor_type.shape.dim]
        inputs.append({"name": value_info.name, "shape": shape})

    opsets = [
        {"domain": opset.domain or "ai.onnx", "version": opset.version}
        for opset in model.opset_import
    ]

    return {"path": str(path), "opsets": opsets, "inputs": inputs}


def main():
    if len(sys.argv) < 2:
        print("Usage: inspect_onnx_inputs.py MODEL.onnx [MODEL.onnx ...]", file=sys.stderr)
        return 2

    reports = [inspect_model(Path(arg)) for arg in sys.argv[1:]]
    print(json.dumps(reports, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
