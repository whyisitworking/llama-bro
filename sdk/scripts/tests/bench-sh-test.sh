#!/usr/bin/env bash
# Runs the real bench.sh against a stub adb that plays a phone (stub-adb/adb, STUB_PHONE) and stub gradle, say and
# afconvert, with the results and charts redirected into a scratch folder: no phone needed. The cases: missing_model,
# thermal_never_cools, happy_two_rounds (also checks that the serial appears nowhere and what each arm's line says),
# instrument_fails, all_arms_fail, thermal_keeps_lines, refused_preflight, phone_lost, build_mismatch, no_say,
# unhandled_failure (the ERR trap), install_fails, no_llama_tag (a temporary checkout whose submodule has no tag).
# usage: bash sdk/scripts/tests/bench-sh-test.sh
here="$(cd "$(dirname "$0")" && pwd)"; bench="$here/../bench.sh"
S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT
SERIAL="SECRET-SERIAL-7Q"
fails=0
check() {  # check NAME CONDITION-COMMAND...
    local name="$1"; shift
    if "$@" > /dev/null 2>&1; then echo "ok   $name"; else echo "FAIL $name"; fails=1; fi
}

# stubs: gradle installs (the app appears on the phone), say writes a file, afconvert writes a 1 s 16 kHz mono WAV
cat > "$S/gradle" << 'EOF'
#!/usr/bin/env bash
echo "gradle $*" >> "$STUB_LOG"
mkdir -p "$STUB_PHONE" && touch "$STUB_PHONE/installed"
echo "Installing APK on 'Stub Phone - 16' ($ANDROID_SERIAL)"
EOF
cat > "$S/say" << 'EOF'
#!/usr/bin/env bash
echo "say $*" >> "$STUB_LOG"
[ "$1" = -o ] && echo aiff > "$2"
EOF
cat > "$S/afconvert" << 'EOF'
#!/usr/bin/env bash
echo "afconvert $*" >> "$STUB_LOG"
python3 -c 'import sys, wave
w = wave.open(sys.argv[1], "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
w.writeframes(b"\x01\x00" * 16000); w.close()' "${@: -1}"
EOF
chmod +x "$S/gradle" "$S/say" "$S/afconvert"

fresh() {  # fresh: a new phone, work folder and outputs; models for the arms
    rm -rf "$S/phone" "$S/work" "$S/out" "$S/locks" "$S/stub.log"
    mkdir -p "$S/locks" "$S/out/bench" "$S/out/charts"; : > "$S/stub.log"
    for f in Qwen3.5-4B-Q4_K_M.gguf Qwen3.5-4B-DFlash-Q8_0.gguf LFM3-Omni-2B-Q4_K_M.gguf mmproj-LFM3-Omni-2B-Q8_0.gguf; do
        [ -f "$S/models/$f" ] || { mkdir -p "$S/models"; echo "stub $f" > "$S/models/$f"; }
    done
}
run() {  # run ARGS...: bench.sh with the stubs, its output in $S/stdout (stderr too), the exit code in $?
    PATH="$here/stub-adb:$PATH" ANDROID_SERIAL="$SERIAL" STUB_PHONE="$S/phone" STUB_LOG="$S/stub.log" \
        LB_DEVICE_LOCK_DIR="$S/locks" LB_BENCH_WORK="$S/work" LB_BENCH_OUT_DIR="$S/out/bench" \
        LB_BENCH_CHARTS_DIR="$S/out/charts" LB_BENCH_GRADLE="$S/gradle" LB_BENCH_SAY="$S/say" \
        LB_BENCH_AFCONVERT="$S/afconvert" LB_BENCH_MODELS_DIR="$S/models" LB_BENCH_COOL_INTERVAL=0.05 \
        LB_BENCH_COOL_TRIES=3 LB_BENCH_ROUNDS=1 "$@" > "$S/stdout" 2>&1 < /dev/null
}
instrument_calls() { grep -c "am instrument" "$S/stub.log" || true; }
arms_of_round() {  # arms_of_round N [CLASS]: the arms the phone ran in round N, in order
    sed -n "s/.*-e class [A-Za-z.]*BenchDeviceTest -e bench \([a-z-]*\) -e benchRound $1 .*/\1/p" "$S/stub.log" | tr '\n' ' '
}
no_trap() {  # no_trap: the ERR trap did not fire (every stop in the run was one the script reports itself)
    ! grep -q "stopped at line" "$S/stdout"
}
untouched() {  # untouched: the seeded results and charts are still as seeded
    [ "$(cat "$S/out/bench/results.json")" = OLD ] && [ "$(cat "$S/out/charts/speed-light.svg")" = OLD ]
}

# ---- missing_model: a file that is nowhere stops the mode before anything is installed, and names it
fresh; rm "$S/models/LFM3-Omni-2B-Q4_K_M.gguf" "$S/models/Qwen3.5-4B-DFlash-Q8_0.gguf"
echo OLD > "$S/out/bench/results.json"; echo OLD > "$S/out/charts/speed-light.svg"
run bash "$bench" speed; code=$?
check "missing_model: exits nonzero" [ $code != 0 ]
check "missing_model: names the missing file" grep -q "LFM3-Omni-2B-Q4_K_M.gguf" "$S/stdout"
check "missing_model: not the file it has" bash -c '! grep -q "  Qwen3.5-4B-Q4_K_M.gguf" "$1"' _ "$S/stdout"
check "missing_model: nothing installed" bash -c '! grep -q "^gradle" "$1"' _ "$S/stub.log"
check "missing_model: nothing run" [ "$(instrument_calls)" = 0 ]
check "missing_model: results untouched" untouched
check "missing_model: lock released" bash -c '[ -z "$(ls "$1")" ]' _ "$S/locks"
check "missing_model: no trap" no_trap
run bash "$bench" extras; code=$?
check "missing_model: extras names the draft too" grep -q "Qwen3.5-4B-DFlash-Q8_0.gguf" "$S/stdout"
# a file already on the phone and not on this machine is fine
fresh; rm "$S/models/LFM3-Omni-2B-Q4_K_M.gguf"; mkdir -p "$S/phone/files"; touch "$S/phone/installed"
echo "on the phone" > "$S/phone/files/LFM3-Omni-2B-Q4_K_M.gguf"
run bash "$bench" speed; check "missing_model: a file only on the phone is enough" [ $? = 0 ]

# ---- thermal_never_cools: the mode stops at the first arm, leaving results as they were
fresh; echo OLD > "$S/out/bench/results.json"; echo OLD > "$S/out/charts/speed-light.svg"
run env STUB_THERMAL=3 bash "$bench" speed; code=$?
check "thermal_never_cools: exits nonzero" [ $code != 0 ]
check "thermal_never_cools: says why" grep -q "did not reach thermal status 1" "$S/stdout"
check "thermal_never_cools: no arm ran" [ "$(instrument_calls)" = 0 ]
check "thermal_never_cools: results untouched" untouched
check "thermal_never_cools: lock released" bash -c '[ -z "$(ls "$1")" ]' _ "$S/locks"
check "thermal_never_cools: no trap" no_trap
run env STUB_THERMAL=1 bash "$bench" speed; check "thermal_never_cools: status 1 is cool enough" [ $? = 0 ]

# ---- instrument_fails: a crashed arm is recorded as failed and the mode goes on
fresh; rm -f "$S/out/bench/results.json"
run env STUB_FAIL_ARM=lfm-gpu LB_BENCH_ROUNDS=2 bash "$bench" speed; code=$?
check "instrument_fails: the mode finishes" [ $code = 0 ]
check "instrument_fails: says the arm was recorded as failed" grep -q "lfm-gpu, round 1: no line was written" "$S/stdout"
check "instrument_fails: both rounds of every arm have a line (12)" bash -c '[ "$(grep -c . "$1/speed.jsonl")" = 12 ]' _ "$S/work"
check "instrument_fails: the crashed arm's lines say why, with the build" python3 - "$S/work/speed.jsonl" << 'EOF'
import json, sys
rows = [json.loads(r) for r in open(sys.argv[1])]
bad = [r for r in rows if r["arm"] == "lfm-gpu" and r.get("error", "").startswith("instrumentation failed: INSTRUMENTATION_FAILED: stub")]
assert len(bad) == 2 and all(not r["ok"] and r["build"]["sdkCommit"] for r in bad), bad
EOF
check "instrument_fails: results published" [ -f "$S/out/bench/results.json" ]
check "instrument_fails: the arm's line says FAILED and why" \
    grep -q "^  lfm-gpu, round 1: FAILED: instrumentation failed: INSTRUMENTATION_FAILED: stub$" "$S/stdout"
check "instrument_fails: the closing message counts both" \
    grep -q "^speed finished: 10 arm runs ok, 2 failed; failed in every round: lfm-gpu; wrote " "$S/stdout"
check "instrument_fails: no trap" no_trap
# every arm failing is an error with nothing published
fresh; echo OLD > "$S/out/bench/results.json"; echo OLD > "$S/out/charts/speed-light.svg"
run env STUB_FAIL_ARM=all bash "$bench" speed; code=$?
check "all_arms_fail: exits nonzero" [ $code != 0 ]
check "all_arms_fail: says nothing to publish" grep -q "nothing to publish" "$S/stdout"
check "all_arms_fail: results untouched" untouched
check "all_arms_fail: each arm said FAILED" bash -c '[ "$(grep -c "^  [a-z-]*, round 1: FAILED: " "$1")" = 6 ]' _ "$S/stdout"
check "all_arms_fail: no trap" no_trap

# ---- thermal_keeps_lines: a stop after measured arms keeps their lines and publishes nothing
fresh; echo OLD > "$S/out/bench/results.json"; echo OLD > "$S/out/charts/speed-light.svg"
run env STUB_HOT_AFTER=2 bash "$bench" speed; code=$?
check "thermal_keeps_lines: exits nonzero" [ $code != 0 ]
check "thermal_keeps_lines: the two measured lines are kept" bash -c '[ "$(grep -c . "$1/speed.jsonl.partial")" = 2 ] && grep -q sdkCommit "$1/speed.jsonl.partial"' _ "$S/work"
check "thermal_keeps_lines: the message says where" grep -q "2 lines measured so far are kept in .*speed.jsonl.partial" "$S/stdout"
check "thermal_keeps_lines: results untouched" untouched
check "thermal_keeps_lines: no trap" no_trap

# ---- refused_preflight: another session's app is running: refuse, and do not stop its app
fresh
run env STUB_PS='com.suhel.llamabro.sample\r\n' bash "$bench" speed; code=$?
check "refused_preflight: exits nonzero" [ $code != 0 ]
check "refused_preflight: says the phone is in use" grep -q "the phone is in use" "$S/stdout"
check "refused_preflight: no force-stop, no install" bash -c '! grep -qE "force-stop|^gradle" "$1"' _ "$S/stub.log"
check "refused_preflight: no trap" no_trap

# ---- phone_lost: adb errors name the serial; the mode stops at once, filtered, keeping the lines
fresh
run env STUB_GONE_AFTER=1 bash "$bench" speed; code=$?
check "phone_lost: exits nonzero" [ $code != 0 ]
check "phone_lost: says the phone is gone, with adb's text filtered" grep -q "no longer on adb (adb said: error: device '<serial>' not found)" "$S/stdout"
check "phone_lost: the serial is not printed" bash -c '! grep -q -- "$1" "$2"' _ "$SERIAL" "$S/stdout"
check "phone_lost: no cooling loop" bash -c '! grep -q "waiting for the phone to cool" "$1"' _ "$S/stdout"
check "phone_lost: the measured line is kept" bash -c '[ "$(grep -c . "$1/speed.jsonl.partial")" = 1 ]' _ "$S/work"
check "phone_lost: the serial is in no file" bash -c '! grep -rq -- "$1" "$2"' _ "$SERIAL" "$S/work"
check "phone_lost: no trap" no_trap

# ---- build_mismatch: the other mode's lines are from another build: refused before anything is installed
fresh
run bash "$bench" speed
sed -i.bak 's/"sdkCommit":"[^"]*"/"sdkCommit":"0ldbuild"/' "$S/work/speed.jsonl"; rm -f "$S/work/speed.jsonl.bak"
: > "$S/stub.log"
run bash "$bench" extras; code=$?
check "build_mismatch: exits nonzero" [ $code != 0 ]
check "build_mismatch: names both builds and the rerun command" bash -c 'grep -q "build 0ldbuild" "$1" && grep -q "this run is build " "$1" && grep -q "sdk/scripts/bench.sh speed" "$1"' _ "$S/stdout"
check "build_mismatch: nothing installed or run" bash -c '[ ! -s "$1" ]' _ "$S/stub.log"
check "build_mismatch: no trap" no_trap

# ---- happy_two_rounds: the order reverses, results appear after the last arm only, extras adds to speed
fresh; rm -f "$S/out/bench/results.json"; rm -f "$S/out/charts"/*
run env STUB_WATCH="$S/out/bench/results.json" LB_BENCH_ROUNDS=2 bash "$bench" speed; code=$?
check "happy_two_rounds: speed exits 0" [ $code = 0 ]
r1="$(arms_of_round 1)"; r2="$(arms_of_round 2)"
check "happy_two_rounds: round 1 order" [ "$r1" = "qwen-npu qwen-gpu qwen-cpu lfm-npu lfm-gpu lfm-cpu " ]
check "happy_two_rounds: round 2 reverses it" [ "$r2" = "lfm-cpu lfm-gpu lfm-npu qwen-cpu qwen-gpu qwen-npu " ]
check "happy_two_rounds: 12 arm runs" [ "$(instrument_calls)" = 12 ]
check "happy_two_rounds: no results before the last arm" bash -c '! grep -q "watch: present" "$1"' _ "$S/stub.log"
check "happy_two_rounds: results.json after the last arm" [ -f "$S/out/bench/results.json" ]
check "happy_two_rounds: the charts" [ -f "$S/out/charts/speed-light.svg" ]
check "happy_two_rounds: installed once, both APKs" bash -c \
    '[ "$(grep -c "^gradle" "$1")" = 1 ] && grep -q "installFullDebug .*installFullDebugAndroidTest" "$1"' _ "$S/stub.log"
check "happy_two_rounds: models pushed" [ -f "$S/phone/files/Qwen3.5-4B-Q4_K_M.gguf" ]
check "happy_two_rounds: the DFlash draft not needed by speed" [ ! -f "$S/phone/files/Qwen3.5-4B-DFlash-Q8_0.gguf" ]
# the fixture's lfm-gpu line is ok:false: the instrumentation says OK, so the script must say it failed
check "happy_two_rounds: an ok arm's line has its numbers" \
    grep -q "^  qwen-npu, round 1: load 4.1 s, prompt 912.4 tokens/s, reply 15.3 tokens/s$" "$S/stdout"
check "happy_two_rounds: an ok:false arm says FAILED and its error" \
    grep -q "^  lfm-gpu, round 2: FAILED: OpenCL backend: unsupported op CONV_TRANSPOSE_1D$" "$S/stdout"
check "happy_two_rounds: a line for every arm run" \
    bash -c '[ "$(grep -c "^  [a-z-]*, round [12]: " "$1")" = 12 ]' _ "$S/stdout"
check "happy_two_rounds: the closing message counts ok and failed runs and names the arm" \
    grep -q "^speed finished: 10 arm runs ok, 2 failed; failed in every round: lfm-gpu; wrote " "$S/stdout"
check "happy_two_rounds: speed, no trap" no_trap
sdk="$(git -C "$here" log -1 --format=%h -- ../../../sdk)"
[ -z "$(git -C "$here" status --porcelain -- ../../../sdk)" ] || sdk="$sdk-dirty"
check "happy_two_rounds: the META names the phone and build" python3 - "$S/work/META.json" "$sdk" << 'EOF'
import json, sys
m = json.load(open(sys.argv[1]))
assert m["phone"] == "Stub Phone" and m["soc"] == "STUB8" and m["android"] == "16", m
assert m["sdkCommit"] == sys.argv[2] and m["llamaCppBuild"], m
import re; assert re.fullmatch(r"\d{4}-\d\d-\d\d", m["date"]), m
EOF
run env LB_BENCH_ROUNDS=2 bash "$bench" extras; code=$?
check "happy_two_rounds: extras exits 0" [ $code = 0 ]
check "happy_two_rounds: the stop, voice and restore lines give their extras" bash -c \
    'grep -q "^  stop, round 1: load 4.1 s, stop 38 ms, cancel return 3 ms$" "$1" &&
     grep -q "^  voice, round 2: load 3.9 s, first audio 312 ms, realtime factor 3.40$" "$1" &&
     grep -q "^  restore, round 1: read again 4200 ms, restore 1900 ms$" "$1"' _ "$S/stdout"
check "happy_two_rounds: extras counts its 14 runs, restore included" \
    grep -q "^extras finished: 14 arm runs ok, 0 failed; wrote " "$S/stdout"
check "happy_two_rounds: extras, no trap" no_trap
ex1="$(grep -oE '\-e bench (json-off|json-dflash|edit-off|edit-ngram-mtp|stop|voice) -e benchRound 1 ' "$S/stub.log" | awk '{print $3}' | tr '\n' ' ')"
check "happy_two_rounds: extras arms in order" [ "$ex1" = "json-off json-dflash edit-off edit-ngram-mtp stop voice " ]
check "happy_two_rounds: the clip and every file pushed" bash -c \
    '[ "$(wc -c < "$1/bench-question.pcm" | tr -d " ")" = 32000 ] && [ -f "$1/Qwen3.5-4B-DFlash-Q8_0.gguf" ] && [ -f "$1/mmproj-LFM3-Omni-2B-Q8_0.gguf" ]' _ "$S/phone/files"
check "happy_two_rounds: say was given the sentence" grep -q "lighthouse keeper who finds a message in a bottle" "$S/stub.log"
check "happy_two_rounds: the slot cache test ran each round" bash -c '[ "$(grep -c "SlotCacheDeviceTest -e slotCache Qwen3.5-4B-Q4_K_M.gguf" "$1")" = 2 ]' _ "$S/stub.log"
check "happy_two_rounds: results hold speed, extras and restore, two rounds each, a failed arm kept as one" python3 - "$S/out/bench/results.json" << 'EOF'
import json, sys
arms = json.load(open(sys.argv[1]))["arms"]
want = "qwen-npu qwen-gpu qwen-cpu lfm-npu lfm-gpu lfm-cpu json-off json-dflash edit-off edit-ngram-mtp stop voice restore".split()
assert sorted(arms) == sorted(want), sorted(arms)
# the fixture lines include one arm that failed (lfm-gpu): its rounds count under roundsFailed
assert all(a["rounds"] + a["roundsFailed"] == 2 for a in arms.values()), arms
r = arms["restore"]["median"]["extra"]
assert r == {"readAgainMs": 4200, "restoreMs": 1900}, r
EOF
check "happy_two_rounds: the serial is in no file" bash -c '! grep -rq -- "$1" "$2"' _ "$SERIAL" "$S/work" "$S/out"
check "happy_two_rounds: the serial is in no printed line" bash -c '! grep -q -- "$1" "$2"' _ "$SERIAL" "$S/stdout"
check "happy_two_rounds: Gradle's mention of it is filtered" grep -q "<serial>" "$S/work/gradle.log"

# ---- no_say: the voice arm is skipped with a reason and the rest runs; a restore line says why when the log is empty
fresh
run env LB_BENCH_SAY="$S/nonexistent-say" STUB_NO_SLOTLOG=1 bash "$bench" extras; code=$?
check "no_say: exits 0" [ $code = 0 ]
check "no_say: prints the reason" grep -q "voice arm skipped" "$S/stdout"
check "no_say: voice not run" bash -c '! grep -q "bench voice" "$1"' _ "$S/stub.log"
check "no_say: the other arms ran" [ "$(instrument_calls)" = 6 ]
check "no_say: a failed restore is a line with its reason" bash -c 'grep "\"arm\":\"restore\"" "$1/extras.jsonl" | grep -q "\"ok\":false"' _ "$S/work"
check "no_say: a failed restore says FAILED" grep -q "^  restore, round 1: FAILED: slotcache.log lacks" "$S/stdout"
# without the voice arm, the LFM3-Omni files are not needed
fresh; rm "$S/models/LFM3-Omni-2B-Q4_K_M.gguf" "$S/models/mmproj-LFM3-Omni-2B-Q8_0.gguf"
run env LB_BENCH_SAY="$S/nonexistent-say" bash "$bench" extras; code=$?
check "no_say: runs without the LFM3-Omni files" [ $code = 0 ]
fresh  # the models removed above are seeded again

# ---- unhandled_failure: a command the script has no message for stops it with the line, the serial filtered
fresh
run env STUB_FAIL_PUSH=1 bash "$bench" speed; code=$?
check "unhandled_failure: exits nonzero" [ $code != 0 ]
push_line="$(grep -n '^    push "$(find_model' "$bench" | cut -d: -f1)"
check "unhandled_failure: names the script's line and the function, once" \
    bash -c '[ "$(grep -Ec "^bench: stopped at line $1 of sdk/scripts/bench\.sh, in push(: .*)?$" "$2")" = 1 ]' _ "$push_line" "$S/stdout"
check "unhandled_failure: adb's message is filtered" grep -q "to '<serial>'" "$S/stdout"
check "unhandled_failure: the serial is not printed" bash -c '! grep -q -- "$1" "$2"' _ "$SERIAL" "$S/stdout"
check "unhandled_failure: lock released" bash -c '[ -z "$(ls "$1")" ]' _ "$S/locks"

# ---- install_fails: the log's last lines, filtered, and where the backends are built
fresh
cat > "$S/gradle-fails" << 'GRADLE'
#!/usr/bin/env bash
echo "gradle $*" >> "$STUB_LOG"
echo "> Task :llamabro-hexagon:buildBackends FAILED on $ANDROID_SERIAL"
echo "Cannot connect to the Docker daemon"
exit 1
GRADLE
chmod +x "$S/gradle-fails"
run env LB_BENCH_GRADLE="$S/gradle-fails" bash "$bench" speed; code=$?
check "install_fails: exits nonzero" [ $code != 0 ]
check "install_fails: says it can take several minutes" grep -q "this can take several minutes" "$S/stdout"
check "install_fails: names the log to follow" grep -qF "tail -f $S/work/gradle.log" "$S/stdout"
check "install_fails: prints the log's last lines, filtered" bash -c \
    'grep -q "buildBackends FAILED on <serial>" "$1" && grep -q "Cannot connect to the Docker daemon" "$1"' _ "$S/stdout"
check "install_fails: points at Docker and CONTRIBUTING.md" \
    bash -c 'grep -q "build in Docker" "$1" && grep -q "CONTRIBUTING.md" "$1"' _ "$S/stdout"
check "install_fails: the serial is not printed" bash -c '! grep -q -- "$1" "$2"' _ "$SERIAL" "$S/stdout"
check "install_fails: no trap" no_trap

# ---- no_llama_tag: a checkout whose llama.cpp submodule has no reachable tag (a shallow clone) names it by commit
T="$S/checkout"; L="$T/sdk/native/third_party/llama.cpp"
mkdir -p "$T/sdk/scripts" "$L"
cp "$here/../bench.sh" "$here/../bench.py" "$here/../device-preflight.sh" "$T/sdk/scripts/"
git_() { git -c user.name=t -c user.email=t@t -c commit.gpgsign=false -c tag.gpgsign=false "$@" > /dev/null 2>&1; }
git_ -C "$T" init; git_ -C "$T" add sdk/scripts; git_ -C "$T" commit -m sdk
echo x > "$L/f"; git_ -C "$L" init; git_ -C "$L" add f; git_ -C "$L" commit -m llama
llama_head="$(git -C "$L" rev-parse --short HEAD)"
fresh
run bash "$T/sdk/scripts/bench.sh" speed; code=$?
check "no_llama_tag: the mode runs" [ $code = 0 ]
check "no_llama_tag: llamaCppBuild is the short commit" python3 -c \
    'import json, sys; assert json.load(open(sys.argv[1]))["llamaCppBuild"] == sys.argv[2]' "$S/work/META.json" "$llama_head"
git_ -C "$L" tag b99999
fresh
run bash "$T/sdk/scripts/bench.sh" speed
check "no_llama_tag: with a tag, the tag" python3 -c \
    'import json, sys; assert json.load(open(sys.argv[1]))["llamaCppBuild"] == "b99999"' "$S/work/META.json"
rm -rf "$L/.git" "$L/f"
fresh
run bash "$T/sdk/scripts/bench.sh" speed; code=$?
check "no_llama_tag: an empty submodule folder stops it, not named by this checkout's history" bash -c \
    '[ "$1" != 0 ] && grep -q "cannot name the llama.cpp build" "$2"' _ "$code" "$S/stdout"

echo; [ $fails = 0 ] && echo "BENCH.SH TESTS PASS" || echo "BENCH.SH TESTS FAILED"; exit $fails
