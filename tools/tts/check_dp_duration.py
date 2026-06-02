#!/usr/bin/env python3
"""One-off: host ground-truth duration_predictor for a given clause, to validate the on-device dp.
Uses the SAME assets the Android app ships (app indexer + F1.json) and the original dp.onnx.
Prints predicted duration + the activeFrames the device formula would yield."""
import json, re, sys
from unicodedata import normalize
import numpy as np
import onnxruntime as ort

APP = "apps/android/app/src/main/assets/tts"
DP_ONNX = "models/original/tts/supertonic-3/onnx/duration_predictor.onnx"
SPEED = 1.05
SR = 44100
CHUNK = 3072  # base_chunk * ccf


def preprocess(text, lang):
    text = normalize("NFKD", text)
    text = re.sub(r"\s+", " ", text).strip()
    if not re.search(r"[.!?;:,]$", text):
        text += "."
    return f"<{lang}>" + text + f"</{lang}>"


def flatten(x, out):
    if isinstance(x, list):
        for e in x:
            flatten(e, out)
    else:
        out.append(float(x))


text = sys.argv[1] if len(sys.argv) > 1 else "안녕하세요. 만나서 반갑습니다."
lang = sys.argv[2] if len(sys.argv) > 2 else "ko"

indexer = json.load(open(f"{APP}/unicode_indexer.json"))
voice = json.load(open(f"{APP}/F1.json"))
sd = []
flatten(voice["style_dp"]["data"], sd)
style_dp = np.array(sd, dtype=np.float32).reshape(1, 8, 16)

s = preprocess(text, lang)
ids = [indexer[ord(c)] for c in s]
true_len = len(ids)
text_ids = np.zeros((1, true_len), dtype=np.int64)
text_ids[0] = np.array(ids, dtype=np.int64)
text_mask = np.ones((1, 1, true_len), dtype=np.float32)

dp = ort.InferenceSession(DP_ONNX, providers=["CPUExecutionProvider"])
# also try the transposed style_dp to see how much it would change the prediction
dur, *_ = dp.run(None, {"text_ids": text_ids, "style_dp": style_dp, "text_mask": text_mask})
dur_v = float(np.array(dur).reshape(-1)[0])

sd_T = np.ascontiguousarray(np.transpose(style_dp, (0, 2, 1)))  # [1,16,8]
try:
    durT, *_ = dp.run(None, {"text_ids": text_ids, "style_dp": sd_T.reshape(1, 8, 16),
                             "text_mask": text_mask})
    durT_v = float(np.array(durT).reshape(-1)[0])
except Exception as e:
    durT_v = None


def frames(d):
    sec = d / SPEED
    import math
    f = math.ceil(sec * SR / CHUNK)
    return max(1, min(128, f))


print(f"text={text!r} true_len={true_len}")
print(f"[onnx order 1,8,16]  duration={dur_v:.3f}s  -> activeFrames={frames(dur_v)}")
if durT_v is not None:
    print(f"[transposed 1,16,8]  duration={durT_v:.3f}s  -> activeFrames={frames(durT_v)}")
