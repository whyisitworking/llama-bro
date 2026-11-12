#!/usr/bin/env bash
# F75 bisection: which op makes OpenCL answer Qwen3.5 4B with "8888..."? Each arm is an env setting for llama-server
# (GGML_OPENCL_OPFILTER = regex of op names OpenCL refuses, so the scheduler runs them on the CPU). Short prompt, greedy,
# 32 tokens (LONG=1: the ~2K-token prompt), same load settings as ../oc.sh. Usage: ob.sh [model] 'ENV=...' 'ENV=...' ...   ("-" = no env)
set -uo pipefail
export ANDROID_SERIAL="${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the device}"
here="$(cd "$(dirname "$0")" && pwd)"
root=/data/local/tmp/lb-spike; bin="${BIN:-P}"; L=$root/$bin/lib; port=8097
m="${1:-Qwen3.5-4B-Q4_K_M}"; shift
dev="${DEV:--dev GPUOpenCL -ngl 99}"
# kills only the server this script started (other jobs share the phone)
stop() { adb shell 'p=$(cat /data/local/tmp/lb-spike/out/pfob.pid 2>/dev/null); [ -n "$p" ] && { kill $p; sleep 2; kill -9 $p; rm -f /data/local/tmp/lb-spike/out/pfob.pid; }' >/dev/null 2>&1; sleep 2; }
for envs in "$@"; do
  [ "$envs" = "-" ] && envs=""
  stop
  if adb shell "for p in \$(pidof llama-server); do tr '\\0' ' ' < /proc/\$p/cmdline; echo; done" | grep -q -- "--port $port"; then echo "port $port still taken: stopping"; exit 1; fi
  adb forward tcp:$port tcp:$port >/dev/null
  adb shell "cd $root; $envs LD_LIBRARY_PATH=$L nohup $root/$bin/bin/llama-server -m models/$m.gguf -c 8192 -np 1 -b 512 -ub 512 --cache-ram 1024 --ctx-checkpoints 8 --load-mode none --fit off $dev ${EXTRA:-} --port $port > $root/out/pfob.log 2>&1 & echo \$! > $root/out/pfob.pid; wait" </dev/null >/dev/null 2>&1 &
  ok=0; for i in $(seq 1 600); do curl -s localhost:$port/health 2>/dev/null | grep -q ok && { ok=1; break; }; sleep 0.5; done
  [ $ok = 1 ] || { echo "$m | $bin | $envs | LOAD FAILED: $(adb shell tail -3 $root/out/pfob.log | tr '\n' ' ')"; continue; }
  HERE="$here" python3 - "$m" "$bin" "$envs" "$port" <<'PY'
import json, sys, urllib.request
m, b, envs, port = sys.argv[1:5]
import os
msg = "Write one sentence about the sea."
if os.environ.get("LONG"):  # the ~2K-token prompt of ../oc.sh
    def post(ep, b):
        return json.load(urllib.request.urlopen(urllib.request.Request(f"http://localhost:{port}/{ep}", data=json.dumps(b).encode(), headers={"Content-Type": "application/json"}), timeout=900))
    src = os.path.join(os.environ["HERE"], "../../../../p2/mtp/source.txt")
    msg = post("detokenize", {"tokens": post("tokenize", {"content": open(src).read()})["tokens"][:2048]})["content"] + "\n\nSummarise the document above in a few paragraphs."
body = {"messages": [{"role": "user", "content": msg}], "max_tokens": 32, "temperature": 0,
        "cache_prompt": False, "chat_template_kwargs": {"enable_thinking": False}}
req = urllib.request.Request(f"http://localhost:{port}/v1/chat/completions", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
try:
    r = json.load(urllib.request.urlopen(req, timeout=900)); print("%s | %s | %s | %r" % (m, b, envs or "(none)", r["choices"][0]["message"]["content"][:120]))
except Exception as e: print("%s | %s | %s | ERROR %s" % (m, b, envs, e))
PY
  adb shell "grep -i -E 'opfilter|split|graph_splits' $root/out/pfob.log | tail -3"
done
stop
