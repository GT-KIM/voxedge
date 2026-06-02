#!/usr/bin/env python3
import json
import sys
from collections import Counter
from pathlib import Path

import numpy as np
import onnx
from onnx import numpy_helper


INTERESTING_OPS = {"Transpose", "Reshape", "PRelu", "Equal", "Pad", "Gather"}


def dim_to_value(dim):
    if dim.HasField("dim_value"):
        return dim.dim_value
    if dim.HasField("dim_param"):
        return dim.dim_param
    return None


def value_info_shapes(model):
    shapes = {}
    values = list(model.graph.input) + list(model.graph.output) + list(model.graph.value_info)
    for value_info in values:
        tensor_type = value_info.type.tensor_type
        if not tensor_type.HasField("shape"):
            continue
        shapes[value_info.name] = [dim_to_value(dim) for dim in tensor_type.shape.dim]
    return shapes


def attr_to_python(attr):
    value = onnx.helper.get_attribute_value(attr)
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    if isinstance(value, np.ndarray):
        return value.tolist()
    if isinstance(value, (list, tuple)):
        return [item.decode("utf-8", errors="replace") if isinstance(item, bytes) else item for item in value]
    return value


def initializer_summary(initializer):
    array = numpy_helper.to_array(initializer)
    summary = {
        "shape": list(array.shape),
        "dtype": str(array.dtype),
    }
    if array.size <= 32:
        summary["values"] = array.tolist()
    else:
        summary["min"] = float(array.min()) if np.issubdtype(array.dtype, np.number) else None
        summary["max"] = float(array.max()) if np.issubdtype(array.dtype, np.number) else None
    return summary


def inspect(path):
    model = onnx.load(path)
    shapes = value_info_shapes(model)
    initializers = {initializer.name: initializer for initializer in model.graph.initializer}
    nodes = []

    for index, node in enumerate(model.graph.node):
        if node.op_type not in INTERESTING_OPS:
            continue

        inputs = []
        for name in node.input:
            item = {"name": name, "shape": shapes.get(name)}
            if name in initializers:
                item["initializer"] = initializer_summary(initializers[name])
            inputs.append(item)

        outputs = [{"name": name, "shape": shapes.get(name)} for name in node.output]
        nodes.append(
            {
                "index": index,
                "name": node.name,
                "op_type": node.op_type,
                "attributes": {attr.name: attr_to_python(attr) for attr in node.attribute},
                "inputs": inputs,
                "outputs": outputs,
            }
        )

    return {
        "path": str(path),
        "op_counts": Counter(node.op_type for node in model.graph.node),
        "interesting_nodes": nodes,
    }


def main():
    if len(sys.argv) < 2:
        print("Usage: diagnose_tts_graph.py MODEL.onnx [MODEL.onnx ...]", file=sys.stderr)
        return 2

    report = [inspect(Path(arg)) for arg in sys.argv[1:]]
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
