#!/system/bin/sh
# On-device TTS per-component latency via load-isolation: time 1 inference vs N, slope = per-infer.
# Runs each Supertonic-3 prepared DLC on the HTP/DSP with burst profile.
DEV=/data/local/tmp/tts_spike
export LD_LIBRARY_PATH=$DEV/lib
export ADSP_LIBRARY_PATH=$DEV/dsp
cd $DEV
N=20

line() { # $1=component -> echo the named-input line
  c=$1; d=$DEV/in/$c
  case $c in
    duration_predictor) echo "style_dp:=$d/style_dp.raw text_ids:=$d/text_ids.raw text_mask:=$d/text_mask.raw";;
    text_encoder)       echo "style_ttl:=$d/style_ttl.raw text_ids:=$d/text_ids.raw text_mask:=$d/text_mask.raw";;
    vector_estimator)   echo "current_step:=$d/current_step.raw latent_mask:=$d/latent_mask.raw noisy_latent:=$d/noisy_latent.raw style_ttl:=$d/style_ttl.raw text_emb:=$d/text_emb.raw text_mask:=$d/text_mask.raw total_step:=$d/total_step.raw";;
    vocoder)            echo "latent:=$d/latent.raw";;
  esac
}

run() { # $1=component $2=listfile -> wall seconds
  c=$1; lf=$2
  t0=$(date +%s.%N)
  ./snpe-net-run --container $DEV/dlc/${c}_sm8750.dlc --input_list $lf --use_dsp --perf_profile burst --output_dir $DEV/out_$c >/dev/null 2>&1
  t1=$(date +%s.%N)
  echo "$t1 - $t0" | awk '{printf "%.4f", $1-$3}'
}

for c in duration_predictor text_encoder vector_estimator vocoder; do
  L=$(line $c)
  : > $DEV/l1_$c.txt;  echo "$L" >> $DEV/l1_$c.txt
  : > $DEV/lN_$c.txt;  i=0; while [ $i -lt $N ]; do echo "$L" >> $DEV/lN_$c.txt; i=$((i+1)); done
  s1=$(run $c $DEV/l1_$c.txt)
  sN=$(run $c $DEV/lN_$c.txt)
  per=$(echo "$s1 $sN $N" | awk '{printf "%.2f", ($2-$1)/($3-1)*1000}')
  load=$(echo "$s1 $per" | awk '{printf "%.2f", $1*1000 - $2}')
  echo "COMP=$c  t1=${s1}s  tN(N=$N)=${sN}s  per_infer_ms=$per  approx_load_ms=$load"
done
