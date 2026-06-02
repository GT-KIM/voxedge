#!/usr/bin/env python3
import argparse
import json
import os
import shutil
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


COMPONENTS = (
    "embed_tokens_fp16.onnx",
    "decoder_model_merged_fp16.onnx",
)


def attr_value(node, name, default=None):
    for attr in node.attribute:
        if attr.name == name:
            return helper.get_attribute_value(attr)
    return default


def tensor_external_locations(model):
    locations = set()
    for tensor in model.graph.initializer:
        for entry in tensor.external_data:
            if entry.key == "location":
                locations.add(entry.value)
    return locations


def link_or_copy(src, dst):
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists():
        return "exists"
    try:
        os.link(src, dst)
        return "hardlink"
    except OSError:
        shutil.copy2(src, dst)
        return "copy"


def set_value_info_dtype(value_infos, name, dtype):
    for value_info in value_infos:
        if value_info.name == name:
            value_info.type.tensor_type.elem_type = dtype


def replace_all_inputs(model, old_name, new_name):
    for node in model.graph.node:
        for index, input_name in enumerate(node.input):
            if input_name == old_name:
                node.input[index] = new_name


def producer_for_output(model, output_name):
    for node in model.graph.node:
        if output_name in node.output:
            return node
    return None


def remove_output_cast(model, output_name):
    node_index_to_remove = None
    for node_index, node in enumerate(model.graph.node):
        if node.op_type != "Cast" or list(node.output) != [output_name]:
            continue
        producer = producer_for_output(model, node.input[0])
        if producer is None:
            return False
        for index, producer_output in enumerate(producer.output):
            if producer_output == node.input[0]:
                producer.output[index] = output_name
        set_value_info_dtype(model.graph.output, output_name, TensorProto.FLOAT16)
        node_index_to_remove = node_index
        break
    if node_index_to_remove is None:
        return False
    kept_nodes = [
        node for node_index, node in enumerate(model.graph.node) if node_index != node_index_to_remove
    ]
    del model.graph.node[:]
    model.graph.node.extend(kept_nodes)
    return True


def remove_input_cast(model, input_name):
    for value_info in model.graph.input:
        if value_info.name == input_name:
            value_info.type.tensor_type.elem_type = TensorProto.FLOAT16

    removed = 0
    nodes_to_keep = []
    for node in model.graph.node:
        if node.op_type == "Cast" and list(node.input) == [input_name]:
            replace_all_inputs(model, node.output[0], input_name)
            removed += 1
            continue
        nodes_to_keep.append(node)

    if removed:
        del model.graph.node[:]
        model.graph.node.extend(nodes_to_keep)
    return removed


def prune_unreachable_nodes(model):
    graph_outputs = {value_info.name for value_info in model.graph.output}
    producer_by_output = {}
    for node in model.graph.node:
        for output in node.output:
            producer_by_output[output] = node

    required_values = set(graph_outputs)
    required_nodes = set()
    stack = list(graph_outputs)
    while stack:
        value_name = stack.pop()
        producer = producer_by_output.get(value_name)
        if producer is None:
            continue
        node_id = id(producer)
        if node_id in required_nodes:
            continue
        required_nodes.add(node_id)
        for input_name in producer.input:
            if input_name and input_name not in required_values:
                required_values.add(input_name)
                stack.append(input_name)

    original_count = len(model.graph.node)
    kept_nodes = [node for node in model.graph.node if id(node) in required_nodes]
    del model.graph.node[:]
    model.graph.node.extend(kept_nodes)
    return original_count - len(kept_nodes)


def rewrite_embed_tokens_for_text_only(model):
    rewrites = {
        "bypassed_multimodal_where": False,
        "removed_output_casts": 0,
        "pruned_dead_nodes": 0,
    }

    for node in model.graph.node:
        if node.name == "/model/embed_tokens_per_layer/Gather" and len(node.input) >= 2:
            node.input[1] = "input_ids"
            rewrites["bypassed_multimodal_where"] = True

    for output_name in ("inputs_embeds", "per_layer_inputs"):
        if remove_output_cast(model, output_name):
            rewrites["removed_output_casts"] += 1

    rewrites["pruned_dead_nodes"] = prune_unreachable_nodes(model)
    return rewrites


def rewrite_decoder_inputs_for_fp16(model):
    rewrites = {
        "removed_input_casts": 0,
        "pruned_dead_nodes": 0,
    }
    for input_name in ("inputs_embeds", "per_layer_inputs"):
        rewrites["removed_input_casts"] += remove_input_cast(model, input_name)
    rewrites["pruned_dead_nodes"] = prune_unreachable_nodes(model)
    return rewrites


