#!/usr/bin/env python3
import argparse
import json
from pathlib import Path


REQUIRED_FILES = (
    "genie_config.json",
    "text-generator.json",
    "tokenizer.json",
    "tokenizer_config.json",
    "htp_backend_ext_config.json",
    "metadata.json",
    "tool-versions.yaml",
    "sample_prompt.txt",
    "genie-app-script.txt",
)


def main():
    parser = argparse.ArgumentParser(description="Validate the Qwen3 Genie bundle structure.")
    parser.add_argument(
        "--bundle-dir",
        default=(
            "models/artifacts/android-qairt/llm/qwen3-4b-instruct-2507/"
            "extracted/qwen3_4b_instruct_2507-genie-w4a16-qualcomm_snapdragon_8_elite"
        ),
    )
    parser.add_argument(
        "--output",
        default="models/artifacts/android-qairt/llm/qwen3-4b-instruct-2507/qwen3_genie_bundle_validation.json",
    )
    args = parser.parse_args()

    bundle_dir = Path(args.bundle_dir)
    missing = [name for name in REQUIRED_FILES if not (bundle_dir / name).is_file()]
    bin_parts = sorted(bundle_dir.glob("*_part_*_of_*.bin"))
    config_path = bundle_dir / "genie_config.json"
    context_size = None
    ctx_bins = []
    if config_path.is_file():
        config = json.loads(config_path.read_text(encoding="utf-8"))
        dialog = config.get("dialog", {})
        context_size = dialog.get("context", {}).get("size")
        ctx_bins = (
            dialog.get("engine", {})
            .get("model", {})
            .get("binary", {})
            .get("ctx-bins", [])
        )

    validation = {
        "bundle_dir": str(bundle_dir),
        "valid": not missing and len(bin_parts) == 4 and len(ctx_bins) == 4,
        "missing_required_files": missing,
        "bin_parts": [path.name for path in bin_parts],
        "bin_part_count": len(bin_parts),
        "ctx_bins": ctx_bins,
        "context_size": context_size,
        "required_files": list(REQUIRED_FILES),
    }
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(validation, indent=2) + "\n", encoding="utf-8")
    if not validation["valid"]:
        raise SystemExit(f"Qwen3 Genie bundle validation failed: {output_path}")
    print(f"Wrote Qwen3 Genie bundle validation: {output_path.resolve()}")


if __name__ == "__main__":
    raise SystemExit(main())

