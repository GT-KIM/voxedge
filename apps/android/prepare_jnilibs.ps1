# Bundle QAIRT runtime for the app. Two destinations (per the FastRPC/DSP loader mechanism):
#  - jniLibs/arm64-v8a/  : ARM/app-side .so loaded into the app process (libSNPE, QNN/SNPE stubs,
#                          libQnnHtp, prepare, system, validator, libc++_shared, + our engine).
#  - assets/qairt_dsp/   : the EXACT hexagon-v79/unsigned/* (skels + .cat catalogs + deps). These
#                          are copied at runtime to filesDir/qairt_dsp (a real dir the DSP FastRPC
#                          loader can read; APK assets are invisible to it) and put first on
#                          ADSP_LIBRARY_PATH. SM8750 = DSP v79 — do NOT use v81 here.
$ErrorActionPreference = "Stop"
# Repo root = two levels up from this script (apps/android/). Override QAIRT_DIR / ANDROID_NDK as needed.
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$Q = if ($env:QAIRT_DIR) { $env:QAIRT_DIR } else { "$root\tools\model_compile\qairt\extracted\qairt\2.46.0.260424" }
$AL = "$Q\lib\aarch64-android"
$V79 = "$Q\lib\hexagon-v79\unsigned"
$ndk = if ($env:ANDROID_NDK) { $env:ANDROID_NDK } else { throw "set ANDROID_NDK to your NDK r30 path" }
$CXX = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so"
$jni = "$root\apps\android\app\src\main\jniLibs\arm64-v8a"
$dsp = "$root\apps\android\app\src\main\assets\qairt_dsp"

Remove-Item "$jni\*" -Recurse -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $jni | Out-Null
# also clear the old assets/dsp (v81 .cat) if present
Remove-Item "$root\apps\android\app\src\main\assets\dsp" -Recurse -ErrorAction SilentlyContinue
Remove-Item "$dsp\*" -Recurse -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $dsp | Out-Null

# ARM/app side (jniLibs): SNPE + QNN HTP backend (v79) + prepare/system/validator,
# plus the Genie LLM runtime (libGenie + HTP net-run extensions) for the Qwen3 dialog engine.
$cpu = @(
    'libSNPE.so','libPlatformValidatorShared.so',
    'libQnnHtp.so','libQnnHtpPrepare.so','libQnnSystem.so',
    'libQnnHtpV79Stub.so','libQnnHtpV79CalculatorStub.so',
    'libSnpeHtpV79Stub.so','libSnpeHtpV79CalculatorStub.so','libSnpeHtpPrepare.so',
    'libGenie.so','libQnnHtpNetRunExtensions.so'
)
foreach ($f in $cpu) { if (Test-Path "$AL\$f") { Copy-Item "$AL\$f" "$jni\$f" -Force } else { Write-Host "MISSING(arm) $f" } }
Copy-Item $CXX "$jni\libc++_shared.so" -Force

# DSP side (assets -> runtime filesDir): the WHOLE hexagon-v79/unsigned dir (skels + .cat + deps).
Get-ChildItem "$V79\*" -File | ForEach-Object { Copy-Item $_.FullName "$dsp\$($_.Name)" -Force }

Write-Host "jniLibs/arm64-v8a ($((Get-ChildItem $jni -Filter *.so).Count) .so):"
Get-ChildItem $jni | Sort-Object Name | ForEach-Object { '  {0,12}  {1}' -f $_.Length, $_.Name }
Write-Host "assets/qairt_dsp ($((Get-ChildItem $dsp).Count) files):"
Get-ChildItem $dsp | Sort-Object Name | ForEach-Object { '  {0,12}  {1}' -f $_.Length, $_.Name }
