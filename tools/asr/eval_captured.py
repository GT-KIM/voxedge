#!/usr/bin/env python3
"""Run the denoiser A/B + CER on REAL device-captured wavs (cap_dump/), which already contain real
background music + the real mic/AEC/NS path. The valid, representative measurement.

Usage (WSL, qairt venv):
  python tools/asr/eval_captured.py "<reference text>" [wav ...]
  default wav = models/original/asr/cap_dump/utt_0.wav
"""
import sys
import wave

import numpy as np
import sherpa_onnx

ASR = "models/original/asr/sherpa-onnx-sensevoice"
DEN = "models/original/asr/denoiser"


def read_wav(path):
    with wave.open(path, "rb") as w:
        sr, ch, n = w.getframerate(), w.getnchannels(), w.getnframes()
        a = np.frombuffer(w.readframes(n), dtype=np.int16).astype(np.float32) / 32768.0
    if ch == 2:
        a = a.reshape(-1, 2).mean(axis=1)
    return sr, a


def make_asr():
    return sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=f"{ASR}/model.int8.onnx", tokens=f"{ASR}/tokens.txt",
        num_threads=4, language="ko", use_itn=True)


def make_denoiser(kind):
    mc = sherpa_onnx.OfflineSpeechDenoiserModelConfig(num_threads=2, provider="cpu")
    if kind == "gtcrn":
        mc.gtcrn = sherpa_onnx.OfflineSpeechDenoiserGtcrnModelConfig(model=f"{DEN}/gtcrn_simple.onnx")
    elif kind == "dpdfnet2":
        mc.dpdfnet = sherpa_onnx.OfflineSpeechDenoiserDpdfNetModelConfig(model=f"{DEN}/dpdfnet2.onnx")
    elif kind == "dpdfnet_base":
        mc.dpdfnet = sherpa_onnx.OfflineSpeechDenoiserDpdfNetModelConfig(model=f"{DEN}/dpdfnet_baseline.onnx")
    return sherpa_onnx.OfflineSpeechDenoiser(sherpa_onnx.OfflineSpeechDenoiserConfig(model=mc))


def transcribe(asr, samples, sr):
    s = asr.create_stream(); s.accept_waveform(sr, samples); asr.decode_stream(s)
    return s.result.text


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


def main():
    ref = sys.argv[1] if len(sys.argv) > 1 else "한국어 음성인식 테스트"
    wavs = sys.argv[2:] or ["models/original/asr/cap_dump/utt_0.wav"]
    asr = make_asr()
    dens = {"raw": None, "gtcrn": make_denoiser("gtcrn"),
            "dpdfnet2": make_denoiser("dpdfnet2"), "dpdfnet_base": make_denoiser("dpdfnet_base")}
    print(f"reference: {ref!r}\n")
    for path in wavs:
        sr, a = read_wav(path)
        print(f"{path}  ({len(a)/sr:.1f}s)")
        for name, den in dens.items():
            if den is None:
                x, xsr = a, sr
            else:
                out = den(a, sr); x, xsr = np.array(out.samples, dtype=np.float32), out.sample_rate
            txt = transcribe(asr, x, xsr)
            print(f"  {name:<13} CER={cer(ref, txt):.2f}  -> {txt!r}")
        print()


if __name__ == "__main__":
    main()
