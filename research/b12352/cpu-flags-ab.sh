#!/bin/bash
# CPU text A/B: b12352's CPU variants built with default floating-point rules against -ffp-model=fast (F123).
# Qwen3.5 4B Q4_K_M on the CPU only. Per run: prefill of pp.json (n_predict 1), decode of 128 tokens, and in round 1
# the greedy replies to five prompts, saved per arm for comparison. Arms alternate; each run waits for thermal <= 1.
# usage: ANDROID_SERIAL=<serial> cpu-flags-ab.sh   (builds pushed to /data/local/tmp/cpu-default and cpu-fpfast)
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the phone}"; export ANDROID_SERIAL
out="${OUT:-$here/out}"; mkdir -p "$out"
model=/data/local/tmp/lb-spike/models/Qwen3.5-4B-Q4_K_M.gguf
api=http://127.0.0.1:8188

cool() {
    local t=9
    for _ in $(seq 1 120); do
        t=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | grep -o '[0-9]')
        [ "${t:-9}" -le 1 ] 2>/dev/null && break
        sleep 15
    done
    echo "$t"
}

start() { # $1 build dir on the phone
    adb shell pkill -f llama-server; sleep 2
    timeout 20 adb shell "cd $1 && LD_LIBRARY_PATH=$1 nohup ./llama-server -m $model -c 4096 -np 1 --load-mode none \
        --fit off --device none --port 8188 > $1/ab.log 2>&1 </dev/null &"
    for _ in $(seq 1 120); do curl -s -m 2 $api/health 2>/dev/null | grep -q ok && return; sleep 2; done
    echo "server did not start in $1" >&2
}

timing() { # $1 json body, $2 key
    curl -s -m 600 $api/completion -H 'Content-Type: application/json' -d "$1" |
        python3 -c "import json,sys; t=json.load(sys.stdin)['timings']; print(round(t['$2'], 2))"
}

replies() { # $1 arm
    python3 - "$out/$1-replies.json" <<'PY'
import json, sys, urllib.request
prompts = [
    "Explain in two sentences why the sky is blue.",
    "Write a Python function that returns the n-th Fibonacci number.",
    "List three capitals of European countries.",
    "What is 17 times 23? Show the calculation.",
    "Translate 'good morning, how are you?' into French.",
]
out = []
for p in prompts:
    body = {"messages": [{"role": "user", "content": p}], "temperature": 0, "max_tokens": 64,
            "chat_template_kwargs": {"enable_thinking": False}}
    req = urllib.request.Request("http://127.0.0.1:8188/v1/chat/completions", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    out.append(json.load(urllib.request.urlopen(req, timeout=600))["choices"][0]["message"]["content"])
json.dump(out, open(sys.argv[1], "w"), indent=1)
PY
}

adb forward tcp:8188 tcp:8188 >/dev/null
pp=$(cat "$here/pp.json")
tg='{"prompt":"Write a long story about a lighthouse keeper.","n_predict":128,"cache_prompt":false,"temperature":0,"ignore_eos":true}'
for round in 1 2 3; do
    for arm in cpu-default cpu-fpfast; do
        t=$(cool)
        start /data/local/tmp/$arm
        pps=$(timing "$pp" prompt_per_second)
        tgs=$(timing "$tg" predicted_per_second)
        [ "$round" = 1 ] && replies $arm
        echo "round=$round arm=$arm thermal=$t prefill_tps=$pps decode_tps=$tgs"
    done
done
adb shell pkill -f llama-server
python3 - "$out" <<'PY'
import json, sys
a = json.load(open(sys.argv[1] + "/cpu-default-replies.json")); b = json.load(open(sys.argv[1] + "/cpu-fpfast-replies.json"))
for i, (x, y) in enumerate(zip(a, b)):
    print(f"prompt {i}: {'identical' if x == y else 'DIFFERENT'}")
PY
