#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
QAIRT_ROOT="${QAIRT_ROOT:-$ROOT_DIR/tools/model_compile/qairt/extracted/qairt/2.46.0.260424}"
VENV_DIR="${QAIRT_VENV_DIR:-$HOME/.venvs/qairt-2.46.0-py310}"
PYTHON_BIN="${PYTHON_BIN:-python3.10}"

if ! "$PYTHON_BIN" -m venv "$VENV_DIR"; then
  echo "python venv module is unavailable; falling back to virtualenv in user site." >&2
  "$PYTHON_BIN" -m pip install --user --upgrade virtualenv
  "$PYTHON_BIN" -m virtualenv "$VENV_DIR"
fi
source "$VENV_DIR/bin/activate"

python -m pip install --upgrade pip
python -m pip install \
  "setuptools<81" \
  numpy==1.26.4 \
  protobuf==6.31.0 \
  onnx==1.19.1 \
  onnxruntime==1.23.2 \
  onnxsim==0.4.36

"$QAIRT_ROOT/bin/check-python-dependency" || true

cat > "$ROOT_DIR/models/artifacts/android-qairt/activate_qairt_venv.sh" <<EOF
source "$VENV_DIR/bin/activate"
source "$ROOT_DIR/models/artifacts/android-qairt/env.sh"
EOF

echo "QAIRT WSL Python venv ready: $VENV_DIR"
echo "Activate with:"
echo "  source models/artifacts/android-qairt/activate_qairt_venv.sh"
