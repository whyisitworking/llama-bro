#!/usr/bin/env bash
# Builds libprobe.so with NDK 29 and gathers the spike's P libraries (b12052 + delta-net patch) and the v79 skeleton.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
spike="$here/../../p0/build/out-android-${V:-P}/lib"
ndk="${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/29.0.14206865}"
cc="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang"
libs="$here/build/probe-libs/arm64-v8a"; assets="$here/build/probe-assets/htp"
rm -rf "$here/build/probe-libs" "$here/build/probe-assets"; mkdir -p "$libs" "$assets"
"$cc" -shared -fPIC -O2 -Wall -o "$libs/libprobe.so" "$here/native/probe.c" -llog -ldl
for f in "$spike"/*.so; do
  case "$(basename "$f")" in
    libggml-htp-v*.so) cp "$f" "$assets/" ;;                         # runs on the DSP: an asset, copied out at start
    libllama-bench-impl.so|libllama-cli-impl.so|libllama-perplexity-impl.so) ;;
    *) cp "$f" "$libs/" ;;
  esac
done
ls -la "$libs" "$assets"
