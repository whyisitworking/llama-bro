#!/bin/bash
# usage: encoder-time.sh <dir on phone> <tag> [mmproj file]: CPU-encoder prompt time for the red/blue picture, model on the NPU
here="$(cd "$(dirname "$0")" && pwd)"; D=$1; tag=$2; mm=${3:-q08-mm.gguf}; : "${ANDROID_SERIAL:?set ANDROID_SERIAL}"; export ANDROID_SERIAL
for i in $(seq 1 120); do t=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | grep -o '[0-9]'); [ "${t:-9}" -le 1 ] 2>/dev/null && break; sleep 15; done
adb shell pkill -f llama-server; sleep 2
timeout 20 adb shell "cd $D && LD_LIBRARY_PATH=$D ADSP_LIBRARY_PATH=$D nohup ./llama-server -m /data/local/tmp/lb-b12352/m/q08.gguf --mmproj /data/local/tmp/lb-b12352/m/$mm -c 4096 -np 1 --load-mode none --fit off --device HTP0 -ngl 99 --no-mmproj-offload --port 8188 > $D/enc.log 2>&1 </dev/null &"
for i in $(seq 1 90); do curl -s -m 2 http://127.0.0.1:8188/health 2>/dev/null | grep -q ok && break; sleep 2; done
cpu=$(adb shell "grep -m1 -oE 'libggml-cpu[^ ]*\.so' $D/enc.log")
echo "$tag thermal=$t cpu=$cpu $(curl -s -m 300 http://127.0.0.1:8188/v1/chat/completions -H 'Content-Type: application/json' -d @$here/red-blue.json | python3 -c 'import json,sys;d=json.load(sys.stdin);print(d["choices"][0]["message"]["content"], "| prompt_ms", round(d["timings"]["prompt_ms"]))' 2>&1 | tail -1)"
adb shell pkill -f llama-server
