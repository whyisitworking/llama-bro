#!/usr/bin/env bash
# The SDK's whole verification in one command. Every check compares against upstream where one exists:
#   patched copy   prepare-llama.sh applies every patch and leaves the submodule untouched
#   native         host C API tests (ctest)
#   contract       the C API vs upstream llama-server, same build, same flags, per model (run.sh)
#   tools-ui       llama.cpp's own tools/ui compaction code regenerated in node; must equal the committed golden
#   api            the release build's public API equals the reviewed sdk/llamabro/api/llamabro.api (D26)
#   lint           Android lint on llamabro and llamabro-testing, warnings as errors
#   jvm            Kotlin unit tests of both libraries and the sample, including the 400-case tools/ui golden replay
#   e2e            Kotlin + JNI + native on this machine vs upstream llama-server (HostNativeTest)
#   aar            release AAR: libraries, names, exports, 16 KB alignment (check-aar.sh)
#   device         (--device) phone tests: native, UI, acceptance per model, and the minified (R8) sample build
#
# usage: sdk/scripts/check.sh [--device] [--only step,step]
# env:   LB_TEST_MODEL (default ~/Downloads/Qwen3.5-4B-Q4_K_M.gguf), LB_TEST_MMPROJ (default ~/Downloads/mmproj-F16.gguf),
#        LB_CONTRACT_MODELS extra GGUFs for the contract test, comma-separated (default: Llama 3.2 3B if present),
#        LB_DEVICE_MODELS file names in the sample app's filesDir (default: the three P1 models)
set -uo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
sdk="$root/sdk"
native="$sdk/native"
logs="$native/build/check"
mkdir -p "$logs"

model="${LB_TEST_MODEL:-$HOME/Downloads/Qwen3.5-4B-Q4_K_M.gguf}"
mmproj="${LB_TEST_MMPROJ:-$HOME/Downloads/mmproj-F16.gguf}"
[ -f "$mmproj" ] || mmproj=""
default_extra="$HOME/Downloads/Llama-3.2-3B-Instruct-Q4_K_M.gguf"
contract_models="${LB_CONTRACT_MODELS:-$( [ -f "$default_extra" ] && echo "$default_extra")}"
device_models="${LB_DEVICE_MODELS:-Qwen3.5-4B-Q4_K_M.gguf,gemma-3-4b-it-qat-UD-Q4_K_XL.gguf,Llama-3.2-3B-Instruct-Q4_K_M.gguf}"

device=0
only=""
while [ $# -gt 0 ]; do
    case "$1" in
        --device) device=1 ;;
        --only) only="$2"; shift ;;
        *) echo "unknown argument: $1"; exit 2 ;;
    esac
    shift
done

results=()
failed=0
run() {  # run NAME COMMAND...: logs to $logs/NAME.log, records PASS/FAIL
    local name="$1"; shift
    if [ -n "$only" ] && [[ ",$only," != *",$name,"* ]]; then return; fi
    local t0=$SECONDS
    printf '%-10s ' "$name"
    if "$@" > "$logs/$name.log" 2>&1; then
        echo "PASS ($((SECONDS - t0)) s)"; results+=("PASS $name")
    else
        echo "FAIL ($((SECONDS - t0)) s): $logs/$name.log"; results+=("FAIL $name"); failed=1
        tail -15 "$logs/$name.log" | sed 's/^/    /'
    fi
}

[ -f "$model" ] || { echo "LB_TEST_MODEL not found: $model"; exit 2; }
export LB_TEST_MODEL="$model"

gradle() { (cd "$root" && ./gradlew -p sdk "$@"); }

step_build() {
    bash "$native/scripts/prepare-llama.sh" "$native/build" &&
    cmake --preset host -S "$native" && cmake --build "$native/build/host"
}

step_contract() {
    LB_TEST_MMPROJ="$mmproj" bash "$native/tests/contract/run.sh" || return 1
    local m
    IFS=',' read -ra extra <<< "$contract_models"
    for m in "${extra[@]}"; do
        [ -n "$m" ] || continue
        echo "=== contract: $m"
        LB_TEST_MODEL="$m" LB_TEST_MMPROJ="" LB_CONTRACT_PORT=18182 bash "$native/tests/contract/run.sh" || return 1
    done
}

step_tools_ui() {
    local fresh="$logs/compaction-golden.json"
    TOOLS_UI_LIB="$native/third_party/llama.cpp/tools/ui/src/lib" \
        node --experimental-transform-types --no-warnings "$sdk/scripts/tools-ui/gen-compaction-golden.mjs" > "$fresh" || return 1
    # a difference means upstream's compaction changed: review it, port it, then copy the new golden in
    diff -u "$sdk/llamabro/src/test/resources/tools-ui/compaction-golden.json" "$fresh"
}

