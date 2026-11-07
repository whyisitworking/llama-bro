#!/usr/bin/env bash
# PF T1: backend sampling (per-request `backend_sampling`), off vs on. Method from spike/p2/mtp/mtp2.sh: SDK settings,
# ~2K-token prompt from spike/p2/mtp/source.txt, 256-token replies via /v1/chat/completions, thinking off.
# One load per chip and round serves both arms (it's a request field); the arm order alternates per round (F6).
# Each reply is saved to replies/ so greedy on/off can be compared byte for byte.
# usage: [ROUNDS=3] [CHIPS="npu gpu cpu"] bs.sh
set -uo pipefail
export ANDROID_SERIAL="${ANDROID_SERIAL:-<serial>}"
root=/data/local/tmp/lb-spike; L=$root/P/lib; port=8099
here="$(cd "$(dirname "$0")" && pwd)"; src="$here/../../p2/mtp/source.txt"; mkdir -p "$here/replies" "$here/logs"
wait_adb() { for i in $(seq 1 120); do adb get-state >/dev/null 2>&1 && return 0; sleep 1; done; echo "adb gone"; exit 1; }
stop() { wait_adb; adb shell "pkill -x llama-server; sleep 2; pkill -9 -x llama-server" >/dev/null 2>&1; sleep 2; }
thermal() { adb shell dumpsys thermalservice | grep -m1 -oE 'Thermal Status: [0-9]+' | grep -oE '[0-9]+$'; }
# wait for thermal <= COOL (default 2, the user's call 2026-11-06: was 1), at most ~3 min
cool() { for i in $(seq 1 9); do [ "$(thermal)" -le "${COOL:-2}" ] && return; sleep 20; done; }
start() {  # dev-args
  stop; wait_adb; adb forward tcp:$port tcp:$port >/dev/null
  adb shell "cd $root && LD_LIBRARY_PATH=$L ADSP_LIBRARY_PATH=$L CDSP_LIBRARY_PATH=$L nohup $root/P/bin/llama-server -m models/Qwen3.5-4B-Q4_K_M.gguf -c 8192 -np 1 -b 512 -ub 512 --cache-ram 1024 --ctx-checkpoints 8 --load-mode none --fit off $1 --port $port > $root/out/pf1.log 2>&1" </dev/null >/dev/null 2>&1 &
  for i in $(seq 1 600); do curl -s -o /dev/null -w '%{http_code}' localhost:$port/health 2>/dev/null | grep -q 200 && return 0; sleep 0.5; done
  echo "server never ready"; adb shell tail -5 $root/out/pf1.log; return 1
}
ask() {  # label kind(prose|json) sampling(greedy|agent|default) bs(0|1) outfile
  python3 - "$@" "$src" "$port" <<'PY'
import json, sys, urllib.request
label, kind, samp, bs, out, path, port = sys.argv[1:8]
def post(ep, body):
    return json.load(urllib.request.urlopen(urllib.request.Request(f"http://localhost:{port}/{ep}", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}), timeout=900))
doc = post("detokenize", {"tokens": post("tokenize", {"content": open(path).read()})["tokens"][:2048]})["content"]
ask = {"prose": "Summarise the document above in a few paragraphs.",
       "json": 'List every backend the document above describes as a JSON array of objects with the keys "name", "cmake_flag" and "platforms" (an array of strings). Output only the JSON.'}[kind]
body = {"messages": [{"role": "user", "content": doc + "\n\n" + ask}], "max_tokens": 256, "seed": 42,
        "cache_prompt": False, "chat_template_kwargs": {"enable_thinking": False}, "backend_sampling": bs == "1"}
if samp == "greedy": body["temperature"] = 0
elif samp == "agent": body.update(temperature=0.2, top_p=0.95, top_k=20, min_p=0)
try:
    r = post("v1/chat/completions", body)
except Exception as e:
    print("%s %s-%s bs=%s ERROR %s" % (label, kind, samp, bs, e)); sys.exit(0)
t = r["timings"]; text = r["choices"][0]["message"]["content"]
open(out, "w").write(text)
print("%s %s-%s bs=%s prompt_n=%d prefill_tps=%.1f predicted_n=%d decode_tps=%.2f" % (label, kind, samp, bs, t["prompt_n"], t["prompt_per_second"], t["predicted_n"], t["predicted_per_second"]))
PY
}
rounds=${ROUNDS:-3}
for chip in ${CHIPS:-npu gpu cpu}; do
  case $chip in cpu) dev="-dev none" ;; npu) dev="-dev HTP0 -ngl 99" ;; gpu) dev="-dev GPUOpenCL -ngl 99" ;; esac
  for r in $(seq 1 $rounds); do
    cool
    start "$dev" || { adb shell tail -30 $root/out/pf1.log > "$here/logs/$chip-r$r-fail.log"; continue; }
    echo "== $chip round $r thermal=$(thermal)"
    order="0 1"; (( r % 2 == 0 )) && order="1 0"
    for w in "prose greedy" "prose default" "json greedy" "json agent"; do
      set -- $w
      for bs in $order; do ask "$chip r$r" $1 $2 $bs "$here/replies/$chip-r$r-$1-$2-bs$bs.txt"; done
    done
    adb shell cat $root/out/pf1.log > "$here/logs/$chip-r$r.log"
    adb shell "pidof llama-server >/dev/null" || echo "!! $chip r$r server died"
  done
done
stop
