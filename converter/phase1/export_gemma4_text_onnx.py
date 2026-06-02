#!/usr/bin/env python3
import argparse
from pathlib import Path

import torch


class TextOnlyNoCache(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_ids, attention_mask):
        outputs = self.model(
            input_ids=input_ids,
            attention_mask=attention_mask,
            use_cache=False,
            return_dict=True,
        )
        return outputs.logits


def resolve_dtype(name):
    if name == "auto":
        return "auto"
    if name == "float32":
        return torch.float32
    if name == "float16":
        return torch.float16
    if name == "bfloat16":
        return torch.bfloat16
    raise ValueError(f"Unsupported dtype: {name}")


def build_static_text_inputs(tokenizer, prompt, sequence_length, device):
    encoded = tokenizer(prompt, return_tensors="pt", add_special_tokens=True)
    token_ids = encoded["input_ids"][0]
    if token_ids.numel() > sequence_length:
        token_ids = token_ids[:sequence_length]

    pad_id = tokenizer.pad_token_id
    if pad_id is None:
        pad_id = tokenizer.eos_token_id
    if pad_id is None:
        pad_id = 0

    input_ids = torch.full((1, sequence_length), int(pad_id), dtype=torch.long)
    attention_mask = torch.zeros((1, sequence_length), dtype=torch.long)
    input_ids[0, : token_ids.numel()] = token_ids
    attention_mask[0, : token_ids.numel()] = 1
    return input_ids.to(device), attention_mask.to(device)


def export_onnx(model, input_ids, attention_mask, output_path, opset):
    wrapped = TextOnlyNoCache(model).eval()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    kwargs = {
        "input_names": ["input_ids", "attention_mask"],
        "output_names": ["logits"],
        "opset_version": opset,
        "do_constant_folding": True,
    }

    try:
        torch.onnx.export(
            wrapped,
            (input_ids, attention_mask),
            str(output_path),
            external_data=True,
            **kwargs,
        )
    except TypeError:
        torch.onnx.export(
            wrapped,
            (input_ids, attention_mask),
            str(output_path),
            **kwargs,
        )


def main():
    parser = argparse.ArgumentParser(
        description="Export Gemma 4 E2B text-only no-cache ONNX for QAIRT smoke validation."
    )
    parser.add_argument(
        "--model-dir",
        default="models/original/llm/google__gemma-4-E2B-it",
    )
    parser.add_argument(
        "--output",
        default="models/artifacts/android-qairt/llm/gemma4_text_prefill_no_cache.onnx",
    )
    parser.add_argument("--sequence-length", type=int, default=128)
    parser.add_argument("--opset", type=int, default=17)
    parser.add_argument("--dtype", choices=("auto", "float32", "float16", "bfloat16"), default="auto")
    parser.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    parser.add_argument("--prompt", default="Hello.")
    args = parser.parse_args()

    from transformers import AutoModelForCausalLM, AutoTokenizer

    model_dir = Path(args.model_dir)
    output_path = Path(args.output)
    if args.device == "auto":
        device = "cuda" if torch.cuda.is_available() else "cpu"
    else:
        device = args.device
    if device == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA was requested but torch.cuda.is_available() is false")

    dtype = resolve_dtype(args.dtype)
    tokenizer = AutoTokenizer.from_pretrained(model_dir)
    model = AutoModelForCausalLM.from_pretrained(
        model_dir,
        dtype=dtype,
        low_cpu_mem_usage=True,
    )
    model.eval().to(device)

    input_ids, attention_mask = build_static_text_inputs(
        tokenizer=tokenizer,
        prompt=args.prompt,
        sequence_length=args.sequence_length,
        device=device,
    )

    export_onnx(
        model=model,
        input_ids=input_ids,
        attention_mask=attention_mask,
        output_path=output_path,
        opset=args.opset,
    )
    print(f"Wrote text-only no-cache ONNX: {output_path.resolve()}")


if __name__ == "__main__":
    raise SystemExit(main())
