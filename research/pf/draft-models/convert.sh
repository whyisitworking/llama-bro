#!/usr/bin/env bash
# PF T3: how the sidecars were made (on the Mac, outside the submodule). Downloads approved by the maintainer 2026-11-06.
#   Qwen/Qwen3.5-4B              config.json, tokenizer*, vocab.json, merges.txt, chat_template.jinja (no weights)
#   z-lab/Qwen3.5-4B-DFlash      model.safetensors 1.27 GB, Apache-2.0
#   yuyijiong/Qwen3.5-4B-Eagle3  model.safetensors 466 MB, Apache-2.0 (SpecForge LlamaForCausalLMEagle3)
#   shanjiaz/Qwen3.5-4B-DSpark-b16-matched6-10k-20261005  model.safetensors 1.29 GB (speculators format)
# Converter: the submodule's convert_hf_to_gguf.py (b12052) in a venv from requirements-convert_hf_to_gguf.txt
# (transformers 4.57.6, which doesn't know qwen3_5: the script falls back to reading config.json, harmless).
# usage: DL=<download dir> OUT=<gguf dir> PY=<venv python> convert.sh
set -euo pipefail
LC="$(cd "$(dirname "$0")/../../../sdk/native/third_party/llama.cpp" && pwd)"
for n in DFlash:dflash Eagle3:eagle3 DSpark:dspark; do
  "$PY" "$LC/convert_hf_to_gguf.py" "$DL/${n##*:}" --target-model-dir "$DL/qwen35-4b" --outtype q8_0 \
    --outfile "$OUT/Qwen3.5-4B-${n%%:*}-Q8_0.gguf"
done
# then: adb push "$OUT"/Qwen3.5-4B-*-Q8_0.gguf /data/local/tmp/lb-spike/models/pf/
