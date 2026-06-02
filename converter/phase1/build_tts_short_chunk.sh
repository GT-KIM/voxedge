#!/usr/bin/env bash
# Build short-chunk Supertonic-3 float DLCs + matching random inputs, using the TESTED QAIRT venv
# (onnx 1.19.1 / onnxsim 0.4.36) so the converter matches the validated toolchain.
# Run inside WSL from repo root. Env: TTS_TEXT_LENGTH (default 64), TTS_LATENT_LENGTH (default 128).
set -uo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
# shellcheck disable=SC1091
source "$ROOT_DIR/models/artifacts/android-qairt/activate_qairt_venv.sh"

export TTS_BATCH_SIZE="${TTS_BATCH_SIZE:-1}"
export TTS_TEXT_LENGTH="${TTS_TEXT_LENGTH:-64}"
export TTS_LATENT_LENGTH="${TTS_LATENT_LENGTH:-128}"
TAG="t${TTS_TEXT_LENGTH}_l${TTS_LATENT_LENGTH}"

REWRITE="models/artifacts/android-qairt/tts/rewritten_onnx_${TAG}"
FLOAT="models/artifacts/android-qairt/tts/float_${TAG}"
CALIB="models/artifacts/android-qairt/calibration/tts_${TAG}"

echo "### python/onnx in use:"; python -c 'import onnx,onnxsim;print("onnx",onnx.__version__,"onnxsim",onnxsim.__version__)'

echo "### 1) rewrite original ONNX -> $REWRITE (text=$TTS_TEXT_LENGTH latent=$TTS_LATENT_LENGTH)"
rm -rf "$REWRITE"; mkdir -p "$REWRITE"
python converter/phase1/rewrite_tts_onnx_for_qairt.py models/original/tts/supertonic-3/onnx "$REWRITE" > "$REWRITE/rewrite_stdout.log" 2>&1 \
  && echo "rewrite OK" || { echo "rewrite FAILED"; tail -8 "$REWRITE/rewrite_stdout.log"; }

echo "### 2) convert -> float DLCs ($FLOAT)"
mkdir -p "$FLOAT"
for c in duration_predictor text_encoder vector_estimator vocoder; do
  if snpe-onnx-to-dlc --input_network "$REWRITE/$c.onnx" --output_path "$FLOAT/$c.dlc" > "$FLOAT/${c}_convert.log" 2>&1; then
    echo "  OK   $c"
  else
    echo "  FAIL $c: $(tail -1 "$FLOAT/${c}_convert.log")"
  fi
done

echo "### 3) generate matching random inputs ($CALIB)"
python converter/phase1/generate_random_tts_calibration.py --onnx-dir "$REWRITE" --output-root "$CALIB" --samples 1 --seed 7 > "$CALIB/gen.log" 2>&1 \
  && echo "inputs OK" || echo "inputs FAILED"

echo "### result DLCs:"; ls -la "$FLOAT"/*.dlc 2>/dev/null || echo "(none)"