step_e2e() {
    local port=18183 pid status
    "$native/build/host/bin/llama-server" -m "$model" ${mmproj:+--mmproj "$mmproj"} -c 8192 -np 1 --cache-ram 1024 \
        --ctx-checkpoints 8 -b 512 -ub 512 --host 127.0.0.1 --port $port > "$logs/e2e-llama-server.log" 2>&1 &
    pid=$!
    for _ in $(seq 1 120); do curl -sf "http://127.0.0.1:$port/health" > /dev/null && break; sleep 1; done
    gradle :llamabro:testDebugUnitTest --tests 'com.suhel.llamabro.HostNativeTest' --rerun-tasks -i \
        -Pllamabro.host.lib="$native/build/host/bin" -Pllamabro.host.server="http://127.0.0.1:$port" \
        -Pllamabro.host.model="$model" ${mmproj:+-Pllamabro.host.mmproj="$mmproj"}
    status=$?
    kill $pid; wait $pid 2>/dev/null
    grep -q 'skipped="0"' "$sdk/llamabro/build/test-results/testDebugUnitTest/TEST-com.suhel.llamabro.HostNativeTest.xml" || { echo "HostNativeTest was skipped"; return 1; }
    return $status
}

step_aar() {
    gradle :llamabro:assembleRelease && bash "$sdk/scripts/check-aar.sh" "$sdk/llamabro/build/outputs/aar/llamabro-release.aar"
}

step_device() {
    adb shell dumpsys window | grep -q "isKeyguardShowing=false" || { echo "the phone is locked: unlock it and run again"; return 1; }
    gradle :llamabro:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || return 1
    adb shell run-as com.suhel.llamabro.sample rm -f files/acceptance.log 2>/dev/null
    gradle :samples:minimal:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
        -Pandroid.testInstrumentationRunnerArguments.models="$device_models"
    local acceptance=$?
    adb shell run-as com.suhel.llamabro.sample cat files/acceptance.log
    # the minified release build (R8 keep rules, F28): it must load a model; then the debug build goes back
    gradle :samples:minimal:assembleRelease || return 1
    adb install -r "$sdk/samples/minimal/build/outputs/apk/release/minimal-release.apk" || return 1
    adb logcat -c
    adb shell am force-stop com.suhel.llamabro.sample
    adb shell am start -W -n com.suhel.llamabro.sample/.MainActivity
    local ok=1
    for _ in $(seq 1 60); do
        adb logcat -d -s LlamaBroSample | grep -q "loaded" && { ok=0; break; }
        sleep 1
    done
    adb logcat -d | grep -E "LlamaBroSample|JNI_OnLoad|FATAL" | tail -5
    adb shell am force-stop com.suhel.llamabro.sample
    gradle :samples:minimal:installDebug || return 1
    [ $ok -eq 0 ] || { echo "the minified build didn't load a model"; return 1; }
    # acceptance: Llama 3.2 3B's runsToolLoop fails on upstream too (F27); anything else failing fails the check
    if [ $acceptance -ne 0 ]; then
        local failures
        failures="$(python3 - "$sdk/samples/minimal/build/outputs/androidTest-results/connected" <<'PY'
import glob, re, sys
for f in glob.glob(sys.argv[1] + "/**/*.xml", recursive=True):
    for m in re.finditer(r'<testcase name="([^"]+)"[^>]*?(?:/>|>(.*?)</testcase>)', open(f).read(), re.S):
        if m.group(2) and "<failure" in m.group(2) and not m.group(1).startswith("runsToolLoop[Llama-3.2-3B"):
            print(m.group(1))
PY
)"
        [ -z "$failures" ] || { echo "acceptance failures: $failures"; return 1; }
        echo "acceptance: only the known Llama 3.2 3B tool loop failed (F27)"
    fi
}

echo "llamabro check: model $(basename "$model")${mmproj:+, mmproj}${contract_models:+, contract also on $(basename "${contract_models//,/ }")}"
run build    step_build
run native   ctest --test-dir "$native/build/host" --output-on-failure
run contract step_contract
run tools-ui step_tools_ui
run api      gradle :llamabro:apiCheck
run lint     gradle :llamabro:lintRelease :llamabro-testing:lintRelease
run jvm      gradle :llamabro:testDebugUnitTest :llamabro-testing:testDebugUnitTest :samples:minimal:testDebugUnitTest
run e2e      step_e2e
run aar      step_aar
[ $device -eq 1 ] && run device step_device

echo
printf '%s\n' "${results[@]}"
[ $failed -eq 0 ] && echo "ALL PASS" || echo "SOME CHECKS FAILED"
exit $failed
