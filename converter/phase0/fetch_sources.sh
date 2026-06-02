#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEST_DIR="$ROOT_DIR/models/original"
TTS_MODEL_ID="${TTS_MODEL_ID:-Supertone/supertonic-3}"
LLM_MODEL_ID="${LLM_MODEL_ID:-google/gemma-4-E2B-it}"

if [[ "${ACCEPT_MODEL_LICENSES:-0}" != "1" ]]; then
  echo "Set ACCEPT_MODEL_LICENSES=1 after reviewing model licenses and access terms." >&2
  exit 2
fi

if command -v hf >/dev/null 2>&1; then
  HF_DOWNLOAD=(hf download)
elif command -v huggingface-cli >/dev/null 2>&1; then
  HF_DOWNLOAD=(huggingface-cli download)
else
  echo "Install huggingface_hub CLI in WSL: python3 -m pip install -U huggingface_hub" >&2
  exit 127
fi

mkdir -p "$DEST_DIR/llm" "$DEST_DIR/tts"

llm_dir_name="${LLM_MODEL_ID//\//__}"
tts_dir_name="${TTS_MODEL_ID##*/}"

echo "Downloading LLM source: $LLM_MODEL_ID"
"${HF_DOWNLOAD[@]}" "$LLM_MODEL_ID" --local-dir "$DEST_DIR/llm/$llm_dir_name"

echo "Downloading TTS source: $TTS_MODEL_ID"
"${HF_DOWNLOAD[@]}" "$TTS_MODEL_ID" --local-dir "$DEST_DIR/tts/$tts_dir_name"

echo "Recording checksums"
(
  cd "$ROOT_DIR"
  find models/original -type f -print0 | sort -z | xargs -0 sha256sum > converter/phase0/checksums.sha256
)

echo "Phase 0 source download complete."
