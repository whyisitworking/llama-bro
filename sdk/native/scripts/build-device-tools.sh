#!/usr/bin/env bash
# Builds llama-server, lb-run and llama-fit-params for the phone, in the Snapdragon image like
# build-backends.sh, from the same patched tree and CMakeLists.txt. Unlike the AAR build, the libraries keep
# upstream's names (LB_PREFIX_LIBS=OFF), so llama-server finds its backends exactly as upstream does; both tools run
# from one directory on the phone with LD_LIBRARY_PATH set to it.
# Output: sdk/native/build/android-device-tools/ (executables, every .so they need, the v79 skeleton, libc++).
# Needs scripts/prepare-llama.sh first.
# Usage: build-device-tools.sh   (env: LB_DEVICE_TOOLS_NAME output name, default android-device-tools;
#                                  LB_EXTRA_CMAKE_ARGS more -D options for a comparison build;
#                                  LB_EXTRA_TARGETS more targets, e.g. ggml-opencl with
#                                  LB_EXTRA_CMAKE_ARGS='"-DLB_BACKENDS=hexagon;opencl"' (the inner quotes keep the ;
#                                  out of the container's shell))
set -euo pipefail

native="$(cd "$(dirname "$0")/.." && pwd)"
image="ghcr.io/snapdragon-toolchain/arm64-android:v0.8"
ndk_rev="$(tr -d '[:space:]' < "$native/ndk.version")"  # the SDK's one NDK pin
name="${LB_DEVICE_TOOLS_NAME:-android-device-tools}"
extra="${LB_EXTRA_CMAKE_ARGS:-}"
targets="${LB_EXTRA_TARGETS:-}"
build="$native/build/$name-build"
out="$native/build/$name"

[ -f "$native/build/llama.cpp/.lb-stamp" ] || { echo "no patched llama.cpp copy: run scripts/prepare-llama.sh" >&2; exit 1; }

docker run --rm -u "$(id -u):$(id -g)" --platform linux/amd64 --volume "$native:/native" -w /native "$image" bash -lc '
  set -euo pipefail
  grep -q "Pkg.Revision = '"$ndk_rev"'" "$ANDROID_NDK_ROOT/source.properties" ||
    { echo "image NDK is not '"$ndk_rev"'"; exit 1; }
  cmake -S /native -B /native/build/'"$name"'-build -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE=Release -DLB_LLAMA_DIR=/native/build/llama.cpp -DLB_BACKENDS=hexagon \
        -DLB_BUILD_TESTS=ON -DLB_PREFIX_LIBS=OFF -DPREBUILT_LIB_DIR=android_aarch64 '"$extra"' >/dev/null
  cpu=$(cmake --build /native/build/'"$name"'-build --target help | sed -n "s/^\(ggml-cpu[^:]*\):.*/\1/p" | tr "\n" " ")
  cmake --build /native/build/'"$name"'-build --target llama-server lb-run llama-fit-params ggml-hexagon htp-v79 $cpu '"$targets"'
  # the NDK runtime the AAR ships too (check-aar.sh); no libomp.so, as OpenMP is off (D38)
  cp "$(find "$ANDROID_NDK_ROOT" -path "*aarch64-linux-android/libc++_shared.so" -print -quit)" /native/build/'"$name"'-build/
'

rm -rf "$out" && mkdir -p "$out"
for f in llama-server lb-run llama-fit-params; do
    exe="$(find "$build" -type f -name "$f" -perm -u+x -print -quit)"
    [ -n "$exe" ] || { echo "$f not built" >&2; exit 1; }
    cp "$exe" "$out/"
done
find "$build" -name '*.so' -not -path '*/CMakeFiles/*' -exec cp {} "$out/" \;
ls "$out"
