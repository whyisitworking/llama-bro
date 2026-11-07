#!/usr/bin/env bash
# P2 T7: upstream's projected memory (the load's memory breakdown, -lv 4) against what the system loses, after load and
# after filling the context, with the SDK's settings. "Lost" is the drop in /proc/meminfo MemAvailable, which counts
# the NPU's shared DMA buffers that a process's PSS doesn't. Upstream llama-server from the P0 spike's P build.
# usage: [CTX=8192] [ONLY="model:arm ..."] measure.sh     (findings: P2 T7)
set -uo pipefail
export ANDROID_SERIAL="${ANDROID_SERIAL:-<serial>}"
root=/data/local/tmp/lb-spike; L=$root/P/lib; port=8099; ctx=${CTX:-8192}
wait_adb() { for i in $(seq 1 120); do adb get-state >/dev/null 2>&1 && return 0; sleep 1; done; echo "adb gone"; exit 1; }
sh() { wait_adb; adb shell "$@"; }
stop() { sh "pkill -x llama-server; sleep 2; pkill -9 -x llama-server" >/dev/null 2>&1; sleep 3; }
avail() { sh "grep MemAvailable /proc/meminfo" | awk '{print int($2/1024)}'; }
pss() { sh "grep -E '^Pss:' /proc/\$(pidof llama-server)/smaps_rollup" | awk '{print int($2/1024)}'; }
para="The lighthouse stood on a narrow spit of rock, and every evening the keeper climbed its spiral stairs to light the lamp. "
run() {  # model arm dev
  local model=$1 name=$2 dev=$3
  stop; wait_adb; adb forward tcp:$port tcp:$port >/dev/null
  local a0=$(avail)
  echo "== $model $name ctx=$ctx ($(sh dumpsys thermalservice | grep -m1 -oE 'Thermal Status: [0-9]+')) avail_before=$a0"
  sh "cd $root && LD_LIBRARY_PATH=$L ADSP_LIBRARY_PATH=$L CDSP_LIBRARY_PATH=$L nohup $root/P/bin/llama-server -m models/$model -c $ctx -np 1 -b 512 -ub 512 --cache-ram 1024 --ctx-checkpoints 8 --load-mode none --fit off $dev -lv 4 --port $port > $root/out/t7-m.log 2>&1" </dev/null >/dev/null 2>&1 &
  for i in $(seq 1 600); do curl -s -o /dev/null -w '%{http_code}' localhost:$port/health 2>/dev/null | grep -q 200 && break; sleep 0.5; done
  sh "grep -E 'common_memory_breakdown_print: \|' $root/out/t7-m.log" | tr -d '\r' | sed 's/.*common_memory_breakdown_print: //'
  local a1=$(avail); echo "after_load: lost=$((a0 - a1)) pss=$(pss)"
  local n_para=$(curl -s localhost:$port/tokenize -d "$(python3 -c 'import json,sys;print(json.dumps({"content":sys.argv[1]}))' "$para")" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["tokens"]))')
  local reps=$(( (ctx - 64) / n_para ))
  curl -s localhost:$port/completion -d "$(python3 -c 'import json,sys;print(json.dumps({"prompt":sys.argv[1]*int(sys.argv[2]),"n_predict":32,"temperature":0,"cache_prompt":True}))' "$para" "$reps")" \
    | python3 -c 'import sys,json;t=json.load(sys.stdin)["timings"];print("prompt_n=%d prefill_tps=%.1f"%(t["prompt_n"],t["prompt_per_second"]))'
  local a2=$(avail); echo "after_prefill: lost=$((a0 - a2)) pss=$(pss)"
}
for job in ${ONLY:-Qwen3.5-4B-Q4_K_M.gguf:cpu Qwen3.5-4B-Q4_K_M.gguf:npu Llama-3.2-3B-Instruct-Q4_K_M.gguf:cpu Llama-3.2-3B-Instruct-Q4_K_M.gguf:npu}; do
  model=${job%%:*}; arm=${job#*:}
  case $arm in cpu) run "$model" cpu "-dev none" ;; npu) run "$model" npu "-dev HTP0 -ngl 99" ;; esac
done
stop
