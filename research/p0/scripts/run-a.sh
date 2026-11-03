#!/usr/bin/env bash
# Test A on the NPU: speed (3 rounds, U/P alternating, MTP on and off), then correctness with MTP on.
set -euo pipefail
cd "$(dirname "$0")/.."
base="-m models/Qwen3.5-4B-Q4_K_M.gguf -dev HTP0 -ngl 99 -c 16384 -t 6 -tb 6"
mtp="--spec-type draft-mtp --spec-draft-n-max 3"
for r in 1 2 3; do
  for m in on off; do
    flags="$base"; [[ $m == on ]] && flags="$base $mtp"
    order="U P"; (( r % 2 == 0 )) && order="P U"
    for v in $order; do
      ./scripts/session.sh cool
      VARIANT=$v ./scripts/session.sh u npu-a-mtp$m-$v$r t3-all.jsonl $flags
    done
  done
done
for v in U P; do VARIANT=$v ./scripts/session.sh u npu-a-lp-$v a-lp.jsonl $base $mtp; done
