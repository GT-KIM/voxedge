#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ -z "${VIRTUAL_ENV:-}" && -f "$ROOT_DIR/models/artifacts/android-qairt/activate_gemma4_onnx_venv.sh" ]]; then
  # Reuse the Gemma ONNX environment when it has already been prepared.
  source "$ROOT_DIR/models/artifacts/android-qairt/activate_gemma4_onnx_venv.sh"
fi

HF_MODEL_ID="${GEMMA4_ONNX_MODEL_ID:-onnx-community/gemma-4-E2B-it-ONNX}"
OUT_DIR="${GEMMA4_ONNX_DIR:-$ROOT_DIR/models/original/llm/onnx-community__gemma-4-E2B-it-ONNX}"

mkdir -p "$OUT_DIR"

python3 - "$HF_MODEL_ID" "$OUT_DIR" <<'PY'
from pathlib import Path
import sys

try:
    from huggingface_hub import snapshot_download
except ImportError as exc:
    raise SystemExit(
        "Missing huggingface_hub. Run converter/phase1/setup_gemma4_onnx_wsl_venv.sh first."
    ) from exc

repo_id = sys.argv[1]
local_dir = Path(sys.argv[2])
allow_patterns = [
    "config.json",
    "generation_config.json",
    "tokenizer.json",
    "tokenizer_config.json",
    "chat_template.jinja",
    "processor_config.json",
    "preprocessor_config.json",
    "special_tokens_map.json",
    "onnx/embed_tokens_fp16.onnx",
    "onnx/embed_tokens_fp16.onnx_data*",
    "onnx/decoder_model_merged_fp16.onnx",
    "onnx/decoder_model_merged_fp16.onnx_data*",
]

snapshot_download(
    repo_id=repo_id,
    local_dir=local_dir,
    local_dir_use_symlinks=False,
    allow_patterns=allow_patterns,
    resume_download=True,
)
print(f"Downloaded selected Gemma4 ONNX-community artifacts to: {local_dir.resolve()}")
PY
