#!/usr/bin/env bash
# Builds one spike variant (U or S) for macOS with Metal (llama.cpp's default on Apple silicon).
# Output: research/p0/build/out-macos-<variant>/bin
set -euo pipefail

variant="${1:?usage: build-macos.sh U|S}"
here="$(cd "$(dirname "$0")/.." && pwd)"
src="$here/build/src-$variant"
out="$here/build/out-macos-$variant"

cmake -S "$src" -B "$src/build-macos" -G Ninja -DCMAKE_BUILD_TYPE=Release \
      -DGGML_METAL=ON -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_UI=OFF -DLLAMA_OPENSSL=OFF
cmake --build "$src/build-macos" --target llama-server llama-perplexity

rm -rf "$out" && mkdir -p "$out"
cp -R "$src/build-macos/bin" "$out/"
ls -la "$out/bin"
