#!/usr/bin/env python3
"""
Prepare STATIC-shape (text_length=64, latent_length=128) input raw tensors for the on-device
short-chunk Supertonic DLC chain (text_encoder -> K x vector_estimator -> vocoder).

IMPORTANT — DLC INPUT LAYOUT:
QAIRT snpe-onnx-to-dlc (axes_to_spatial_first_order=True) TRANSPOSES the last two axes of every
multi-dim network input. The DLC therefore expects (verified via snpe-dlc-info APP_WRITE):
  style_ttl   ONNX [1,50,256]  -> DLC [1,256,50]
  text_mask   ONNX [1,1,64]    -> DLC [1,64,1]
  noisy_latent ONNX [1,144,128] -> DLC [1,128,144]
  latent_mask  ONNX [1,1,128]   -> DLC [1,128,1]
  text_ids    [1,64] int32 (unchanged)
Feeding ONNX-order data scrambles the conditioning tensors and destroys speaker identity / audio
quality (content survives). So by default we emit DLC layout. Use --layout onnx for host-onnxruntime
reference runs (which expect ONNX order).

dp is bypassed in this harness: latent_len fixed at 128, latent_mask = ones up to a clamped length.
For a faithful clause, pass --use-dp to size latent_mask from duration_predictor.

Outputs (to --out-dir):
  text_ids.raw int32 (1,64); plus float32 inputs in the chosen layout; current_step_{0..K-1}.raw; total_step.raw
"""
import argparse, json, os, re
from unicodedata import normalize
import numpy as np

T = 64        # static text_length
LAT = 128     # static latent_length
LDIM = 24
CCF = 6
AVAILABLE = {"en", "ko", "ja", "na"}


def preprocess(text, lang):
    text = normalize("NFKD", text)
    text = re.sub(r"\s+", " ", text).strip()
    if not re.search(r"[.!?;:,]$", text):
        text += "."
    return f"<{lang}>" + text + f"</{lang}>"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--onnx-dir", required=True)
    ap.add_argument("--voice", required=True)
    ap.add_argument("--text", required=True)
    ap.add_argument("--lang", default="ko")
    ap.add_argument("--total-step", type=int, default=6)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--layout", choices=["dlc", "onnx"], default="dlc",
                    help="dlc = device layout (last two axes transposed); onnx = host-onnxruntime order")
    ap.add_argument("--use-dp", action="store_true",
                    help="size latent_mask from duration_predictor (needs onnxruntime); else mask=ones")
    ap.add_argument("--speed", type=float, default=1.05)
    ap.add_argument("--out-dir", required=True)
    a = ap.parse_args()
    np.random.seed(a.seed)
    os.makedirs(a.out_dir, exist_ok=True)

    indexer = json.load(open(os.path.join(a.onnx_dir, "unicode_indexer.json")))
    s = preprocess(a.text, a.lang)
    ids = [indexer[ord(c)] for c in s]
    assert len(ids) <= T, f"clause too long for T={T}: {len(ids)} tokens — split into shorter clauses"
    true_len = len(ids)
    text_ids = np.zeros((1, T), dtype=np.int32)
    text_ids[0, :true_len] = np.array(ids, dtype=np.int32)

    # ONNX-order base tensors
    text_mask = (np.arange(T) < true_len).astype(np.float32).reshape(1, 1, T)   # [1,1,64]
    vs = json.load(open(a.voice))
    style_ttl = np.array(vs["style_ttl"]["data"], dtype=np.float32).reshape(1, 50, 256)  # [1,50,256]
    style_dp = np.array(vs["style_dp"]["data"], dtype=np.float32).reshape(1, 8, 16)

    clamped = LAT
    if a.use_dp:
        import onnxruntime as ort
        cfg = json.load(open(os.path.join(a.onnx_dir, "tts.json")))
        sr = cfg["ae"]["sample_rate"]; base = cfg["ae"]["base_chunk_size"]
        dp = ort.InferenceSession(os.path.join(a.onnx_dir, "duration_predictor.onnx"),
                                  providers=["CPUExecutionProvider"])
        ti = np.zeros((1, true_len), np.int64); ti[0] = np.array(ids, np.int64)
        tm = np.ones((1, 1, true_len), np.float32)
        dur, *_ = dp.run(None, {"text_ids": ti, "style_dp": style_dp, "text_mask": tm})
        dur = dur / a.speed
        chunk = base * CCF
        true_latent = int(((dur * sr).astype(np.int64).max() + chunk - 1) // chunk)
        clamped = min(true_latent, LAT)

    latent_mask = (np.arange(LAT) < clamped).astype(np.float32).reshape(1, 1, LAT)  # [1,1,128]
    noisy = (np.random.randn(1, LDIM * CCF, LAT).astype(np.float32)) * latent_mask  # [1,144,128]

    def emit(name, arr):
        a32 = np.ascontiguousarray(arr, dtype=np.float32)
        a32.tofile(os.path.join(a.out_dir, name))

    # text_ids is int32 and layout-invariant ([1,64])
    text_ids.tofile(os.path.join(a.out_dir, "text_ids.raw"))

    if a.layout == "dlc":
        emit("text_mask.raw",    np.transpose(text_mask,  (0, 2, 1)))   # [1,64,1]
        emit("style_ttl.raw",    np.transpose(style_ttl,  (0, 2, 1)))   # [1,256,50]
        emit("noisy_latent.raw", np.transpose(noisy,      (0, 2, 1)))   # [1,128,144]
        emit("latent_mask.raw",  np.transpose(latent_mask,(0, 2, 1)))   # [1,128,1]
    else:  # onnx
        emit("text_mask.raw",    text_mask)     # [1,1,64]
        emit("style_ttl.raw",    style_ttl)     # [1,50,256]
        emit("noisy_latent.raw", noisy)         # [1,144,128]
        emit("latent_mask.raw",  latent_mask)   # [1,1,128]
    emit("style_dp.raw", style_dp)
    emit("total_step.raw", np.array([a.total_step], dtype=np.float32))
    for k in range(a.total_step):
        emit(f"current_step_{k}.raw", np.array([k], dtype=np.float32))

    print(json.dumps({
        "processed_text": s, "true_text_len": true_len, "static_T": T, "latent_len": LAT,
        "clamped_latent": clamped, "K": a.total_step, "layout": a.layout, "use_dp": a.use_dp,
        "out_dir": a.out_dir,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    raise SystemExit(main())
