#!/usr/bin/env bash
# The SDK's whole verification in one command. Every check compares against upstream where one exists:
#   build          the patched llama.cpp copy (prepare-llama.sh, which leaves the submodule untouched) and the host build
#   native         host C API tests (ctest)
#   hub-golden     upstream model-resolution self-test and recorded golden cases
#   contract       the C API vs upstream llama-server, same build, same flags, per model (run.sh); audio on
#                  upstream's audio test model with the committed clip (fixtures/clip.wav)
#   server-contract llamabro-server vs upstream llama-server, same build, same flags (server-run.sh): chat, messages,
#                  responses, completions, streamed and whole, on the model; embeddings on bge-small; then the official
#                  OpenAI and Anthropic Python clients against the server with a key (a venv in the host build)
#   tools-ui       llama.cpp's own Llama Chat compaction code (ui/apps/chat; tools/ui before b12479) regenerated in
#                  node; must equal the committed golden
#   api            core, llamabro-typed, llamabro-testing and llamabro-server release public APIs equal their reviewed dumps (D26)
#   hub-api        Hub, hub-work and hub-testing release public APIs equal their dumps (sdk/llamabro-hub*/api/*.api)
#   quality        ktlint and detekt on every Kotlin module (the root build's qualityCheck; detekt's baselines list what predates it)
#   lint           Android lint on all SDK libraries, warnings as errors
#   jvm            Kotlin unit tests of the libraries (FakeLlamaBroHub's and llamabro-typed's included),
#                  including the 400-case tools/ui golden replay; hub-work's androidTest compiles; llamabro-typed-ksp's
#                  build errors and generated code on snippets compiled by kctfork (D81)
#   e2e            Kotlin + JNI + native on this machine vs upstream llama-server (HostNativeTest), load cancel;
#                  typed output on the same model, Think off and on (HostTypedTest); speech input from a file, a
#                  descriptor and bytes on upstream's audio test model (HostAudioTest); a voice session over the
#                  in-memory /v1/realtime on the same model (HostVoiceTest)
#   aar            release AAR: libraries, names, exports, 16 KB alignment (check-aar.sh); hub, hub-work and typed carry
#                  no native code, and hub-work's manifest declares the dataSync foreground service
#   scripts        the host scripts against stubs, no phone: device-preflight.sh (tests/device-preflight-test.sh), installed-guard.sh
#                  (tests/installed-guard-test.sh),
#                  bench.sh end to end (tests/bench-sh-test.sh) and bench.py's merge, charts and README check
#                  (tests/bench-test.py), and that every decision or finding link in the sources has its anchor
#                  (tests/anchor-links-test.py)
#   device         (--device) phone tests: native, UI, acceptance per model and per acceptance-app flavor (cpu, gpu, full,
#                  noskel), and each flavor's minified (R8) build
#   device-contract (--device) the C API vs upstream llama-server on the phone's NPU (tests/device-contract/run.sh)
#   device-speech (--device) LFM3-Omni-2B speaks through upstream /tts with everything on the NPU (patch 0005, F155;
#                  tests/device-speech/run.sh, after device-contract, which builds the device tools)
#   hub-device    (--device) Hub cache and fake download tests on the phone
#   hub-resume    (--device) acceptance-app kill/force-stop resume and notification checks on the phone
#
# usage: sdk/scripts/check.sh [--device] [--only step,step]
# env:   LB_MODELS_DIR where the large GGUFs are (default ~/Downloads); in it by default:
#        LB_TEST_MODEL (Qwen3.5-4B-Q4_K_M.gguf), LB_TEST_MMPROJ (mmproj-F16.gguf),
#        LB_TEST_DFLASH (default ~/llamabro-models/Qwen3.5-4B-DFlash-Q8_0.gguf),
#        LB_CONTRACT_MODELS extra GGUFs for the contract test, comma-separated (default: Llama 3.2 3B if present),
#        LB_DEVICE_MODELS file names in the acceptance app's filesDir (default: the three acceptance models),
#        LB_VISION_MODEL, LB_VISION_MMPROJ a small vision model and its projector in the same filesDir, for VisionTest on
#        the NPU (default: Qwen3.5 0.8B Q4_K_M and its BF16 projector, the demo's files; F120),
#        LB_TEST_MODELS_DIR upstream's small test models for embeddings, rerank, LoRA and audio (fetch-test-models.sh)
# ports: every server on this machine listens on a port the OS picks, so checks in several worktrees run at once
#        (F159); fixed ones: LB_CONTRACT_PORT (the extra models use +1, audio +4), LB_E2E_PORT, LB_HUB_API_PORT,
#        LB_HUB_CDN_PORT; on the phone's side see tests/device-contract/run.sh and tests/device-speech/run.sh.
# --device first checks that no other session uses the phone (device-preflight.sh, F150) and holds it until the end.
set -uo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
sdk="$root/sdk"
native="$sdk/native"
# shellcheck source=ports.sh
. "$sdk/scripts/ports.sh"
logs="$native/build/check"
mkdir -p "$logs"

