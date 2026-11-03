#!/usr/bin/env bash
# Phone helper for the P0 spike. Works only inside /data/local/tmp/lb-spike; never changes settings.
#   phone.sh check                 # read-only: unlocked?, battery temperature, thermal status
#   phone.sh push VARIANT          # copies build/out-android-VARIANT/{bin,lib} to the phone
#   phone.sh push-model FILE       # copies a model to /data/local/tmp/lb-spike/models/ (skips if same size)
#   phone.sh run VARIANT CMD...    # runs bin/CMD with upstream's env (scripts/snapdragon/run.py)
set -euo pipefail

root=/data/local/tmp/lb-spike
here="$(cd "$(dirname "$0")/.." && pwd)"

case "${1:-}" in
  check)
    adb get-state >/dev/null
    locked=$(adb shell dumpsys window | grep -m1 -oE 'mDreamingLockscreen=(true|false)|isKeyguardShowing=(true|false)' || true)
    awake=$(adb shell dumpsys power | grep -m1 -oE 'mWakefulness=[A-Za-z]+' || true)
    temp=$(adb shell dumpsys battery | grep -m1 -E '^\s*temperature:' | awk '{print $2/10 " C"}')
    plugged=$(adb shell dumpsys battery | grep -m1 -E 'AC powered|USB powered' | tr -s ' ')
    thermal=$(adb shell dumpsys thermalservice | grep -m1 -E 'Thermal Status' || true)
    echo "lock: ${locked:-unknown} | ${awake:-?} | battery ${temp} | ${plugged} | ${thermal}"
    ;;
  push)
    v="${2:?variant}"
    adb shell mkdir -p "$root/$v/bin" "$root/$v/lib"
    adb push "$here/build/out-android-$v/bin/." "$root/$v/bin/" >/dev/null
    adb push "$here/build/out-android-$v/lib/." "$root/$v/lib/" >/dev/null
    adb shell chmod 755 "$root/$v/bin/*"
    adb shell ls -la "$root/$v/bin"
    ;;
  push-model)
    f="${2:?file}"
    adb shell mkdir -p "$root/models"
    remote="$root/models/$(basename "$f")"
    local_size=$(stat -f %z "$f")
    remote_size=$(adb shell "stat -c %s '$remote' 2>/dev/null || echo 0" | tr -d '\r')
    if [[ "$local_size" == "$remote_size" ]]; then echo "already there: $remote"; else adb push "$f" "$remote"; fi
    ;;
  run)
    v="${2:?variant}"; shift 2
    lib="$root/$v/lib"
    env="LD_LIBRARY_PATH=$lib ADSP_LIBRARY_PATH=$lib CDSP_LIBRARY_PATH=$lib CDSP1_LIBRARY_PATH=$lib CDSP2_LIBRARY_PATH=$lib CDSP3_LIBRARY_PATH=$lib"
    adb shell "cd $root && $env $root/$v/bin/$*"
    ;;
  *)
    sed -n 2,7p "$0"; exit 1 ;;
esac
