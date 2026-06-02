#!/usr/bin/env bash
# Phase 1 on-device vertical-slice spike for Qwen3-4B-Instruct-2507 Genie w4a16 on SM8750.
# Measures: load time, TTFT, token rate, output, and (separately) peak memory + thermal.
# Run from repo root. Requires an SM8750 device attached to adb.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"

ADB="${ADB:-adb}"   # or set ADB to your platform-tools/adb path
QAIRT="tools/model_compile/qairt/extracted/qairt/2.46.0.260424"
ANDROID_LIB="$QAIRT/lib/aarch64-android"
DSP_LIB="$QAIRT/lib/hexagon-v79/unsigned"
BIN="$QAIRT/bin/aarch64-android/genie-t2t-run"
BUNDLE="models/artifacts/android-qairt/llm/qwen3-4b-instruct-2507/extracted/qwen3_4b_instruct_2507-genie-w4a16-qualcomm_snapdragon_8_elite"
LIBCXX="${ANDROID_NDK:?set ANDROID_NDK}/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"

DEV="/data/local/tmp/qwen3_spike"
LOGDIR="models/artifacts/android-qairt/logs"
mkdir -p "$LOGDIR"

CPU_LIBS=(libGenie.so libQnnHtp.so libQnnSystem.so libQnnHtpV79Stub.so \
          libQnnHtpV79CalculatorStub.so libQnnHtpPrepare.so libQnnHtpNetRunExtensions.so)
DSP_LIBS=(libQnnHtpV79.so libQnnHtpV79Skel.so)

stage() { echo "=== $* ==="; }

stage "device"
"$ADB" shell getprop ro.soc.model
"$ADB" shell mkdir -p "$DEV/lib" "$DEV/dsp" "$DEV/bundle"

stage "push binary + cpu libs + libc++_shared"
"$ADB" push "$BIN" "$DEV/" >/dev/null
"$ADB" push "$LIBCXX" "$DEV/lib/" >/dev/null
for f in "${CPU_LIBS[@]}"; do "$ADB" push "$ANDROID_LIB/$f" "$DEV/lib/" >/dev/null; done

stage "push dsp skel libs"
for f in "${DSP_LIBS[@]}"; do "$ADB" push "$DSP_LIB/$f" "$DEV/dsp/" >/dev/null; done

stage "push bundle (~3GB, this takes a while)"
"$ADB" push "$BUNDLE/." "$DEV/bundle/" >/dev/null
"$ADB" shell chmod 755 "$DEV/genie-t2t-run"

stage "thermal before"
"$ADB" shell "for z in /sys/class/thermal/thermal_zone*/temp; do cat \$z 2>/dev/null; done | sort -nr | head -3" || true

stage "run genie-t2t-run (profiled)"
PROMPT="$(cat "$BUNDLE/sample_prompt.txt")"
RUN="cd $DEV/bundle && \
export LD_LIBRARY_PATH=$DEV/lib:\$LD_LIBRARY_PATH && \
export ADSP_LIBRARY_PATH=$DEV/dsp && \
$DEV/genie-t2t-run -c genie_config.json -p '$PROMPT'"
echo "--- command ---"; echo "$RUN"
echo "--- output ---"
time "$ADB" shell "$RUN" 2>&1 | tee "$LOGDIR/qwen3_genie_device_spike_$(date +%Y%m%d_%H%M%S).log"

stage "thermal after"
"$ADB" shell "for z in /sys/class/thermal/thermal_zone*/temp; do cat \$z 2>/dev/null; done | sort -nr | head -3" || true
