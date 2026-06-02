#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODEL_ID="qualcomm/Qwen3-4B-Instruct-2507"
MODEL_SLUG="qwen3_4b_instruct_2507"
DEVICE_PROFILE="${QWEN3_DEVICE_PROFILE:-snapdragon_8_elite}"
FETCH_MODE="${QWEN3_FETCH_MODE:-download}"
OUT_ROOT="${QWEN3_GENIE_OUT_DIR:-$ROOT_DIR/models/artifacts/android-qairt/llm/qwen3-4b-instruct-2507}"
DOWNLOAD_DIR="$OUT_ROOT/downloads"
EXTRACT_DIR="$OUT_ROOT/extracted"
BUNDLE_LINK="$OUT_ROOT/genie_bundle"
MANIFEST="$OUT_ROOT/qwen3_genie_bundle_manifest.json"
ASSET_SELECTION="$OUT_ROOT/qwen3_asset_selection.json"

case "$DEVICE_PROFILE" in
  snapdragon_8_elite)
    CHIPSET_NAME="Snapdragon 8 Elite Mobile"
    CHIPSET_KEY="qualcomm-snapdragon-8-elite"
    FALLBACK_ASSET_URL="https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/$MODEL_SLUG/releases/v0.54.0/qwen3_4b_instruct_2507-genie-w4a16-qualcomm_snapdragon_8_elite.zip"
    ;;
  snapdragon_8_elite_gen5)
    CHIPSET_NAME="Snapdragon 8 Elite Gen 5 Mobile"
    CHIPSET_KEY="qualcomm-snapdragon-8-elite-gen5"
    FALLBACK_ASSET_URL="https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/$MODEL_SLUG/releases/v0.54.0/qwen3_4b_instruct_2507-genie-w4a16-qualcomm_snapdragon_8_elite_gen5.zip"
    ;;
  *)
    echo "Unsupported QWEN3_DEVICE_PROFILE=$DEVICE_PROFILE" >&2
    echo "Use snapdragon_8_elite or snapdragon_8_elite_gen5." >&2
    exit 2
    ;;
esac

mkdir -p "$DOWNLOAD_DIR" "$EXTRACT_DIR"

python3 - "$MODEL_ID" "$CHIPSET_KEY" "$FALLBACK_ASSET_URL" "$ASSET_SELECTION" <<'PY'
import json
import sys
from pathlib import Path

repo_id, chipset_key, fallback_url, output_path = sys.argv[1:5]
selection = {
    "repo_id": repo_id,
    "chipset_key": chipset_key,
    "download_url": fallback_url,
    "asset_source": "fallback_static_url",
    "qairt_tool_version": "2.45",
}

try:
    from huggingface_hub import hf_hub_download

    release_assets = hf_hub_download(repo_id=repo_id, filename="release_assets.json")
    data = json.loads(Path(release_assets).read_text(encoding="utf-8"))
    genie = data["precisions"]["w4a16"]["chipset_assets"][chipset_key]["genie"]
    selection["download_url"] = genie["download_url"]
    selection["asset_source"] = "huggingface_release_assets_json"
    selection["qairt_tool_version"] = genie.get("tool_versions", {}).get("qairt", "2.45")
except Exception as exc:
    selection["asset_selection_warning"] = str(exc)

Path(output_path).write_text(json.dumps(selection, indent=2) + "\n", encoding="utf-8")
PY

ASSET_URL="$(python3 - "$ASSET_SELECTION" <<'PY'
import json
import sys
from pathlib import Path
print(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))["download_url"])
PY
)"
ZIP_PATH="$DOWNLOAD_DIR/${ASSET_URL##*/}"

case "$FETCH_MODE" in
  plan|download)
    ;;
  *)
    echo "Unsupported QWEN3_FETCH_MODE=$FETCH_MODE. Use plan or download." >&2
    exit 2
    ;;
esac

if [[ "$FETCH_MODE" == "plan" ]]; then
  echo "Selected Qwen3 Genie asset for $CHIPSET_NAME: $ASSET_URL"
  echo "Set QWEN3_FETCH_MODE=download, or omit it, to download and extract the bundle."
  exit 0
fi

if [[ ! -f "$ZIP_PATH" ]]; then
  if command -v curl >/dev/null 2>&1; then
    curl -L --fail --continue-at - --output "$ZIP_PATH" "$ASSET_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -c -O "$ZIP_PATH" "$ASSET_URL"
  else
    echo "curl or wget is required to download $MODEL_ID Genie assets." >&2
    exit 2
  fi
fi

if command -v unzip >/dev/null 2>&1; then
  unzip -q -o "$ZIP_PATH" -d "$EXTRACT_DIR"
else
  python3 - "$ZIP_PATH" "$EXTRACT_DIR" <<'PY'
import sys
import zipfile
from pathlib import Path

zip_path = Path(sys.argv[1])
extract_dir = Path(sys.argv[2])
extract_dir.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(zip_path) as archive:
    archive.extractall(extract_dir)
PY
fi

BUNDLE_DIR="$(find "$EXTRACT_DIR" -name genie_config.json -printf '%h\n' | head -n 1 || true)"
if [[ -z "$BUNDLE_DIR" ]]; then
  echo "Extracted assets do not contain genie_config.json under $EXTRACT_DIR" >&2
  exit 2
fi

ln -sfn "$BUNDLE_DIR" "$BUNDLE_LINK"

python3 - "$MANIFEST" "$MODEL_ID" "$DEVICE_PROFILE" "$CHIPSET_NAME" "$ASSET_URL" "$ZIP_PATH" "$BUNDLE_DIR" "$BUNDLE_LINK" <<'PY'
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
data = {
    "model_id": sys.argv[2],
    "runtime": "GENIE",
    "precision": "w4a16",
    "device_profile": sys.argv[3],
    "chipset": sys.argv[4],
    "qairt_sdk_version_on_model_card": "2.45",
    "asset_url": sys.argv[5],
    "zip_path": sys.argv[6],
    "bundle_dir": sys.argv[7],
    "bundle_link": sys.argv[8],
    "required_runtime_file": "genie_config.json",
}
manifest_path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
print(f"Wrote Qwen3 Genie bundle manifest: {manifest_path.resolve()}")
PY

echo "Qwen3 Genie bundle ready: $BUNDLE_LINK"
