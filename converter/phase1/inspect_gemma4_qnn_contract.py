#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

import onnx
from onnx import TensorProto


def dim_to_value(dim):
    if dim.HasField("dim_value"):
        return dim.dim_value
    if dim.HasField("dim_param"):
        return dim.dim_param
    return None


def value_info_to_dict(value_info):
    tensor_type = value_info.type.tensor_type
    return {
        "name": value_info.name,
        "dtype": TensorProto.DataType.Name(tensor_type.elem_type),
        "shape": [dim_to_value(dim) for dim in tensor_type.shape.dim],
    }


def load_onnx_signature(path):
    model = onnx.load(str(path), load_external_data=False)
    graph = model.graph
    return {
        "path": str(path),
        "graph_name": graph.name,
        "ir_version": model.ir_version,
        "opset_imports": {
            (opset.domain or "ai.onnx"): opset.version for opset in model.opset_import
        },
        "inputs": [value_info_to_dict(value_info) for value_info in graph.input],
        "outputs": [value_info_to_dict(value_info) for value_info in graph.output],
        "initializers": len(graph.initializer),
    }


def names(items):
    return [item["name"] for item in items]


def contains_any(name, fragments):
    return any(fragment in name for fragment in fragments)


def categorize_decoder_io(decoder_signature):
    input_names = names(decoder_signature["inputs"])
    output_names = names(decoder_signature["outputs"])
    known_inputs = set()

    categories = {
        "inputs_embeds": [name for name in input_names if "inputs_embeds" in name],
        "per_layer_inputs": [name for name in input_names if "per_layer_inputs" in name],
        "position_ids": [name for name in input_names if "position_ids" in name],
        "attention_mask": [name for name in input_names if "attention_mask" in name],
        "num_logits_to_keep": [name for name in input_names if "num_logits_to_keep" in name],
        "past_key_values": [
            name
            for name in input_names
            if contains_any(name, ("past_key_values", "past.", "past_"))
        ],
    }
    for grouped in categories.values():
        known_inputs.update(grouped)
    categories["other"] = [name for name in input_names if name not in known_inputs]

    output_categories = {
        "logits": [name for name in output_names if "logits" in name],
        "present_key_values": [
            name
            for name in output_names
            if contains_any(name, ("present", "past_key_values", "key_values"))
        ],
        "other": [],
    }
    known_outputs = set(output_categories["logits"] + output_categories["present_key_values"])
    output_categories["other"] = [name for name in output_names if name not in known_outputs]

    return {
        "input_categories": categories,
        "output_categories": output_categories,
        "android_runtime_responsibilities": [
            "Apply the tokenizer and chat template outside QNN.",
            "Run embed_tokens for token ids and pass its output to decoder inputs_embeds.",
            "Provide per_layer_inputs, position_ids, attention_mask, and KV-cache tensors explicitly.",
            "Run sampling and stopping criteria outside QNN.",
        ],
    }


def validate_contract(manifest):
    decoder_contract = manifest["decoder_contract"]
    input_categories = decoder_contract["input_categories"]
    output_categories = decoder_contract["output_categories"]
    required = ("inputs_embeds", "position_ids", "attention_mask")
    missing = [name for name in required if not input_categories[name]]
    if not input_categories["past_key_values"]:
        missing.append("past_key_values")
    if not output_categories["logits"]:
        missing.append("logits")
    if missing:
        raise SystemExit(f"Gemma4 direct-QNN contract is missing expected IO: {', '.join(missing)}")


def main():
    parser = argparse.ArgumentParser(
        description="Inspect ONNX-community Gemma4 artifacts for direct QNN runtime packaging."
    )
    parser.add_argument(
        "--source-dir",
        default="models/original/llm/onnx-community__gemma-4-E2B-it-ONNX",
    )
    parser.add_argument(
        "--output",
        default="models/artifacts/android-qairt/llm/gemma4-direct-qnn/gemma4_qnn_contract.json",
    )
    parser.add_argument("--no-strict", action="store_true")
    args = parser.parse_args()

    source_dir = Path(args.source_dir)
    embed_path = source_dir / "onnx" / "embed_tokens_fp16.onnx"
    decoder_path = source_dir / "onnx" / "decoder_model_merged_fp16.onnx"
    missing = [path for path in (embed_path, decoder_path) if not path.is_file()]
    if missing:
        raise SystemExit(
            "Missing ONNX-community Gemma4 files: "
            + ", ".join(str(path) for path in missing)
            + ". Run converter/phase1/fetch_gemma4_onnx_community.sh first."
        )

    embed_signature = load_onnx_signature(embed_path)
    decoder_signature = load_onnx_signature(decoder_path)
    manifest = {
        "source_model_id": "onnx-community/gemma-4-E2B-it-ONNX",
        "source_dir": str(source_dir),
        "packaging": "direct_qnn_context_binaries",
        "runtime_owner": "android_app",
        "components": {
            "embed_tokens": embed_signature,
            "decoder_model_merged": decoder_signature,
        },
        "decoder_contract": categorize_decoder_io(decoder_signature),
    }

    if not args.no_strict:
        validate_contract(manifest)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote Gemma4 direct-QNN contract: {output_path.resolve()}")


if __name__ == "__main__":
    raise SystemExit(main())

