#!/usr/bin/env bash
# P3 F95: DflashDeviceTest right after a fresh install of the hexagon test app, TRIALS times (F95 happened on the first
# run after a reinstall). Each trial: uninstall the test app (my own test app, deletes only its data), push Qwen3.5 and
# the draft where the test reads them, then Gradle installs the APK and runs the test once.
# usage: [TRIALS=4] sdk_probe.sh   (run from anywhere; WT = the worktree holding the built SDK)
set -uo pipefail
export ANDROID_SERIAL=<serial>
WT=${WT:-$HOME/Llama-Bro}; here="$(cd "$(dirname "$0")" && pwd)"
pkg=com.suhel.llamabro.hexagon.test; files=/sdcard/Android/data/$pkg/files
for t in $(seq 1 ${TRIALS:-4}); do
  adb shell dumpsys window | grep -q isKeyguardShowing=false || { echo "phone locked"; exit 1; }
  adb uninstall $pkg >/dev/null 2>&1
  adb shell "mkdir -p $files" && adb push "$HOME/Downloads/Qwen3.5-4B-Q4_K_M.gguf" $files/ >/dev/null && adb push "$HOME/llamabro-models/Qwen3.5-4B-DFlash-Q8_0.gguf" $files/ >/dev/null && adb shell "chmod 644 $files/*.gguf" || { echo "push failed"; exit 1; }
  adb logcat -c; rm -f "$WT"/sdk/llamabro-hexagon/build/outputs/androidTest-results/connected/debug/*.xml
  ( cd "$WT" && ./gradlew -p sdk :llamabro-hexagon:connectedDebugAndroidTest \
      -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
      -Pandroid.testInstrumentationRunnerArguments.class=com.suhel.llamabro.hexagon.DflashDeviceTest --rerun-tasks > "$here/logs/probe-t$t.gradle.log" 2>&1 ); rc=$?
  adb logcat -d -s DflashDevice > "$here/logs/probe-t$t.logcat" 2>&1
  xml=$(ls -t "$WT"/sdk/llamabro-hexagon/build/outputs/androidTest-results/connected/debug/*.xml 2>/dev/null | head -1); [ -n "$xml" ] && cp "$xml" "$here/logs/probe-t$t.xml"
  echo "trial $t gradle_rc=$rc $(grep -o 'tests="[0-9]*"\|failures="[0-9]*"\|skipped="[0-9]*"' "$here/logs/probe-t$t.xml" 2>/dev/null | tr '\n' ' ') $(grep -h 'decode\|draft_n' "$here/logs/probe-t$t.logcat" | tail -1 | cut -c1-160)"
done
