#!/bin/bash
# NPU prefill A/B: b12352 with and without the delta-net head/tail patch, MTP on (F7, F8)
here="$(cd "$(dirname "$0")" && pwd)"; : "${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the phone}"; export ANDROID_SERIAL
cool() { for i in $(seq 1 120); do t=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | grep -o '[0-9]'); [ "${t:-9}" -le 1 ] 2>/dev/null && return; sleep 15; done; echo "still hot ($t)"; }
run() { # $1 arm dir
  D=/data/local/tmp/$1
  adb shell pkill -f llama-server; sleep 2
  timeout 20 adb shell "cd $D && LD_LIBRARY_PATH=$D ADSP_LIBRARY_PATH=$D CDSP_LIBRARY_PATH=$D nohup ./llama-server -m /data/local/tmp/lb-spike/models/Qwen3.5-4B-Q4_K_M.gguf -c 8192 -np 1 --load-mode none --fit off --device HTP0 -ngl 99 --spec-type draft-mtp --spec-draft-n-max 3 --port 8188 > $D/ab.log 2>&1 </dev/null &"
  for i in $(seq 1 120); do curl -s -m 2 http://127.0.0.1:8188/health 2>/dev/null | grep -q ok && break; sleep 2; done
  curl -s -m 300 http://127.0.0.1:8188/completion -H 'Content-Type: application/json' -d @$here/pp.json | python3 -c 'import json,sys;t=json.load(sys.stdin)["timings"];print(t["prompt_n"], round(t["prompt_per_second"],1))'
}
adb forward tcp:8188 tcp:8188 >/dev/null
for round in 1 2 3; do
  for arm in lb-b12352 lb-b12352-np; do cool; echo "round=$round arm=$arm thermal=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | grep -o '[0-9]') $(run $arm)"; done
done
adb shell pkill -f llama-server
