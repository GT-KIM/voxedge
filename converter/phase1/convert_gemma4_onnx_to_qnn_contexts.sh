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
OUT_DIR="${GEMMA4_QNN_OUT_DIR:-$ROOT_DIR/models/artifacts/android-qairt/llm/gemma4-direct-qnn}"
DEFAULT_GEMMA4_ONNX_DIR="$ROOT_DIR/models/original/llm/onnx-community__gemma-4-E2B-it-ONNX"
REWRITTEN_GEMMA4_ONNX_DIR="$OUT_DIR/rewritten_onnx"
if [[ -z "${GEMMA4_ONNX_DIR:-}" && -d "$REWRITTEN_GEMMA4_ONNX_DIR/onnx" ]]; then
  GEMMA4_ONNX_DIR="$REWRITTEN_GEMMA4_ONNX_DIR"
else
  GEMMA4_ONNX_DIR="${GEMMA4_ONNX_DIR:-$DEFAULT_GEMMA4_ONNX_DIR}"
fi
MODE="${GEMMA4_QNN_MODE:-plan}"
TARGET_SOC_MODEL="${TARGET_SOC_MODEL:-SM8750}"
QNN_CONTEXT_SOC_MODEL="${QNN_CONTEXT_SOC_MODEL:-69}"
QNN_HTP_ARCH="${QNN_HTP_ARCH:-v79}"
PERF_PROFILE="${QNN_PERF_PROFILE:-burst}"
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

export SNPE_ROOT="$QAIRT_ROOT"
export QNN_SDK_ROOT="$QAIRT_ROOT"
export PATH="$BIN_DIR:$PATH"
export LD_LIBRARY_PATH="$QAIRT_ROOT/lib/$HOST_TRIPLET:$runtime_ld_path:${LD_LIBRARY_PATH:-}"
export PYTHONPATH="$QAIRT_ROOT/lib/python:${PYTHONPATH:-}"

QNN_ONNX_CONVERTER="$BIN_DIR/qnn-onnx-converter"
QNN_MODEL_LIB_GENERATOR="$BIN_DIR/qnn-model-lib-generator"
QNN_CONTEXT_BINARY_GENERATOR="$BIN_DIR/qnn-context-binary-generator"
QNN_NET_RUN="$BIN_DIR/qnn-net-run"
QNN_THROUGHPUT_NET_RUN="$BIN_DIR/qnn-throughput-net-run"
QNN_HTP_BACKEND="$QAIRT_ROOT/lib/$HOST_TRIPLET/libQnnHtp.so"

mkdir -p "$OUT_DIR"/{configs,cpp,bin,lib,contexts,logs}

HTP_CONFIG="$OUT_DIR/configs/htp_backend_config.json"
HTP_EXT_CONFIG="$OUT_DIR/configs/htp_backend_extensions.json"
PLAN_MANIFEST="$OUT_DIR/gemma4_direct_qnn_plan.json"

cat > "$HTP_CONFIG" <<EOF
{
  "graphs": [
    {
      "graph_names": ["gemma4_embed_tokens"],
      "vtcm_mb": 0
    },
    {
      "graph_names": ["gemma4_decoder"],
      "vtcm_mb": 0
    }
  ],
  "devices": [
    {
      "htp_arch": "$QNN_HTP_ARCH",
      "soc_model": $QNN_CONTEXT_SOC_MODEL,
      "cores": [
        {
          "perf_profile": "$PERF_PROFILE",
          "rpc_control_latency": 100
        }
      ]
    }
  ]
}
EOF

cat > "$HTP_EXT_CONFIG" <<EOF
{
  "backend_extensions": {
    "shared_library_path": "libQnnHtpNetRunExtensions.so",
    "config_file_path": "$HTP_CONFIG"
  }
}
EOF

python3 - "$PLAN_MANIFEST" "$GEMMA4_ONNX_DIR" "$OUT_DIR" "$TARGET_SOC_MODEL" "$QNN_HTP_ARCH" "$QNN_CONTEXT_SOC_MODEL" "$PERF_PROFILE" <<'PY'
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
onnx_dir = Path(sys.argv[2])
out_dir = Path(sys.argv[3])
target_soc_model = sys.argv[4]
htp_arch = sys.argv[5]
context_soc_model = int(sys.argv[6])
perf_profile = sys.argv[7]

