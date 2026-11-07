#!/usr/bin/env bash
# F50 A/B: llama-server on the phone with --load-mode auto vs none. Per run: wall time to /health ok,
# PSS split (smaps_rollup), and a 64-token greedy reply's timings. Arms alternate within each round.
# Uses the P0 spike's P build on the phone (b12052 + the shipped delta-net patch, CPU only via -dev none).
# usage: [MODEL=file.gguf] [ROUNDS=n] [ARMS="auto none dio" or "P:auto P:none M:auto"] ab.sh   (results: results.md; findings F50, F58)
set -uo pipefail
root=/data/local/tmp/lb-spike
v=${V:-P}
model=${MODEL:-Qwen3.5-4B-Q4_K_M.gguf}
rounds=${ROUNDS:-3}
port=8099
lib=$root/$v/lib
env="LD_LIBRARY_PATH=$lib"
adb forward tcp:$port tcp:$port >/dev/null

stop_servers() {
  adb shell "pkill -x llama-server; sleep 2; pkill -9 -x llama-server" >/dev/null 2>&1
  for i in $(seq 1 25); do adb shell pidof llama-server >/dev/null || return 0; sleep 0.2; done
  echo "WARNING: a server survived SIGKILL: $(adb shell pidof llama-server | tr -d '\r')"
}

run_arm() {
  # an arm is MODE or VARIANT:MODE (VARIANT = a spike build dir on the phone, default $V)
  local arm=$1 v=$v mode=$1
  if [[ $arm == *:* ]]; then v=${arm%%:*}; mode=${arm#*:}; fi
  local lib=$root/$v/lib env="LD_LIBRARY_PATH=$root/$v/lib"
  # by exact name: "pkill -f llama-server" also matches the shell running it and kills that first
  stop_servers
  local t0=$(python3 -c 'import time;print(time.time())')
  adb shell "cd $root && $env nohup $root/$v/bin/llama-server -m models/$model -c 4096 -np 1 -dev none --port $port -lm $mode > $root/out/f50-$v-$mode.log 2>&1" </dev/null >/dev/null 2>&1 &
  local ok=0
  for i in $(seq 1 600); do
    if curl -s -o /dev/null -w '%{http_code}' localhost:$port/health 2>/dev/null | grep -q 200; then ok=1; break; fi
    sleep 0.1
  done
  local t1=$(python3 -c 'import time;print(time.time())')
  [[ $ok == 1 ]] || { echo "$arm: never ready"; adb shell tail -5 $root/out/f50-$v-$mode.log; return; }
  local pids=$(adb shell pidof llama-server | tr -d '\r')
  local pid=$(echo $pids | tr ' ' '\n' | sort -n | tail -1)
  [[ $(echo $pids | wc -w) -eq 1 ]] || { echo "$arm: SKIPPED, several servers: $pids"; return; }
  local roll=$(adb shell cat /proc/$pid/smaps_rollup | tr -d '\r' | awk '/^(Rss|Pss|Pss_Anon|Pss_File):/{printf "%s=%d ", $1, $2/1024}')
  local hwm=$(adb shell grep VmHWM /proc/$pid/status | tr -d '\r' | awk '{printf "PeakRss=%d", $2/1024}')
  local gen=$(curl -s localhost:$port/completion -d '{"prompt":"Write a short story about a lighthouse keeper.","n_predict":64,"temperature":0,"cache_prompt":false}' \
     | python3 -c 'import sys,json,hashlib;j=json.load(sys.stdin);t=j["timings"];print("decode_tps=%.2f prefill_tps=%.2f reply_md5=%s"%(t["predicted_per_second"],t["prompt_per_second"],hashlib.md5(j["content"].encode()).hexdigest()[:8]))')
  local roll2=$(adb shell cat /proc/$pid/smaps_rollup | tr -d '\r' | awk '/^(Pss):/{printf "PssAfterGen=%d", $2/1024}')
  local lt=$(adb shell "grep -E 'buffer size' $root/out/f50-$v-$mode.log" | tr -d '\r' | sed -E 's/.*load_tensors: +//' | tr '\n' ';')
  printf "%s ready_s=%.2f %s %s %s | %s\n" "$arm" "$(python3 -c "print($t1-$t0)")" "$roll $hwm" "$roll2" "$gen" "$lt"
}

for r in $(seq 1 $rounds); do
  therm=$(adb shell dumpsys thermalservice | grep -m1 -oE 'Thermal Status: [0-9]+')
  echo "== round $r ($therm)"
  # ARMS: space-separated load modes, rotated by one each round so every arm takes every position
  read -ra arms <<< "${ARMS:-auto none}"
  n=${#arms[@]}
  for k in $(seq 0 $((n-1))); do run_arm "${arms[$(( (k + r) % n ))]}"; done
done
stop_servers
