#!/usr/bin/env python3
"""Host (x86) accuracy smoke test for the owned offline ASR candidate: Whisper-small int8 via
sherpa-onnx. Decodes EN + KO test wavs and prints transcript + elapsed, to confirm KO+EN quality
BEFORE committing to on-device integration. Host latency is NOT representative of the SM8750 device
(that gets measured in-app via the prebuilt AAR); this validates the MODEL + decode path only.

Run (WSL, qairt venv): python tools/asr/host_whisper_test.py <wav> [<wav> ...]
"""
import sys
import time
import wave

import numpy as np
import sherpa_onnx

M = "models/original/asr/sherpa-onnx-whisper-small"


def build():
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=f"{M}/small-encoder.int8.onnx",
        decoder=f"{M}/small-decoder.int8.onnx",
        tokens=f"{M}/small-tokens.txt",
        num_threads=4,
        language="",      # auto-detect language
        task="transcribe",
    )


def read_wav(path):
    with wave.open(path, "rb") as w:
        sr = w.getframerate()
        ch = w.getnchannels()
        data = w.readframes(w.getnframes())
    a = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
    if ch == 2:
        a = a.reshape(-1, 2).mean(axis=1)
    return sr, a


def main():
    rec = build()
    for f in sys.argv[1:]:
        sr, a = read_wav(f)
        t0 = time.time()
        s = rec.create_stream()
        s.accept_waveform(sr, a)
        rec.decode_stream(s)
        dt = (time.time() - t0) * 1000.0
        print(f"{f}\n   ({len(a)/sr:.1f}s audio, {dt:.0f} ms decode) -> {s.result.text!r}\n")


if __name__ == "__main__":
    main()
