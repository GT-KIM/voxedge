#!/usr/bin/env bash
# Quantize the short-chunk (T=64, LAT=128) float TTS DLCs with representative calibration
# data (generate_representative_tts_calibration.py) and graph-prepare for SM8750.
# Precision via env: QUANT_TAG (output dir suffix, default int8) and QUANT_ARGS
# (extra snpe-dlc-quantize flags). Plain INT8 (the default) audibly BREAKS this model
# (constant dp duration, garbled audio) — use W8A16:
#   QUANT_TAG=w8a16 QUANT_ARGS="--weights_bitwidth 8 --act_bitwidth 16 --bias_bitwidth 32 \
#     --act_quantizer enhanced --param_quantizer enhanced --use_per_channel_quantization" \
#     bash converter/phase1/quantize_tts_t64_l128.sh
# Outputs: models/artifacts/android-qairt/tts/<tag>_t64_l128/ and prepared_<tag>_t64_l128/
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ -z "${VIRTUAL_ENV:-}" && -f "$ROOT_DIR/models/artifacts/android-qairt/activate_qairt_venv.sh" ]]; then
  source "$ROOT_DIR/models/artifacts/android-qairt/activate_qairt_venv.sh"
fi

QAIRT_ROOT="${QAIRT_ROOT:-$ROOT_DIR/tools/model_compile/qairt/extracted/qairt/2.46.0.260424}"
HOST_TRIPLET="${HOST_TRIPLET:-x86_64-linux-clang}"
BIN_DIR="$QAIRT_ROOT/bin/$HOST_TRIPLET"
FLOAT_DIR="$ROOT_DIR/models/artifacts/android-qairt/tts/float_t64_l128"
CALIBRATION_ROOT="${CALIBRATION_ROOT:-$ROOT_DIR/models/artifacts/android-qairt/calibration/tts_t64_l128_rep}"
QUANT_TAG="${QUANT_TAG:-int8}"
INT8_DIR="$ROOT_DIR/models/artifacts/android-qairt/tts/${QUANT_TAG}_t64_l128"
PREPARED_DIR="$ROOT_DIR/models/artifacts/android-qairt/tts/prepared_${QUANT_TAG}_t64_l128"
RUNTIME_LIB_ROOT="${RUNTIME_LIB_ROOT:-$ROOT_DIR/tools/model_compile/qairt/runtime-libs/ubuntu-22.04/root}"

RUNTIME_LIB_PATHS=()
[[ -d "$RUNTIME_LIB_ROOT/usr/lib/x86_64-linux-gnu" ]] && RUNTIME_LIB_PATHS+=("$RUNTIME_LIB_ROOT/usr/lib/x86_64-linux-gnu")
[[ -d "$RUNTIME_LIB_ROOT/usr/lib/llvm-14/lib" ]] && RUNTIME_LIB_PATHS+=("$RUNTIME_LIB_ROOT/usr/lib/llvm-14/lib")
runtime_ld_path=""
(( ${#RUNTIME_LIB_PATHS[@]} > 0 )) && runtime_ld_path="$(IFS=:; echo "${RUNTIME_LIB_PATHS[*]}")"

export SNPE_ROOT="$QAIRT_ROOT"
export QNN_SDK_ROOT="$QAIRT_ROOT"
export PATH="$BIN_DIR:$PATH"
export LD_LIBRARY_PATH="$QAIRT_ROOT/lib/$HOST_TRIPLET:$runtime_ld_path:${LD_LIBRARY_PATH:-}"
export PYTHONPATH="$QAIRT_ROOT/lib/python:${PYTHONPATH:-}"

mkdir -p "$INT8_DIR" "$PREPARED_DIR"

components=(duration_predictor text_encoder vector_estimator vocoder)
for component in "${components[@]}"; do
  float_dlc="$FLOAT_DIR/$component.dlc"
  input_list="$CALIBRATION_ROOT/${component}_input_list.txt"
  int8_dlc="$INT8_DIR/${component}.dlc"
  prepared_dlc="$PREPARED_DIR/${component}.dlc"

  [[ -f "$float_dlc" ]] || { echo "missing float DLC: $float_dlc" >&2; exit 2; }
  [[ -f "$input_list" ]] || { echo "missing calibration list: $input_list" >&2; exit 2; }

  echo "=== [$component] $QUANT_TAG quantize ($(wc -l < "$input_list") samples)"
  # shellcheck disable=SC2086 — QUANT_ARGS is intentionally word-split
  "$BIN_DIR/snpe-dlc-quantize" \
    --input_dlc "$float_dlc" \
    --input_list "$input_list" \
    --output_dlc "$int8_dlc" ${QUANT_ARGS:-} 2>&1 | tee "$INT8_DIR/${component}_quantize.log" | tail -3

  echo "=== [$component] graph-prepare for SM8750"
  "$BIN_DIR/snpe-dlc-graph-prepare" \
    --input_dlc "$int8_dlc" \
    --output_dlc "$prepared_dlc" \
    --htp_socs=sm8750 \
    --optimization_level=3 \
    --vtcm_override=0 \
    --overwrite_cache_records 2>&1 | tee "$PREPARED_DIR/${component}_prepare.log" | tail -3
done

echo "$QUANT_TAG short-chunk TTS DLCs ready: $PREPARED_DIR"
ls -l "$PREPARED_DIR"
