#!/usr/bin/env bash
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"
source models/artifacts/android-qairt/activate_qairt_venv.sh 2>/dev/null || true
Q=tools/model_compile/qairt/extracted/qairt/2.46.0.260424
OUT=/tmp/dp_info_full.txt
"$Q/bin/x86_64-linux-clang/snpe-dlc-info" -i models/artifacts/android-qairt/tts/prepared_t64_l128/duration_predictor.dlc > "$OUT" 2>&1 || true
echo "=== lines: $(wc -l < "$OUT") ==="
echo "=== APP_WRITE inputs ==="
grep -oE "[A-Za-z_]+ \(data type: [^;]+; tensor dimension: \[[0-9,]*\]; tensor type: APP_WRITE\)" "$OUT" | sort -u
echo "=== APP_READ outputs ==="
grep -oE "[A-Za-z_/.]+ \(data type: [^;]+; tensor dimension: \[[0-9,]*\]; tensor type: APP_READ\)" "$OUT" | sort -u
echo "=== Output Names section ==="
grep -iE "Output Name|out_names|^Total|Model Version" "$OUT" | head
