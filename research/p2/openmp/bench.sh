#!/usr/bin/env bash
# Does OpenMP change CPU speed? The SDK's CMakeLists.txt leaves GGML_OPENMP on (llama.cpp's default); upstream's
# Snapdragon preset, which built every llama-server the phone spikes measured, turns it off. Two builds from the
# same tree that differ only in -DGGML_OPENMP (build-device-tools.sh), Qwen3.5 4B on the CPU, a fresh load per arm,
# arms alternating, thermal status <= 2 before each. Prompt ~2K tokens from research/p2/mtp/source.txt, 128 tokens greedy.
# Usage: ANDROID_SERIAL=... bench.sh ROUNDS   -> one line per arm: round arm prefill_tps decode_tps
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
build="$here/../../../sdk/native/build"
rounds="${1:-2}"
model=/data/local/tmp/lb-spike/models/Qwen3.5-4B-Q4_K_M.gguf
port=18184
body="$(python3 -c '
import json, sys
text = open(sys.argv[1]).read()[:8000]
print(json.dumps({"messages": [{"role": "user", "content": "Summarise this document:\n\n" + text}],
                  "max_tokens": 128, "temperature": 0, "chat_template_kwargs": {"enable_thinking": False}}))' "$here/../mtp/source.txt")"

for arm in omp noomp; do
    dir="$build/android-device-tools"; [ $arm = omp ] || dir="$dir-noomp"
    adb shell "rm -rf /data/local/tmp/lb-omp/$arm && mkdir -p /data/local/tmp/lb-omp/$arm"
    adb push -q "$dir/." "/data/local/tmp/lb-omp/$arm/" >/dev/null
done
cool() {
    for _ in $(seq 1 60); do
        s="$(adb shell dumpsys thermalservice | sed -n 's/^Thermal Status: //p' | tr -d '\r')"
        [ "${s:-0}" -le 2 ] && return 0; sleep 10
    done
}
adb forward tcp:$port tcp:$port >/dev/null
trap 'adb shell pkill -x llama-server || true; adb forward --remove tcp:'$port' || true' EXIT
for r in $(seq 1 "$rounds"); do
    arms="omp noomp"; [ $((r % 2)) = 0 ] && arms="noomp omp"
    for arm in $arms; do
        cool
        d=/data/local/tmp/lb-omp/$arm
        adb shell "cd $d && LD_LIBRARY_PATH=$d exec ./llama-server -m $model -c 8192 -np 1 --cache-ram 0 --load-mode none \
            --fit off -b 512 -ub 512 -dev none --host 127.0.0.1 --port $port > server.log 2>&1" < /dev/null > /dev/null 2>&1 &
        until curl -sf "http://127.0.0.1:$port/health" >/dev/null 2>&1; do sleep 1; done
        curl -s "http://127.0.0.1:$port/v1/chat/completions" -d "$body" -H 'Content-Type: application/json' |
            python3 -c 'import json,sys; t=json.load(sys.stdin)["timings"]; print(sys.argv[1], sys.argv[2], t["prompt_n"], round(t["prompt_per_second"],2), t["predicted_n"], round(t["predicted_per_second"],2))' "$r" "$arm"
        adb shell pkill -x llama-server || true; wait 2>/dev/null || true; sleep 3
    done
done
