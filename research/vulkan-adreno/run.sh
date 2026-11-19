#!/usr/bin/env bash
# Runs one command on the phone in a build directory from build.sh, under the device lock
# (sdk/scripts/device-preflight.sh). Pushes the build when it changed. The command runs with LD_LIBRARY_PATH set to
# the build directory, so test-backend-ops and llama-bench find every backend.
# usage: run.sh BUILD 'command'   e.g. run.sh vk-fix './test-backend-ops test -b Vulkan0 -o MUL_MAT -p "type_a=q4_K,"'
set -uo pipefail
export ANDROID_SERIAL="${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the device}"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
build="$1"; shift
lock="$(bash "$repo/sdk/scripts/device-preflight.sh" $$)" || exit 1
trap 'rm -f "$lock"' EXIT
d=/data/local/tmp/lb-vk/$build
src="$repo/sdk/native/build/$build"
stamp="$(cd "$src" && ls -l | shasum | cut -c1-12)"
if [ "$(adb shell cat $d/.stamp 2>/dev/null | tr -d '\r')" != "$stamp" ]; then
  adb shell "rm -rf $d && mkdir -p $d" && adb push -q "$src/." "$d/" >/dev/null && adb shell "echo $stamp > $d/.stamp"
fi
adb shell "cd $d && export LD_LIBRARY_PATH=$d && $*" 2>&1
rc=$?
adb shell 'pkill -x llama-server; pkill -x test-backend-ops; pkill -x llama-bench' 2>/dev/null
echo "rc=$rc $(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r')"
