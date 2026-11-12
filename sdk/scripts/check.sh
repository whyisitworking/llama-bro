#!/usr/bin/env bash
# The SDK's whole verification in one command. Every check compares against upstream where one exists:
#   patched copy   prepare-llama.sh applies every patch and leaves the submodule untouched
#   native         host C API tests (ctest)
#   hub-golden     upstream model-resolution self-test and recorded golden cases
#   contract       the C API vs upstream llama-server, same build, same flags, per model (run.sh)
#   tools-ui       llama.cpp's own tools/ui compaction code regenerated in node; must equal the committed golden
#   api            core release public API equals its reviewed dump (D26)
#   hub-api        Hub release public API equals sdk/llamabro-hub/api/llamabro-hub.api
#   quality        ktlint and detekt on every Kotlin module (the root build's qualityCheck; detekt's baselines list what predates it)
#   lint           Android lint on all SDK libraries, warnings as errors
#   jvm            Kotlin unit tests of the libraries, including the 400-case tools/ui golden replay
#   e2e            Kotlin + JNI + native on this machine vs upstream llama-server (HostNativeTest)
#   aar            release AAR: libraries, names, exports, 16 KB alignment (check-aar.sh)
#   device         (--device) phone tests: native, UI, acceptance per model and per acceptance-app flavor (cpu, gpu, full,
#                  noskel), and each flavor's minified (R8) build
#   device-contract (--device) the C API vs upstream llama-server on the phone's NPU (tests/device-contract/run.sh)
#   hub-device    (--device) Hub cache and fake download tests on the phone
#   hub-resume    (--device) acceptance-app kill/force-stop resume and notification checks on the phone
#
# usage: sdk/scripts/check.sh [--device] [--only step,step]
# env:   LB_TEST_MODEL (default ~/Downloads/Qwen3.5-4B-Q4_K_M.gguf), LB_TEST_MMPROJ (default ~/Downloads/mmproj-F16.gguf),
#        LB_TEST_DFLASH (default ~/llamabro-models/Qwen3.5-4B-DFlash-Q8_0.gguf),
#        LB_CONTRACT_MODELS extra GGUFs for the contract test, comma-separated (default: Llama 3.2 3B if present),
#        LB_DEVICE_MODELS file names in the acceptance app's filesDir (default: the three P1 models),
#        LB_TEST_MODELS_DIR upstream's small test models for embeddings, rerank and LoRA (fetch-test-models.sh)
set -uo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
sdk="$root/sdk"
native="$sdk/native"
logs="$native/build/check"
mkdir -p "$logs"

model="${LB_TEST_MODEL:-$HOME/Downloads/Qwen3.5-4B-Q4_K_M.gguf}"
draft_model="${LB_TEST_DFLASH:-$HOME/llamabro-models/Qwen3.5-4B-DFlash-Q8_0.gguf}"
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
[ -f "$draft_model" ] || { echo "LB_TEST_DFLASH not found: $draft_model"; exit 2; }
export LB_TEST_MODEL="$model"
export LB_TEST_DFLASH="$draft_model"
export LB_TEST_MODELS_DIR="${LB_TEST_MODELS_DIR:-$HOME/Downloads/llamabro-test}"
for f in bge-small-f16.gguf jina-reranker-tiny-f16.gguf stories15M_MOE-F16.gguf moe_shakespeare15M.gguf; do
    [ -f "$LB_TEST_MODELS_DIR/$f" ] || { echo "$LB_TEST_MODELS_DIR/$f not found: run sdk/scripts/fetch-test-models.sh"; exit 2; }
done

gradle() { (cd "$root" && ./gradlew -p sdk "$@"); }

# ktlint and detekt are configured in the root build (which includes these modules), so they run from there
step_quality() { (cd "$root" && ./gradlew qualityCheck); }

step_build() {
    bash "$native/scripts/prepare-llama.sh" "$native/build" &&
    cmake --preset host -S "$native" && cmake --build "$native/build/host"
}

