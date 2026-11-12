#!/usr/bin/env bash
# P2 T4/T5: CPU vs OpenCL (vs Vulkan) on the phone with upstream llama-server (the P0 spike's P build: b12052 + delta-net patch, the
# SDK's source), the SDK's load settings, arms alternating within each round (F6). Per run: wall time to /health ok,
# then one ~600-token prompt with a 128-token greedy reply (llama-server's own timings) and an MD5 of the reply.
# usage: [MODEL=file.gguf] [ROUNDS=n] [ARMS="cpu opencl vulkan"] speed.sh     (findings F63, F64)
set -uo pipefail
export ANDROID_SERIAL="${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the device}"
root=/data/local/tmp/lb-spike
model=${MODEL:-Qwen3.5-4B-Q4_K_M.gguf}
rounds=${ROUNDS:-3}
port=8099
adb forward tcp:$port tcp:$port >/dev/null
para="The lighthouse stood on a narrow spit of rock, and every evening the keeper climbed its spiral stairs to light the lamp. Ships passed in the dark, guided by the beam that swept across the water. "
prompt=$(python3 -c "print(('$para' * 16).strip())")
req=$(python3 -c 'import json,sys; print(json.dumps({"prompt": sys.argv[1] + "\n\nSummarise the story above in a few sentences.", "n_predict": 128, "temperature": 0, "cache_prompt": False}))' "$prompt")

stop_servers() {
  adb shell "pkill -x llama-server; sleep 2; pkill -9 -x llama-server" >/dev/null 2>&1
  for i in $(seq 1 25); do adb shell pidof llama-server >/dev/null || return 0; sleep 0.2; done
  echo "WARNING: a server survived SIGKILL"
}

run_arm() {
  local arm=$1 dev v=P
  # P: the P0 spike's build (CPU, OpenCL, Hexagon); V: the same source with Vulkan (P2 T5, upstream names)
  case $arm in cpu) dev="-dev none" ;; opencl) dev="-dev GPUOpenCL -ngl 99" ;; vulkan) dev="-dev Vulkan0 -ngl 99"; v=V ;; esac
  stop_servers
  local t0=$(python3 -c 'import time;print(time.time())')
  adb shell "cd $root && LD_LIBRARY_PATH=$root/$v/lib nohup $root/$v/bin/llama-server -m models/$model -c 4096 -np 1 -b 512 -ub 512 --load-mode none $dev --port $port > $root/out/t4-$arm.log 2>&1" </dev/null >/dev/null 2>&1 &
  local ok=0
  for i in $(seq 1 1200); do curl -s -o /dev/null -w '%{http_code}' localhost:$port/health 2>/dev/null | grep -q 200 && { ok=1; break; }; sleep 0.1; done
  local t1=$(python3 -c 'import time;print(time.time())')
  [[ $ok == 1 ]] || { echo "$arm: never ready"; adb shell tail -5 $root/out/t4-$arm.log; return; }
  [[ $(adb shell pidof llama-server | wc -w) -eq 1 ]] || { echo "$arm: SKIPPED, several servers"; return; }
  local gen=$(curl -s localhost:$port/completion -d "$req" | python3 -c 'import sys,json,hashlib;j=json.load(sys.stdin);t=j["timings"];print("prompt_n=%d prefill_tps=%.1f decode_tps=%.2f reply_md5=%s"%(t["prompt_n"],t["prompt_per_second"],t["predicted_per_second"],hashlib.md5(j["content"].encode()).hexdigest()[:8]))')
  local off=$(adb shell "grep -oE 'offloaded [0-9]+/[0-9]+ layers' $root/out/t4-$arm.log" | tr -d '\r')
  printf "%s ready_s=%.2f %s %s\n" "$arm" "$(python3 -c "print($t1-$t0)")" "$gen" "$off"
}

for r in $(seq 1 $rounds); do
  echo "== round $r ($(adb shell dumpsys thermalservice | grep -m1 -oE 'Thermal Status: [0-9]+'), battery $(adb shell dumpsys battery | grep -m1 -E '^\s*temperature:' | awk '{print $2/10}') C)"
  read -ra arms <<< "${ARMS:-cpu opencl}"
  n=${#arms[@]}
  for k in $(seq 0 $((n-1))); do run_arm "${arms[$(( (k + r) % n ))]}"; done
done
stop_servers
