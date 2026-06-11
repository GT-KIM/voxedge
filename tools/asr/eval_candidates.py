#!/usr/bin/env python3
"""Benchmark candidate ASR models on the KO test set to decide a model swap BY DATA.

Dataset = the clean trans.txt wavs (_ko_testwavs) + the real noisy capture (cap_dump/utt_0.wav).
Per model: per-wav CER + decode latency, then mean / clean-only / noisy-only CER. References come
from trans.txt (so no Korean has to be passed through the shell).

Run (WSL, qairt venv): python tools/asr/eval_candidates.py
"""
import os
import time
import wave

import numpy as np
import sherpa_onnx

A = "models/original/asr"


def read_wav(path):
    with wave.open(path, "rb") as w:
        sr, ch, n = w.getframerate(), w.getnchannels(), w.getnframes()
        a = np.frombuffer(w.readframes(n), dtype=np.int16).astype(np.float32) / 32768.0
    if ch == 2:
        a = a.reshape(-1, 2).mean(axis=1)
    return sr, a


def cer(ref, hyp):
    r = ref.replace(" ", ""); h = hyp.replace(" ", "")
    if not r:
        return 0.0
    dp = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev = dp[0]; dp[0] = i
        for j in range(1, len(h) + 1):
            cur = dp[j]; dp[j] = min(dp[j] + 1, dp[j - 1] + 1, prev + (r[i - 1] != h[j - 1])); prev = cur
    return dp[len(h)] / len(r)


def builders():
    b = {}
    b["dolphin_base"] = lambda: sherpa_onnx.OfflineRecognizer.from_dolphin_ctc(
        model=f"{A}/sherpa-onnx-dolphin/model.int8.onnx",
        tokens=f"{A}/sherpa-onnx-dolphin/tokens.txt", num_threads=4)
    b["dolphin_small"] = lambda: sherpa_onnx.OfflineRecognizer.from_dolphin_ctc(
        model=f"{A}/sherpa-onnx-dolphin-small/model.int8.onnx",
        tokens=f"{A}/sherpa-onnx-dolphin-small/tokens.txt", num_threads=4)
    b["sensevoice"] = lambda: sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=f"{A}/sherpa-onnx-sensevoice/model.int8.onnx",
        tokens=f"{A}/sherpa-onnx-sensevoice/tokens.txt", num_threads=4, language="ko", use_itn=True)
    b["whisper_medium"] = lambda: sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=f"{A}/sherpa-onnx-whisper-medium/medium-encoder.int8.onnx",
        decoder=f"{A}/sherpa-onnx-whisper-medium/medium-decoder.int8.onnx",
        tokens=f"{A}/sherpa-onnx-whisper-medium/medium-tokens.txt", num_threads=4,
        language="ko", task="transcribe")
    return b


def load_dataset():
    items = []  # (name, wav_path, ref)
    tw = f"{A}/_ko_testwavs/test_wavs"
    trans = os.path.join(tw, "trans.txt")
    if os.path.isfile(trans):
        with open(trans, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                fn, _, ref = line.partition(" ")
                items.append((f"clean/{fn}", os.path.join(tw, fn), ref.strip()))
    cap = f"{A}/cap_dump/utt_0.wav"
    if os.path.isfile(cap):
        items.append(("noisy/utt_0", cap, "한국어 음성인식 테스트"))
    return items


def mean(xs):
    return sum(xs) / len(xs) if xs else float("nan")


def main():
    data = load_dataset()
    nclean = sum(1 for n, _, _ in data if n.startswith("clean"))
    nnoisy = sum(1 for n, _, _ in data if n.startswith("noisy"))
    print(f"dataset: {len(data)} wavs ({nclean} clean + {nnoisy} noisy)\n")
    results = {}
    for mname, build in builders().items():
        try:
            rec = build()
        except Exception as e:
            print(f"=== {mname} ===\n  BUILD ERROR: {e}\n")
            continue
        print(f"=== {mname} ===")
        pairs, mss = [], []
        for dname, wav, ref in data:
            try:
                sr, a = read_wav(wav)
                s = rec.create_stream(); s.accept_waveform(sr, a)
                t0 = time.time(); rec.decode_stream(s); ms = (time.time() - t0) * 1000
                txt = s.result.text
                c = cer(ref, txt)
                pairs.append((dname, c)); mss.append(ms)
                print(f"  {dname:<14} CER={c:.2f} {ms:>7.0f}ms -> {txt!r}")
            except Exception as e:
                print(f"  {dname:<14} ERROR: {e}")
        if pairs:
            allc = [c for _, c in pairs]
            clean = [c for n, c in pairs if n.startswith("clean")]
            noisy = [c for n, c in pairs if n.startswith("noisy")]
            results[mname] = (mean(allc), mean(clean), mean(noisy), mean(mss))
            print(f"  -> all={mean(allc):.3f}  clean={mean(clean):.3f}  noisy={mean(noisy):.3f}  "
                  f"mean {mean(mss):.0f}ms\n")
    print("=== SUMMARY (CER lower=better; sorted by clean) ===")
    print(f"  {'model':<16} {'all':>6} {'clean':>6} {'noisy':>6} {'ms':>7}")
    for mname, (mc, cc, nc, mms) in sorted(results.items(), key=lambda kv: kv[1][1]):
        print(f"  {mname:<16} {mc:>6.3f} {cc:>6.3f} {nc:>6.3f} {mms:>7.0f}")


if __name__ == "__main__":
    main()
