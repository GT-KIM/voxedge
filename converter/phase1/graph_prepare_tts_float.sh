#!/usr/bin/env bash
# Graph-prepare FLOAT Supertonic-3 DLCs for SM8750 HTP (fp16), so they run correctly on the HTP.
# Usage: graph_prepare_tts_float.sh <FLOAT_DLC_DIR> <OUT_PREPARED_DIR> [components...]
set -uo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
# shellcheck disable=SC1091
source "$ROOT_DIR/models/artifacts/android-qairt/activate_qairt_venv.sh"

SRC="${1:?float dlc dir}"; OUT="${2:?out dir}"; shift 2 || true
COMPS="${*:-duration_predictor text_encoder vector_estimator vocoder}"
mkdir -p "$OUT"
which snpe-dlc-graph-prepare
for c in $COMPS; do
  [ -f "$SRC/$c.dlc" ] || { echo "skip $c (no float dlc)"; continue; }
  echo "=== graph-prepare $c (sm8750) ==="
  if snpe-dlc-graph-prepare --input_dlc "$SRC/$c.dlc" --output_dlc "$OUT/${c}.dlc" \
       --htp_socs sm8750 --optimization_level 3 --overwrite_cache_records \
       > "$OUT/${c}_prepare.log" 2>&1; then
    echo "  OK $c"
  else
    echo "  FAIL $c: $(tail -2 "$OUT/${c}_prepare.log" | tr '\n' ' ')"
  fi
done
echo "=== prepared DLCs ==="; ls -la "$OUT"/*.dlc 2>/dev/null || echo "(none)"
