#!/usr/bin/env bash
# W9: where a speech frame's time goes on the phone, per configuration (research build with LB_* switches and the
# LBTT per-stage timer, see stages-2026-11-17.md). One llama-server per configuration, the 55-word text with seed 42,
# the phone cooled to thermal status 0 first.
# usage: ANDROID_SERIAL=<serial> LB_DEVICE_TOOLS=<research device tools> tts-stages.sh <label> '<ENV=1 ...>' '<extra args>' ...
#        (pairs of env and args; each pair is one configuration)
set -uo pipefail
label="$1"; shift
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/stages-$label"; mkdir -p "$out"
tools="${LB_DEVICE_TOOLS:?set LB_DEVICE_TOOLS to the research device tools}"
remote=/data/local/tmp/lb-stages
models=/data/local/tmp/lb-spike/models
port=18598
adb shell pkill -x llama-server 2>/dev/null || true
adb shell "rm -rf $remote && mkdir -p $remote/bin"
adb push -q "$tools/." "$remote/bin/" > /dev/null
trap 'adb shell pkill -x llama-server 2>/dev/null || true; adb forward --remove tcp:'$port' 2>/dev/null || true' EXIT
text="Tomorrow will be warmer, around eighteen degrees, and the sun should come out by the afternoon. If you are planning a trip to the museum, go early, because the queues grow quickly after ten o'clock. Bring an umbrella, a light jacket, and a bottle of water, and remember that most shops close early on Sunday."
body=$(python3 -c "import json,sys; print(json.dumps({'input':sys.argv[1],'voice':'female','seed':42,'response_format':'wav','n_predict':1200}))" "$text")
n=0
while [ $# -ge 2 ]; do
  envs="$1"; args="$2"; shift 2; n=$((n+1))
  for _ in $(seq 1 60); do
    s="$(adb shell dumpsys thermalservice | sed -n 's/^Thermal Status: //p' | tr -d '\r')"
    [ "${s:-0}" -eq 0 ] && break; sleep 10
  done
  adb shell "cd $remote && LB_TTIME=1 $envs LD_LIBRARY_PATH=$remote/bin exec bin/llama-server -m $models/LFM3-Omni-2B-Q4_K_M.gguf \
    --mmproj $models/mmproj-LFM3-Omni-2B-Q8_0.gguf -c 4096 -np 1 --fit off -dev HTP0 -ngl 99 $args \
    --host 127.0.0.1 --port $port > s$n.log 2>&1" < /dev/null > /dev/null 2>&1 &
  adb forward tcp:$port tcp:$port > /dev/null
  for _ in $(seq 1 180); do curl -sf "http://127.0.0.1:$port/health" > /dev/null 2>&1 && break; sleep 1; done
  t=$(curl -s -o "$out/c$n.wav" -w "%{time_total}" -H 'Content-Type: application/json' -d "$body" "http://127.0.0.1:$port/tts")
  adb shell pkill -x llama-server 2>/dev/null; sleep 1
  adb shell "cat $remote/s$n.log" > "$out/c$n.log"
  audio=$(python3 -c "import sys,struct;b=open(sys.argv[1],'rb').read();print(round((len(b)-44)/2/struct.unpack('<I',b[24:28])[0],2) if len(b)>44 else 0)" "$out/c$n.wav")
  rtf=$(python3 -c "print(round($audio/$t,2) if $t>0 else 0)")
  echo "== c$n [$envs] [$args]: audio ${audio}s in ${t}s = ${rtf}x real time"
  grep -a "LBTT \(srv_step_gen\|srv_text_sample\|h_sample_frame\|h_decode_wav\|h_lm_decode\|enc_build\|enc_alloc\|enc_compute\|h_process_calls\)" "$out/c$n.log" | sed 's/^.*LBTT /   /'
  grep -a "LBHEX" "$out/c$n.log" | tail -1 | sed 's/^.*LBHEX/   hex/'
done
