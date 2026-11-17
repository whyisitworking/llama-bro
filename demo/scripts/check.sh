#!/usr/bin/env bash
# The demo app's verification in one command.
#   unit      JVM tests of :designsystem (tokens, theme mapping, contrast) and :app
#   lint      Android lint on :app and :designsystem, warnings as errors
#   assemble  debug APK, the minified (R8) release APK signed with the debug key, and the device-test APKs
#   ds        the component library's gates: the Compose compiler's stability report (scripts/stability.py), and no raw colour, text style, shape, shadow, motion or Material widget in
#             demo/app/src/main, and (D48) no number, padding or font override in a screen and no role overridden
#   quality   ktlint and detekt over every Kotlin module (the root build's `qualityCheck`)
#   device    (--device) :designsystem's and :app's instrumented tests on the
#             device or emulator named in DEMO_DEVICE (the demo's tests use a fake engine, so an arm64 emulator will do)
#   journey   (--device) the journeys (androidTest/.../journey): whole-app stories over a fake engine; the system's
#             notification question is asked once per install, so `NotificationDeniedJourney` runs first on a fresh
#             install and the rest on another
#   monkey    (--device) three seeds of random input at the app (androidTest/.../monkey); no crash, no ANR
#   coverage  the merged unit and device JaCoCo reports against the floors (scripts/coverage.py); run after device
#   perf      (--device --perf) the Macrobenchmarks on the phone against the budgets, one class at a
#             time, each after the phone's thermal status is 2 or lower (scripts/perf.py); about an hour, so
#             it never runs by default; the results and Perfetto traces are kept in demo/build/check/perf
#   real      (--device --real) the end-to-end journey on the phone with real models from huggingface.co
#             (scripts/e2e/real_model_e2e.py): long (about 30 to 45 minutes) and it downloads about 4 GB, so it is NOT
#             a routine gate (docs/testing.md: a step that passed once is not repeated; device runs are short; ask first);
#             the log, results and pictures are kept in demo/build/check/real
#   map       every test the requirement map names exists and passed in the latest results (scripts/map_check.py)
#
# The fake hub listens on ports the OS picks (F159); --device first checks that no other session uses the device
# (sdk/scripts/device-preflight.sh, F150), holds it until the end, and stops the demo app when the device steps end.
#
# usage: demo/scripts/check.sh [--device [--perf] [--real]] [--only step,step]
set -uo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../../sdk/scripts/ports.sh
. "$root/sdk/scripts/ports.sh"
logs="$root/demo/build/check"
mkdir -p "$logs"

steps="unit lint assemble ds quality device journey monkey coverage map perf real"
device=0
perf=0
real=0
only=""
while [ $# -gt 0 ]; do
    case "$1" in
        --device) device=1 ;;
        --perf) perf=1 ;;
        --real) real=1 ;;
        --only)
            [ $# -ge 2 ] || { echo "--only needs a step list: $steps"; exit 2; }
            only="$2"; shift ;;
        *) echo "unknown argument: $1"; exit 2 ;;
    esac
    shift
done
if [ -n "$only" ]; then
    for step in $(echo "$only" | tr ',' ' '); do
        case " $steps " in
            *" $step "*) ;;
            *) echo "unknown step for --only: $step (steps: $steps)"; exit 2 ;;
        esac
    done
    # run() matches a comma list: a space list ("lint quality") once passed this check, ran nothing and said ALL PASS
    only="$(echo "$only" | tr -s ' ' ',')"
fi

if [ $perf -eq 1 ] && [ $device -eq 0 ]; then echo "--perf needs --device"; exit 2; fi
if [ $real -eq 1 ] && [ $device -eq 0 ]; then echo "--real needs --device"; exit 2; fi

results=()
failed=0
skipped=0
run() {  # run NAME COMMAND...: logs to $logs/NAME.log, records PASS, SKIP (exit 77: not run, or only partly) or FAIL
    local name="$1"; shift
    if [ -n "$only" ] && [[ ",$only," != *",$name,"* ]]; then return; fi
    local t0=$SECONDS rc=0
    printf '%-10s ' "$name"
    "$@" > "$logs/$name.log" 2>&1 || rc=$?
    if [ $rc -eq 0 ]; then
        echo "PASS ($((SECONDS - t0)) s)"; results+=("PASS $name")
    elif [ $rc -eq 77 ]; then
        echo "SKIP ($((SECONDS - t0)) s): $(tail -1 "$logs/$name.log")"; results+=("SKIP $name"); skipped=1
    else
        echo "FAIL ($((SECONDS - t0)) s): $logs/$name.log"; results+=("FAIL $name"); failed=1
        tail -15 "$logs/$name.log" | sed 's/^/    /'
    fi
}

