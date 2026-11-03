#!/usr/bin/env bash
# Rest of session 2: test C (2 rounds), checkpoint trace run, CPU check of the patch (MTP on, U/P alternating).
set -uo pipefail
cd "$(dirname "$0")/.."
./scripts/run-c.sh
cpu="-m models/Qwen3.5-4B-Q4_K_M.gguf -dev none -ngl 0 -c 16384 -t 6 -tb 6 --spec-type draft-mtp --spec-draft-n-max 3"
for r in 1 2; do
  order="U P"; (( r % 2 == 0 )) && order="P U"
  for v in $order; do
    ./scripts/session.sh cool
    VARIANT=$v ./scripts/session.sh u cpu-a-$v$r c-cpu.jsonl $cpu
  done
done
