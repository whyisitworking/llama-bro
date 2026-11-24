#!/usr/bin/env bash
# Builds the optional backends in the Snapdragon image (F18, F34):
#   hexagon: libllamabro-ggml-hexagon.so and the HTP skeletons libggml-htp-v73/v75/v79/v81/v85.so (Hexagon SDK)
#   opencl:  libllamabro-ggml-opencl.so, linked against the image sysroot's libOpenCL.so; the phone's own
#            libOpenCL.so is used at run time (<uses-native-library>), so none is shipped.
# It runs this repo's sdk/native/CMakeLists.txt with LB_BACKENDS, so ggml-base comes from the same file and options
# as the AGP build and the backend links it by the same soname. The image's NDK is the SDK's (ndk.version,
# checked below), so one libc++_shared.so serves every library in the APK.
# Output, one directory per backend module: sdk/native/build/android-backends/hexagon/{jniLibs/arm64-v8a,
# assets/llamabro/htp} (each skeleton plus its .sha256, which the app checks after copying it out: SkeletonInstall.kt)
# and .../opencl/jniLibs/arm64-v8a. Vulkan is not built (D31); its recipe is research/vulkan-adreno/ (F174). Needs scripts/prepare-llama.sh first.
# Usage: build-backends.sh
set -euo pipefail

native="$(cd "$(dirname "$0")/.." && pwd)"
image="ghcr.io/snapdragon-toolchain/arm64-android:v0.8"
ndk_rev="$(tr -d '[:space:]' < "$native/ndk.version")"  # the SDK's one NDK pin
out="$native/build/android-backends"
skels="v73 v75 v79 v81 v85"

[ -f "$native/build/llama.cpp/.lb-stamp" ] || { echo "no patched llama.cpp copy: run scripts/prepare-llama.sh" >&2; exit 1; }

docker run --rm -u "$(id -u):$(id -g)" --platform linux/amd64 --volume "$native:/native" -w /native "$image" bash -lc '
  set -euo pipefail
  grep -q "Pkg.Revision = '"$ndk_rev"'" "$ANDROID_NDK_ROOT/source.properties" ||
    { echo "image NDK is not '"$ndk_rev"':"; cat "$ANDROID_NDK_ROOT/source.properties"; exit 1; }
  # the same Android settings AGP passes for :llamabro (minSdk 29, c++_shared, Release)
  cmake -S /native -B /native/build/android-backends-build -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE=Release -DLB_LLAMA_DIR=/native/build/llama.cpp "-DLB_BACKENDS=hexagon;opencl" \
        -DPREBUILT_LIB_DIR=android_aarch64  # the Hexagon SDK cmake needs it, as upstream'"'"'s Snapdragon preset sets it
  targets="ggml-hexagon ggml-opencl"
  for v in '"$skels"'; do targets="$targets htp-$v"; done
  cmake --build /native/build/android-backends-build --target $targets
'

build="$native/build/android-backends-build"
htp="$out/hexagon/assets/llamabro/htp"
rm -rf "$out" && mkdir -p "$out/hexagon/jniLibs/arm64-v8a" "$htp" "$out/opencl/jniLibs/arm64-v8a"
for b in hexagon opencl; do
  f="$(find "$build" -name "libllamabro-ggml-$b.so" -not -path '*/CMakeFiles/*' -print -quit)"
  [ -n "$f" ] || { echo "libllamabro-ggml-$b.so not built" >&2; exit 1; }
  cp "$f" "$out/$b/jniLibs/arm64-v8a/"
done
for v in $skels; do
  f="$(find "$build" -name "libggml-htp-$v.so" -not -path '*/CMakeFiles/*' -print -quit)"
  [ -n "$f" ] || { echo "skeleton $v not built" >&2; exit 1; }
  cp "$f" "$htp/"
  shasum -a 256 "$htp/libggml-htp-$v.so" | cut -d' ' -f1 > "$htp/libggml-htp-$v.so.sha256"
done
ls -la "$out"/*/jniLibs/arm64-v8a "$htp"