components = [
    {
        "name": "embed_tokens",
        "graph_name": "gemma4_embed_tokens",
        "onnx": str(onnx_dir / "onnx" / "embed_tokens_fp16.onnx"),
        "qnn_cpp": str(out_dir / "cpp" / "gemma4_embed_tokens.cpp"),
        "qnn_bin": str(out_dir / "bin" / "gemma4_embed_tokens.bin"),
        "model_lib_name": "gemma4_embed_tokens",
        "context_binary": str(out_dir / "contexts" / "gemma4_embed_tokens.serialized.bin"),
    },
    {
        "name": "decoder_model_merged",
        "graph_name": "gemma4_decoder",
        "onnx": str(onnx_dir / "onnx" / "decoder_model_merged_fp16.onnx"),
        "qnn_cpp": str(out_dir / "cpp" / "gemma4_decoder.cpp"),
        "qnn_bin": str(out_dir / "bin" / "gemma4_decoder.bin"),
        "model_lib_name": "gemma4_decoder",
        "context_binary": str(out_dir / "contexts" / "gemma4_decoder.serialized.bin"),
    },
]

manifest = {
    "source_model_id": "onnx-community/gemma-4-E2B-it-ONNX",
    "packaging": "direct_qnn_context_binaries",
    "target_soc_model": target_soc_model,
    "context_soc_model": context_soc_model,
    "htp_arch": htp_arch,
    "perf_profile": perf_profile,
    "default_mode": "plan",
    "modes": {
        "plan": "write configs and intended artifacts without requiring downloaded ONNX files",
        "dry-run": "run qnn-onnx-converter --dry_run info for each ONNX file",
        "convert": "run qnn-onnx-converter and qnn-model-lib-generator",
        "context": "also run qnn-context-binary-generator for HTP context binaries",
    },
    "components": components,
    "runtime_loop_owner": "android_app",
    "runtime_contract": [
        "tokenizer",
        "chat_template",
        "embed_tokens",
        "inputs_embeds",
        "per_layer_inputs",
        "position_ids",
        "attention_mask",
        "past_key_values",
        "sampler",
    ],
}
manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
print(f"Wrote Gemma4 direct-QNN plan: {manifest_path}")
PY

case "$MODE" in
  plan|dry-run|convert|context)
    ;;
  *)
    echo "Unsupported GEMMA4_QNN_MODE=$MODE. Use plan, dry-run, convert, or context." >&2
    exit 2
    ;;
esac

if [[ "$MODE" == "plan" ]]; then
  echo "Gemma4 direct-QNN plan written under: $OUT_DIR"
  echo "Set GEMMA4_QNN_MODE=dry-run after downloading ONNX-community artifacts."
  exit 0
fi

for tool in "$QNN_ONNX_CONVERTER" "$QNN_MODEL_LIB_GENERATOR" "$QNN_CONTEXT_BINARY_GENERATOR" "$QNN_NET_RUN" "$QNN_THROUGHPUT_NET_RUN"; do
  if [[ ! -x "$tool" ]]; then
    echo "Missing executable QAIRT/QNN tool: $tool" >&2
    echo "Run converter/phase1/prepare_qairt_workspace.sh first." >&2
    exit 2
  fi
done

embed_onnx="$GEMMA4_ONNX_DIR/onnx/embed_tokens_fp16.onnx"
decoder_onnx="$GEMMA4_ONNX_DIR/onnx/decoder_model_merged_fp16.onnx"
for onnx_path in "$embed_onnx" "$decoder_onnx"; do
  if [[ ! -f "$onnx_path" ]]; then
    echo "Missing ONNX source: $onnx_path" >&2
    echo "Run converter/phase1/fetch_gemma4_onnx_community.sh first." >&2
    exit 2
  fi
done