step_hub_golden() {
    python3 -m unittest discover -s "$sdk/scripts/hub-fake" || return 1
    "$native/build/host/bin/lb-hub-golden" self-test || return 1
    "$native/build/host/bin/lb-hub-golden" gguf-offset \
        "$HOME/Downloads/Qwen3.5-4B-Q4_K_M.gguf" \
        "$HOME/Downloads/gemma-3-4b-it-qat-UD-Q4_K_XL.gguf" \
        "$HOME/Downloads/Llama-3.2-3B-Instruct-Q4_K_M.gguf" > "$logs/gguf-offsets.json" || return 1
    "$native/build/host/bin/lb-hub-golden" cases > "$logs/model-resolution.json" || return 1
    diff -u "$sdk/llamabro-hub/src/test/resources/upstream/model-resolution.json" "$logs/model-resolution.json" || return 1
    python3 "$sdk/scripts/hub-fake/run_cache_golden.py" \
        --golden "$native/build/host/bin/lb-hub-golden" \
        --fake "$sdk/scripts/hub-fake/fake_hub.py" \
        --corpus "$sdk/scripts/hub-fake/corpus-golden.json" \
        --out "$logs/hub-golden" || return 1
    cp "$logs/gguf-offsets.json" "$logs/hub-golden/gguf-offsets.json" || return 1
    gradle :llamabro-hub:testDebugUnitTest --tests 'com.suhel.llamabro.hub.CacheGoldenTest' \
        --rerun-tasks -Pllamabro.hub.golden.dir="$logs/hub-golden" || return 1
    grep -q 'skipped="0"' "$sdk/llamabro-hub/build/test-results/testDebugUnitTest/TEST-com.suhel.llamabro.hub.CacheGoldenTest.xml"
}

step_jvm() {
    gradle :llamabro:testDebugUnitTest :llamabro-testing:testDebugUnitTest \
        :llamabro-hexagon:testDebugUnitTest :llamabro-hub:testDebugUnitTest \
        -Pllamabro.hub.model.dir="$HOME/Downloads" -Pllamabro.hub.golden.dir="$logs/hub-golden" || return 1
    grep -q 'skipped="0"' \
        "$sdk/llamabro-hub/build/test-results/testDebugUnitTest/TEST-com.suhel.llamabro.hub.GgufHeaderTest.xml" || {
        echo "GgufHeaderTest was skipped"; return 1;
    }
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
        --ctx-checkpoints 8 --load-mode none --fit off -b 512 -ub 512 --host 127.0.0.1 --port $port > "$logs/e2e-llama-server.log" 2>&1 &
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
    local core="$sdk/llamabro/build/outputs/aar/llamabro-release.aar" b
    gradle :llamabro:assembleRelease :llamabro-hexagon:assembleRelease :llamabro-opencl:assembleRelease &&
        bash "$sdk/scripts/check-aar.sh" "$core" || return 1
    gradle :llamabro-hub:assembleRelease || return 1
    unzip -l "$sdk/llamabro-hub/build/outputs/aar/llamabro-hub-release.aar" > "$logs/hub-aar-contents.txt" || return 1
    ! grep -q ' jni/' "$logs/hub-aar-contents.txt" || return 1
    for b in hexagon opencl; do
        echo "--- llamabro-$b"
        bash "$sdk/scripts/check-aar.sh" "$sdk/llamabro-$b/build/outputs/aar/llamabro-$b-release.aar" "$core" || return 1
    done
}