gradle() { (cd "$root" && ./gradlew "$@"); }

# Global constraints, "Design system only": these never appear in :app's main sources.
raw_visuals='(^|[^A-Za-z0-9_])((Color|TextStyle|RoundedCornerShape|CutCornerShape)\(|Color\.[A-Z]|(Button|OutlinedButton|TextButton|ElevatedButton|FilledTonalButton|IconButton|FloatingActionButton|ExtendedFloatingActionButton|SmallFloatingActionButton|Card|ElevatedCard|OutlinedCard|TextField|OutlinedTextField|ListItem|Surface|ModalBottomSheet|BottomSheetScaffold|TopAppBar|CenterAlignedTopAppBar|MediumTopAppBar|LargeTopAppBar|NavigationBar|NavigationBarItem|NavigationRail|AssistChip|FilterChip|SuggestionChip|InputChip|LinearProgressIndicator|CircularProgressIndicator|HorizontalDivider|VerticalDivider|Divider|Checkbox|RadioButton|Switch|Slider|SegmentedButton|SingleChoiceSegmentedButtonRow|Badge|BadgedBox)[[:space:]]*[({])|\.shadow\('
raw_motion='(^|[^A-Za-z0-9_.])(tween|spring|keyframes|AnimatedVisibility|animateItem|animate[A-Z][A-Za-z]*AsState|rememberInfiniteTransition)\('

design_system_only() {
    local hits
    hits="$(grep -rnE "$raw_visuals" "$root/demo/app/src/main" --include='*.kt' || true)"
    if [ -n "$hits" ]; then
        echo "raw visuals in :app; use a :designsystem component:"
        echo "$hits"
        return 1
    fi
    # motion too comes from the design system (DemoMotion's durations and easings, in its components)
    hits="$(grep -rnE "$raw_motion" "$root/demo/app/src/main" --include='*.kt' || true)"
    if [ -n "$hits" ]; then
        echo "raw motion in :app; use a :designsystem component (Reveal, messageMotion, TypingDots...):"
        echo "$hits"
        return 1
    fi
    echo "demo/app/src/main: no raw colour, text style, shape, shadow, motion or Material widget"
}

# Font sizes come from DemoType and spacing from DemoSpacing: no sizes in sp, and no dp literal (but 0) in a padding,
# a gap or a spacer of :app.
# Design system v2 (D48): a screen takes its type from a DemoType role and its gaps and edges from the layout primitives
# (Gap, Stack, Inline, Section, Screen, BottomBar...), and carries no number of its own: no .dp or .sp, no padding,
# Spacer, spacedBy or PaddingValues, no DemoSpacing, no font override. Previews (frames and sizes for a picture) and the
# share card (its pixel maths for an off-screen render) are not screens. The design system itself keeps its roles whole:
# no component overrides a role's size, weight or tracking.
screens_have_no_numbers() {
    local files hits
    files="$(find "$root/demo/app/src/main" -name '*.kt' ! -name '*Previews.kt' ! -name 'PreviewSupport.kt' ! -name 'ShareCard.kt')"
    # shellcheck disable=SC2086
    hits="$(grep -nE '[0-9]\.(dp|sp)\b|fontSize|fontWeight|letterSpacing|\bpadding\(|Spacer\(|spacedBy\(|PaddingValues\(|DemoSpacing\.|\.copy\(font' $files || true)"
    if [ -n "$hits" ]; then
        echo "a screen carries no number: type from a DemoType role, gaps and edges from Gap and the layout primitives:"
        echo "$hits"
        return 1
    fi
    hits="$(grep -rnE '\.copy\([^)]*(fontWeight|fontSize|letterSpacing|lineHeight)' \
        "$root/demo/designsystem/src/main" "$root/demo/app/src/main" --include='*.kt' | grep -v '/theme/DemoType.kt' || true)"
    if [ -n "$hits" ]; then
        echo "a role is used whole (size, weight, tracking): add a role to DemoType instead of overriding one:"
        echo "$hits"
        return 1
    fi
    echo "demo/app/src/main: no number, padding or font override in a screen; roles used whole"
    components_use_the_scale
}

# The components take every size from the scales in theme/ (DemoSpacing, DemoSize, DemoShapes): no dp literal but 0
# (previews in samples/ excepted)
# outside theme/, so sizes rank the same way everywhere and a change to the scale reaches every component.
components_use_the_scale() {
    local hits
    hits="$(grep -rnE '[0-9]\.?[0-9]*\.dp\b' "$root/demo/designsystem/src/main" --include='*.kt' | grep -vE '/(theme|samples)/' |
        grep -vE '[^0-9.]0\.dp\b' || true)"
    if [ -n "$hits" ]; then
        echo "a component takes its sizes from DemoSpacing, DemoSize and DemoShapes, not a dp literal:"
        echo "$hits"
        return 1
    fi
    echo "demo/designsystem: every size from the scales in theme/"
}

