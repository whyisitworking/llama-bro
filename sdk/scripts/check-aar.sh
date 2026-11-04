#!/usr/bin/env bash
# Checks an llamabro AAR (P1 T6): the native libraries it must carry under our own names, the symbols
# libllamabro.so exports, and 16 KB LOAD alignment for every .so.
# usage: check-aar.sh AAR
set -euo pipefail

aar="${1:?usage: check-aar.sh AAR}"
sdk_dir="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
readelf="$(ls "$sdk_dir"/ndk/29.0.14206865/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | head -1)"
[ -x "$readelf" ] || { echo "FAIL: llvm-readelf from NDK 29.0.14206865 not found"; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unzip -q "$aar" 'jni/*' -d "$tmp"
libs="$tmp/jni/arm64-v8a"
[ -d "$libs" ] || { echo "FAIL: no jni/arm64-v8a in $aar"; exit 1; }

bad=0
for required in libllamabro.so libc++_shared.so libllamabro-ggml-base.so; do
    [ -f "$libs/$required" ] || { echo "missing: $required"; bad=1; }
done
ls "$libs"/libllamabro-ggml-cpu-android_armv8*.so >/dev/null 2>&1 || { echo "missing: libllamabro-ggml-cpu-android_armv8*.so"; bad=1; }

# our libraries carry our own names, so they can't collide with another llama.cpp build in the same app;
# only the NDK's runtimes keep their standard names
for so in "$libs"/*.so; do
    name="$(basename "$so")"
    case "$name" in
        libllamabro.so|libllamabro-*.so|libc++_shared.so|libomp.so) ;;
        *) echo "unprefixed library: $name"; bad=1 ;;
    esac
done

# libllamabro.so exports the C API and JNI_OnLoad only
nm="$(dirname "$readelf")/llvm-nm"
extra="$("$nm" -D --defined-only "$libs/libllamabro.so" | awk '{print $NF}' | grep -v -e '^lb_' -e '^JNI_OnLoad$' || true)"
if [ -n "$extra" ]; then
    echo "libllamabro.so exports $(echo "$extra" | wc -l | tr -d ' ') symbols beyond lb_* and JNI_OnLoad, e.g. $(echo "$extra" | head -3 | tr '\n' ' ')"
    bad=1
fi

for so in "$libs"/*.so; do
    # smallest LOAD alignment must be at least 0x4000 (16 KB pages)
    min_align="$("$readelf" -lW "$so" | awk '$1 == "LOAD" { print $NF }' | sort -u | head -1)"
    if [ -z "$min_align" ] || [ $((min_align)) -lt $((0x4000)) ]; then
        echo "alignment $min_align < 0x4000: $(basename "$so")"
        bad=1
    fi
done

[ "$bad" -eq 0 ] || { echo FAIL; exit 1; }
echo OK
