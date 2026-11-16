#!/bin/bash
# Builds upstream llama-server for the phone at b12352 three times, with research/b12352/build-at.sh (upstream's CMake):
#   base  = no patch; split = delta-net-head-tail-split.patch (the SDK's old 0001); gate = hexagon-gdn-gate.patch
# -> sdk/native/build/bisect/dn-<arm>-out, then pushed to /data/local/tmp/dn-<arm> when ANDROID_SERIAL is set.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
build_at="$root/research/b12352/build-at.sh"
out="$root/sdk/native/build/bisect"

PATCHES="" "$build_at" b12352 dn-base
PATCHES="$here/delta-net-head-tail-split.patch" "$build_at" b12352 dn-split
PATCHES="$here/hexagon-gdn-gate.patch" "$build_at" b12352 dn-gate

[ -n "${ANDROID_SERIAL:-}" ] || { echo "built; set ANDROID_SERIAL and re-run to push"; exit 0; }
for arm in base split gate; do
    adb shell rm -rf "/data/local/tmp/dn-$arm"
    adb push "$out/dn-$arm-out" "/data/local/tmp/dn-$arm" >/dev/null
    echo "pushed dn-$arm"
done
