#!/usr/bin/env bash
# Cross-compile the resident Supertonic TTS engine for android arm64 (NDK clang++ + libSNPE).
# This NDK ships only generic clang++.exe (no per-API aarch64-linux-android34-clang++.exe), so use
# an explicit --target. Most reliable from PowerShell (see equivalent below).
# Verified 2026-05-30: exit 0, ELF aarch64 PIE ~105 KB; runs on SM8750.
set -euo pipefail
# Configure for your environment:
ROOT="${PROJECT_ROOT:-$(cd "$(dirname "$0")/../../../.." && pwd)}"
ANDROID_NDK="${ANDROID_NDK:?set ANDROID_NDK to your NDK r30 path}"
NDK_BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
CLANG="$NDK_BIN/clang++.exe"
TARGET="--target=aarch64-linux-android34"
QAIRT="$ROOT/tools/model_compile/qairt/extracted/qairt/2.46.0.260424"
INC="$QAIRT/include/SNPE"
LIB="$QAIRT/lib/aarch64-android"
SRC="$ROOT/apps/android/native/tts_engine/supertonic_tts_engine.cpp"
OUTDIR="$ROOT/apps/android/native/tts_engine/build"
mkdir -p "$OUTDIR"

"$CLANG" $TARGET -std=c++17 -O2 -fPIE -pie \
  -I"$INC" "$SRC" \
  -L"$LIB" -lSNPE \
  -o "$OUTDIR/supertonic_tts_engine"
echo "built: $OUTDIR/supertonic_tts_engine"

# PowerShell equivalent:
#   $clang="$env:ANDROID_NDK\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe"
#   $INC="<QAIRT>\include\SNPE"; $LIB="<QAIRT>\lib\aarch64-android"
#   & $clang --target=aarch64-linux-android34 -std=c++17 -O2 -fPIE -pie "-I$INC" <SRC> "-L$LIB" -lSNPE -o <OUT>
#
# Device run (output via logfile; inline output unreliable on this env):
#   adb shell "cd /data/local/tmp/tts_spike && LD_LIBRARY_PATH=./lib ADSP_LIBRARY_PATH=./dsp \
#     ./supertonic_tts_engine dlc chain_in engine_audio.raw 6 3 > engine_run.log 2>&1"
# Device deps in /data/local/tmp/tts_spike:
#   lib/{libSNPE.so,libSnpeHtpV79Stub.so,libSnpeHtpV79CalculatorStub.so,libSnpeHtpPrepare.so,
#        libPlatformValidatorShared.so,libc++_shared.so}, dsp/libSnpeHtpV79Skel.so,
#   dlc/{text_encoder,vector_estimator,vocoder}.dlc, chain_in/*.raw
