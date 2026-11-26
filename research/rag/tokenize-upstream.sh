#!/usr/bin/env bash
# F190: does upstream llama-server --embeddings answer POST /tokenize? Starts it on bge-small from the host build,
# posts one text, prints the status and body. Needs the host build and sdk/scripts/fetch-test-models.sh's models.
# usage: research/rag/tokenize-upstream.sh     env: LB_TEST_MODELS_DIR (default ~/Downloads/llamabro-test)
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
bin="$root/sdk/native/build/host/bin"
model="${LB_TEST_MODELS_DIR:-$HOME/Downloads/llamabro-test}/bge-small-f16.gguf"
[ -x "$bin/llama-server" ] || { echo "FAIL: $bin/llama-server missing (build the host preset)"; exit 1; }
[ -f "$model" ] || { echo "FAIL: $model missing (sdk/scripts/fetch-test-models.sh)"; exit 1; }
log="$(mktemp)"
"$bin/llama-server" -m "$model" --embeddings -c 512 -np 1 --host 127.0.0.1 --port 0 > "$log" 2>&1 &
pid=$!
trap 'kill $pid 2> /dev/null || true; rm -f "$log"' EXIT
port=""
for _ in $(seq 1 120); do
    port="$(grep -Eo 'listening on http://127.0.0.1:[0-9]+' "$log" | grep -Eo '[0-9]+$' | head -1 || true)"
    [ -n "$port" ] && curl -fs "http://127.0.0.1:$port/health" > /dev/null 2>&1 && break
    kill -0 $pid 2> /dev/null || { echo "FAIL: llama-server exited"; tail -20 "$log"; exit 1; }
    sleep 0.5
done
[ -n "$port" ] || { echo "FAIL: no port"; tail -20 "$log"; exit 1; }
echo "POST /tokenize on --embeddings:"
curl -s -w '\nHTTP %{http_code}\n' "http://127.0.0.1:$port/tokenize" -H 'Content-Type: application/json' \
    -d '{"content":"A cat is sleeping on the sofa."}'
