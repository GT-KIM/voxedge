#!/usr/bin/env bash
# Convert already-rewritten (static-shaped) Supertonic-3 ONNX to FLOAT DLCs only.
# Usage: convert_tts_float_only.sh <REWRITTEN_ONNX_DIR> <OUT_DLC_DIR>
# Env compile per project rule: run inside WSL.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="${1:?source onnx dir}"
OUT="${2:?output dlc dir}"

QAIRT_ROOT="${QAIRT_ROOT:-$ROOT_DIR/tools/model_compile/qairt/extracted/qairt/2.46.0.260424}"
HT="${HOST_TRIPLET:-x86_64-linux-clang}"
RT="$ROOT_DIR/tools/model_compile/qairt/runtime-libs/ubuntu-22.04/root"

export SNPE_ROOT="$QAIRT_ROOT" QNN_SDK_ROOT="$QAIRT_ROOT"
export PATH="$QAIRT_ROOT/bin/$HT:$PATH"
export LD_LIBRARY_PATH="$QAIRT_ROOT/lib/$HT:$RT/usr/lib/x86_64-linux-gnu:$RT/usr/lib/llvm-14/lib:${LD_LIBRARY_PATH:-}"
export PYTHONPATH="$QAIRT_ROOT/lib/python:${PYTHONPATH:-}"

mkdir -p "$OUT"
which snpe-onnx-to-dlc
COMPONENTS="${COMPONENTS:-duration_predictor text_encoder vector_estimator vocoder}"
for c in $COMPONENTS; do
  echo "=== converting $c ==="
  if snpe-onnx-to-dlc --input_network "$SRC/$c.onnx" --output_path "$OUT/$c.dlc" > "$OUT/${c}_convert.log" 2>&1; then
    echo "OK $c"
  else
    echo "FAILED $c (see ${c}_convert.log):"; tail -4 "$OUT/${c}_convert.log"
  fi
done
echo "=== output DLCs ==="
ls -la "$OUT"