step_device() {
    adb shell dumpsys window | grep -q "isKeyguardShowing=false" || { echo "the phone is locked: unlock it and run again"; return 1; }
    gradle :llamabro:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || return 1
    # the device-choice test reads Qwen3.5 from the hexagon test app's external files directory (P2 T6)
    local htp_files=/sdcard/Android/data/com.suhel.llamabro.hexagon.test/files qwen=Qwen3.5-4B-Q4_K_M.gguf
    adb shell "mkdir -p $htp_files" || return 1
    if [ "$(adb shell "stat -c %s $htp_files/$qwen 2>/dev/null" | tr -d '\r')" != "$(stat -f %z "$model")" ]; then
        adb push "$model" "$htp_files/$qwen" || return 1
    fi
    adb shell "chmod 644 $htp_files/$qwen" || return 1
    local draft_name=Qwen3.5-4B-DFlash-Q8_0.gguf
    if [ "$(adb shell "stat -c %s $htp_files/$draft_name 2>/dev/null" | tr -d '\r')" != "$(stat -f %z "$draft_model")" ]; then
        adb push "$draft_model" "$htp_files/$draft_name" || return 1
    fi
    adb shell "chmod 644 $htp_files/$draft_name" || return 1
    # the task test's embedding and reranker models (F69)
    for f in bge-small-f16.gguf jina-reranker-tiny-f16.gguf; do
        adb push "$LB_TEST_MODELS_DIR/$f" "$htp_files/$f" >/dev/null && adb shell "chmod 644 $htp_files/$f" || return 1
    done
    # each backend AAR alone makes its device available (P2 T3, T4; Vulkan dropped, D31)
    gradle :llamabro-hexagon:connectedDebugAndroidTest :llamabro-opencl:connectedDebugAndroidTest \
        -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || return 1
    adb shell run-as com.suhel.llamabro.sample rm -f files/acceptance.log 2>/dev/null
    local results="$sdk/acceptance/backends/build/outputs/androidTest-results/connected"
    rm -rf "$results"   # a stale passing file must not stand in for a flavor that didn't run
    # one acceptance flavor per set of backend artifacts (P2 T10, D29): full runs everything on every model; cpu, gpu and
    # noskel (full with the NPU skeleton broken: Auto must say why and use the GPU) run device choice, a chat and stop
    gradle :acceptance:backends:connectedFullDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
        -Pandroid.testInstrumentationRunnerArguments.models="$device_models"
    local acceptance=$? f
    for f in Cpu Gpu Noskel; do
        gradle ":acceptance:backends:connected${f}DebugAndroidTest" -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
            -Pandroid.testInstrumentationRunnerArguments.models=Qwen3.5-4B-Q4_K_M.gguf \
            -Pandroid.testInstrumentationRunnerArguments.class=com.suhel.llamabro.sample.AcceptanceTest || acceptance=1
    done
    adb shell run-as com.suhel.llamabro.sample cat files/acceptance.log
    # each flavor's minified release build (R8 keep rules, F28) must load a model; then the full debug build goes back
    for f in cpu:Cpu gpu:Gpu full:Full; do   # bash 3.2 (macOS) has no ${f^}
        gradle ":acceptance:backends:assemble${f#*:}Release" || return 1
        f="${f%%:*}"
        adb install -r "$sdk/acceptance/backends/build/outputs/apk/$f/release/backends-$f-release.apk" || return 1
        adb logcat -c
        adb shell am force-stop com.suhel.llamabro.sample
        adb shell am start -W -n com.suhel.llamabro.sample/.MainActivity
        local ok=1
        for _ in $(seq 1 90); do
            adb logcat -d -s LlamaBroAcceptance | grep -q "loaded" && { ok=0; break; }
            sleep 1
        done
        adb logcat -d | grep -E "LlamaBroAcceptance|JNI_OnLoad|FATAL" | tail -5
        adb shell am force-stop com.suhel.llamabro.sample
        [ $ok -eq 0 ] || { gradle :acceptance:backends:installFullDebug; echo "the minified $f build didn't load a model"; return 1; }
    done
    gradle :acceptance:backends:installFullDebug || return 1
    # acceptance: every flavor must have run, and Llama 3.2 3B's runsToolLoop, which fails on upstream too (F27), is
    # the only failure allowed; a gradle failure with no failing test (a build or install error) fails the check
    local verdict
    verdict="$(python3 - "$results" $acceptance <<'PY'
import glob, os, re, sys
results, gradle_failed = sys.argv[1], sys.argv[2] != "0"
bad, tolerated = [], 0
for flavor in ("full", "cpu", "gpu", "noskel"):
    files = glob.glob(os.path.join(results, "debug", "flavors", flavor, "*.xml"))
    cases = [m for f in files for m in re.finditer(r'<testcase name="([^"]+)"[^>]*?(?:/>|>(.*?)</testcase>)', open(f).read(), re.S)]
    if not cases:
        bad.append(f"{flavor}: no test results")
    for m in cases:
        if m.group(2) and "<failure" in m.group(2):
            if flavor == "full" and m.group(1).startswith("runsToolLoop[Llama-3.2-3B"):
                tolerated += 1
            else:
                bad.append(f"{flavor}: {m.group(1)}")
if not bad and gradle_failed and not tolerated:
    bad.append("gradle failed without a failing test (see the log above)")
print("; ".join(bad) if bad else ("only the known Llama 3.2 3B tool loop failed (F27)" if tolerated else "all passed"))
sys.exit(1 if bad else 0)
PY
)" || { echo "acceptance failures: $verdict"; return 1; }
    echo "acceptance: $verdict"
}

wait_fake_hub() {
    local server_pid="$1" ready=0
    for _ in $(seq 1 240); do
        kill -0 "$server_pid" >/dev/null 2>&1 || return 1
        if curl -sf http://127.0.0.1:18190/_log >/dev/null 2>&1; then ready=1; break; fi
        sleep 0.5
    done
    [ "$ready" -eq 1 ]
}

