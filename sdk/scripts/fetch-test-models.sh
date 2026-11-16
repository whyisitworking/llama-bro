#!/usr/bin/env bash
# Downloads the small models llama.cpp's own server tests use (tools/server/tests/utils.py ServerPreset) for the SDK's
# embedding, rerank and LoRA tests (F69) and audio input and transcription (D84, F55, ServerPreset.tinylfm3omni): from
# Hugging Face's ggml-org and ggml-org-dev. Skips files already there.
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
fetch ggml-org-dev/tinyLFM3-Omni-for-testing-gguf/resolve/main/tinyLFM3-Omni-for-testing-Q2_K.gguf tinyLFM3-Omni-for-testing-Q2_K.gguf
fetch ggml-org-dev/tinyLFM3-Omni-for-testing-gguf/resolve/main/mmproj-tinyLFM3-Omni-for-testing-Q2_K.gguf mmproj-tinyLFM3-Omni-for-testing-Q2_K.gguf
