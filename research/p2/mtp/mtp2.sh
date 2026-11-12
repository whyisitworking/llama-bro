#!/usr/bin/env bash
# P2 T8 follow-up (F68): why an earlier prototype saw MTP win. Same server, model and settings as mtp.sh, but per output kind:
# prose and JSON extraction, each greedy (temperature 0) and sampled (the prototype's agent preset 0.2/0.95/20, and the
# model's defaults for prose), via /v1/chat/completions with thinking off. Arms: MTP off, draft 3, draft 4.
# usage: [ROUNDS=3] [CHIPS="npu cpu"] mtp2.sh
set -uo pipefail
export ANDROID_SERIAL="${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the device}"
root=/data/local/tmp/lb-spike; L=$root/P/lib; port=8099
here="$(cd "$(dirname "$0")" && pwd)"
wait_adb() { for i in $(seq 1 120); do adb get-state >/dev/null 2>&1 && return 0; sleep 1; done; echo "adb gone"; exit 1; }
stop() { wait_adb; adb shell "pkill -x llama-server; sleep 2; pkill -9 -x llama-server" >/dev/null 2>&1; sleep 2; }
thermal() { adb shell dumpsys thermalservice | grep -m1 -oE 'Thermal Status: [0-9]+' | grep -oE '[0-9]+$'; }
cool() { for i in $(seq 1 60); do [ "$(thermal)" -le 1 ] && return; sleep 20; done; }
start() {  # dev-args n_max(0 = off)
  local spec=""; [ "$2" != 0 ] && spec="--spec-type draft-mtp --spec-draft-n-max $2"
  stop; wait_adb; adb forward tcp:$port tcp:$port >/dev/null
  adb shell "cd $root && LD_LIBRARY_PATH=$L ADSP_LIBRARY_PATH=$L CDSP_LIBRARY_PATH=$L nohup $root/P/bin/llama-server -m models/Qwen3.5-4B-Q4_K_M.gguf -c 8192 -np 1 -b 512 -ub 512 --cache-ram 1024 --ctx-checkpoints 8 --load-mode none --fit off $1 $spec --port $port > $root/out/t8b.log 2>&1" </dev/null >/dev/null 2>&1 &
  for i in $(seq 1 600); do curl -s -o /dev/null -w '%{http_code}' localhost:$port/health 2>/dev/null | grep -q 200 && return 0; sleep 0.5; done
  echo "server never ready"; adb shell tail -3 $root/out/t8b.log; return 1
}
ask() {  # label kind(prose|json) sampling(greedy|agent|default)
  python3 - "$@" "$here/source.txt" "$port" <<'PY'
import json, sys, urllib.request
label, kind, samp, path, port = sys.argv[1:6]
def post(ep, body):
    return json.load(urllib.request.urlopen(urllib.request.Request(f"http://localhost:{port}/{ep}", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})))
doc = post("detokenize", {"tokens": post("tokenize", {"content": open(path).read()})["tokens"][:2048]})["content"]
ask = {"prose": "Summarise the document above in a few paragraphs.",
       "json": 'List every backend the document above describes as a JSON array of objects with the keys "name", "cmake_flag" and "platforms" (an array of strings). Output only the JSON.'}[kind]
body = {"messages": [{"role": "user", "content": doc + "\n\n" + ask}], "max_tokens": 256, "seed": 42,
        "cache_prompt": False, "chat_template_kwargs": {"enable_thinking": False}}
if samp == "greedy": body["temperature"] = 0
elif samp == "agent": body.update(temperature=0.2, top_p=0.95, top_k=20, min_p=0)
t = post("v1/chat/completions", body)["timings"]
d, a = t.get("draft_n", 0), t.get("draft_n_accepted", 0)
print("%s %s-%s prompt_n=%d prefill_tps=%.1f predicted_n=%d decode_tps=%.2f draft_n=%d accepted=%d" % (label, kind, samp, t["prompt_n"], t["prompt_per_second"], t["predicted_n"], t["predicted_per_second"], d, a))
PY
}
rounds=${ROUNDS:-3}
for chip in ${CHIPS:-npu cpu}; do
  case $chip in cpu) dev="-dev none" ;; npu) dev="-dev HTP0 -ngl 99" ;; gpu) dev="-dev GPUOpenCL -ngl 99" ;; esac
  for r in $(seq 1 $rounds); do
    cool
    order="0 3 4"; (( r % 3 == 2 )) && order="3 4 0"; (( r % 3 == 0 )) && order="4 0 3"
    for n in $order; do
      start "$dev" $n || continue
      echo "== $chip round $r n_max=$n thermal=$(thermal)"
      for w in "prose greedy" "prose default" "json greedy" "json agent"; do ask "$chip n=$n" $w; done
    done
  done
done
stop
