#!/usr/bin/env bash
# Test C on the NPU (U, MTP off): n_batch/n_ubatch vs prefill speed, memory and prefill-cancel time; then
# one trace-level cache run (T7 requests) to read checkpoint and RAM-cache sizes.
set -euo pipefail
cd "$(dirname "$0")/.."
base="-m models/Qwen3.5-4B-Q4_K_M.gguf -dev HTP0 -ngl 99 -c 16384 -t 6 -tb 6"
for r in 1 2; do
  for cfg in 2048:512 2048:1024 2048:2048 1024:1024 512:512; do
    b=${cfg%:*}; ub=${cfg#*:}
    ./scripts/session.sh cool
    ./scripts/session.sh u npu-c-b$b-ub$ub-r$r c.jsonl $base -b $b -ub $ub
  done
done
./scripts/session.sh u npu-c-ckpt t7.jsonl $base -lv 4
