#!/usr/bin/env python3
"""
Representative INT8 calibration data for the SHORT-CHUNK (T=64, LAT=128) Supertonic TTS DLCs.

Replaces converter/phase1/generate_random_tts_calibration.py output for accuracy-grade
quantization: instead of Gaussian noise, every tensor is produced by actually running the float
rewritten_onnx_t64_l128 graphs over representative assistant clauses (KO + EN, including the
spoken-number words SpokenTextNormalizer emits) across the bundled voice styles, exactly the way
the app builds inputs (TtsInputBuilder preprocess -> unicode_indexer -> dp-sized latent mask ->
K-step flow with xt feedback).

LAYOUT: snpe-dlc-quantize consumes raws in the DLC's RUNTIME input layout, which QAIRT produced
by transposing the last two axes of every multi-dim tensor vs ONNX (device-verified; see
tools/tts/prep_static_tts_inputs.py). Host onnxruntime runs use ONNX order; raws are transposed
on write. text_ids [1,64] int32 is layout-invariant.

Run inside the WSL qairt venv (needs onnxruntime):
  python3 converter/phase1/generate_representative_tts_calibration.py
Then quantize + graph-prepare with converter/phase1/quantize_tts_t64_l128.sh.
"""
import argparse
import json
import re
from pathlib import Path
from unicodedata import normalize

import numpy as np
import onnxruntime as ort

T = 64
LAT = 128
LDIM_CCF = 144           # latent_dim(24) * chunk_compress_factor(6)
SAMPLES_PER_FRAME = 3072  # base_chunk_size(512) * ccf(6) @ 44.1 kHz
SPEED = 1.05
K = 6                    # app default flow steps
VE_CAPTURE_STEPS = (0, 2, 5)  # early/mid/late timesteps per clause

# Representative app output: short acks, mid-length clauses near the segmenter's typical
# 22-45 chars, clock/number words as SpokenTextNormalizer renders them, KO and EN.
CLAUSES = [
    ("ko", "네, 알겠습니다."),
    ("ko", "안녕하세요, 만나서 반갑습니다."),
    ("ko", "오늘 서울의 날씨는 대체로 맑고 포근합니다."),
    ("ko", "지금 시각은 오후 세 시 삼십 분입니다."),
    ("ko", "타이머를 오 분으로 설정했어요."),
    ("ko", "백분율로는 이십오 퍼센트 정도 됩니다."),
    ("ko", "더 궁금한 점이 있으면 언제든지 말씀해 주세요."),
    ("en", "Sure, I can help with that."),
    ("en", "It is three thirty in the afternoon."),
    ("en", "The weather today is mostly sunny and warm."),
    ("en", "I set a timer for five minutes."),
    ("en", "Let me know if you need anything else."),
]
# Voice per clause (F1 weighted: it is the app default).
VOICES = ["F1", "M1", "F2", "M3", "F4", "M5", "F1", "F3", "M2", "F5", "M4", "F1"]


def preprocess(text, lang):
    t = normalize("NFKD", text)
    t = re.sub(r"\s+", " ", t).strip()
    if not re.search(r"[.!?;:,]$", t):
        t += "."
    return f"<{lang}>" + t + f"</{lang}>"


def to_dlc_layout(arr):
    """DLC runtime layout = ONNX with the last two axes transposed (multi-dim tensors only)."""
    if arr.ndim >= 3:
        return np.ascontiguousarray(np.swapaxes(arr, -1, -2))
    return np.ascontiguousarray(arr)


