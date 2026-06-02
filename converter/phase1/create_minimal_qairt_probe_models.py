#!/usr/bin/env python3
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def make_rank3_transpose(path):
    x = helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 256, 64])
    y = helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, 64, 256])
    node = helper.make_node("Transpose", ["x"], ["y"], name="rank3_transpose", perm=[0, 2, 1])
    graph = helper.make_graph([node], "rank3_transpose_probe", [x], [y])
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
    onnx.save(model, path)


def make_gather_transpose(path):
    ids = helper.make_tensor_value_info("text_ids", TensorProto.INT64, [1, 256])
    y = helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, 64, 256])
    weight = numpy_helper.from_array(np.zeros((8322, 64), dtype=np.float32), "weight")
    gather = helper.make_node("Gather", ["weight", "text_ids"], ["gather_out"], name="gather")
    transpose = helper.make_node("Transpose", ["gather_out"], ["y"], name="gather_transpose", perm=[0, 2, 1])
    graph = helper.make_graph([gather, transpose], "gather_transpose_probe", [ids], [y], [weight])
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
    onnx.save(model, path)


def make_rank3_transpose_via_rank4(path):
    x = helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 256, 64])
    y = helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, 64, 256])
    shape_a = numpy_helper.from_array(np.array([1, 1, 256, 64], dtype=np.int64), "shape_a")
    shape_b = numpy_helper.from_array(np.array([1, 64, 256], dtype=np.int64), "shape_b")
    reshape_a = helper.make_node("Reshape", ["x", "shape_a"], ["x4"], name="reshape_to_rank4")
    transpose = helper.make_node("Transpose", ["x4"], ["y4"], name="rank4_transpose", perm=[0, 1, 3, 2])
    reshape_b = helper.make_node("Reshape", ["y4", "shape_b"], ["y"], name="reshape_to_rank3")
    graph = helper.make_graph(
        [reshape_a, transpose, reshape_b],
        "rank3_transpose_via_rank4_probe",
        [x],
        [y],
        [shape_a, shape_b],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
    onnx.save(model, path)


def make_rank3_transpose_via_unsqueeze(path):
    x = helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 256, 64])
    y = helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, 64, 256])
    axes = numpy_helper.from_array(np.array([1], dtype=np.int64), "axes")
    unsqueeze = helper.make_node("Unsqueeze", ["x", "axes"], ["x4"], name="unsqueeze_to_rank4")
    transpose = helper.make_node("Transpose", ["x4"], ["y4"], name="rank4_transpose", perm=[0, 1, 3, 2])
    squeeze = helper.make_node("Squeeze", ["y4", "axes"], ["y"], name="squeeze_to_rank3")
    graph = helper.make_graph(
        [unsqueeze, transpose, squeeze],
        "rank3_transpose_via_unsqueeze_probe",
        [x],
        [y],
        [axes],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
    onnx.save(model, path)


def make_rank4_transpose(path):
    x = helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 1, 256, 64])
    y = helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, 1, 64, 256])
    transpose = helper.make_node("Transpose", ["x"], ["y"], name="rank4_transpose", perm=[0, 1, 3, 2])
    graph = helper.make_graph([transpose], "rank4_transpose_probe", [x], [y])
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
    onnx.save(model, path)


def main():
    if len(sys.argv) != 2:
        print("Usage: create_minimal_qairt_probe_models.py OUTPUT_DIR", file=sys.stderr)
        return 2

    output_dir = Path(sys.argv[1])
    output_dir.mkdir(parents=True, exist_ok=True)
    make_rank3_transpose(output_dir / "rank3_transpose.onnx")
    make_gather_transpose(output_dir / "gather_transpose.onnx")
    make_rank3_transpose_via_rank4(output_dir / "rank3_transpose_via_rank4.onnx")
    make_rank3_transpose_via_unsqueeze(output_dir / "rank3_transpose_via_unsqueeze.onnx")
    make_rank4_transpose(output_dir / "rank4_transpose.onnx")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
