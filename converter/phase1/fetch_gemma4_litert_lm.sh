#!/usr/bin/env bash
set -euo pipefail

# Fetch the official Gemma 4 E2B .litertlm bundle (LiteRT-LM runtime) from Hugging Face.
# Counterpart of fetch_qwen3_4b_instruct_2507_genie_bundle.sh for the second LlmEngine backend.
#
# The .litertlm file is SoC-agnostic (CPU/GPU backends; NPU via QNN where supported), so unlike the
# Genie bundle there is no per-chipset asset selection.
#
# Provision to the device (see MODELS.md):
#   adb push "$OUT_ROOT/gemma-4-E2B-it.litertlm" /data/local/tmp/gemma-4-E2B-it.litertlm
#   adb shell run-as com.conversationalai.agent mkdir -p files/llm_litert
#   adb shell run-as com.conversationalai.agent cp /data/local/tmp/gemma-4-E2B-it.litertlm files/llm_litert/

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPO_ID="${GEMMA4_LITERT_REPO:-litert-community/gemma-4-E2B-it-litert-lm}"
FILENAME="${GEMMA4_LITERT_FILENAME:-gemma-4-E2B-it.litertlm}"
OUT_ROOT="${GEMMA4_LITERT_OUT_DIR:-$ROOT_DIR/models/artifacts/android-litert/llm/gemma4-e2b}"
MANIFEST="$OUT_ROOT/gemma4_litert_lm_manifest.json"

mkdir -p "$OUT_ROOT"

python3 - "$REPO_ID" "$FILENAME" "$OUT_ROOT" "$MANIFEST" <<'PY'
import json
import shutil
import sys
from pathlib import Path

repo_id, filename, out_root, manifest_path = sys.argv[1:5]
out_dir = Path(out_root)
target = out_dir / filename

from huggingface_hub import hf_hub_download

local = hf_hub_download(repo_id=repo_id, filename=filename)
if not target.exists() or target.stat().st_size != Path(local).stat().st_size:
    shutil.copyfile(local, target)

manifest = {
    "model_id": repo_id,
    "runtime": "LITERT_LM",
    "quantization": "gemma4-mobile mixed 2/4/8-bit",
    "filename": filename,
    "local_path": str(target.resolve()),
    "size_bytes": target.stat().st_size,
    "device_dest": "files/llm_litert/" + filename,
    "license_note": "Gemma Terms of Use - review before redistribution/commercial use.",
}
Path(manifest_path).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
print(f"Gemma 4 E2B litertlm ready: {target.resolve()}")
PY
