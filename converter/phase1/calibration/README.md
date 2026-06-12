# Calibration Inputs

INT8 quantization requires representative input data for each DLC.

## VERDICT (2026-06-12): TTS quantization REJECTED — ship FP16

Representative calibration was built and tried (see below); quantization still audibly
destroys the short-chunk Supertonic graphs. The shipping configuration remains the FP16
graph-prepared set (`models/artifacts/android-qairt/tts/prepared_t64_l128`). Findings:

| component          | INT8 (a8w8)            | W8A16 + enhanced quantizers          |
|--------------------|------------------------|--------------------------------------|
| duration_predictor | constant 8.066 s output | cosine 1.0 but scale 1.65-1.86x      |
| text_encoder       | broken                 | cosine ~0 vs float — destroyed       |
| vector_estimator   | broken                 | cosine 0.93-0.99 (best survivor)     |
| vocoder            | broken                 | cosine ~0.5 — destroyed              |

Even the mixed set (vector_estimator W8A16, everything else FP16) was audibly garbled on
device ("완전히 뭉개짐") despite host cosine 0.93-0.99 — K=6 flow steps feed the output back
into the input, so per-step quantization error compounds. `--use_per_channel_quantization`
hard-fails on Supertonic (bias-less Conv).

Reusable pipeline from this attempt:

- `converter/phase1/generate_representative_tts_calibration.py` — 12 representative KO/EN
  clauses x 10 voices, real intermediate activations via host onnxruntime, raws in the
  device-verified DLC layout (last two axes transposed).
- `converter/phase1/quantize_tts_t64_l128.sh` — quantize + SM8750 graph-prepare with
  `QUANT_TAG` / `QUANT_ARGS` precision knobs.
- `converter/phase1/validate_quantized_tts.py` — host-side cosine/scale check
  (snpe-net-run CPU vs float onnxruntime). RUN THIS BEFORE ANY DEVICE PROVISIONING;
  it catches every failure above without a device cycle.

## TTS components

The Supertonic 3 ONNX files are split into:

- `duration_predictor.onnx`
- `text_encoder.onnx`
- `vector_estimator.onnx`
- `vocoder.onnx`

Create one SNPE input list per component under:

```text
models/artifacts/android-qairt/calibration/tts/
```

Expected filenames:

```text
duration_predictor_input_list.txt
text_encoder_input_list.txt
vector_estimator_input_list.txt
vocoder_input_list.txt
```

Each input list must reference raw tensors compatible with the corresponding ONNX model inputs. Generate them from representative short assistant responses, target voices from `voice_styles/`, and audio lengths typical of the final app.

For pipeline validation only, random calibration inputs can be generated with:

```bash
python3 converter/phase1/generate_random_tts_calibration.py
```

Do not treat the random calibration outputs as accuracy-grade quantization data.

The current default conversion profile is:

- Batch size: `1`
- Text length: `256`
- Latent length: `1000`

## LLM

LLM calibration is blocked until the Gemma 4 E2B export artifact is selected and generated.

Representative prompts should include:

- Short conversational questions.
- Follow-up turns using prior context.
- ASR-like transcripts with punctuation and casing variability.
- Korean and English examples if both are product targets.
