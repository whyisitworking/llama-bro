#!/usr/bin/env bash
# P3 T9 Step 2: one real download through samples/background-download (huggingface.co, no token), killed once at
# 50% of the plan's bytes, resumed, both files verified. Writes hub.log, timeline.txt and headers.txt here.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; sdk="$here/../../../sdk"
export ANDROID_SERIAL="${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the device}"
pkg=com.suhel.llamabro.download
ref='unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M'
model_oid=bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517
mmproj_oid=d312c4d02fd46eea7a16e4f3bbb58840e6222209322ca1e33ca03247ad8935d6
total=739863648
blobs=no_backup/llamabro/hub/models--unsloth--Qwen3.5-0.8B-GGUF/blobs
apk="$sdk/samples/background-download/build/outputs/apk/debug/background-download-debug.apk"
now() { python3 -c 'import time; print(f"{time.time():.1f}")'; }
size() { adb shell run-as $pkg stat -c %s "$blobs/$1" 2>/dev/null | tr -d '\r' || echo 0; }
start() { adb shell am start -W -n $pkg/.MainActivity --es ref "$ref" --ez autostart true >/dev/null; }

# headers only: the tree's Link header (pagination), the resolve redirect and the CDN's HEAD answer
{
  echo "## tree (body discarded)"; curl -s -D - -o /dev/null "https://huggingface.co/api/models/unsloth/Qwen3.5-0.8B-GGUF/tree/main?recursive=true" | grep -iE '^(HTTP|link|content-length)'
  echo "## HEAD resolve, following redirects"; curl -sIL "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf" | grep -iE '^(HTTP|location|accept-ranges|content-length|x-linked-size)' | sed -E 's/\?.*//'
  echo "## Range bytes=0-0 on the CDN (one byte)"; curl -s -o /dev/null -D - -r 0-0 -L "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf" | grep -iE '^(HTTP|content-range|content-length)'
} > "$here/headers.txt"

adb uninstall $pkg >/dev/null 2>&1 || true
adb install -g "$apk" >/dev/null
adb logcat -c
adb logcat -v time -s LlamaBroHub:D ModelDownloadWorker:V > "$here/hub.log" 2>&1 &
logcat=$!
trap 'kill $logcat 2>/dev/null || true' EXIT
t0=$(now); start
until [ "$(size $model_oid.downloadInProgress)" -ge $((total / 2)) ]; do sleep 1; done
t_kill=$(now); at_kill=$(size $model_oid.downloadInProgress)
adb shell run-as $pkg kill -9 "$(adb shell pidof $pkg | tr -d '\r')"
sleep 2; t_restart=$(now); start
deadline=$(( $(date +%s) + 1200 ))
until adb shell run-as $pkg ls $blobs 2>/dev/null | tr -d '\r' > "$here/blobs.txt" &&
      grep -qx $model_oid "$here/blobs.txt" && grep -qx $mmproj_oid "$here/blobs.txt" &&
      ! grep -q downloadInProgress "$here/blobs.txt"; do
  [ "$(date +%s)" -lt $deadline ] || { echo "timed out"; exit 1; }
  sleep 2
done
t_done=$(now)
printf 'start %s\nkill %s at %s bytes of the model file\nrestart %s\ndone %s\nblobs:\n%s\n' \
  "$t0" "$t_kill" "$at_kill" "$t_restart" "$t_done" "$(cat "$here/blobs.txt")" > "$here/timeline.txt"
cat "$here/timeline.txt"