run_converter() {
  local component="$1"
  local onnx_path="$2"
  local cpp_path="$3"
  local log_path="$4"
  shift 4

  echo "Running qnn-onnx-converter for $component"
  # QAIRT 2.46 qnn-onnx-converter is target-agnostic; SM8750/HTP targeting is
  # applied when generating the HTP context binary from the QNN model library.
  "$QNN_ONNX_CONVERTER" \
    --input_network "$onnx_path" \
    --output_path "$cpp_path" \
    --float_bitwidth 16 \
    --preserve_io datatype layout \
    "$@" 2>&1 | tee "$log_path"
  local converter_status=${PIPESTATUS[0]}
  if (( converter_status != 0 )); then
    return "$converter_status"
  fi
  if grep -Eq "ERROR -|Encountered Error|unsupported in ONNX schema|wrong type|No Op registered|Graph does not have" "$log_path"; then
    echo "QAIRT reported an incompatible graph for $component. See: $log_path" >&2
    return 1
  fi
}

if [[ "$MODE" == "dry-run" ]]; then
  run_converter embed_tokens "$embed_onnx" "$OUT_DIR/cpp/gemma4_embed_tokens.cpp" "$OUT_DIR/logs/gemma4_embed_tokens_qnn_dry_run.log" --dry_run info
  run_converter decoder_model_merged "$decoder_onnx" "$OUT_DIR/cpp/gemma4_decoder.cpp" "$OUT_DIR/logs/gemma4_decoder_qnn_dry_run.log" --dry_run info
  echo "Gemma4 QNN dry run complete. Inspect logs under: $OUT_DIR/logs"
  exit 0
fi

build_component() {
  local component="$1"
  local onnx_path="$2"
  local graph_stem="$3"
  local lib_name="$4"
  local cpp_path="$OUT_DIR/cpp/$graph_stem.cpp"
  local bin_path="$OUT_DIR/bin/$graph_stem.bin"
  local log_path="$OUT_DIR/logs/${graph_stem}_qnn_convert.log"

  run_converter "$component" "$onnx_path" "$cpp_path" "$log_path"

  echo "Generating QNN model library for $component"
  "$QNN_MODEL_LIB_GENERATOR" \
    -c "$cpp_path" \
    -b "$bin_path" \
    -t "$HOST_TRIPLET" \
    -l "$lib_name" \
    -o "$OUT_DIR/lib" 2>&1 | tee "$OUT_DIR/logs/${graph_stem}_model_lib.log"
}

find_model_lib() {
  local lib_name="$1"
  local found
  found="$(find "$OUT_DIR/lib" -name "lib${lib_name}.so" -print -quit || true)"
  if [[ -z "$found" ]]; then
    echo "Missing generated model library for $lib_name under $OUT_DIR/lib" >&2
    exit 2
  fi
  printf '%s\n' "$found"
}

build_component embed_tokens "$embed_onnx" gemma4_embed_tokens gemma4_embed_tokens
build_component decoder_model_merged "$decoder_onnx" gemma4_decoder gemma4_decoder

if [[ "$MODE" == "convert" ]]; then
  echo "Gemma4 QNN C++/model library conversion complete under: $OUT_DIR"
  exit 0
fi

generate_context() {
  local component="$1"
  local lib_name="$2"
  local binary_file="$3"
  local model_lib
  model_lib="$(find_model_lib "$lib_name")"

  echo "Generating HTP context binary for $component"
  "$QNN_CONTEXT_BINARY_GENERATOR" \
    --backend "$QNN_HTP_BACKEND" \
    --model "$model_lib" \
    --binary_file "$binary_file" \
    --output_dir "$OUT_DIR/contexts" \
    --config_file "$HTP_EXT_CONFIG" 2>&1 | tee "$OUT_DIR/logs/${binary_file}_context.log"
}

generate_context embed_tokens gemma4_embed_tokens gemma4_embed_tokens.serialized
generate_context decoder_model_merged gemma4_decoder gemma4_decoder.serialized

echo "Gemma4 direct-QNN context generation complete under: $OUT_DIR/contexts"
