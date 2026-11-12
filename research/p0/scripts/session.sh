#!/usr/bin/env bash
# Runs one request file on the phone through llama-server (U, over adb forward) or lb-spike (H, in-process),
# with identical llama.cpp flags. Results land in research/p0/results/raw/<name>/.
#   session.sh u NAME REQFILE FLAGS...
#   session.sh h NAME REQFILE FLAGS...
#   session.sh cool            # waits (at most 60 s) for thermal status 0 or 1 (no throttling), then 5 s
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
root=/data/local/tmp/lb-spike
v="${VARIANT:-U}"   # which build to run: U, P or S
lib="$root/$v/lib"
env="LD_LIBRARY_PATH=$lib ADSP_LIBRARY_PATH=$lib CDSP_LIBRARY_PATH=$lib CDSP1_LIBRARY_PATH=$lib CDSP2_LIBRARY_PATH=$lib CDSP3_LIBRARY_PATH=$lib"
port=18080

cool() {
  for _ in $(seq 1 12); do
    t=$(adb shell dumpsys battery | awk '/^ *temperature:/ {print $2; exit}')
    s=$(adb shell dumpsys thermalservice | awk -F': ' '/Thermal Status/ {print $2; exit}' | tr -d '\r')
    if [[ "$s" == "0" || "$s" == "1" ]]; then sleep 5; echo "cool: battery $((t/10)).$((t%10)) C, status $s"; return; fi
    sleep 5
  done
  echo "cool: gave up waiting (battery $t, status $s)"
}

mode="${1:?u|h|cool}"
[[ "$mode" == "cool" ]] && { cool; exit 0; }
name="${2:?name}"; req="${3:?request file}"; shift 3
out="$here/results/raw/$name"
rm -rf "$out" && mkdir -p "$out"
adb shell "rm -rf $root/out/$name && mkdir -p $root/out/$name"

if [[ "$mode" == "u" ]]; then
  # adb shell waits for the server to exit, so the launcher itself runs in the background here
  adb shell "cd $root && $env exec $root/$v/bin/llama-server $* --host 127.0.0.1 --port $port > $root/out/$name/server.log 2>&1" < /dev/null &
  launcher=$!
  adb forward tcp:$port tcp:$port >/dev/null
  for _ in $(seq 1 180); do curl -sf "http://127.0.0.1:$port/health" >/dev/null && break; sleep 1; done
  python3 "$here/scripts/http-run.py" --url "http://127.0.0.1:$port" --requests "$here/requests/$req" --out "$out"
  pid=$(adb shell pidof llama-server | tr -d '\r')
  hwm=$(adb shell "grep VmHWM /proc/$pid/status" | awk '{print $2}')
  adb shell "cat /proc/$pid/smaps_rollup" > "$out/smaps_rollup.txt"
  echo "{\"vm_hwm_kb\": $hwm}" > "$out/memory.json"
  adb shell "kill $pid"
  for _ in $(seq 1 30); do adb shell pidof llama-server >/dev/null 2>&1 || break; sleep 1; done
  wait "$launcher" 2>/dev/null || true
  adb forward --remove tcp:$port >/dev/null || true
  adb pull "$root/out/$name/server.log" "$out/" >/dev/null
else
  adb shell "cd $root && $env $root/$v/bin/lb-spike $* --spike-requests req/$req --spike-out out/$name > out/$name/server.log 2>&1"
  adb pull "$root/out/$name/." "$out/" >/dev/null
fi
echo "$mode $name: $(wc -l < "$out/summary.jsonl") requests, peak $(cat "$out/memory.json")"