models_dir="${LB_MODELS_DIR:-$HOME/Downloads}"
model="${LB_TEST_MODEL:-$models_dir/Qwen3.5-4B-Q4_K_M.gguf}"
draft_model="${LB_TEST_DFLASH:-$HOME/llamabro-models/Qwen3.5-4B-DFlash-Q8_0.gguf}"
mmproj="${LB_TEST_MMPROJ:-$models_dir/mmproj-F16.gguf}"
[ -f "$mmproj" ] || mmproj=""
default_extra="$models_dir/Llama-3.2-3B-Instruct-Q4_K_M.gguf"
contract_models="${LB_CONTRACT_MODELS:-$( [ -f "$default_extra" ] && echo "$default_extra")}"
device_models="${LB_DEVICE_MODELS:-Qwen3.5-4B-Q4_K_M.gguf,gemma-3-4b-it-qat-UD-Q4_K_XL.gguf,Llama-3.2-3B-Instruct-Q4_K_M.gguf}"

vision_model="${LB_VISION_MODEL:-Qwen3.5-0.8B-Q4_K_M.gguf}"
vision_mmproj="${LB_VISION_MMPROJ:-mmproj-Qwen3.5-0.8B-BF16.gguf}"

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
steps="build native hub-golden contract server-contract tools-ui api hub-api quality lint jvm e2e aar scripts device hub-device hub-resume device-contract device-speech"
if [ -n "$only" ]; then
    for step in $(echo "$only" | tr ',' ' '); do
        case " $steps " in
            *" $step "*) ;;
            *) echo "unknown step for --only: $step (steps: $steps)"; exit 2 ;;
        esac
    done
    only="$(echo "$only" | tr -s ' ' ',')"  # run() matches a comma list
fi

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

# a file's size in bytes, on macOS and Linux alike
size_of() { wc -c < "$1" | tr -d ' '; }

# the audio tests' clip: speech from llama.cpp's own sample, committed (fixtures/README.md); cmake bakes the path in
export LB_TEST_CLIP="$sdk/scripts/fixtures/clip.wav"
export LB_TEST_MODELS_DIR="${LB_TEST_MODELS_DIR:-$HOME/Downloads/llamabro-test}"

omni="$LB_TEST_MODELS_DIR/tinyLFM3-Omni-for-testing-Q2_K.gguf"
omni_mmproj="$LB_TEST_MODELS_DIR/mmproj-tinyLFM3-Omni-for-testing-Q2_K.gguf"

# the steps that read no model: a check of only these needs none on disk
model_free="build tools-ui api hub-api quality lint aar scripts"
needs_models=1
if [ -n "$only" ]; then
    needs_models=0
    for step in $(echo "$only" | tr ',' ' '); do
        case " $model_free " in *" $step "*) ;; *) needs_models=1 ;; esac
    done
fi
if [ "$needs_models" = 1 ]; then
    [ -f "$model" ] || { echo "LB_TEST_MODEL not found: $model"; exit 2; }
    [ -f "$draft_model" ] || { echo "LB_TEST_DFLASH not found: $draft_model"; exit 2; }
    export LB_TEST_MODEL="$model"
    export LB_TEST_DFLASH="$draft_model"
    for f in bge-small-f16.gguf jina-reranker-tiny-f16.gguf stories15M_MOE-F16.gguf moe_shakespeare15M.gguf \
        "$(basename "$omni")" "$(basename "$omni_mmproj")"; do
        [ -f "$LB_TEST_MODELS_DIR/$f" ] || { echo "$LB_TEST_MODELS_DIR/$f not found: run sdk/scripts/fetch-test-models.sh"; exit 2; }
    done
