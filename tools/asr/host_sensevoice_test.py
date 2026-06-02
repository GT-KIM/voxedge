#!/usr/bin/env python3
"""Host (x86) accuracy smoke test for SenseVoice int8 (KO+EN+...) via sherpa-onnx — the bilingual,
also-chunked alternative to Whisper-small. Same posture (non-streaming) but smaller/faster; this
checks whether its Korean beats Whisper-small's (which dropped syllables). Host latency only.

Run (WSL, qairt venv): python tools/asr/host_sensevoice_test.py <wav> [<wav> ...]
"""
import sys
import time
import wave

import numpy as np
import sherpa_onnx

M = "models/original/asr/sherpa-onnx-sensevoice"


def build():
    return sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=f"{M}/model.int8.onnx",
        tokens=f"{M}/tokens.txt",
        num_threads=4,
        language="auto",
        use_itn=True,
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
