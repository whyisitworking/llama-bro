#!/usr/bin/env bash
# P2 T8: MTP off vs on (--spec-type draft-mtp, n_max 3) per chip, Qwen3.5 4B, upstream llama-server (P0 spike's P
# build: b12052 + the shipped delta-net patch), the SDK's settings. Prompts: llama.cpp's docs/build.md cut to 512 and
# 2048 tokens plus a summarise instruction; 256-token replies with the model's default sampling and seed 42. MTP is a
# load setting, so every arm is a fresh load; arms alternate within each round (F6).
# usage: [ROUNDS=5] [CHIPS="cpu npu gpu"] mtp.sh     (findings: P2 T8)
set -uo pipefail
export ANDROID_SERIAL="${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the device}"
root=/data/local/tmp/lb-spike; L=$root/P/lib; port=8099
here="$(cd "$(dirname "$0")" && pwd)"
wait_adb() { for i in $(seq 1 120); do adb get-state >/dev/null 2>&1 && return 0; sleep 1; done; echo "adb gone"; exit 1; }
stop() { wait_adb; adb shell "pkill -x llama-server; sleep 2; pkill -9 -x llama-server" >/dev/null 2>&1; sleep 2; }
thermal() { adb shell dumpsys thermalservice | grep -m1 -oE 'Thermal Status: [0-9]+' | grep -oE '[0-9]+$'; }
cool() { for i in $(seq 1 60); do [ "$(thermal)" -le 1 ] && return; sleep 20; done; }
start() {  # dev-args mtp(0/1)
  local spec=""; [ "$2" = 1 ] && spec="--spec-type draft-mtp --spec-draft-n-max 3"
  stop; wait_adb; adb forward tcp:$port tcp:$port >/dev/null
  adb shell "cd $root && LD_LIBRARY_PATH=$L ADSP_LIBRARY_PATH=$L CDSP_LIBRARY_PATH=$L nohup $root/P/bin/llama-server -m models/Qwen3.5-4B-Q4_K_M.gguf -c 8192 -np 1 -b 512 -ub 512 --cache-ram 1024 --ctx-checkpoints 8 --load-mode none --fit off $1 $spec --port $port > $root/out/t8.log 2>&1" </dev/null >/dev/null 2>&1 &
  for i in $(seq 1 600); do curl -s -o /dev/null -w '%{http_code}' localhost:$port/health 2>/dev/null | grep -q 200 && return 0; sleep 0.5; done
  echo "server never ready"; adb shell tail -3 $root/out/t8.log; return 1
}
prompt() {  # n tokens of the source text, via this server's tokenizer
  python3 - "$1" "$here/source.txt" "$port" <<'PY'
import json, sys, urllib.request
n, path, port = int(sys.argv[1]), sys.argv[2], sys.argv[3]
def post(ep, body):
    return json.load(urllib.request.urlopen(urllib.request.Request(f"http://localhost:{port}/{ep}", data=json.dumps(body).encode())))
toks = post("tokenize", {"content": open(path).read()})["tokens"][:n]
print(json.dumps(post("detokenize", {"tokens": toks})["content"] + "\n\nSummarise the document above in a few paragraphs."))
PY
}
ask() {  # label prompt-json
  curl -s localhost:$port/completion -d "{\"prompt\":$2,\"n_predict\":256,\"seed\":42,\"cache_prompt\":false}" | python3 -c '
import sys, json
t = json.load(sys.stdin)["timings"]
acc = "draft_n=%d accepted=%d" % (t["draft_n"], t["draft_n_accepted"]) if "draft_n" in t else "draft_n=0 accepted=0"
print("%s prompt_n=%d prefill_tps=%.1f predicted_n=%d decode_tps=%.2f %s" % (sys.argv[1], t["prompt_n"], t["prompt_per_second"], t["predicted_n"], t["predicted_per_second"], acc))' "$1"
}
rounds=${ROUNDS:-5}
for chip in ${CHIPS:-cpu npu gpu}; do
  case $chip in cpu) dev="-dev none" ;; npu) dev="-dev HTP0 -ngl 99" ;; gpu) dev="-dev GPUOpenCL -ngl 99" ;; esac
  for r in $(seq 1 $rounds); do
    cool
    order="0 1"; (( r % 2 == 0 )) && order="1 0"
    for mtp in $order; do
      start "$dev" $mtp || continue
      p512=$(prompt 512); p2k=$(prompt 2048)
      echo "== $chip round $r mtp=$mtp thermal=$(thermal)"
      ask "$chip mtp=$mtp p512" "$p512"
      ask "$chip mtp=$mtp p2k" "$p2k"
    done
  done
done
[ -n "${KEEP_LOG:-}" ] && adb shell tail -30 $root/out/t8.log; stop
