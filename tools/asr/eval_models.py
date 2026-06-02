#!/usr/bin/env python3
"""Compare ASR models on the SAME real captured wav (music + mic path): does a stronger/newer model
recover the signal SenseVoice-int8 + Whisper-small failed on? CER + decode latency. Test a stronger
ASR as an upper bound — if even Whisper-medium fails, it's the signal/capture, not the model.

Run (WSL, qairt venv): python tools/asr/eval_models.py ["<ref>"] [wav]
"""
import sys
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
    b["sensevoice_int8"] = lambda: sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=f"{A}/sherpa-onnx-sensevoice/model.int8.onnx",
        tokens=f"{A}/sherpa-onnx-sensevoice/tokens.txt", num_threads=4, language="ko", use_itn=True)
    b["whisper_small"] = lambda: sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=f"{A}/sherpa-onnx-whisper-small/small-encoder.int8.onnx",
        decoder=f"{A}/sherpa-onnx-whisper-small/small-decoder.int8.onnx",
        tokens=f"{A}/sherpa-onnx-whisper-small/small-tokens.txt", num_threads=4, language="ko", task="transcribe")
    b["whisper_medium"] = lambda: sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=f"{A}/sherpa-onnx-whisper-medium/medium-encoder.int8.onnx",
        decoder=f"{A}/sherpa-onnx-whisper-medium/medium-decoder.int8.onnx",
        tokens=f"{A}/sherpa-onnx-whisper-medium/medium-tokens.txt", num_threads=4, language="ko", task="transcribe")
    b["dolphin_ctc"] = lambda: sherpa_onnx.OfflineRecognizer.from_dolphin_ctc(
        model=f"{A}/sherpa-onnx-dolphin/model.int8.onnx",
        tokens=f"{A}/sherpa-onnx-dolphin/tokens.txt", num_threads=4)
    return b


def main():
    ref = sys.argv[1] if len(sys.argv) > 1 else "한국어 음성인식 테스트"
    wav = sys.argv[2] if len(sys.argv) > 2 else f"{A}/cap_dump/utt_0.wav"
    sr, a = read_wav(wav)
    print(f"reference: {ref!r}\nwav: {wav} ({len(a)/sr:.1f}s)\n")
    for name, build in builders().items():
        try:
            rec = build()
            s = rec.create_stream(); s.accept_waveform(sr, a)
            t0 = time.time(); rec.decode_stream(s); ms = (time.time() - t0) * 1000
            txt = s.result.text
            print(f"  {name:<16} CER={cer(ref, txt):.2f}  {ms:>6.0f}ms  -> {txt!r}")
        except Exception as e:
            print(f"  {name:<16} ERROR: {e}")


if __name__ == "__main__":
    main()
