# Phase 0 Download Report

Date: 2026-05-27

## Downloaded sources

| Component | Model id | Local path | Files | Size excluding HF cache |
| --- | --- | --- | ---: | ---: |
| LLM | `google/gemma-4-E2B-it` | `models/original/llm/google__gemma-4-E2B-it` | 9 | 10,278,846,104 bytes |
| TTS | `Supertone/supertonic-3` | `models/original/tts/supertonic-3` | 38 | 414,728,474 bytes |

## Notes

- Downloads were performed with the Hugging Face CLI from the project-local Windows `.venv` because WSL is not installed in this desktop sandbox.
- Future model compile steps should still use the user's WSL environment as required by the project instructions.
- Checksums for downloaded source files are recorded in `converter/phase0/checksums.sha256`.
- Hugging Face `.cache` metadata files are excluded from the file counts, byte totals, and checksum manifest.
