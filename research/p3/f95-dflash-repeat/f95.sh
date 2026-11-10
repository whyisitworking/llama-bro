#!/usr/bin/env bash
# P3 F95 (derived from PF T3): draft-model speculation for Qwen3.5 4B: EAGLE-3, DFlash, DSpark sidecars (converted to Q8_0 GGUF on the Mac
# with upstream's convert_hf_to_gguf.py --target-model-dir; see convert.sh). Method from spike/p2/mtp/mtp2.sh: SDK
# settings, a fresh load per arm, arms rotated per round (F6), thermal <= 2 before each round (at most ~3 min wait), 256-token replies via
# /v1/chat/completions, thinking off, F68's four output kinds. Per arm also: load time, MemAvailable drop (as F66).
# Arms (upstream's example flags):
#   off     no speculation
#   mtp     --spec-type draft-mtp --spec-draft-n-max 4 (F68's opt-in, the bar to beat)
#   eagle3  -md eagle3 --spec-type draft-eagle3
#   dflash  -md dflash --spec-type draft-dflash --spec-draft-n-max 15 -fa on
#   dspark  -md dspark --spec-type draft-dspark --spec-draft-n-max 7 -fa on
# usage: [ROUNDS=3] [CHIPS="npu cpu"] [ARMS="..."] dm.sh
set -uo pipefail
export ANDROID_SERIAL="${ANDROID_SERIAL:-<serial>}"
root=/data/local/tmp/lb-spike; L=$root/P/lib; port=8099
here="$(cd "$(dirname "$0")" && pwd)"; src="$here/../../p2/mtp/source.txt"; ref="$here/../../pf/draft-models/replies/npu-r1-dflash-json-greedy.txt"; mkdir -p "$here/replies" "$here/logs"
wait_adb() { for i in $(seq 1 120); do adb get-state >/dev/null 2>&1 && return 0; sleep 1; done; echo "adb gone"; exit 1; }
stop() { wait_adb; adb shell "pkill -x llama-server; sleep 2; pkill -9 -x llama-server" >/dev/null 2>&1; sleep 2; }
thermal() { adb shell dumpsys thermalservice | grep -m1 -oE 'Thermal Status: [0-9]+' | grep -oE '[0-9]+$'; }
# wait for thermal <= COOL (default 2, the user's call 2026-11-06: was 1), at most ~3 min
cool() { for i in $(seq 1 9); do [ "$(thermal)" -le "${COOL:-2}" ] && return; sleep 20; done; }
# before each arm: never start one at thermal >= 3 (the NPU reached 105 C, skin 70 C, at status 4 in T2); wait for <= 2, at most ~10 min
guard() { [ "$(thermal)" -lt 3 ] && return; for i in $(seq 1 30); do [ "$(thermal)" -le 2 ] && return; sleep 20; done; }
memavail() { adb shell grep MemAvailable /proc/meminfo | awk '{print int($2/1024)}'; }
spec() { case $1 in
  off) echo "" ;;
  mtp) echo "--spec-type draft-mtp --spec-draft-n-max 4" ;;
  eagle3) echo "-md models/pf/Qwen3.5-4B-Eagle3-Q8_0.gguf --spec-type draft-eagle3" ;;
  dflash) echo "-md models/pf/Qwen3.5-4B-DFlash-Q8_0.gguf --spec-type draft-dflash --spec-draft-n-max 15 -fa on" ;;
  dspark) echo "-md models/pf/Qwen3.5-4B-DSpark-Q8_0.gguf --spec-type draft-dspark --spec-draft-n-max 7 -fa on" ;;
esac; }
start() {  # dev-args arm
  stop; wait_adb; adb forward tcp:$port tcp:$port >/dev/null
  local t0=$(python3 -c 'import time;print(time.time())')
  adb shell "cd $root && LD_LIBRARY_PATH=$L ADSP_LIBRARY_PATH=$L CDSP_LIBRARY_PATH=$L nohup $root/P/bin/llama-server -m models/Qwen3.5-4B-Q4_K_M.gguf -c 8192 -np 1 -b 512 -ub 512 --cache-ram 1024 --ctx-checkpoints 8 --load-mode none --fit off $1 $(spec $2) --port $port > $root/out/pf3.log 2>&1" </dev/null >/dev/null 2>&1 &
  for i in $(seq 1 1200); do
    curl -s -o /dev/null -w '%{http_code}' localhost:$port/health 2>/dev/null | grep -q 200 && { echo "load_s=$(python3 -c "import time;print(round(time.time()-$t0,1))")"; return 0; }
    adb shell "pidof llama-server >/dev/null" || { echo "server exited"; return 1; }
    sleep 0.5; done
  echo "server never ready"; return 1
}
ask() {  # label kind(prose|json) sampling(greedy|agent|default) outfile
  python3 - "$@" "$src" "$port" <<'PY'
import json, sys, urllib.request
label, kind, samp, out, path, port = sys.argv[1:7]
def post(ep, body):
    return json.load(urllib.request.urlopen(urllib.request.Request(f"http://localhost:{port}/{ep}", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}), timeout=900))
doc = post("detokenize", {"tokens": post("tokenize", {"content": open(path).read()})["tokens"][:2048]})["content"]
ask = {"prose": "Summarise the document above in a few paragraphs.",
       "json": 'List every backend the document above describes as a JSON array of objects with the keys "name", "cmake_flag" and "platforms" (an array of strings). Output only the JSON.'}[kind]
body = {"messages": [{"role": "user", "content": doc + "\n\n" + ask}], "max_tokens": 256, "seed": 42,
        "cache_prompt": False, "chat_template_kwargs": {"enable_thinking": False}}
if samp == "greedy": body["temperature"] = 0
elif samp == "agent": body.update(temperature=0.2, top_p=0.95, top_k=20, min_p=0)
try:
    r = post("v1/chat/completions", body)
except Exception as e:
    print("%s %s-%s ERROR %s" % (label, kind, samp, e)); sys.exit(0)
t = r["timings"]; open(out, "w").write(r["choices"][0]["message"]["content"])
print("%s %s-%s prompt_n=%d prefill_tps=%.1f predicted_n=%d decode_tps=%.2f draft_n=%d accepted=%d" % (label, kind, samp, t["prompt_n"], t["prompt_per_second"], t["predicted_n"], t["predicted_per_second"], t.get("draft_n", 0), t.get("draft_n_accepted", 0)))
PY
}
# usage: [RUNS=8] [ASKS=3] f95.sh : RUNS fresh upstream llama-server loads (DFlash, NPU, SDK-like flags); in each, ASKS json-greedy asks.
# Every reply is compared byte for byte with the F73 reference reply.
for run in $(seq 1 ${RUNS:-8}); do
  cool; guard; stop
  lt=$(start "-dev HTP0 -ngl 99" dflash) || { echo "!! run $run failed to start: $lt"; continue; }
  for a in $(seq 1 ${ASKS:-3}); do
    out="$here/replies/run$run-ask$a.txt"
    ask "run$run ask$a $lt thermal=$(thermal)" json greedy "$out"
    if cmp -s "$out" "$ref"; then echo "   IDENTICAL"; else echo "   DIFFERS from reference"; diff <(cat "$ref") <(cat "$out") | head -4; fi
  done
  adb shell "grep -vE 'unused tensor|print_timing: id.*n_gen' $root/out/pf3.log" > "$here/logs/run$run.log"
done
stop