step_hub_device() (
    bash "$sdk/scripts/hub-fake/make-device-corpus.sh" "$logs/corpus-device.json" "$model" "$mmproj" || return 1
    local server_pid
    python3 "$sdk/scripts/hub-fake/fake_hub.py" \
        --corpus "$sdk/scripts/hub-fake/corpus-golden.json" \
        --corpus "$logs/corpus-device.json" \
        --api-port 18190 --cdn-port 18191 > "$logs/hub-device-server.log" 2>&1 &
    server_pid=$!
    cleanup_hub_device() {
        adb reverse --remove tcp:18190 >/dev/null 2>&1 || true
        adb reverse --remove tcp:18191 >/dev/null 2>&1 || true
        kill "$server_pid" >/dev/null 2>&1 || true
        wait "$server_pid" >/dev/null 2>&1 || true
    }
    trap cleanup_hub_device EXIT
    wait_fake_hub "$server_pid" || { cat "$logs/hub-device-server.log"; return 1; }
    adb reverse tcp:18190 tcp:18190 || return 1
    adb reverse tcp:18191 tcp:18191 || return 1
    local hub_files=/sdcard/Android/data/com.suhel.llamabro.hub.test/files file name
    adb shell "mkdir -p $hub_files" || return 1
    for file in "$model" "$mmproj"; do
        [ -n "$file" ] || continue
        name="$(basename "$file")"
        if [ "$(adb shell "stat -c %s $hub_files/$name 2>/dev/null" | tr -d '\r')" != "$(stat -f %z "$file")" ]; then
            adb push "$file" "$hub_files/$name" || return 1
        fi
        adb shell "chmod 644 $hub_files/$name" || return 1
    done
    gradle :llamabro-hub:connectedDebugAndroidTest \
        -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
)

step_hub_resume() (
    bash "$sdk/scripts/hub-fake/make-device-corpus.sh" "$logs/corpus-device.json" "$model" "$mmproj" || return 1
    local server_pid
    python3 "$sdk/scripts/hub-fake/fake_hub.py" \
        --corpus "$logs/corpus-device.json" --api-port 18190 --cdn-port 18191 \
        --rate-mib-s 30 > "$logs/hub-resume-server.log" 2>&1 &
    server_pid=$!
    cleanup_hub_resume() {
        adb reverse --remove tcp:18190 >/dev/null 2>&1 || true
        adb reverse --remove tcp:18191 >/dev/null 2>&1 || true
        kill "$server_pid" >/dev/null 2>&1 || true
        wait "$server_pid" >/dev/null 2>&1 || true
    }
    trap cleanup_hub_resume EXIT
    wait_fake_hub "$server_pid" || { cat "$logs/hub-resume-server.log"; return 1; }
    adb reverse tcp:18190 tcp:18190 || return 1
    adb reverse tcp:18191 tcp:18191 || return 1
    gradle :acceptance:download:installDebug || return 1
    python3 "$sdk/scripts/hub-fake/run_device_resume.py" --out "$logs/hub-resume" \
        --apk "$sdk/acceptance/download/build/outputs/apk/debug/download-debug.apk" || return 1
    # fresh install with the notification permission granted (-g): the UI test reads the notification,
    # and Gradle's own reinstall keeps the grant
    adb uninstall com.suhel.llamabro.download || return 1
    adb install -g "$sdk/acceptance/download/build/outputs/apk/debug/download-debug.apk" || return 1
    gradle :acceptance:download:connectedDebugAndroidTest \
        -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
)

step_device_contract() {
    # both tools built in the Snapdragon image from the same tree; the model and mmproj copied once (P2 T9)
    bash "$native/scripts/build-device-tools.sh" > "$logs/device-tools-build.log" 2>&1 ||
        { tail -20 "$logs/device-tools-build.log"; return 1; }
    local dir=/data/local/tmp/lb-contract-models f
    adb shell "mkdir -p $dir" || return 1
    for f in "$model" $mmproj; do
        if [ "$(adb shell "stat -c %s $dir/$(basename "$f") 2>/dev/null" | tr -d '\r')" != "$(stat -f %z "$f")" ]; then
            adb push "$f" "$dir/" || return 1
        fi
    done
    LB_DEVICE_MODEL_DIR=$dir LB_DEVICE_MODEL="$(basename "$model")" LB_DEVICE_MMPROJ="$(basename "${mmproj:-none}")" \
        bash "$native/tests/device-contract/run.sh"
}

echo "llamabro check: model $(basename "$model")${mmproj:+, mmproj}${contract_models:+, contract also on $(basename "${contract_models//,/ }")}"
run build    step_build
run native   ctest --test-dir "$native/build/host" --output-on-failure
run hub-golden step_hub_golden
run contract step_contract
run tools-ui step_tools_ui
run api      gradle :llamabro:apiCheck
run hub-api  gradle :llamabro-hub:apiCheck
run quality  step_quality
run lint     gradle :llamabro:lintRelease :llamabro-testing:lintRelease :llamabro-hexagon:lintRelease :llamabro-opencl:lintRelease :llamabro-hub:lintRelease
run jvm      step_jvm
run e2e      step_e2e
run aar      step_aar
[ $device -eq 1 ] && run device step_device
[ $device -eq 1 ] && run hub-device step_hub_device
[ $device -eq 1 ] && run hub-resume step_hub_resume
[ $device -eq 1 ] && run device-contract step_device_contract

echo
printf '%s\n' "${results[@]}"
[ $failed -eq 0 ] && echo "ALL PASS" || echo "SOME CHECKS FAILED"
exit $failed
