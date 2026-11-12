#!/usr/bin/env bash
# PF T1 side check: is upstream llama-server's OpenCL output readable at all? (T1 found "888..." on the GPU, both arms.)
# Per model: OpenCL and CPU, a short prompt (one ubatch) and the ~2K-token T1 prompt (four 512-token ubatches),
# greedy, 64 tokens, backend sampling off. Same build and SDK settings as bs.sh.
set -uo pipefail
export ANDROID_SERIAL="${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the device}"
root=/data/local/tmp/lb-spike; L=$root/P/lib; port=8099
here="$(cd "$(dirname "$0")" && pwd)"; src="$here/../../../p2/mtp/source.txt"
stop() { adb shell "pkill -x llama-server; sleep 2; pkill -9 -x llama-server" >/dev/null 2>&1; sleep 2; }
for m in Qwen3.5-4B-Q4_K_M Llama-3.2-3B-Instruct-Q4_K_M; do
  for dev in "-dev GPUOpenCL -ngl 99" "-dev none"; do
    stop; adb forward tcp:$port tcp:$port >/dev/null
    adb shell "cd $root && LD_LIBRARY_PATH=$L nohup $root/P/bin/llama-server -m models/$m.gguf -c 8192 -np 1 -b 512 -ub 512 --cache-ram 1024 --ctx-checkpoints 8 --load-mode none --fit off $dev --port $port > $root/out/pfoc.log 2>&1" </dev/null >/dev/null 2>&1 &
    for i in $(seq 1 600); do curl -s localhost:$port/health 2>/dev/null | grep -q ok && break; sleep 0.5; done
    for p in short long; do
      python3 - "$m" "$dev" "$p" "$src" "$port" <<'PY'
import json, sys, urllib.request
m, dev, p, path, port = sys.argv[1:6]
def post(ep, body):
    return json.load(urllib.request.urlopen(urllib.request.Request(f"http://localhost:{port}/{ep}", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}), timeout=900))
if p == "short": msg = "Write one sentence about the sea."
else:
    doc = post("detokenize", {"tokens": post("tokenize", {"content": open(path).read()})["tokens"][:2048]})["content"]
    msg = doc + "\n\nSummarise the document above in a few paragraphs."
r = post("v1/chat/completions", {"messages": [{"role": "user", "content": msg}], "max_tokens": 64, "temperature": 0, "cache_prompt": False, "chat_template_kwargs": {"enable_thinking": False}})
print("%s | %s | %s | prompt_n=%d | %r" % (m, dev, p, r["timings"]["prompt_n"], r["choices"][0]["message"]["content"][:160]))
PY
    done
  done
done
stop
