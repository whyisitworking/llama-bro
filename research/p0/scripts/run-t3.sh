#!/usr/bin/env bash
# T3/T4 on the NPU: 5 rounds; each round runs U then H (round order alternates), MTP off then on.
set -euo pipefail
cd "$(dirname "$0")/.."
base="-m models/Qwen3.5-4B-Q4_K_M.gguf -dev HTP0 -ngl 99 -c 16384 -t 6 -tb 6"
for r in 1 2 3 4 5; do
  for mtp in off on; do
    flags="$base"; [[ $mtp == on ]] && flags="$base --spec-type draft-mtp --spec-draft-n-max 3"
    order="u h"; (( r % 2 == 0 )) && order="h u"
    for m in $order; do
      ./scripts/session.sh cool
      ./scripts/session.sh $m npu-t3-mtp$mtp-$m$r t3-all.jsonl $flags
    done
  done
done
