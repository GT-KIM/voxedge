#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ -z "${VIRTUAL_ENV:-}" && -f "$ROOT_DIR/models/artifacts/android-qairt/activate_qairt_venv.sh" ]]; then
  # QAIRT converter behavior depends on tested ONNX package versions.
  source "$ROOT_DIR/models/artifacts/android-qairt/activate_qairt_venv.sh"
fi

QAIRT_ROOT="${QAIRT_ROOT:-$ROOT_DIR/tools/model_compile/qairt/extracted/qairt/2.46.0.260424}"
HOST_TRIPLET="${HOST_TRIPLET:-x86_64-linux-clang}"
BIN_DIR="$QAIRT_ROOT/bin/$HOST_TRIPLET"
DEFAULT_SOURCE_DIR="$ROOT_DIR/models/original/tts/supertonic-3/onnx"
REWRITTEN_SOURCE_DIR="$ROOT_DIR/models/artifacts/android-qairt/tts/rewritten_onnx_venv_l1000"
STATIC_SOURCE_DIR="$ROOT_DIR/models/artifacts/android-qairt/tts/static_onnx"
SOURCE_DIR="${TTS_ONNX_DIR:-$DEFAULT_SOURCE_DIR}"
if [[ -z "${TTS_ONNX_DIR:-}" && -d "$REWRITTEN_SOURCE_DIR" ]]; then
  SOURCE_DIR="$REWRITTEN_SOURCE_DIR"
elif [[ -z "${TTS_ONNX_DIR:-}" && -d "$STATIC_SOURCE_DIR" ]]; then
  SOURCE_DIR="$STATIC_SOURCE_DIR"
fi
OUT_DIR="$ROOT_DIR/models/artifacts/android-qairt/tts"
CALIBRATION_ROOT="${CALIBRATION_ROOT:-$ROOT_DIR/models/artifacts/android-qairt/calibration/tts}"
TTS_BATCH_SIZE="${TTS_BATCH_SIZE:-1}"
TTS_TEXT_LENGTH="${TTS_TEXT_LENGTH:-256}"
TTS_LATENT_LENGTH="${TTS_LATENT_LENGTH:-1000}"
RUNTIME_LIB_ROOT="${RUNTIME_LIB_ROOT:-$ROOT_DIR/tools/model_compile/qairt/runtime-libs/ubuntu-22.04/root}"
RUNTIME_LIB_PATHS=()
if [[ -n "${GRAPH_PREPARE_EXTRA_ARGS:-}" ]]; then
  read -r -a graph_prepare_args <<< "$GRAPH_PREPARE_EXTRA_ARGS"
else
  graph_prepare_args=(
    --htp_socs=sm8750
    --optimization_level=3
    --vtcm_override=0
    --overwrite_cache_records
  )
fi

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

export SNPE_ROOT="$QAIRT_ROOT"
export QNN_SDK_ROOT="$QAIRT_ROOT"
export PATH="$BIN_DIR:$PATH"
export LD_LIBRARY_PATH="$QAIRT_ROOT/lib/$HOST_TRIPLET:$runtime_ld_path:${LD_LIBRARY_PATH:-}"
export PYTHONPATH="$QAIRT_ROOT/lib/python:${PYTHONPATH:-}"

SNPE_ONNX_TO_DLC="$BIN_DIR/snpe-onnx-to-dlc"
SNPE_DLC_QUANTIZE="$BIN_DIR/snpe-dlc-quantize"
SNPE_DLC_GRAPH_PREPARE="$BIN_DIR/snpe-dlc-graph-prepare"

for tool in "$SNPE_ONNX_TO_DLC" "$SNPE_DLC_QUANTIZE" "$SNPE_DLC_GRAPH_PREPARE"; do
  if [[ ! -x "$tool" ]]; then
    echo "Missing executable QAIRT tool: $tool" >&2
    echo "Run converter/phase1/prepare_qairt_workspace.sh first." >&2
    exit 2
  fi
done

mkdir -p "$OUT_DIR/float" "$OUT_DIR/int8" "$OUT_DIR/prepared"

components=(duration_predictor text_encoder vector_estimator vocoder)
symbol_args=(
  --define_symbol batch_size "$TTS_BATCH_SIZE"
  --define_symbol text_length "$TTS_TEXT_LENGTH"
  --define_symbol latent_length "$TTS_LATENT_LENGTH"
)

for component in "${components[@]}"; do
  onnx="$SOURCE_DIR/$component.onnx"
  float_dlc="$OUT_DIR/float/$component.dlc"
  int8_dlc="$OUT_DIR/int8/${component}_int8.dlc"
  prepared_dlc="$OUT_DIR/prepared/${component}_sm8750.dlc"
  input_list="$CALIBRATION_ROOT/${component}_input_list.txt"

  if [[ ! -f "$onnx" ]]; then
    echo "Missing ONNX source: $onnx" >&2
    exit 2
  fi

  echo "Converting $component ONNX to float DLC"
  "$SNPE_ONNX_TO_DLC" \
    --input_network "$onnx" \
    --output_path "$float_dlc" \
    "${symbol_args[@]}"

  if [[ ! -f "$input_list" ]]; then
    echo "Skipping INT8 quantization for $component; missing calibration input list: $input_list" >&2
    continue
  fi

  echo "Quantizing $component DLC to INT8"
  "$SNPE_DLC_QUANTIZE" \
    --input_dlc "$float_dlc" \
    --input_list "$input_list" \
    --output_dlc "$int8_dlc"

  echo "Graph-preparing $component for SM8750"
  "$SNPE_DLC_GRAPH_PREPARE" \
    --input_dlc "$int8_dlc" \
    --output_dlc "$prepared_dlc" \
    "${graph_prepare_args[@]}"
done

echo "TTS DLC preparation pass complete."
