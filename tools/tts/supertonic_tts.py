#!/usr/bin/env python3
"""
Self-contained Supertonic-3 ONNX TTS pipeline (host reference).

Reimplements the documented inference pipeline from supertone-inc/supertonic (py/helper.py,
MIT sample code) to verify the local ONNX models produce correct audio, choose the flow-matching
step count K, and serve as the reference for the on-device port.
NOTE: Supertonic model WEIGHTS are OpenRAIL-M — review distribution terms before shipping.
NOTE: a Windows-side watcher has truncated/zeroed this file before. Run it via a /tmp copy
      (see tools/tts/run_host_tts.sh) so on-disk corruption can't break execution.

Pipeline: text -> UnicodeProcessor(text_ids,text_mask) -> duration_predictor -> text_encoder
          -> noisy latent (len from duration) -> K x vector_estimator -> vocoder -> 44.1kHz wav.
"""
import argparse, json, os, re, time, wave
from unicodedata import normalize
import numpy as np
import onnxruntime as ort

AVAILABLE_LANGS = ["en","ko","ja","ar","bg","cs","da","de","el","es","et","fi","fr","hi","hr",
                   "hu","id","it","lt","lv","nl","pl","pt","ro","ru","sk","sl","sv","tr","uk","vi","na"]


def length_to_mask(lengths, max_len=None):
    max_len = max_len or int(lengths.max())
    ids = np.arange(0, max_len)
    mask = (ids < np.expand_dims(lengths, 1)).astype(np.float32)
    return mask.reshape(-1, 1, max_len)


def get_latent_mask(wav_lengths, base_chunk_size, chunk_compress_factor):
    latent_size = base_chunk_size * chunk_compress_factor
    latent_lengths = (wav_lengths + latent_size - 1) // latent_size
    return length_to_mask(latent_lengths)


class UnicodeProcessor:
    def __init__(self, indexer_path):
        with open(indexer_path) as f:
            self.indexer = json.load(f)

    def _preprocess(self, text, lang):
        text = normalize("NFKD", text)
        repl = {"–":"-","‑":"-","—":"-","_":" ","“":'"',"”":'"',
                "‘":"'","’":"'","´":"'","`":"'","[":" ","]":" ","|":" ",
                "/":" ","#":" "}
        for k,v in repl.items():
            text = text.replace(k,v)
        text = re.sub(r"\s+"," ",text).strip()
        if not re.search(r"[.!?;:,]$", text):
            text += "."
        if lang not in AVAILABLE_LANGS:
            raise ValueError(f"Invalid language: {lang}")
        return f"<{lang}>" + text + f"</{lang}>"

    def __call__(self, text_list, lang_list):
        text_list = [self._preprocess(t,l) for t,l in zip(text_list, lang_list)]
        lengths = np.array([len(t) for t in text_list], dtype=np.int64)
        text_ids = np.zeros((len(text_list), int(lengths.max())), dtype=np.int64)
        for i,t in enumerate(text_list):
            vals = [ord(c) for c in t]
            text_ids[i,:len(vals)] = np.array([self.indexer[v] for v in vals], dtype=np.int64)
        return text_ids, length_to_mask(lengths)


def load_voice_style(path):
    with open(path) as f:
        s = json.load(f)
    ttl_flat = np.array(s["style_ttl"]["data"], dtype=np.float32).reshape(-1)
    dp_flat = np.array(s["style_dp"]["data"], dtype=np.float32).reshape(-1)
    return ttl_flat.reshape(1, 50, 256), dp_flat.reshape(1, 8, 16)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--onnx-dir", required=True)
    ap.add_argument("--voice", required=True)
    ap.add_argument("--text", required=True)
    ap.add_argument("--lang", default="ko")
    ap.add_argument("--total-step", type=int, default=6)
    ap.add_argument("--speed", type=float, default=1.05)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", default="/tmp/supertonic_out.wav")
    args = ap.parse_args()
    np.random.seed(args.seed)

    cfg = json.load(open(os.path.join(args.onnx_dir, "tts.json")))
    sr = cfg["ae"]["sample_rate"]; base_chunk = cfg["ae"]["base_chunk_size"]
    ccf = cfg["ttl"]["chunk_compress_factor"]; ldim = cfg["ttl"]["latent_dim"]

    opts = ort.SessionOptions()
    prov = ["CPUExecutionProvider"]
    def L(n): return ort.InferenceSession(os.path.join(args.onnx_dir, n), opts, providers=prov)
    dp_ort = L("duration_predictor.onnx"); te_ort = L("text_encoder.onnx")
    ve_ort = L("vector_estimator.onnx"); vo_ort = L("vocoder.onnx")

    tp = UnicodeProcessor(os.path.join(args.onnx_dir, "unicode_indexer.json"))
    ttl, dp = load_voice_style(args.voice)
    text_ids, text_mask = tp([args.text], [args.lang])

    dur, *_ = dp_ort.run(None, {"text_ids":text_ids, "style_dp":dp, "text_mask":text_mask})
    dur = dur / args.speed
    text_emb, *_ = te_ort.run(None, {"text_ids":text_ids, "style_ttl":ttl, "text_mask":text_mask})

    wav_len_max = dur.max() * sr
    wav_lengths = (dur * sr).astype(np.int64)
    chunk = base_chunk * ccf
    latent_len = int((wav_len_max + chunk - 1)//chunk)
    xt = np.random.randn(1, ldim*ccf, latent_len).astype(np.float32)
    latent_mask = get_latent_mask(wav_lengths, base_chunk, ccf)
    xt = xt * latent_mask
    K = args.total_step
    total_step_np = np.array([K], dtype=np.float32)
    t = time.time()
    for step in range(K):
        cur = np.array([step], dtype=np.float32)
        xt, *_ = ve_ort.run(None, {"noisy_latent":xt, "text_emb":text_emb, "style_ttl":ttl,
                                   "text_mask":text_mask, "latent_mask":latent_mask,
                                   "current_step":cur, "total_step":total_step_np})
    ve_ms = (time.time()-t)*1000
    wav, *_ = vo_ort.run(None, {"latent":xt})

    wav = np.asarray(wav).reshape(-1).astype(np.float32)
    peak = float(np.max(np.abs(wav))) if wav.size else 0.0
    rms = float(np.sqrt(np.mean(wav**2))) if wav.size else 0.0
    pcm16 = (np.clip(wav,-1,1)*32767).astype(np.int16)
    with wave.open(args.out,"wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm16.tobytes())
    print(json.dumps({"K":K,"audio_s":round(wav.size/sr,3),"latent_len":latent_len,
                      "ve_total_ms":round(ve_ms,1),"ve_per_step_ms":round(ve_ms/K,1),
                      "peak":round(peak,4),"rms":round(rms,4),"nonsilent":peak>0.01,
                      "out":args.out}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    raise SystemExit(main())
