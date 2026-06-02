#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
QAIRT_ZIP="${QAIRT_ZIP:-$ROOT_DIR/tools/model_compile/qairt/v2.46.0.260424.zip}"
QAIRT_EXTRACT_DIR="${QAIRT_EXTRACT_DIR:-$ROOT_DIR/tools/model_compile/qairt/extracted}"
QAIRT_ROOT="${QAIRT_ROOT:-$QAIRT_EXTRACT_DIR/qairt/2.46.0.260424}"
HOST_TRIPLET="${HOST_TRIPLET:-x86_64-linux-clang}"
BIN_DIR="$QAIRT_ROOT/bin/$HOST_TRIPLET"
RUNTIME_LIB_ROOT="${RUNTIME_LIB_ROOT:-$ROOT_DIR/tools/model_compile/qairt/runtime-libs/ubuntu-22.04/root}"
RUNTIME_LIB_PATHS=()

if [[ -d "$RUNTIME_LIB_ROOT/usr/lib/x86_64-linux-gnu" ]]; then
  RUNTIME_LIB_PATHS+=("$RUNTIME_LIB_ROOT/usr/lib/x86_64-linux-gnu")
fi

if [[ -d "$RUNTIME_LIB_ROOT/usr/lib/llvm-14/lib" ]]; then
  RUNTIME_LIB_PATHS+=("$RUNTIME_LIB_ROOT/usr/lib/llvm-14/lib")
fi

runtime_ld_path=""
if (( ${#RUNTIME_LIB_PATHS[@]} > 0 )); then
  runtime_ld_path="$(IFS=:; echo "${RUNTIME_LIB_PATHS[*]}")"
fi

require_file() {
  if [[ ! -f "$1" ]]; then
    echo "Missing required file: $1" >&2
    exit 2
  fi
}

require_file "$QAIRT_ZIP"
require_file "$ROOT_DIR/models/original/llm/google__gemma-4-E2B-it/model.safetensors"
require_file "$ROOT_DIR/models/original/tts/supertonic-3/onnx/duration_predictor.onnx"
require_file "$ROOT_DIR/models/original/tts/supertonic-3/onnx/text_encoder.onnx"
require_file "$ROOT_DIR/models/original/tts/supertonic-3/onnx/vector_estimator.onnx"
require_file "$ROOT_DIR/models/original/tts/supertonic-3/onnx/vocoder.onnx"

if [[ ! -d "$QAIRT_ROOT" ]]; then
  mkdir -p "$QAIRT_EXTRACT_DIR"
  python3 - "$QAIRT_ZIP" "$QAIRT_EXTRACT_DIR" <<'PY'
import sys
import zipfile

zip_path, extract_dir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(zip_path) as archive:
    archive.extractall(extract_dir)
PY
fi

for tool in snpe-onnx-to-dlc snpe-dlc-quantize snpe-dlc-graph-prepare qnn-onnx-converter genie-t2t-run; do
  if [[ ! -x "$BIN_DIR/$tool" ]]; then
    echo "Missing or non-executable QAIRT tool: $BIN_DIR/$tool" >&2
    exit 2
  fi
done

mkdir -p \
  "$ROOT_DIR/models/artifacts/android-qairt/llm" \
  "$ROOT_DIR/models/artifacts/android-qairt/tts/float" \
  "$ROOT_DIR/models/artifacts/android-qairt/tts/int8" \
  "$ROOT_DIR/models/artifacts/android-qairt/tts/prepared" \
  "$ROOT_DIR/models/artifacts/android-qairt/calibration" \
  "$ROOT_DIR/models/artifacts/android-qairt/logs"

cat > "$ROOT_DIR/models/artifacts/android-qairt/env.sh" <<EOF
export QAIRT_ROOT="$QAIRT_ROOT"
export SNPE_ROOT="\$QAIRT_ROOT"
export QNN_SDK_ROOT="\$QAIRT_ROOT"
export PATH="\$QAIRT_ROOT/bin/$HOST_TRIPLET:\$PATH"
export LD_LIBRARY_PATH="\$QAIRT_ROOT/lib/$HOST_TRIPLET:$runtime_ld_path:\${LD_LIBRARY_PATH:-}"
export PYTHONPATH="\$QAIRT_ROOT/lib/python:\${PYTHONPATH:-}"
EOF

echo "QAIRT workspace prepared at $QAIRT_ROOT"
echo "Source this file before manual commands:"
echo "  source models/artifacts/android-qairt/env.sh"
