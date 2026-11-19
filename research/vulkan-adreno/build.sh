#!/usr/bin/env bash
# Builds upstream llama.cpp for the phone on this Mac, with NDK 29 and the SDK's Android settings: llama-server,
# llama-bench, llama-cli, test-backend-ops, the CPU variants and the Vulkan and OpenCL backends, with upstream's library
# names, so one directory runs every arm (F174). Vulkan can't build in the Snapdragon image (F64), hence the host NDK.
# Without SRC it copies the SDK's patched tree (sdk/native/build/llama.cpp, from prepare-llama.sh) to
# sdk/native/build/llama.cpp-vk and applies this folder's patches/vk*.patch in name order: Qualcomm's three upstream
# fixes and our Adreno 830 patch. They stay out of sdk/native/patches because Vulkan is not in the build (D31).
# Pass SRC=sdk/native/build/llama.cpp to build without them, which reproduces the failures.
# Needs sdk/native/scripts/prepare-llama.sh and this folder's fetch-deps.sh first. Run from the repository root.
# Output: sdk/native/build/NAME (and NAME-build). Usage: research/vulkan-adreno/build.sh NAME [SRC]
set -euo pipefail
native="$(pwd)/sdk/native"
name="${1:-vk-tools}"; src="${2:-}"
if [ -z "$src" ]; then
  src="$native/build/llama.cpp-vk"
  [ -f "$native/build/llama.cpp/.lb-stamp" ] || { echo "no patched llama.cpp copy: run sdk/native/scripts/prepare-llama.sh sdk/native/build" >&2; exit 1; }
  rm -rf "$src" && cp -R "$native/build/llama.cpp" "$src"
  for p in "$(cd "$(dirname "$0")" && pwd)"/patches/vk*.patch; do
    (cd "$src" && GIT_CEILING_DIRECTORIES="$native/build" git apply --whitespace=nowarn "$p") || { echo "$(basename "$p") does not apply" >&2; exit 1; }
    echo "applied $(basename "$p")"
  done
fi
ndk="$HOME/Library/Android/sdk/ndk/29.0.14206865"
deps="$native/build/deps"; spirv="$deps/SPIRV-Headers-install"
[ -f "$spirv/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake" ] || {
  cmake -S "$deps/SPIRV-Headers" -B "$deps/SPIRV-Headers-build" -G Ninja -DCMAKE_INSTALL_PREFIX="$spirv" -DSPIRV_HEADERS_ENABLE_TESTS=OFF >/dev/null
  cmake --install "$deps/SPIRV-Headers-build" >/dev/null; }
sysroot="$(ls -d "$ndk"/toolchains/llvm/prebuilt/*/sysroot | head -1)"
b="$native/build/$name-build"
cmake -S "$src" -B "$b" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_NATIVE=OFF \
  -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=ON -DLLAMA_BUILD_EXAMPLES=OFF \
  -DGGML_VULKAN=ON -DGGML_OPENCL=ON -DGGML_OPENCL_USE_ADRENO_KERNELS=ON -DGGML_OPENCL_EMBED_KERNELS=ON \
  -DVulkan_INCLUDE_DIR="$deps/Vulkan-Headers/include" \
  -DVulkan_LIBRARY="$sysroot/usr/lib/aarch64-linux-android/29/libvulkan.so" \
  -DVulkan_GLSLC_EXECUTABLE="$(ls "$ndk"/shader-tools/*/glslc | head -1)" \
  -DSPIRV-Headers_DIR="$spirv/share/cmake/SPIRV-Headers" ${LB_EXTRA_CMAKE_ARGS:-} >/dev/null
cmake --build "$b" --target llama-server test-backend-ops llama-cli llama-bench ggml-vulkan ggml-opencl \
  $(cmake --build "$b" --target help | sed -n "s/^\(ggml-cpu[^:]*\):.*/\1/p" | tr "\n" " ")
out="$native/build/$name"; rm -rf "$out"; mkdir -p "$out"
for t in llama-server test-backend-ops llama-cli llama-bench; do cp "$b/bin/$t" "$out/"; done
cp "$b"/bin/*.so "$out/"
cp "$(find "$ndk" -path "*aarch64-linux-android/libc++_shared.so" -print -quit)" "$out/"
ls -la "$out"