class Writer:
    def __init__(self, root: Path):
        self.root = root
        self.lists = {}   # component -> list of "name:=path ..." lines

    def add(self, component, sample_idx, tensors):
        d = self.root / component / f"sample_{sample_idx:03d}"
        d.mkdir(parents=True, exist_ok=True)
        entries = []
        for name, arr in tensors.items():
            p = d / f"{name}.raw"
            to_dlc_layout(arr).tofile(p)
            entries.append(f"{name}:={p.resolve()}")
        self.lists.setdefault(component, []).append(" ".join(entries))

    def finish(self):
        for component, lines in self.lists.items():
            (self.root / f"{component}_input_list.txt").write_text(
                "\n".join(lines) + "\n", encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--onnx-dir", default="models/artifacts/android-qairt/tts/rewritten_onnx_t64_l128")
    ap.add_argument("--assets-dir", default="models/original/tts/supertonic-3/onnx",
                    help="unicode_indexer.json location")
    ap.add_argument("--voices-dir", default="models/original/tts/supertonic-3/voice_styles")
    ap.add_argument("--output-root", default="models/artifacts/android-qairt/calibration/tts_t64_l128_rep")
    ap.add_argument("--seed", type=int, default=20260612)
    a = ap.parse_args()

    onnx_dir = Path(a.onnx_dir)
    out_root = Path(a.output_root)
    out_root.mkdir(parents=True, exist_ok=True)
    indexer = json.load(open(Path(a.assets_dir) / "unicode_indexer.json"))

    opts = ort.SessionOptions()
    opts.log_severity_level = 3
    sess = {}
    for c in ("duration_predictor", "text_encoder", "vector_estimator", "vocoder"):
        sess[c] = ort.InferenceSession(str(onnx_dir / f"{c}.onnx"),
                                       sess_options=opts, providers=["CPUExecutionProvider"])
        print(f"{c}: inputs={[(i.name, i.shape) for i in sess[c].get_inputs()]}", flush=True)

    voices = {}
    for name in set(VOICES):
        v = json.load(open(Path(a.voices_dir) / f"{name}.json"))
        voices[name] = {
            "style_ttl": np.array(v["style_ttl"]["data"], np.float32).reshape(1, 50, 256),
            "style_dp": np.array(v["style_dp"]["data"], np.float32).reshape(1, 8, 16),
        }

    w = Writer(out_root)
    rng = np.random.default_rng(a.seed)
    manifest = []
    ve_idx = 0

    for ci, ((lang, text), vname) in enumerate(zip(CLAUSES, VOICES)):
        s = preprocess(text, lang)
        ids = [indexer[ord(ch)] for ch in s]
        assert len(ids) <= T, f"clause too long for T={T}: {len(ids)}: {text!r}"
        true_len = len(ids)
        text_ids = np.zeros((1, T), np.int32)
        text_ids[0, :true_len] = ids
        text_mask = (np.arange(T) < true_len).astype(np.float32).reshape(1, 1, T)
        style_ttl = voices[vname]["style_ttl"]
        style_dp = voices[vname]["style_dp"]

        # The rewritten ONNX graphs keep int64 token ids; the DLC stores them as Int_32.
        # Host runs get int64, calibration raws get the int32 the DLC expects.
        text_ids_i64 = text_ids.astype(np.int64)

        # duration_predictor (static T=64 graph, padded inputs — same as on device)
        w.add("duration_predictor", ci,
              {"text_ids": text_ids, "style_dp": style_dp, "text_mask": text_mask})
        dur = sess["duration_predictor"].run(
            None, {"text_ids": text_ids_i64, "style_dp": style_dp, "text_mask": text_mask})[0]
        seconds = float(np.max(dur)) / SPEED
        frames = max(1, min(LAT, int(np.ceil(seconds * 44100.0 / SAMPLES_PER_FRAME))))

        latent_mask = (np.arange(LAT) < frames).astype(np.float32).reshape(1, 1, LAT)
        noisy = rng.standard_normal((1, LDIM_CCF, LAT)).astype(np.float32) * latent_mask

        # text_encoder
        w.add("text_encoder", ci,
              {"text_ids": text_ids, "style_ttl": style_ttl, "text_mask": text_mask})
        text_emb = sess["text_encoder"].run(
            None, {"text_ids": text_ids_i64, "style_ttl": style_ttl, "text_mask": text_mask})[0]

        # K-step flow matching with xt feedback; capture selected steps as VE samples.
        xt = noisy
        total_step = np.array([float(K)], np.float32)
        for k in range(K):
            ve_feed = {
                "noisy_latent": xt, "text_emb": text_emb, "style_ttl": style_ttl,
                "latent_mask": latent_mask, "text_mask": text_mask,
                "current_step": np.array([float(k)], np.float32), "total_step": total_step,
            }
            if k in VE_CAPTURE_STEPS:
                w.add("vector_estimator", ve_idx, ve_feed)
                ve_idx += 1
            xt = sess["vector_estimator"].run(None, ve_feed)[0]

        # vocoder on the final latent
        w.add("vocoder", ci, {"latent": xt})

        audio_secs = frames * SAMPLES_PER_FRAME / 44100.0
        manifest.append({"index": ci, "lang": lang, "text": text, "voice": vname,
                         "tokens": true_len, "dp_seconds": round(seconds, 3),
                         "latent_frames": frames, "audio_secs": round(audio_secs, 2)})
        print(f"[{ci}] {vname} {lang} tok={true_len} dur={seconds:.2f}s frames={frames}", flush=True)

    w.finish()
    (out_root / "representative_calibration_manifest.json").write_text(
        json.dumps({"kind": "representative_tts_calibration", "seed": a.seed, "K": K,
                    "ve_capture_steps": list(VE_CAPTURE_STEPS), "speed": SPEED,
                    "layout": "dlc(last-two-axes-transposed)", "clauses": manifest},
                   ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"done: {out_root} (dp/te/vo samples={len(CLAUSES)}, ve samples={ve_idx})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
