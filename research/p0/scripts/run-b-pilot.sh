#!/bin/bash
# Test B pilot on the Mac: GSM8K, 200 problems x seeds 1,2,3, arms U / B1 / U-full on build U, B2-full on build S.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=results/raw/b/pilot.jsonl
MODEL=~/Downloads/Qwen3.5-4B-Q4_K_M.gguf
serve() {
  pkill -f "bin/llama-server" || true; sleep 2
  build/out-macos-$1/bin/llama-server -m $MODEL -c 65536 -np 16 -ngl 99 --port 8080 --no-webui > results/raw/b/server-$1.log 2>&1 &
  for i in $(seq 120); do curl -s localhost:8080/health | grep -q ok && return; sleep 1; done; echo "server $1 failed"; exit 1
}
serve U
for arm in U B1 U-full; do python3 scripts/b-run.py $arm 200 1,2,3 $OUT --threads 16; done
serve S
python3 scripts/b-run.py B2-full 200 1,2,3 $OUT --threads 16
pkill -f "bin/llama-server" || true
echo PILOT DONE