step_ds() {
    gradle :demo:designsystem:compileReleaseKotlin --rerun || return 1
    python3 "$root/demo/scripts/stability.py" "$root/demo" || return 1
    design_system_only || return 1
    screens_have_no_numbers
}

# no test ever touches the phone's radios, and the real-model run needs the network: a run stops when airplane mode is on
# (F117), instead of failing later in a way that looks like the app's fault
radios_on() { [ "$(adb -s "$1" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r')" = "0" ]; }

# The instrumented tests talk to the SDK's fake Hugging Face server (corpus demo/scripts/hub-corpus.json) through adb
# reverse, started by sdk/scripts/ports.sh's start_fake_hub on ports the OS picks (F159); the subshell's EXIT trap
# removes the reverses and stops it.
with_fake_hub() (
    local device="${DEMO_DEVICE:?set DEMO_DEVICE to the adb serial of the device or emulator}"
    adb -s "$device" get-state >/dev/null 2>&1 || { echo "the device $device is not connected"; return 1; }
    adb -s "$device" shell dumpsys window | grep -q "isKeyguardShowing=false" || { echo "the phone is locked: unlock it and run again"; return 1; }
    radios_on "$device" || { echo "airplane mode is on: switch it off and run again"; return 1; }
    export ANDROID_SERIAL="$device"
    start_fake_hub "$logs/hub-server.log" --corpus "$root/demo/scripts/hub-corpus.json" || return 1
    "$@"
)

journeys="com.suhel.llamabro.demo.journey"
monkeys="com.suhel.llamabro.demo.monkey"
denied="com.suhel.llamabro.demo.journey.NotificationDeniedJourney"
runner_arg() { echo "-Pandroid.testInstrumentationRunnerArguments.$1=$2"; }
# a test that hangs fails after 10 minutes instead of holding the gate for hours (F129)
per_test_limit="$(runner_arg timeout_msec 600000)"

# each connected run overwrites the device's coverage file: keep them all, and put them back for the report
kept="$logs/coverage"
device_coverage="$root/demo/app/build/outputs/code_coverage/debugAndroidTest/connected"
device_results="$root/demo/app/build/outputs/androidTest-results/connected"
keep_coverage() {
    # this run's results only: a stale file from an earlier run must not count as tests that ran
    rm -rf "$logs/results/$1"
    mkdir -p "$kept" "$logs/results/$1"
    cp "$device_results"/*/*.xml "$logs/results/$1/" 2>/dev/null
    # AGP 9.5 writes <device>/coverage_data/coverage.ec, earlier versions <device>/coverage.ec
    local file
    while IFS= read -r file; do
        cp "$file" "$kept/$1.ec"
    done < <(find "$device_coverage" -name coverage.ec 2>/dev/null)
    # a run that installed nothing (a full emulator) reports zero tests and a successful build: that is not a pass
    if ! grep -qsE 'tests="[1-9]' "$logs/results/$1"/*.xml; then
        echo "no $1 test ran (see $logs/$1.log: an install that failed?)"
        return 1
    fi
    return 0
}

device_tests() {
    rm -rf "$logs/results" "$kept"
    gradle :demo:designsystem:connectedDebugAndroidTest "$per_test_limit" || return 1
    # results and coverage are kept whether or not a test failed: the next run overwrites them
    gradle :demo:app:connectedDebugAndroidTest "$(runner_arg notPackage "$journeys,$monkeys")" "$per_test_limit"
    local status=$?
    keep_coverage device || return 1
    return $status
}

# skipped_tests DIR: how many tests of a kept run were skipped (an assumption that failed, as on a phone that refuses
# the test a permission, F150)
skipped_tests() {
    cat "$1"/*.xml 2>/dev/null | grep -o '<testsuite [^>]*' | sed -n 's/.* skipped="\([0-9][0-9]*\)".*/\1/p' |
        awk '{ n += $1 } END { print n + 0 }'
}

