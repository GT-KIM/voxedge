#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VENV_DIR="${GEMMA4_ONNX_VENV_DIR:-$HOME/.venvs/gemma4-onnx-py310}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
ARTIFACT_DIR="$ROOT_DIR/models/artifacts/android-qairt"

"$PYTHON_BIN" -m venv "$VENV_DIR"
# shellcheck source=/dev/null
source "$VENV_DIR/bin/activate"

python -m pip install --upgrade pip setuptools wheel
python -m pip install \
  "torch" \
  "transformers" \
  "accelerate" \
  "safetensors" \
  "onnx" \
  "onnxruntime" \
  "optimum[onnxruntime]"

mkdir -p "$ARTIFACT_DIR"
cat > "$ARTIFACT_DIR/activate_gemma4_onnx_venv.sh" <<EOF
#!/usr/bin/env bash
source "$VENV_DIR/bin/activate"
EOF
chmod +x "$ARTIFACT_DIR/activate_gemma4_onnx_venv.sh"

python - <<'PY'
import importlib.metadata as metadata

for package in ("torch", "transformers", "accelerate", "safetensors", "onnx", "onnxruntime", "optimum"):
    print(f"{package}=={metadata.version(package)}")
PY
