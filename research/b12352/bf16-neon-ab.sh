#!/bin/bash
# A/B of patch 0002 (upstream PR #30432, a NEON path for ggml_vec_dot_bf16): b12352 without and with it, built by
# build-at.sh and pushed to /data/local/tmp/ab-base and ab-neon. Per round and arm: the CPU image encoder with
# Qwen3.5 0.8B's BF16 projector (encoder-time.sh). Round 1 also: the F16 projector (a control the patch must not
# change) and greedy text replies on the CPU (Qwen3.5 4B, Q4_K: no BF16 matmuls, so they must be identical).
# usage: ANDROID_SERIAL=<serial> bf16-neon-ab.sh
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the phone}"; export ANDROID_SERIAL
out="${OUT:-$here/out}"; mkdir -p "$out"
adb forward tcp:8188 tcp:8188 >/dev/null

text_replies() { # $1 build dir on the phone, $2 output file
    adb shell pkill -f llama-server; sleep 2
    timeout 20 adb shell "cd $1 && LD_LIBRARY_PATH=$1 nohup ./llama-server \
        -m /data/local/tmp/lb-spike/models/Qwen3.5-4B-Q4_K_M.gguf -c 4096 -np 1 --load-mode none --fit off \
        --device none --port 8188 > $1/text.log 2>&1 </dev/null &"
    for _ in $(seq 1 120); do curl -s -m 2 http://127.0.0.1:8188/health 2>/dev/null | grep -q ok && break; sleep 2; done
    python3 - "$2" <<'PY'
import json, sys, urllib.request
prompts = ["Explain in two sentences why the sky is blue.", "List three capitals of European countries.",
           "What is 17 times 23? Show the calculation."]
out = []
for p in prompts:
    body = {"messages": [{"role": "user", "content": p}], "temperature": 0, "max_tokens": 64,
            "chat_template_kwargs": {"enable_thinking": False}}
    req = urllib.request.Request("http://127.0.0.1:8188/v1/chat/completions", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    out.append(json.load(urllib.request.urlopen(req, timeout=600))["choices"][0]["message"]["content"])
json.dump(out, open(sys.argv[1], "w"), indent=1)
PY
    adb shell pkill -f llama-server
}

for round in 1 2 3; do
    for arm in ab-base ab-neon; do
        "$here/encoder-time.sh" /data/local/tmp/$arm "round=$round arm=$arm bf16"
        if [ "$round" = 1 ]; then
            "$here/encoder-time.sh" /data/local/tmp/$arm "round=$round arm=$arm f16" q08-mm-f16.gguf
            text_replies /data/local/tmp/$arm "$out/$arm-text.json"
        fi
    done
done
python3 -c "import json,sys; a=json.load(open(sys.argv[1])); b=json.load(open(sys.argv[2])); \
print('text replies identical' if a == b else 'TEXT REPLIES DIFFER')" "$out/ab-base-text.json" "$out/ab-neon-text.json"