fi

gradle() { (cd "$root" && ./gradlew -p sdk "$@"); }

# ktlint and detekt are configured in the root build (which includes these modules), so they run from there
step_quality() { (cd "$root" && ./gradlew qualityCheck); }

step_scripts() {
    bash "$sdk/scripts/tests/device-preflight-test.sh" && bash "$sdk/scripts/tests/installed-guard-test.sh" &&
        bash "$sdk/scripts/tests/bench-sh-test.sh" &&
        python3 "$sdk/scripts/tests/bench-test.py" && python3 "$sdk/scripts/tests/anchor-links-test.py"
}

step_build() {
    bash "$native/scripts/prepare-llama.sh" "$native/build" &&
    cmake --preset host -S "$native" && cmake --build "$native/build/host"
}

step_hub_golden() {
    python3 -m unittest discover -s "$sdk/scripts/hub-fake" || return 1
    "$native/build/host/bin/lb-hub-golden" self-test || return 1
    "$native/build/host/bin/lb-hub-golden" gguf-offset \
        "$models_dir/Qwen3.5-4B-Q4_K_M.gguf" \
        "$models_dir/gemma-3-4b-it-qat-UD-Q4_K_XL.gguf" \
        "$models_dir/Llama-3.2-3B-Instruct-Q4_K_M.gguf" > "$logs/gguf-offsets.json" || return 1
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
        :llamabro-hub-work:testDebugUnitTest :llamabro-hub-work:compileDebugAndroidTestKotlin \
        :llamabro:compileDebugAndroidTestKotlin \
        :llamabro-hub-testing:testDebugUnitTest :llamabro-typed:testDebugUnitTest :llamabro-typed-ksp:test \
        :llamabro-server:testDebugUnitTest \
        -Pllamabro.hub.model.dir="$models_dir" -Pllamabro.hub.golden.dir="$logs/hub-golden" || return 1
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
        LB_TEST_MODEL="$m" LB_TEST_MMPROJ="" LB_CONTRACT_PORT="$(contract_port 1)" \
            bash "$native/tests/contract/run.sh" || return 1
    done
    # audio: the clip through upstream's multipart upload and through attachments (F55, D84), on upstream's audio test model
    echo "=== contract: audio on $(basename "$omni")"
    LB_TEST_MODEL="$omni" LB_TEST_MMPROJ="$omni_mmproj" LB_CONTRACT_REQUESTS="$native/tests/contract/audio.jsonl" \
        LB_CONTRACT_PORT="$(contract_port 4)" bash "$native/tests/contract/run.sh"
}

step_server_contract() {
    bash "$native/tests/contract/server-run.sh" || return 1
    echo "=== server-contract: embeddings on bge-small-f16.gguf"
    LB_TEST_MODEL="$LB_TEST_MODELS_DIR/bge-small-f16.gguf" LB_SERVER_TASK=embed LB_CONTRACT_PORT="$(contract_port 5)" \
        bash "$native/tests/contract/server-run.sh"
}

contract_port() {  # contract_port OFFSET: LB_CONTRACT_PORT + OFFSET when it is set, else 0 (a free one)
    if [ "${LB_CONTRACT_PORT:-0}" = 0 ]; then echo 0; else echo $((LB_CONTRACT_PORT + $1)); fi
}

step_tools_ui() {
    local fresh="$logs/compaction-golden.json"
    TOOLS_UI_LIB="$native/third_party/llama.cpp/ui/apps/chat/src/lib" \
        node --experimental-transform-types --no-warnings "$sdk/scripts/tools-ui/gen-compaction-golden.mjs" > "$fresh" || return 1
    # a difference means upstream's compaction changed: review it, port it, then copy the new golden in
    diff -u "$sdk/llamabro/src/test/resources/tools-ui/compaction-golden.json" "$fresh"
}

