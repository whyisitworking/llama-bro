#!/usr/bin/env bash
# F167: one /v1/realtime turn per seed against a fresh upstream llama-server (CPU), to measure how often LFM3-Omni's
# text-and-audio reply runs away. A reply over 80 s of audio for the 7 s lighthouse question is a runaway.
# usage: [RT_VOICE=female RT_LANG=en RT_MODEL=FILE.gguf RT_THREADS=n RT_TIMEOUT=s] realtime-sweep.sh LLAMA_SERVER QUESTION.wav OUT_DIR PORT FIRST_SEED LAST_SEED [instructions]
#        (models from LB_TEST_MODELS_DIR, default ~/Downloads/llamabro-test; RT_MODEL defaults to its Q4_K_M)
set -uo pipefail
server=$1; question=$2; out=$3; port=$4; first=$5; last=$6; ins=${7:-}
here="$(cd "$(dirname "$0")" && pwd)"; M="${LB_TEST_MODELS_DIR:-$HOME/Downloads/llamabro-test}"
model="${RT_MODEL:-$M/LFM3-Omni-2B-Q4_K_M.gguf}"
mkdir -p "$out"
for seed in $(seq "$first" "$last"); do
  "$server" -m "$model" --mmproj "$M/mmproj-LFM3-Omni-2B-Q8_0.gguf" -c 2048 -np 1 --fit off \
    -dev none --no-mmproj-offload --seed "$seed" ${RT_THREADS:+-t "$RT_THREADS" -tb "$RT_THREADS"} --host 127.0.0.1 --port "$port" > "$out/srv.log" 2>&1 & pid=$!
  for _ in $(seq 60); do curl -sf "127.0.0.1:$port/health" > /dev/null && break; sleep 1; done
  timeout "${RT_TIMEOUT:-200}" python3 "$here/realtime-turn.py" "127.0.0.1:$port" "$question" "$out/s$seed" text,audio "$ins" > /dev/null 2>&1
  python3 -c "import json;d=json.load(open('$out/s$seed.json'))['summary'];print($seed, d['audio_s'], len(d['text'].split()), 'RUNAWAY' if d['audio_s'] > 80 else '')" 2>/dev/null || echo "$seed failed"
  kill $pid; wait $pid 2>/dev/null
done | tee "$out/results.txt"
