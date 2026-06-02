#!/usr/bin/env python3
"""Quality eval harness: measure ASR robustness under background music and the effect of
each speech denoiser, by CER — so denoiser choice is data-driven, not eyeballed.

For each KO test wav x each SNR, mix speech + a synthetic music-like masker, then transcribe with
SenseVoice under each front-end {raw, GTCRN, DPDFNet2, DPDFNet-baseline} and report CER vs reference.
Host (x86) only — relative CER deltas transfer; absolute device latency is measured separately.

Music masker is SYNTHETIC (seeded chord progression + light percussion) — a controllable proxy, not
real music; note this when reading results.

Run (WSL, qairt venv): python tools/asr/eval_asr_noise.py
"""
import math
import wave

import numpy as np
import sherpa_onnx

ASR = "models/original/asr/sherpa-onnx-sensevoice"
DEN = "models/original/asr/denoiser"
KO = "models/original/asr/_ko_testwavs/test_wavs"
SNRS_DB = [None, 15, 10, 5, 0]   # None = clean (no music)


# ---------- io ----------
def read_wav(path):
    with wave.open(path, "rb") as w:
        sr, ch, n = w.getframerate(), w.getnchannels(), w.getnframes()
        a = np.frombuffer(w.readframes(n), dtype=np.int16).astype(np.float32) / 32768.0
    if ch == 2:
        a = a.reshape(-1, 2).mean(axis=1)
    return sr, a


def load_refs():
    refs = {}
    with open(f"{KO}/trans.txt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            name, _, text = line.partition(" ")
            refs[name] = text.strip()
    return refs


# ---------- synthetic music masker ----------
def music_like(n, sr, seed=0):
    rng = np.random.default_rng(seed)
    t = np.arange(n) / sr
    out = np.zeros(n, dtype=np.float32)
    # a little chord progression (root midi notes), each held ~0.8 s, looped
    prog = [57, 60, 64, 62]  # A3 C4 E4 D4
    dur = int(0.8 * sr)
    for i, start in enumerate(range(0, n, dur)):
        end = min(start + dur, n)
        seg_t = t[start:end]
        root = prog[i % len(prog)]
        for semi in (0, 4, 7, 12):                       # triad + octave
            f = 440.0 * 2 ** ((root + semi - 69) / 12.0)
            vib = 1.0 + 0.004 * np.sin(2 * math.pi * 5 * seg_t)   # slight vibrato
            partial = np.sin(2 * math.pi * f * seg_t * vib)
            partial += 0.3 * np.sin(2 * math.pi * 2 * f * seg_t)  # 2nd harmonic
            env = np.minimum(1.0, (seg_t - seg_t[0]) * 8) * np.exp(-(seg_t - seg_t[0]) * 0.6)
            out[start:end] += (partial * env).astype(np.float32)
    # light percussion: filtered noise bursts every 0.4 s
    for start in range(0, n, int(0.4 * sr)):
        end = min(start + int(0.05 * sr), n)
        out[start:end] += 0.5 * rng.standard_normal(end - start).astype(np.float32) \
            * np.exp(-np.arange(end - start) / (0.01 * sr))
    return out


def mix_at_snr(speech, music, snr_db):
    ps = float(np.mean(speech ** 2)) + 1e-9
    pm = float(np.mean(music ** 2)) + 1e-9
    g = math.sqrt(ps / (pm * (10 ** (snr_db / 10.0))))
    y = speech + g * music
    peak = float(np.max(np.abs(y))) + 1e-9
    if peak > 1.0:
        y = y / peak
    return y.astype(np.float32)


# ---------- engines ----------
def make_asr():
    return sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=f"{ASR}/model.int8.onnx", tokens=f"{ASR}/tokens.txt",
        num_threads=4, language="ko", use_itn=True,
    )


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
    s = asr.create_stream()
    s.accept_waveform(sr, samples)
    asr.decode_stream(s)
    return s.result.text


def denoise(den, samples, sr):
    out = den(samples, sr)
    return np.array(out.samples, dtype=np.float32), out.sample_rate


# ---------- CER ----------
def cer(ref, hyp):
    r = ref.replace(" ", "")
    h = hyp.replace(" ", "")
    if not r:
        return 0.0
    dp = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev = dp[0]; dp[0] = i
        for j in range(1, len(h) + 1):
            cur = dp[j]
            dp[j] = min(dp[j] + 1, dp[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev = cur
    return dp[len(h)] / len(r)


def main():
    refs = load_refs()
    asr = make_asr()
    methods = {
        "raw": None,
        "gtcrn": make_denoiser("gtcrn"),
        "dpdfnet2": make_denoiser("dpdfnet2"),
        "dpdfnet_base": make_denoiser("dpdfnet_base"),
    }
    wavs = sorted(k for k in refs if k.endswith(".wav"))

    # accumulate mean CER per (snr, method)
    agg = {}
    print(f"{'wav':<7}{'snr':>5}  " + "".join(f"{m:>14}" for m in methods))
    for name in wavs:
        sr, speech = read_wav(f"{KO}/{name}")
        music = music_like(len(speech), sr, seed=1)
        for snr in SNRS_DB:
            mix = speech if snr is None else mix_at_snr(speech, music, snr)
            row = []
            for m, den in methods.items():
                x, xsr = (mix, sr) if den is None else denoise(den, mix, sr)
                c = cer(refs[name], transcribe(asr, x, xsr))
                row.append(c)
                agg.setdefault((snr, m), []).append(c)
            snr_s = "clean" if snr is None else f"{snr}dB"
            print(f"{name:<7}{snr_s:>5}  " + "".join(f"{c:>13.2f} " for c in row))

    print("\n=== mean CER by SNR x method ===")
    print(f"{'snr':>6}  " + "".join(f"{m:>14}" for m in methods))
    for snr in SNRS_DB:
        snr_s = "clean" if snr is None else f"{snr}dB"
        print(f"{snr_s:>6}  " + "".join(
            f"{np.mean(agg[(snr, m)]):>13.2f} " for m in methods))


if __name__ == "__main__":
    main()