step_e2e() {
    local port pid status
    "$native/build/host/bin/llama-server" -m "$model" ${mmproj:+--mmproj "$mmproj"} -c 8192 -np 1 --cache-ram 1024 \
        --ctx-checkpoints 8 --load-mode none --fit off -b 512 -ub 512 --host 127.0.0.1 --port "${LB_E2E_PORT:-0}" \
        > "$logs/e2e-llama-server.log" 2>&1 &
    pid=$!
    port="$(server_port "$logs/e2e-llama-server.log" $pid)" ||
        { echo "llama-server exited or never listened"; tail -20 "$logs/e2e-llama-server.log"
          kill $pid 2> /dev/null; wait $pid 2> /dev/null; return 1; }
    for _ in $(seq 1 120); do curl -sf "http://127.0.0.1:$port/health" > /dev/null && break; sleep 1; done
    # HostEmbeddingTest: typed embed and rerank on upstream's test models (D65); HostAudioTest: speech input through
    # the public API (D84); HostVoiceTest: the voice session (D95, D97); HostTranscriptionTest: live transcription
    # (D138); HostSpeechTest: speech (D139); none needs a server
    gradle :llamabro:testDebugUnitTest --tests 'com.suhel.llamabro.HostNative*' \
        --tests 'com.suhel.llamabro.HostEmbeddingTest' --tests 'com.suhel.llamabro.HostAudioTest' \
        --tests 'com.suhel.llamabro.HostVoiceTest' --tests 'com.suhel.llamabro.HostTranscriptionTest' \
        --tests 'com.suhel.llamabro.HostSpeechTest' --rerun-tasks -i \
        -Pllamabro.host.lib="$native/build/host/bin" -Pllamabro.host.server="http://127.0.0.1:$port" \
        -Pllamabro.host.model="$model" ${mmproj:+-Pllamabro.host.mmproj="$mmproj"} \
        -Pllamabro.host.models="$LB_TEST_MODELS_DIR" -Pllamabro.host.clip="$LB_TEST_CLIP"
    status=$?
    kill $pid; wait $pid 2>/dev/null
    # typed output over the same core and model, Think off and on (D63, F143); it needs no server
    gradle :llamabro-typed:testDebugUnitTest --tests 'com.suhel.llamabro.typed.HostTyped*' --rerun-tasks -i \
        -Pllamabro.host.lib="$native/build/host/bin" -Pllamabro.host.model="$model" || status=1
    local t
    for t in HostNativeTest HostNativeLogTest HostNativeFailedInitTest HostNativeLoadCancelTest HostNativeAttachmentTest \
        HostEmbeddingTest HostAudioTest HostVoiceTest; do
        grep -q 'skipped="0"' "$sdk/llamabro/build/test-results/testDebugUnitTest/TEST-com.suhel.llamabro.$t.xml" || { echo "$t was skipped or has no result"; return 1; }
    done
    grep -q 'skipped="0"' "$sdk/llamabro-typed/build/test-results/testDebugUnitTest/TEST-com.suhel.llamabro.typed.HostTypedTest.xml" || { echo "HostTypedTest was skipped or has no result"; return 1; }
    return $status
}

step_aar() {
    local core="$sdk/llamabro/build/outputs/aar/llamabro-release.aar" b
    gradle :llamabro:assembleRelease :llamabro-hexagon:assembleRelease :llamabro-opencl:assembleRelease &&
        bash "$sdk/scripts/check-aar.sh" "$core" || return 1
    gradle :llamabro-hub:assembleRelease || return 1
    unzip -l "$sdk/llamabro-hub/build/outputs/aar/llamabro-hub-release.aar" > "$logs/hub-aar-contents.txt" || return 1
    ! grep -q ' jni/' "$logs/hub-aar-contents.txt" || return 1
    # the worker runs as a dataSync foreground service: its manifest must carry the type and the permissions (D75)
    gradle :llamabro-hub-work:assembleRelease || return 1
    local work_aar="$sdk/llamabro-hub-work/build/outputs/aar/llamabro-hub-work-release.aar" work_manifest
    unzip -l "$work_aar" > "$logs/hub-work-aar-contents.txt" || return 1
    ! grep -q ' jni/' "$logs/hub-work-aar-contents.txt" || return 1
    work_manifest="$(unzip -p "$work_aar" AndroidManifest.xml)" || return 1
    for b in 'foregroundServiceType="dataSync"' FOREGROUND_SERVICE_DATA_SYNC POST_NOTIFICATIONS; do
        grep -q "$b" <<< "$work_manifest" || { echo "llamabro-hub-work's manifest lacks $b"; return 1; }
    done
    gradle :llamabro-typed:assembleRelease || return 1
    unzip -l "$sdk/llamabro-typed/build/outputs/aar/llamabro-typed-release.aar" > "$logs/typed-aar-contents.txt" || return 1
    ! grep -q ' jni/' "$logs/typed-aar-contents.txt" || return 1
    for b in hexagon opencl; do
        echo "--- llamabro-$b"
        bash "$sdk/scripts/check-aar.sh" "$sdk/llamabro-$b/build/outputs/aar/llamabro-$b-release.aar" "$core" || return 1
    done
}

