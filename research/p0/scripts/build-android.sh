#!/usr/bin/env bash
# Builds one spike variant (U, P or S) for Android with upstream's Snapdragon toolchain and preset
# (docs/backend/snapdragon/README.md in llama.cpp b12052). Output: research/p0/build/out-android-<variant>/{bin,lib}
set -euo pipefail

variant="${1:?usage: build-android.sh U|P|S}"
here="$(cd "$(dirname "$0")/.." && pwd)"
src="$here/build/src-$variant"
out="$here/build/out-android-$variant"
image="ghcr.io/snapdragon-toolchain/arm64-android:v0.8"

[[ -d "$src" ]] || { echo "missing $src" >&2; exit 1; }

docker run --rm -u "$(id -u):$(id -g)" --platform linux/amd64 --volume "$src:/workspace" --volume "$here/harness:/spike-harness:ro" -w /workspace "$image" bash -lc '
  set -euo pipefail
  cp docs/backend/snapdragon/CMakeUserPresets.json .
  cmake --preset arm64-android-snapdragon-release -B build-snapdragon -G Ninja \
        -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_UI=OFF -DLLAMA_SUBPROCESS=OFF -DLB_HARNESS_DIR=/spike-harness
  targets="llama-server llama-bench llama-perplexity llama-cli htp-v79"
  grep -q "lb-spike harness" tools/server/CMakeLists.txt && targets="$targets lb-spike"
  cmake --build build-snapdragon --target $targets
'

rm -rf "$out" && mkdir -p "$out/bin" "$out/lib"
for t in llama-server llama-bench llama-perplexity llama-cli lb-spike; do
  if [[ -f "$src/build-snapdragon/bin/$t" ]]; then cp "$src/build-snapdragon/bin/$t" "$out/bin/"; fi
done
find "$src/build-snapdragon" -name '*.so' -not -path '*/CMakeFiles/*' -exec cp {} "$out/lib/" \;
ls -la "$out/bin" "$out/lib"