journey_tests() {
    # a fresh install each (the gradle run uninstalls at its end): the system asks about notifications once
    gradle :demo:app:connectedDebugAndroidTest "$(runner_arg class "$denied")" "$per_test_limit"
    local status=$?
    keep_coverage denied || return 1
    [ $status -eq 0 ] || return $status
    gradle :demo:app:connectedDebugAndroidTest "$(runner_arg package "$journeys")" "$(runner_arg notClass "$denied")" "$per_test_limit"
    status=$?
    keep_coverage journeys || return 1
    [ $status -eq 0 ] || return $status
    # a journey this device can't drive is skipped, and says why: the step is partial, not a plain pass
    local skips=$(( $(skipped_tests "$logs/results/denied") + $(skipped_tests "$logs/results/journeys") ))
    if [ "$skips" -gt 0 ]; then
        echo "$skips journey(s) skipped on this device (see the results in $logs/results)"
        return 77
    fi
    return 0
}

monkey_tests() {
    gradle :demo:app:connectedDebugAndroidTest "$(runner_arg package "$monkeys")" "$per_test_limit"
    local status=$?
    keep_coverage monkey || return 1
    return $status
}

step_device() { with_fake_hub device_tests; }
step_journey() { with_fake_hub journey_tests; }
# random input stays off the phone (F117): it can reach the system's quick settings and radios, so the monkey runs on the emulator
step_monkey() {
    case "${DEMO_DEVICE:?set DEMO_DEVICE to the adb serial of the device or emulator}" in
        emulator-*) with_fake_hub monkey_tests ;;
        *) echo "skipped on the phone: random input can reach the system UI (F117); run it with DEMO_DEVICE=emulator-5554"; return 77 ;;
    esac
}

