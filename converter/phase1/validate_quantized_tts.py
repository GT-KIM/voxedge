#!/usr/bin/env python3
"""
Host-side sanity check for quantized short-chunk TTS DLCs BEFORE device provisioning.

For each component, runs the quantized DLC on the SNPE host CPU runtime (snpe-net-run) over the
first few representative calibration samples and compares outputs against the float ONNX graphs
(onnxruntime). Reports cosine similarity + relative scale per sample. The 2026-06-12 plain-INT8
build failed exactly here: duration_predictor returned a constant 8.066 s for every input, and
on-device audio was garbled — cosine vs float would have flagged both without a device cycle.

Usage (WSL qairt venv, PATH/LD_LIBRARY_PATH set as in quantize_tts_t64_l128.sh):
  python3 converter/phase1/validate_quantized_tts.py --quant-dir <int8_or_w8a16_t64_l128_dir>
"""
import argparse
import json
import subprocess
import tempfile
from pathlib import Path
from unicodedata import normalize as unorm

import numpy as np
import onnxruntime as ort

COMPONENTS = ("duration_predictor", "text_encoder", "vector_estimator", "vocoder")


def from_dlc_layout(arr):
    return np.ascontiguousarray(np.swapaxes(arr, -1, -2)) if arr.ndim >= 3 else arr


def parse_list_line(line):
    """'name:=/abs/path.raw name2:=...' -> {name: path}"""
    out = {}
    for tok in line.strip().split():
        name, path = tok.split(":=", 1)
        out[name] = Path(path)
    return out


def cosine(a, b):
    a = a.ravel().astype(np.float64)
    b = b.ravel().astype(np.float64)
    denom = np.linalg.norm(a) * np.linalg.norm(b)
    return float(a @ b / denom) if denom > 0 else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--onnx-dir", default="models/artifacts/android-qairt/tts/rewritten_onnx_t64_l128")
    ap.add_argument("--calibration-root", default="models/artifacts/android-qairt/calibration/tts_t64_l128_rep")
    ap.add_argument("--quant-dir", required=True, help="dir with quantized (pre-graph-prepare) DLCs")
    ap.add_argument("--samples", type=int, default=3)
    ap.add_argument("--min-cosine", type=float, default=0.99)
    a = ap.parse_args()

    onnx_dir = Path(a.onnx_dir)
    cal = Path(a.calibration_root)
    quant = Path(a.quant_dir)
    opts = ort.SessionOptions()
    opts.log_severity_level = 3

    failures = []
    for comp in COMPONENTS:
        sess = ort.InferenceSession(str(onnx_dir / f"{comp}.onnx"),
                                    sess_options=opts, providers=["CPUExecutionProvider"])
        in_meta = {i.name: i for i in sess.get_inputs()}
        out_name = sess.get_outputs()[0].name
        lines = (cal / f"{comp}_input_list.txt").read_text().strip().splitlines()[: a.samples]

        for si, line in enumerate(lines):
            paths = parse_list_line(line)
            # Float reference from the DLC-layout raws (undo the transpose for ONNX).
            feed = {}
            for name, p in paths.items():
                meta = in_meta[name]
                shape = [int(d) for d in meta.shape]
                if "int" in meta.type:
                    arr = np.fromfile(p, np.int32).reshape(shape)
                    feed[name] = arr.astype(np.int64) if meta.type == "tensor(int64)" else arr
                else:
                    dlc_shape = shape[:-2] + [shape[-1], shape[-2]] if len(shape) >= 3 else shape
                    feed[name] = from_dlc_layout(np.fromfile(p, np.float32).reshape(dlc_shape))
            ref = sess.run([out_name], feed)[0]

            # Quantized run on the SNPE host CPU runtime.
            with tempfile.TemporaryDirectory() as td:
                tdp = Path(td)
                (tdp / "list.txt").write_text(line + "\n")
                r = subprocess.run(
                    ["snpe-net-run", "--container", str(quant / f"{comp}.dlc"),
                     "--input_list", str(tdp / "list.txt"), "--output_dir", str(tdp / "out")],
                    capture_output=True, text=True)
                if r.returncode != 0:
                    failures.append(f"{comp}[{si}]: snpe-net-run failed: {r.stderr.strip()[-300:]}")
                    continue
                raws = sorted((tdp / "out" / "Result_0").glob("*.raw"))
                if not raws:
                    failures.append(f"{comp}[{si}]: no output raw produced")
                    continue
                got = np.fromfile(raws[0], np.float32)

            ref_dlc = np.swapaxes(ref, -1, -2) if ref.ndim >= 3 else ref
            if got.size != ref_dlc.size:
                failures.append(f"{comp}[{si}]: size mismatch dlc={got.size} onnx={ref_dlc.size}")
                continue
            c = cosine(got, ref_dlc.ravel())
            scale = float(np.linalg.norm(got) / (np.linalg.norm(ref_dlc) + 1e-12))
            ok = c >= a.min_cosine and 0.7 < scale < 1.4
            print(f"{comp}[{si}] cosine={c:.5f} scale={scale:.3f} "
                  f"ref0={ref.ravel()[0]:.4f} got0={got[0]:.4f} {'OK' if ok else 'FAIL'}", flush=True)
            if not ok:
                failures.append(f"{comp}[{si}]: cosine={c:.5f} scale={scale:.3f}")

    if failures:
        print(json.dumps({"verdict": "FAIL", "failures": failures}, indent=2))
        return 1
    print(json.dumps({"verdict": "PASS"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
