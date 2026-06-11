#!/usr/bin/env python3
"""KO ASR candidate eval v2: labeled clean CER + side-by-side transcripts of LIVE device captures.

Datasets:
  - _ko_testwavs/test_wavs + trans.txt  -> CER per model (ground truth)
  - cap_live/*.wav (pulled from the device after a real hands-free session) -> transcripts only
    (no refs; judge plausibility side by side)

Candidates: current Dolphin base, Dolphin small, SenseVoice(ko), Whisper small/medium,
Korean-specific zipformer (KsponSpeech-trained transducer).

Run (host venv): python tools/asr/eval_live.py
"""
import glob
import os
import sys
import time
import wave

import numpy as np
import sherpa_onnx

A = "models/original/asr"
ZIP = f"{A}/sherpa-onnx-zipformer-korean-2024-06-24"


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
    b["sensevoice_ko"] = lambda: sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=f"{A}/sherpa-onnx-sensevoice/model.int8.onnx",
        tokens=f"{A}/sherpa-onnx-sensevoice/tokens.txt", num_threads=4, language="ko", use_itn=True)
    b["whisper_small"] = lambda: sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=f"{A}/sherpa-onnx-whisper-small/small-encoder.int8.onnx",
        decoder=f"{A}/sherpa-onnx-whisper-small/small-decoder.int8.onnx",
        tokens=f"{A}/sherpa-onnx-whisper-small/small-tokens.txt", num_threads=4,
        language="ko", task="transcribe")
    b["whisper_medium"] = lambda: sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=f"{A}/sherpa-onnx-whisper-medium/medium-encoder.int8.onnx",
        decoder=f"{A}/sherpa-onnx-whisper-medium/medium-decoder.int8.onnx",
        tokens=f"{A}/sherpa-onnx-whisper-medium/medium-tokens.txt", num_threads=4,
        language="ko", task="transcribe")
    b["zipformer_ko"] = lambda: sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{ZIP}/encoder-epoch-99-avg-1.int8.onnx",
        decoder=f"{ZIP}/decoder-epoch-99-avg-1.int8.onnx",
        joiner=f"{ZIP}/joiner-epoch-99-avg-1.int8.onnx",
        tokens=f"{ZIP}/tokens.txt", num_threads=4)
    return b


def load_labeled():
    items = []
    tw = f"{A}/_ko_testwavs/test_wavs"
    with open(os.path.join(tw, "trans.txt"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                fn, _, ref = line.partition(" ")
                items.append((fn, os.path.join(tw, fn), ref.strip()))
    return items


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    labeled = load_labeled()
    live = sorted(glob.glob(f"{A}/cap_live/*.wav"))
    print(f"labeled: {len(labeled)} wavs / live captures: {len(live)}\n")
    summary = {}
    live_rows = {}
    for mname, build in builders().items():
        try:
            rec = build()
        except Exception as e:
            print(f"=== {mname} === BUILD ERROR: {e}\n")
            continue
        cers, mss = [], []
        for fn, wav, ref in labeled:
            sr, a = read_wav(wav)
            s = rec.create_stream(); s.accept_waveform(sr, a)
            t0 = time.time(); rec.decode_stream(s); ms = (time.time() - t0) * 1000
            cers.append(cer(ref, s.result.text)); mss.append(ms)
        outs = []
        for wav in live:
            sr, a = read_wav(wav)
            s = rec.create_stream(); s.accept_waveform(sr, a)
            rec.decode_stream(s)
            outs.append(s.result.text.strip())
        live_rows[mname] = outs
        summary[mname] = (sum(cers) / len(cers), sum(mss) / len(mss))
        print(f"=== {mname} ===  clean CER={summary[mname][0]:.3f}  mean {summary[mname][1]:.0f}ms")
        for i, t in enumerate(outs):
            print(f"  live utt_{i}: {t!r}")
        print()
    print("=== SUMMARY (clean CER, lower=better) ===")
    for mname, (c, ms) in sorted(summary.items(), key=lambda kv: kv[1][0]):
        print(f"  {mname:<16} CER={c:.3f}  {ms:>6.0f}ms")


if __name__ == "__main__":
    main()
