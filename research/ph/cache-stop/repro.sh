#!/bin/bash
# usage: repro.sh <path to llama-server> [extra server args...]
# Starts llama-server with the public tiny LFM3 test model, runs repro.py, stops the server.
set -e
BIN=${1:?path to llama-server}; shift
PORT=${PORT:-8089}
DIR=$(cd "$(dirname "$0")" && pwd)
"$BIN" -hf ggml-org-dev/tinyLFM3-Omni-for-testing-gguf:Q2_K -c 8192 -np 1 --port "$PORT" --no-warmup "$@" > repro-server.log 2>&1 &
pid=$!
trap 'kill $pid 2>/dev/null' EXIT
for i in $(seq 1 600); do
    kill -0 $pid 2>/dev/null || { echo "server exited, see repro-server.log"; exit 1; }
    curl -sf "http://127.0.0.1:$PORT/health" > /dev/null && break
    sleep 0.5
done
python3 "$DIR/repro.py" --port "$PORT"
