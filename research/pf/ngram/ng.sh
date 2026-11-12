#!/usr/bin/env bash
# PF T2: n-gram speculation (a load setting). Method from research/p2/mtp/mtp2.sh: SDK settings, a fresh load per arm,
# arms rotated within a round (F6), thermal <= 2 before each round (at most ~3 min wait), 256-token replies via /v1/chat/completions,
# thinking off. Output kinds: the four of F68 plus code edit (code.cpp back with one function renamed) and quoting
# (three verbatim sentences from the document). Arms:
#   off     no speculation
#   mod     --spec-default (upstream: ngram-mod, n_match 24, n_min 48, n_max 64)
#   k4v     ngram-map-k4v, upstream's example (size-n 8, size-m 8, min-hits 2, draft-n-max 64)
#   mtp     draft-mtp, n-max 4 (F68's opt-in)           [npu only]
#   modmtp  ngram-mod (as mod) then draft-mtp n-max 4    [npu only; n-gram drafts first, MTP when none]
# usage: [ROUNDS=3] [CHIPS="npu cpu"] [ARMS="..."] ng.sh
set -uo pipefail
export ANDROID_SERIAL="${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the device}"
root=/data/local/tmp/lb-spike; L=$root/P/lib; port=8099
here="$(cd "$(dirname "$0")" && pwd)"; src="$here/../../p2/mtp/source.txt"; mkdir -p "$here/replies" "$here/logs"
wait_adb() { for i in $(seq 1 120); do adb get-state >/dev/null 2>&1 && return 0; sleep 1; done; echo "adb gone"; exit 1; }
stop() { wait_adb; adb shell "pkill -x llama-server; sleep 2; pkill -9 -x llama-server" >/dev/null 2>&1; sleep 2; }
thermal() { adb shell dumpsys thermalservice | grep -m1 -oE 'Thermal Status: [0-9]+' | grep -oE '[0-9]+$'; }
# wait for thermal <= COOL (default 2; it was 1 before 2026-11-06), at most ~3 min
cool() { for i in $(seq 1 9); do [ "$(thermal)" -le "${COOL:-2}" ] && return; sleep 20; done; }
# before each arm: never start one at thermal >= 3 (the NPU reached 105 C, skin 70 C, at status 4 in T2); wait for <= 2, at most ~10 min
guard() { [ "$(thermal)" -lt 3 ] && return; for i in $(seq 1 30); do [ "$(thermal)" -le 2 ] && return; sleep 20; done; }
mod="--spec-ngram-mod-n-match 24 --spec-ngram-mod-n-min 48 --spec-ngram-mod-n-max 64"
spec() { case $1 in
  off) echo "" ;;
  mod) echo "--spec-default" ;;
  k4v) echo "--spec-type ngram-map-k4v --spec-ngram-map-k4v-size-n 8 --spec-ngram-map-k4v-size-m 8 --spec-ngram-map-k4v-min-hits 2 --spec-draft-n-max 64" ;;
  mtp) echo "--spec-type draft-mtp --spec-draft-n-max 4" ;;
  modmtp) echo "--spec-type ngram-mod,draft-mtp $mod --spec-draft-n-max 4" ;;
esac; }
start() {  # dev-args arm
  stop; wait_adb; adb forward tcp:$port tcp:$port >/dev/null
  adb shell "cd $root && LD_LIBRARY_PATH=$L ADSP_LIBRARY_PATH=$L CDSP_LIBRARY_PATH=$L nohup $root/P/bin/llama-server -m models/Qwen3.5-4B-Q4_K_M.gguf -c 8192 -np 1 -b 512 -ub 512 --cache-ram 1024 --ctx-checkpoints 8 --load-mode none --fit off $1 $(spec $2) --port $port > $root/out/pf2.log 2>&1" </dev/null >/dev/null 2>&1 &
  for i in $(seq 1 600); do curl -s -o /dev/null -w '%{http_code}' localhost:$port/health 2>/dev/null | grep -q 200 && return 0; sleep 0.5; done
  echo "server never ready"; return 1
}
ask() {  # label kind(prose|json|code|quote) sampling(greedy|agent|default) outfile
  python3 - "$@" "$src" "$here/code.cpp" "$port" <<'PY'
import json, sys, urllib.request
label, kind, samp, out, path, code, port = sys.argv[1:8]
def post(ep, body):
    return json.load(urllib.request.urlopen(urllib.request.Request(f"http://localhost:{port}/{ep}", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}), timeout=900))
doc = post("detokenize", {"tokens": post("tokenize", {"content": open(path).read()})["tokens"][:2048]})["content"]
msg = {"prose": doc + "\n\nSummarise the document above in a few paragraphs.",
       "json": doc + '\n\nList every backend the document above describes as a JSON array of objects with the keys "name", "cmake_flag" and "platforms" (an array of strings). Output only the JSON.',
       "quote": doc + "\n\nQuote, word for word, the three sentences of the document above that best explain how to do a CPU build and speed it up. Put each on its own line and output only the quotes.",
       "code": "```cpp\n" + open(code).read() + "```\n\nRename the function `release` to `release_request` everywhere in the file above. Output the whole file, changed, and nothing else."}[kind]
body = {"messages": [{"role": "user", "content": msg}], "max_tokens": 256, "seed": 42,
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
rounds=${ROUNDS:-3}
for chip in ${CHIPS:-npu cpu}; do
  case $chip in cpu) dev="-dev none"; arms="off mod k4v" ;; npu) dev="-dev HTP0 -ngl 99"; arms="off mod k4v mtp modmtp" ;; gpu) dev="-dev GPUOpenCL -ngl 99"; arms="off mod k4v" ;; esac
  arms=(${ARMS:-$arms}); n=${#arms[@]}
  for r in $(seq 1 $rounds); do
    cool
    for i in $(seq 0 $((n-1))); do
      arm=${arms[$(( (i + r - 1) % n ))]}
      guard
      start "$dev" $arm || { adb shell tail -40 $root/out/pf2.log > "$here/logs/$chip-r$r-$arm-fail.log"; echo "!! $chip r$r $arm failed to start"; continue; }
      echo "== $chip round $r arm=$arm thermal=$(thermal)"
      for w in "prose greedy" "prose default" "json greedy" "json agent" "code greedy" "quote greedy"; do
        set -- $w; ask "$chip $arm" $1 $2 "$here/replies/$chip-r$r-$arm-$1-$2.txt"
        adb shell "pidof llama-server >/dev/null" || { echo "!! $chip r$r $arm died after $1-$2"; adb shell tail -40 $root/out/pf2.log > "$here/logs/$chip-r$r-$arm-crash.log"; break; }
      done
      adb shell "grep -E 'statistics|draft acceptance|accept' $root/out/pf2.log | tail -8" > "$here/logs/$chip-r$r-$arm-stats.log"
    done
  done
done
stop
