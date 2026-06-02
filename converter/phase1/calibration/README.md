# Calibration Inputs

INT8 quantization requires representative input data for each DLC.

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
