#!/usr/bin/env bash
# Downloads the small models llama.cpp's own server tests use (tools/server/tests/utils.py ServerPreset) for the SDK's
# embedding, rerank and LoRA tests (F69): about 225 MB from Hugging Face's ggml-org. Skips files already there.
# usage: sdk/scripts/fetch-test-models.sh     env: LB_TEST_MODELS_DIR (default ~/Downloads/llamabro-test)
set -euo pipefail
dir="${LB_TEST_MODELS_DIR:-$HOME/Downloads/llamabro-test}"
mkdir -p "$dir"
fetch() {  # hub-path file
  [ -s "$dir/$2" ] && { echo "have $2"; return; }
  echo "fetch $2"
  curl -fL --retry 3 -o "$dir/$2.part" "https://huggingface.co/$1" && mv "$dir/$2.part" "$dir/$2"
}
fetch ggml-org/models/resolve/main/bert-bge-small/ggml-model-f16.gguf bge-small-f16.gguf
fetch ggml-org/models/resolve/main/jina-reranker-v1-tiny-en/ggml-model-f16.gguf jina-reranker-tiny-f16.gguf
fetch ggml-org/stories15M_MOE/resolve/main/stories15M_MOE-F16.gguf stories15M_MOE-F16.gguf
fetch ggml-org/stories15M_MOE/resolve/main/moe_shakespeare15M.gguf moe_shakespeare15M.gguf