step_device() {
    adb shell dumpsys window | grep -q "isKeyguardShowing=false" || { echo "the phone is locked: unlock it and run again"; return 1; }
    gradle :llamabro:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || return 1
    # the device-choice test reads Qwen3.5 from the hexagon test app's external files directory
    local htp_files=/sdcard/Android/data/com.suhel.llamabro.hexagon.test/files qwen=Qwen3.5-4B-Q4_K_M.gguf
    adb shell "mkdir -p $htp_files" || return 1
    if [ "$(adb shell "stat -c %s $htp_files/$qwen 2>/dev/null" | tr -d '\r')" != "$(size_of "$model")" ]; then
        adb push "$model" "$htp_files/$qwen" || return 1
    fi
    adb shell "chmod 644 $htp_files/$qwen" || return 1
    local draft_name=Qwen3.5-4B-DFlash-Q8_0.gguf
    if [ "$(adb shell "stat -c %s $htp_files/$draft_name 2>/dev/null" | tr -d '\r')" != "$(size_of "$draft_model")" ]; then
        adb push "$draft_model" "$htp_files/$draft_name" || return 1
    fi
    adb shell "chmod 644 $htp_files/$draft_name" || return 1
    # the task test's embedding and reranker models (F69)
    for f in bge-small-f16.gguf jina-reranker-tiny-f16.gguf; do
        adb push "$LB_TEST_MODELS_DIR/$f" "$htp_files/$f" >/dev/null && adb shell "chmod 644 $htp_files/$f" || return 1
    done
    # each backend AAR alone makes its device available (T4; Vulkan dropped, D31)
    gradle :llamabro-hexagon:connectedDebugAndroidTest :llamabro-opencl:connectedDebugAndroidTest \
        -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || return 1
    adb shell run-as com.suhel.llamabro.sample rm -f files/acceptance.log 2>/dev/null
    local results="$sdk/acceptance/backends/build/outputs/androidTest-results/connected"
    rm -rf "$results"   # a stale passing file must not stand in for a flavor that didn't run
    # one acceptance flavor per set of backend artifacts (D29): full runs everything on every model; cpu, gpu and
    # noskel (full with the NPU skeleton broken: Auto must say why and use the GPU) run device choice, a chat and stop
    gradle :acceptance:backends:connectedFullDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
        -Pandroid.testInstrumentationRunnerArguments.models="$device_models" \
        -Pandroid.testInstrumentationRunnerArguments.vision="$vision_model" \
        -Pandroid.testInstrumentationRunnerArguments.visionMmproj="$vision_mmproj"
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

step_hub_device() (
    bash "$sdk/scripts/hub-fake/make-device-corpus.sh" "$logs/corpus-device.json" "$model" "$mmproj" || return 1
    start_fake_hub "$logs/hub-device-server.log" \
        --corpus "$sdk/scripts/hub-fake/corpus-golden.json" --corpus "$logs/corpus-device.json" || return 1
    local hub_files=/sdcard/Android/data/com.suhel.llamabro.hub.test/files file name
    adb shell "mkdir -p $hub_files" || return 1
    for file in "$model" "$mmproj"; do
        [ -n "$file" ] || continue
        name="$(basename "$file")"
        if [ "$(adb shell "stat -c %s $hub_files/$name 2>/dev/null" | tr -d '\r')" != "$(size_of "$file")" ]; then
            adb push "$file" "$hub_files/$name" || return 1
        fi
        adb shell "chmod 644 $hub_files/$name" || return 1
    done
    gradle :llamabro-hub:connectedDebugAndroidTest \
        -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
)

step_hub_resume() (
    bash "$sdk/scripts/hub-fake/make-device-corpus.sh" "$logs/corpus-device.json" "$model" "$mmproj" || return 1
    start_fake_hub "$logs/hub-resume-server.log" --corpus "$logs/corpus-device.json" --rate-mib-s 30 || return 1
    gradle :acceptance:download:installDebug || return 1
    python3 "$sdk/scripts/hub-fake/run_device_resume.py" --out "$logs/hub-resume" \
        --host-endpoint "http://127.0.0.1:$hub_api/" \
        --apk "$sdk/acceptance/download/build/outputs/apk/debug/download-debug.apk" || return 1
    # fresh install with the notification permission granted (-g): the UI test reads the notification,
    # and Gradle's own reinstall keeps the grant
    adb uninstall com.suhel.llamabro.download || return 1
    adb install -g "$sdk/acceptance/download/build/outputs/apk/debug/download-debug.apk" || return 1
    local status=0
    gradle :acceptance:download:connectedDebugAndroidTest \
        -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || status=1
    # the app stays cached after its run, and the next check's pre-flight refuses a phone with a Llama Bro app running
    adb shell am force-stop com.suhel.llamabro.download
    return $status
)

step_device_contract() {
    # both tools built in the Snapdragon image from the same tree; the model and mmproj copied once
    bash "$native/scripts/build-device-tools.sh" > "$logs/device-tools-build.log" 2>&1 ||
        { tail -20 "$logs/device-tools-build.log"; return 1; }
    local dir=/data/local/tmp/lb-contract-models f
    adb shell "mkdir -p $dir" || return 1
    for f in "$model" $mmproj; do
        if [ "$(adb shell "stat -c %s $dir/$(basename "$f") 2>/dev/null" | tr -d '\r')" != "$(size_of "$f")" ]; then
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
run server-contract step_server_contract
run tools-ui step_tools_ui
run api      gradle :llamabro:apiCheck :llamabro-typed:apiCheck :llamabro-testing:apiCheck :llamabro-server:apiCheck
run hub-api  gradle :llamabro-hub:apiCheck :llamabro-hub-work:apiCheck :llamabro-hub-testing:apiCheck
run quality  step_quality
run lint     gradle :llamabro:lintRelease :llamabro-testing:lintRelease :llamabro-hexagon:lintRelease :llamabro-opencl:lintRelease :llamabro-hub:lintRelease \
    :llamabro-hub-work:lintRelease :llamabro-hub-testing:lintRelease :llamabro-typed:lintRelease \
    :llamabro-server:lintRelease
run jvm      step_jvm
run e2e      step_e2e
run aar      step_aar
run scripts  step_scripts
device_steps=device,hub-device,hub-resume,device-contract,device-speech
wants_device=0
if [ $device -eq 1 ]; then
    for s in ${device_steps//,/ }; do [ -z "$only" ] || [[ ",$only," == *",$s,"* ]] && wants_device=1; done
fi
if [ $wants_device -eq 1 ]; then
    # one session per phone (F150): refuse if another uses it, else hold its lock until this script ends
    printf '%-10s ' preflight
    if device_lock="$(bash "$sdk/scripts/device-preflight.sh" $$ 2> "$logs/preflight.log")"; then
        trap 'rm -f "$device_lock"' EXIT
        echo PASS; cat "$logs/preflight.log"
        run device step_device
        run hub-device step_hub_device
        run hub-resume step_hub_resume
        run device-contract step_device_contract
        run device-speech bash "$native/tests/device-speech/run.sh"
    else
        echo "FAIL: the phone steps did not run"; sed 's/^/    /' "$logs/preflight.log"
        results+=("FAIL preflight"); failed=1
    fi
fi

echo
# a step named in --only that needs --device without it runs nothing: that is not a pass
if [ ${#results[@]} -eq 0 ]; then echo "NO STEP RAN (a device step needs --device)"; exit 1; fi
printf '%s\n' "${results[@]}"
[ $failed -eq 0 ] && echo "ALL PASS" || echo "SOME CHECKS FAILED"
exit $failed
