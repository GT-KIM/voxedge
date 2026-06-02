#!/system/bin/sh
# Generalized on-device TTS per-component latency (load-isolated: 1 vs N inferences).
# Usage: tts_device_timing2.sh <DLC_DIR> <IN_DIR> <TAG>
# Runs each present component DLC on HTP/DSP with burst profile.
DEV=/data/local/tmp/tts_spike
DLC_DIR=$1; IN_DIR=$2; TAG=$3
export LD_LIBRARY_PATH=$DEV/lib
export ADSP_LIBRARY_PATH=$DEV/dsp
cd $DEV
N=10

line() {
  c=$1; d=$IN_DIR/$c
  case $c in
    duration_predictor) echo "style_dp:=$d/style_dp.raw text_ids:=$d/text_ids.raw text_mask:=$d/text_mask.raw";;
    text_encoder)       echo "style_ttl:=$d/style_ttl.raw text_ids:=$d/text_ids.raw text_mask:=$d/text_mask.raw";;
    vector_estimator)   echo "current_step:=$d/current_step.raw latent_mask:=$d/latent_mask.raw noisy_latent:=$d/noisy_latent.raw style_ttl:=$d/style_ttl.raw text_emb:=$d/text_emb.raw text_mask:=$d/text_mask.raw total_step:=$d/total_step.raw";;
    vocoder)            echo "latent:=$d/latent.raw";;
  esac
}

run() {
  c=$1; lf=$2
  t0=$(date +%s.%N)
  ./snpe-net-run --container $DLC_DIR/${c}.dlc --input_list $lf --use_dsp --perf_profile burst --output_dir $DEV/o_${TAG}_$c >/dev/null 2>&1
  t1=$(date +%s.%N)
  echo "$t1 $t0" | awk '{printf "%.4f", $1-$2}'
}

for c in duration_predictor text_encoder vector_estimator vocoder; do
  [ -f "$DLC_DIR/${c}.dlc" ] || { echo "[$TAG] $c: no dlc, skip"; continue; }
  [ -f "$IN_DIR/$c/text_mask.raw" ] || [ "$c" = vocoder ] || { echo "[$TAG] $c: no inputs, skip"; continue; }
  L=$(line $c)
  : > $DEV/L1.txt; echo "$L" >> $DEV/L1.txt
  : > $DEV/LN.txt; i=0; while [ $i -lt $N ]; do echo "$L" >> $DEV/LN.txt; i=$((i+1)); done
  s1=$(run $c $DEV/L1.txt); sN=$(run $c $DEV/LN.txt)
  per=$(echo "$s1 $sN $N" | awk '{printf "%.2f", ($2-$1)/($3-1)*1000}')
  echo "[$TAG] $c  per_infer_ms=$per  (t1=${s1}s tN=${sN}s)"
done
