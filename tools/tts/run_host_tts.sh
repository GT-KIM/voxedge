#!/usr/bin/env bash
# Host Supertonic TTS smoke at K=4,6,8. All paths stay INSIDE the project (.work/ is gitignored).
# Copies the script to an in-project work dir before running, to survive transient editor/watcher
# truncation of tools/tts/*.py during concurrent /mnt/d access.
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"
PY=""
for cand in "$HOME/.venvs/qairt-2.46.0-py310/bin/python" python3; do
  if "$cand" -c "import onnxruntime" 2>/dev/null; then PY="$cand"; break; fi
done
[ -z "$PY" ] && { echo "no python with onnxruntime"; exit 2; }
echo "Using: $PY"; "$PY" -c "import onnxruntime as o; print('onnxruntime', o.__version__)"

mkdir -p .work/tts models/artifacts
cp tools/tts/supertonic_tts.py .work/tts/supertonic_tts.py

OD=models/original/tts/supertonic-3/onnx
V=models/original/tts/supertonic-3/voice_styles/F1.json
TXT="안녕하세요. 오늘 회의는 3시에 시작합니다. Let's keep it short and useful."
for K in 4 6 8; do
  echo "===== K=$K ====="
  "$PY" .work/tts/supertonic_tts.py --onnx-dir "$OD" --voice "$V" --text "$TXT" --lang ko \
    --total-step "$K" --seed 0 --out "models/artifacts/tts_host_k${K}.wav"
done
