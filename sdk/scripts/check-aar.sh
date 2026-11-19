#!/usr/bin/env bash
# Checks an llamabro AAR: the native libraries it must carry under our own names, the symbols
# libllamabro.so exports, and 16 KB LOAD alignment for every .so.
# A backend AAR (llamabro-<backend>) is checked against the core AAR it runs with: its libraries are
# prefixed and 16 KB aligned, it carries no copy of the core's (one libc++_shared.so per APK, from the same NDK),
# every NEEDED library is in one of the two AARs or a system library, and each HTP skeleton matches its .sha256.
# usage: check-aar.sh AAR            (the core)
#        check-aar.sh AAR CORE_AAR   (a backend)
set -euo pipefail

aar="${1:?usage: check-aar.sh AAR [CORE_AAR]}"
core="${2:-}"
sdk_dir="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
readelf="$(ls "$sdk_dir"/ndk/29.0.14206865/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | head -1 || true)"
[ -x "$readelf" ] || { echo "FAIL: llvm-readelf from NDK 29.0.14206865 not found"; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unzip -q "$aar" 'jni/*' -d "$tmp"
libs="$tmp/jni/arm64-v8a"
[ -d "$libs" ] || { echo "FAIL: no jni/arm64-v8a in $aar"; exit 1; }

align_ok() {  # smallest LOAD alignment must be at least 0x4000 (16 KB pages)
    local min_align
    min_align="$("$readelf" -lW "$1" | awk '$1 == "LOAD" { print $NF }' | sort -u | head -1)"
    [ -n "$min_align" ] && [ $((min_align)) -ge $((0x4000)) ] || { echo "alignment $min_align < 0x4000: $(basename "$1")"; return 1; }
}

bad=0
if [ -n "$core" ]; then
    mkdir -p "$tmp/core"
    unzip -q "$core" 'jni/*' -d "$tmp/core"
    core_libs="$tmp/core/jni/arm64-v8a"
    # the NDK's system libraries, plus libOpenCL.so, the vendor public library llamabro-opencl's manifest asks for
    # (<uses-native-library>); libcdsprpc.so, llamabro-hexagon's, is opened at run time, never NEEDED
    system="libc.so libm.so libdl.so liblog.so libandroid.so libOpenCL.so"
    for so in "$libs"/*.so; do
        name="$(basename "$so")"
        case "$name" in
            libllamabro-ggml-*.so) ;;
            *) echo "unexpected library in a backend AAR: $name"; bad=1 ;;
        esac
        [ -f "$core_libs/$name" ] && { echo "duplicates the core AAR's $name"; bad=1; }
        align_ok "$so" || bad=1
        for needed in $("$readelf" -d "$so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'); do
            [ -f "$libs/$needed" ] || [ -f "$core_libs/$needed" ] || [[ " $system " == *" $needed "* ]] ||
                { echo "$name needs $needed, found in neither AAR nor the system"; bad=1; }
        done
    done
    if unzip -l "$aar" 'assets/llamabro/htp/*' >/dev/null 2>&1; then
        unzip -q "$aar" 'assets/llamabro/htp/*' -d "$tmp"
        n=0
        for skel in "$tmp"/assets/llamabro/htp/*.so; do
            n=$((n + 1))
            [ "$(shasum -a 256 "$skel" | cut -d' ' -f1)" = "$(cat "$skel.sha256" 2>/dev/null)" ] ||
                { echo "skeleton $(basename "$skel") doesn't match its .sha256"; bad=1; }
        done
        echo "skeletons: $n"
    fi
    # llamabro-hexagon: one skeleton per supported HTP version (v73, v75, v79, v81, v85; build-backends.sh)
    if [ -f "$libs/libllamabro-ggml-hexagon.so" ] && [ "${n:-0}" -ne 5 ]; then
        echo "llamabro-hexagon has ${n:-0} HTP skeletons, expected 5"; bad=1
    fi
    [ "$bad" -eq 0 ] || { echo FAIL; exit 1; }
    echo OK
    exit 0
fi
for required in libllamabro.so libc++_shared.so libllamabro-ggml-base.so; do
    [ -f "$libs/$required" ] || { echo "missing: $required"; bad=1; }
done
ls "$libs"/libllamabro-ggml-cpu-android_armv8*.so >/dev/null 2>&1 || { echo "missing: libllamabro-ggml-cpu-android_armv8*.so"; bad=1; }

# our libraries carry our own names, so they can't collide with another llama.cpp build in the same app;
# only the NDK's runtimes keep their standard names
for so in "$libs"/*.so; do
    name="$(basename "$so")"
    case "$name" in
        libllamabro.so|libllamabro-*.so|libc++_shared.so) ;;   # no libomp.so: OpenMP is off (D38)
        *) echo "unprefixed library: $name"; bad=1 ;;
    esac
done

# libllamabro.so exports the C API, JNI_OnLoad and C++ type information only (exports.map, F76)
nm="$(dirname "$readelf")/llvm-nm"
extra="$("$nm" -D --defined-only "$libs/libllamabro.so" | awk '{print $NF}' | grep -v -e '^lb_' -e '^JNI_OnLoad$' -e '^_ZT[IS]' || true)"
if [ -n "$extra" ]; then
    echo "libllamabro.so exports $(echo "$extra" | wc -l | tr -d ' ') symbols beyond lb_*, JNI_OnLoad and type info, e.g. $(echo "$extra" | head -3 | tr '\n' ' ')"
    bad=1
fi
# and the type info upstream's catches need across libraries (F76): without it named tool_choice answers 500
exported="$("$nm" -D --defined-only "$libs/libllamabro.so")"   # not piped into grep -q: SIGPIPE with pipefail
grep -q ' _ZTI17common_json_error$' <<< "$exported" ||
    { echo "libllamabro.so doesn't export common_json_error's type info (exports.map, F76)"; bad=1; }

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
