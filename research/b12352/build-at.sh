#!/bin/bash
# Builds upstream llama-server for the phone at one llama.cpp commit, with upstream's own CMake and the SDK's CPU
# options (all CPU variants, no OpenMP) plus the Hexagon backend, in Qualcomm's image like build-device-tools.sh.
# usage: build-at.sh <commit> [name] -> sdk/native/build/bisect/<name>-out (llama-server and its libraries)
#   name defaults to the short commit; PATCHES="a.patch b.patch" applies those files to the copy first
set -euo pipefail
native="$(cd "$(dirname "$0")/../../sdk/native" && pwd)"
sha=$(git -C "$native/third_party/llama.cpp" rev-parse --short "$1")
name="${2:-$sha}"
src="$native/build/bisect/$name/llama.cpp"
rm -rf "$src" && mkdir -p "$src"
git -C "$native/third_party/llama.cpp" archive --format=tar "$sha" | tar -x -m -C "$src"
for p in ${PATCHES:-}; do
    abs="$(cd "$(dirname "$p")" && pwd)/$(basename "$p")"   # resolved before cd, so a relative path works
    (cd "$src" && GIT_CEILING_DIRECTORIES="$(dirname "$src")" git apply --whitespace=nowarn "$abs")
    echo "applied $(basename "$p")"
done
echo "commit $sha" > "$src/.lb-stamp"
docker run --rm -u "$(id -u):$(id -g)" --platform linux/amd64 --volume "$native:/native" -w /native \
    ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash -lc "
  set -euo pipefail
  b=/native/build/bisect/$name/build
  cmake -S /native/build/bisect/$name/llama.cpp -B \$b -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE=\$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release -DGGML_OPENMP=OFF \
    -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_NATIVE=OFF -DGGML_HEXAGON=ON \
    -DHEXAGON_SDK_ROOT=\$HEXAGON_SDK_ROOT -DHEXAGON_TOOLS_ROOT=\$HEXAGON_TOOLS_ROOT -DLLAMA_CURL=OFF \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DPREBUILT_LIB_DIR=android_aarch64 > /dev/null
  cpu=\$(cmake --build \$b --target help | sed -n 's/^\(ggml-cpu[^:]*\):.*/\1/p' | tr '\n' ' ')
  cmake --build \$b --target llama-server ggml-hexagon htp-v79 \$cpu > /dev/null
  cp \$(find \$ANDROID_NDK_ROOT -path '*aarch64-linux-android/libc++_shared.so' -print -quit) \$b/
"
out="$native/build/bisect/$name-out"; rm -rf "$out"; mkdir -p "$out"
cp "$(find "$native/build/bisect/$name/build" -type f -name llama-server -perm -u+x -print -quit)" "$out/"
find "$native/build/bisect/$name/build" -name '*.so' -not -path '*/CMakeFiles/*' -exec cp {} "$out/" \;
ls "$out" | wc -l
