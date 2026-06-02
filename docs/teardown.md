# Running a fully-offline speech-to-speech AI agent on a Snapdragon phone — what it actually takes

*Airplane mode on. I speak; a 4-billion-parameter LLM answers out loud — speech recognition, the
language model, and text-to-speech all running locally on one Snapdragon phone, with no network at
any point. First audio lands ~0.6 s after my words are recognized (the LLM→TTS path; the full
speech-end→audio also includes the VAD endpoint and ASR).*

This is a teardown, not a product pitch. I wanted to know whether a *full* speech loop — ASR → LLM →
TTS, hands-free, low-latency — could run entirely on-device on a current mobile SoC, and what it
actually costs to get there. Below are the architecture, the measured numbers on a real device, the
bugs that ate days, and an honest table of what works and what doesn't. Code and the measurement
harness are in the repo.

I'm not trying to out-assistant Apple, Google, or OpenAI. The interesting, under-served space is a
developer-inspectable, end-to-end *offline* speech loop on real silicon — and the engineering reality
of making it run.

## The architecture

```
mic → VAD (endpoint) → ASR → LLM (token stream) → ClauseSegmenter → TTS → gapless playback
                                         └────────────── half-duplex ──────────────┘
```

The one idea that makes it feel real-time: **clause streaming**. You do not wait for the full LLM
answer, and you do not run TTS on the whole utterance. As the LLM streams tokens, a segmenter cuts
the stream into short clauses on sentence/clause boundaries and hands each clause to TTS immediately.
Clause *N* synthesizes while the LLM is still decoding clause *N+1* and the speaker is still playing
clause *N−1*. First audio therefore depends on *first clause*, not the whole answer.

All three engines stay resident in one app process: the LLM on the NPU/HTP (Qualcomm Genie), TTS also
on the HTP (SNPE), ASR on CPU (ONNX). They are loaded once and kept warm for the whole session — never
re-initialized per turn.

## The numbers (Galaxy Z Fold7 / Snapdragon SM8750, airplane mode)

| Stage | Measured |
|---|---|
| **Recognized text → first audio** (LLM→clause→TTS) | **~0.55–0.66 s** |
| LLM (Qwen3-4B, Genie w4a16, HTP) | TTFT ~65–90 ms · ~22 tok/s decode · ~1.18 GB · ~70 °C steady |
| TTS (Supertonic short-chunk, fp16, 6 flow steps) | ~220 ms / clause, resident |
| ASR decode (SenseVoice int8 / Dolphin CTC) | ~36 ms / ~27 ms |

Measured in-app with on-device timers, in airplane mode. Numbers are from this open project on this
specific device — not extrapolated, not a spec sheet.

## What ate the days (the parts worth reading)

The headline number hides the real work. A few that were instructive:

**1. The NPU wouldn't run inside a normal app.** On the bench (a shell binary) the HTP backend was
available; inside a packaged app it reported `isRuntimeAvailable = 0` and the engine build returned
null. It was *not* a missing CPU library. Three things together flipped it: declaring the vendor
native library in the manifest (`uses-native-library libcdsprpc.so` — Android 12+ requires apps to
request vendor public libs explicitly); copying the DSP skel libraries to a *real* directory at
runtime, because the FastRPC/DSP loader cannot see files inside the APK's assets; and assembling a
semicolon-separated `ADSP_LIBRARY_PATH` (plus the unsigned-PD platform option) *before* the runtime
loads. None of these are in the happy-path docs.

**2. The compiler silently transposed my tensors.** The on-device graph compiler reorders the last
two axes of every multi-dimensional input. Feed it data in the original framework's layout and it
runs without error but silently scrambles the conditioning tensors — speaker identity and prosody
degrade while the content mostly survives, so it *sounds* plausibly wrong. The subtle case: one style
tensor feeding the TTS duration predictor was being fed in the wrong layout, so the model
under-predicted duration and **truncated the end of every Korean sentence**. The fix was trivial once
found; finding it required comparing on-device output against a host reference number by number.

**3. Korean sentences got silently dropped.** The TTS front-end NFKD-normalizes text, which
decomposes each Hangul syllable into 2–3 jamo. The model has a fixed 64-token input shape. A clause
that looked like 25 characters became ~70 tokens after decomposition, blew the shape, threw an
exception — which the pipeline caught and *skipped*. Result: long Korean clauses occasionally
vanished from the spoken output with no error surfaced. Fix: cap clauses by their *NFKD-token* length,
not by raw character count. (English never hit this, because it doesn't decompose.)

**4. Measurement refuted my own plan — twice.** Background music wrecks the ASR. My first instinct
was "use a stronger denoiser." I built a CER harness, captured a *real* noisy utterance through the
actual mic path, and measured: the bigger neural denoiser made Korean **worse** (it damages final
consonants), and the small one didn't help at all. The actual win was switching the ASR *model*
per language — a Korean-tuned CTC model cut CER from 0.60 to 0.30 on that clip and ran faster — and
then **dropping the denoiser entirely**. Both of my initial bets were wrong, and only the harness
told me so before I shipped them. That harness is now the most valuable thing in the repo.

## What works, and what doesn't (the honest part)

| Capability | Status |
|---|---|
| Fully offline (airplane-mode) Korean/English voice loop | ✅ works, measured |
| Hands-free VAD turn-taking | ✅ works |
| ~0.6 s first audio (via clause streaming) | ✅ measured on SM8750 |
| On-device ASR + 4B LLM + TTS coexisting in one process | ✅ works |
| ASR under loud background music | ⚠️ improved (per-language model), not solved |
| LLM answer quality | ⚠️ 4B-class; prompt/history-tuned, not GPT-class |
| Barge-in (interrupt the assistant mid-answer) | 🧪 experimental, off by default |
| iOS | ❌ not built (on the roadmap) |
| Portability across SoCs | ❌ single SoC (SM8750); per-SoC graph prep required |

Two of these deserve a note. **Barge-in** — letting the user cut in while the assistant is talking —
turned out to be genuinely hard: keeping the mic open during playback plus the platform's acoustic
echo canceller still left enough residual that the assistant interrupted *itself*. I shipped it
off-by-default behind an experimental flag, and use a clean half-duplex separation (mic muted during
the turn + a short tail guard) that actually works. **Portability** is the honest ceiling of this
class of work: the models are compiled and graph-prepared for one specific SoC; moving to another
Snapdragon — let alone another vendor — is real per-device effort, not a recompile.

## What I'd try next

Better far-field robustness (the capture path and audio source matter more than the denoiser);
reference-signal echo cancellation so barge-in can be trusted; an iOS path via Core ML / MLX to test
the cross-platform story; and a portability layer so this isn't welded to one chip.

## Closing

The point of this project isn't a finished product — it's a measured, honest reference for what a
fully-offline speech loop costs on today's mobile silicon, with the war stories that the latency
number hides. If you work on on-device or edge AI, I'd love your eyes on it: the code, the numbers,
and the limitations are all in the repo, and issues/critiques are welcome.

*— I build on-device AI for a living (Staff Engineer, Mobile eXperience, Samsung; PhD in multimodal
human–AI interaction). All numbers here are from this personal, open project.*

🔗 Repo: github.com/GT-KIM/voxedge · Demo video: … · Contact / more: …