def replace_simplified_layer_norm(model):
    new_nodes = []
    new_initializers = []
    replacement_count = 0

    for node_index, node in enumerate(model.graph.node):
        if node.op_type != "SimplifiedLayerNormalization":
            new_nodes.append(node)
            continue

        if len(node.input) < 2 or len(node.output) != 1:
            raise ValueError(
                f"Unsupported SimplifiedLayerNormalization signature at {node.name}: "
                f"{len(node.input)} inputs, {len(node.output)} outputs"
            )

        axis = int(attr_value(node, "axis", -1))
        epsilon = float(attr_value(node, "epsilon", 1e-6))
        base = node.name or f"SimplifiedLayerNormalization_{node_index}"
        base = base.strip("/").replace("/", "_")

        x_name = node.input[0]
        scale_name = node.input[1]
        output_name = node.output[0]
        axes_name = f"{base}_qnn_axes"
        eps_name = f"{base}_qnn_epsilon"
        square_name = f"{base}_qnn_square"
        mean_name = f"{base}_qnn_mean"
        variance_eps_name = f"{base}_qnn_variance_eps"
        denom_name = f"{base}_qnn_denom"
        normalized_name = f"{base}_qnn_normalized"

        new_initializers.append(
            numpy_helper.from_array(np.asarray([axis], dtype=np.int64), name=axes_name)
        )
        new_initializers.append(
            numpy_helper.from_array(np.asarray(epsilon, dtype=np.float16), name=eps_name)
        )
        new_nodes.extend(
            [
                helper.make_node(
                    "Mul",
                    [x_name, x_name],
                    [square_name],
                    name=f"{base}/qnn_square",
                ),
                helper.make_node(
                    "ReduceMean",
                    [square_name, axes_name],
                    [mean_name],
                    name=f"{base}/qnn_reduce_mean",
                    keepdims=1,
                ),
                helper.make_node(
                    "Add",
                    [mean_name, eps_name],
                    [variance_eps_name],
                    name=f"{base}/qnn_add_epsilon",
                ),
                helper.make_node(
                    "Sqrt",
                    [variance_eps_name],
                    [denom_name],
                    name=f"{base}/qnn_sqrt",
                ),
                helper.make_node(
                    "Div",
                    [x_name, denom_name],
                    [normalized_name],
                    name=f"{base}/qnn_div",
                ),
                helper.make_node(
                    "Mul",
                    [normalized_name, scale_name],
                    [output_name],
                    name=f"{base}/qnn_scale",
                ),
            ]
        )
        replacement_count += 1

    del model.graph.node[:]
    model.graph.node.extend(new_nodes)
    model.graph.initializer.extend(new_initializers)
    return replacement_count


def rewrite_component(source_path, output_path):
    model = onnx.load(str(source_path), load_external_data=False)
    external_locations = tensor_external_locations(model)
    replacement_count = replace_simplified_layer_norm(model)
    component_rewrites = {}
    if source_path.name == "embed_tokens_fp16.onnx":
        component_rewrites["text_only_embed_tokens"] = rewrite_embed_tokens_for_text_only(model)
    elif source_path.name == "decoder_model_merged_fp16.onnx":
        component_rewrites["fp16_decoder_inputs"] = rewrite_decoder_inputs_for_fp16(model)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    onnx.save_model(model, str(output_path), save_as_external_data=False)

    external_results = {}
    for location in sorted(external_locations):
        source_external = source_path.parent / location
        target_external = output_path.parent / location
        if not source_external.is_file():
            raise FileNotFoundError(f"Missing external data shard: {source_external}")
        external_results[location] = link_or_copy(source_external, target_external)

    return {
        "source": str(source_path),
        "output": str(output_path),
        "simplified_layer_norm_replacements": replacement_count,
        "component_rewrites": component_rewrites,
        "external_data_locations": sorted(external_locations),
        "external_data_materialization": external_results,
    }


def main():
    parser = argparse.ArgumentParser(
        description="Rewrite ONNX-community Gemma4 graphs into a more QAIRT/QNN-compatible form."
    )
    parser.add_argument(
        "--source-dir",
        default="models/original/llm/onnx-community__gemma-4-E2B-it-ONNX",
    )
    parser.add_argument(
        "--output-dir",
        default="models/artifacts/android-qairt/llm/gemma4-direct-qnn/rewritten_onnx",
    )
    parser.add_argument(
        "--report",
        default="models/artifacts/android-qairt/llm/gemma4-direct-qnn/rewrite_report.json",
    )
    args = parser.parse_args()

    source_dir = Path(args.source_dir)
    output_dir = Path(args.output_dir)
    report_path = Path(args.report)

    results = []
    for component in COMPONENTS:
        source_path = source_dir / "onnx" / component
        output_path = output_dir / "onnx" / component
        if not source_path.is_file():
            raise SystemExit(
                f"Missing Gemma4 ONNX source: {source_path}. "
                "Run converter/phase1/fetch_gemma4_onnx_community.sh first."
            )
        results.append(rewrite_component(source_path, output_path))

    report = {
        "source_dir": str(source_dir),
        "output_dir": str(output_dir),
        "qnn_rewrite_scope": [
            "materialize_external_data_shards",
            "text_only_embed_tokens_without_multimodal_where",
            "fp16_embed_decoder_handoff_without_boundary_casts",
            "SimplifiedLayerNormalization_to_primitive_rms_norm",
        ],
        "components": results,
        "remaining_known_risks": [
            "Where and Cast nodes may still require additional QAIRT-specific rewrites.",
            "com.microsoft RotaryEmbedding and GroupQueryAttention support must be proven by dry run.",
        ],
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote Gemma4 QNN rewrite report: {report_path.resolve()}")


if __name__ == "__main__":
    raise SystemExit(main())