step_coverage() {
    local last
    # beside the file the last run wrote, at whatever depth this AGP puts it
    last="$(find "$device_coverage" -name coverage.ec 2>/dev/null | head -1)"
    [ -n "$last" ] && [ -d "$kept" ] && cp "$kept"/*.ec "$(dirname "$last")" 2>/dev/null
    gradle :demo:app:createDebugUnitTestCoverageReport :demo:app:createDebugAndroidTestCoverageReport \
        -x :demo:app:testDebugUnitTest -x :demo:app:connectedDebugAndroidTest || return 1
    python3 "$root/demo/scripts/coverage.py" "$root/demo"
}

step_map() {
    # an emulator may lack what a journey needs (a photo picker): its skip is reported, not failed; the phone has no such leave
    case "${DEMO_DEVICE:?set DEMO_DEVICE to the adb serial of the device or emulator}" in
        emulator-*) python3 "$root/demo/scripts/map_check.py" --allow-skipped ;;
        *) python3 "$root/demo/scripts/map_check.py" ;;
    esac
}

benchmarks="com.suhel.llamabro.demo.benchmark"
# PERF_CLASSES narrows a run to some classes (a partial run; the others are reported "NOT RUN")
benchmark_classes="${PERF_CLASSES:-StartupBenchmark ScrollBenchmark StreamingBenchmark SheetAndLayoutBenchmark}"
for _c in $benchmark_classes; do
    case " StartupBenchmark ScrollBenchmark StreamingBenchmark SheetAndLayoutBenchmark " in
        *" $_c "*) ;;
        *) echo "unknown benchmark class in PERF_CLASSES: $_c"; exit 2 ;;
    esac
done

# the phone's thermal status must be 2 or lower before a run; wait up to ten minutes for it to cool
wait_for_cool_phone() {
    local device="$1" status
    for _ in $(seq 1 60); do
        status="$(adb -s "$device" shell dumpsys thermalservice | sed -n 's/^Thermal Status: //p' | head -1 | tr -d '\r')"
        [ -n "$status" ] && [ "$status" -le 2 ] && return 0
        echo "thermal status $status: waiting for the phone to cool"
        sleep 10
    done
    return 1
}

# Macrobenchmark refuses a phone whose clocks are not locked (UNLOCKED); only rooted phones can lock them, so the
# check is suppressed here and the budgets are written for an unlocked, cooled phone
step_perf() {
    local device="${DEMO_DEVICE:?set DEMO_DEVICE to the adb serial of the device or emulator}" out="$logs/perf" results_dir cls
    case "$device" in emulator-*) echo "the budgets are for the phone: unset DEMO_DEVICE"; return 1 ;; esac
    adb -s "$device" get-state >/dev/null 2>&1 || { echo "the device $device is not connected"; return 1; }
    export ANDROID_SERIAL="$device"
    rm -rf "$out"; mkdir -p "$out"
    for cls in $benchmark_classes; do
        wait_for_cool_phone "$device" || { echo "the phone stayed too hot for $cls"; return 1; }
        gradle :demo:benchmark:connectedBenchmarkReleaseAndroidTest \
            "$(runner_arg class "$benchmarks.$cls")" \
            -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=UNLOCKED || return 1
        results_dir="$root/demo/benchmark/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected"
        cp "$results_dir"/*/*benchmarkData.json "$out/$cls.json" || return 1
        cp "$results_dir"/*/heap-alloc.json "$out/$cls-heap-alloc.json" 2>/dev/null
        cp "$results_dir"/*/*.perfetto-trace "$out/" 2>/dev/null
    done
    partial=()
    [ -n "${PERF_CLASSES:-}" ] && partial=(--classes "$(echo "$PERF_CLASSES" | tr ' ' ',')")
    python3 "$root/demo/scripts/perf.py" "$out"/*.json ${partial[@]+"${partial[@]}"} --report "$out/report.txt"
}

# the end-to-end journey with real models, on the phone: a debug build (its hub requests are logged, which the
# resume check reads) installed fresh, then seven steps; leave the phone alone while it runs
step_real() {
    local device="${DEMO_DEVICE:?set DEMO_DEVICE to the adb serial of the device or emulator}" out="$logs/real"
    case "$device" in emulator-*) echo "the real-model run is for the phone: unset DEMO_DEVICE"; return 1 ;; esac
    adb -s "$device" get-state >/dev/null 2>&1 || { echo "the device $device is not connected"; return 1; }
    adb -s "$device" shell dumpsys window | grep -q "isKeyguardShowing=false" || { echo "the phone is locked: unlock it and run again"; return 1; }
    radios_on "$device" || { echo "airplane mode is on: switch it off and run again"; return 1; }
    rm -rf "$out"; mkdir -p "$out"
    python3 "$root/demo/scripts/e2e/real_model_e2e.py" --serial "$device" --out "$out" \
        --fresh "$root/demo/app/build/outputs/apk/debug/app-debug.apk"
}

run unit     gradle :demo:designsystem:testDebugUnitTest :demo:app:testDebugUnitTest
run lint     gradle :demo:app:lintDebug :demo:designsystem:lintDebug
run assemble gradle :demo:app:assembleDebug :demo:app:assembleRelease :demo:designsystem:assembleDebugAndroidTest :demo:app:assembleDebugAndroidTest
run ds       step_ds
run quality  gradle qualityCheck
# the steps that use the device, under its lock; map only reads their results, so it runs after the lock is let go
wants() { [ -z "$only" ] || [[ ",$only," == *",$1,"* ]]; }
wants_device=0
refused=0
if [ $device -eq 1 ]; then
    for s in device journey monkey coverage; do wants "$s" && wants_device=1; done
    [ $perf -eq 1 ] && wants perf && wants_device=1
    [ $real -eq 1 ] && wants real && wants_device=1
fi
# nothing of the demo is left running for the next session's pre-flight to find, and the lock goes, also on Ctrl-C
let_device_go() {
    local app
    for app in com.suhel.llamabro.demo com.suhel.llamabro.demo.test com.suhel.llamabro.demo.benchmark; do
        adb -s "${DEMO_DEVICE:-}" shell am force-stop "$app" > /dev/null 2>&1
    done
    rm -f "$device_lock"
}
if [ $wants_device -eq 1 ]; then
    # one session per device (F150): refuse if another uses it, else hold its lock until the device steps end
    printf '%-10s ' preflight
    if device_lock="$(ANDROID_SERIAL="${DEMO_DEVICE:-}" bash "$root/sdk/scripts/device-preflight.sh" $$ 2> "$logs/preflight.log")"; then
        trap let_device_go EXIT
        echo PASS; cat "$logs/preflight.log"
        run device step_device
        run journey step_journey
        run monkey step_monkey
        run coverage step_coverage
        [ $perf -eq 1 ] && run perf step_perf
        [ $real -eq 1 ] && run real step_real
        let_device_go
        trap - EXIT
    else
        echo "FAIL: the device steps did not run"; sed 's/^/    /' "$logs/preflight.log"
        results+=("FAIL preflight"); failed=1; refused=1
    fi
fi
[ $device -eq 1 ] && [ $refused -eq 0 ] && run map step_map

echo
[ ${#results[@]} -gt 0 ] && printf '%s\n' ${results[@]+"${results[@]}"}
# a step named in --only that needs --device (or --perf, --real) without it runs nothing: that is not a pass
if [ ${#results[@]} -eq 0 ]; then echo "NO STEP RAN (a device step needs --device)"; exit 1; fi
if [ $failed -ne 0 ]; then echo "SOME CHECKS FAILED"; elif [ $skipped -ne 0 ]; then echo "PASS, WITH SKIPPED OR PARTIAL STEPS (see SKIP above)"; else echo "ALL PASS"; fi
exit $failed
