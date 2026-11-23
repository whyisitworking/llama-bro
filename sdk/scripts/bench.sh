#!/usr/bin/env bash
# The README's benchmark, run on the phone: install the acceptance app once, run BenchDeviceTest one arm per
# instrumentation call (a fresh process and a fresh load each), wait for the phone to cool before every arm, collect
# the lines, and write research/bench/results.json and the charts in assets/readme/ (bench.py merge, then charts).
# The method is in research/bench/README.md.
#
#   speed    the six chip arms: qwen-npu qwen-gpu qwen-cpu lfm-npu lfm-gpu lfm-cpu (needs the Qwen3.5 4B and the
#            LFM3-Omni 2B models)
#   extras   drafting (json-off json-dflash edit-off edit-ngram-mtp), stop, voice, then SlotCacheDeviceTest (the
#            `restore` arm); needs Qwen3.5 4B and its DFlash draft (read by json-dflash only), and for voice LFM3-Omni 2B
#            and its projector, and macOS `say` and `afconvert` for its question clip (without them the voice arm is
#            skipped with a printed reason, and the LFM3-Omni files are not needed)
#   ladder   six models from 230M to 4B on the NPU, GPU and CPU, each with a short request (ladder-<model>-<chip>), then
#            embeddings with bge-small and reranking with jina-reranker-tiny on each chip (embed-<chip>, rerank-<chip>)
#
# One round (one by default) runs every arm of the mode once; the arm order reverses in every second round, so no arm always runs on
# the warmest or the coolest phone. The modes accumulate in a work folder, so after all three, results.json holds every
# arm. results.json and the charts are replaced only when a mode finished completely: a mode that stops early (a
# missing model, a phone that does not cool, a phone that drops off adb) leaves both as they were, and keeps every line
# measured so far in the work folder ($mode.jsonl.partial, named in the stop message). An arm whose instrumentation
# crashes or writes no line is recorded as a failed arm (ok:false, with the runner's message) and the mode goes on; a
# mode in which every arm failed publishes nothing. A run replaces its own mode's earlier lines.
#
# As it runs, the script prints one line per arm and round from the line it recorded: the load time, the prompt and
# reply speeds and the extras of an arm that ran, or FAILED and the reason for one that did not. The closing message
# counts the runs that went well and the runs that failed, and names any arm that failed in every round. A command
# that fails where the script has no message of its own stops it with the line it stopped at.
#
# Every mode must be measured on one build: the build is the last commit that touched sdk/ (with -dirty when sdk/ has
# uncommitted changes) and llama.cpp's build (llamaCppBuild: the submodule's latest reachable tag, such as b12653, or
# its short commit hash when no tag is reachable, as in a shallow clone of the submodule), so committing results or
# docs between the modes does not change it. A mode refuses to start when another mode's kept lines are from another
# build.
#
# usage: ANDROID_SERIAL=<serial> sdk/scripts/bench.sh speed|extras|ladder   (ANDROID_SERIAL may be left out with one phone)
# env:   LB_BENCH_ROUNDS         rounds per arm (default 1)
#        LB_BENCH_MODELS_DIR     where the model files are on this machine: folders separated by colons, searched in
#                                order (default ~/Downloads/llamabro-test:~/Downloads:~/llamabro-models); a file
#                                already in the app's files on the phone is not pushed again unless its size differs
#        LB_BENCH_COOL_INTERVAL  seconds between two thermal status reads (default 10)
#        LB_BENCH_COOL_TRIES     reads before giving up (default 60, with the interval 10 minutes); the status must be 1
#                                or lower before each arm
#        LB_BENCH_WORK           the work folder (default sdk/native/build/bench, ignored by git)
#        LB_BENCH_OUT_DIR        where results.json goes (default research/bench)
#        LB_BENCH_CHARTS_DIR     where the SVGs go (default assets/readme)
#        LB_BENCH_GRADLE         the Gradle wrapper (default ./gradlew); LB_BENCH_SAY and LB_BENCH_AFCONVERT the
#                                speech commands (default say and afconvert)
#        LB_DEVICE_LOCK_DIR      see device-preflight.sh
# The phone's serial is never written to a file or printed: every adb call goes through one function that filters its
# error output, and so does Gradle's output.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
mode="${1:-}"
case "$mode" in
    speed | extras | ladder) ;;
    *) echo "usage: ANDROID_SERIAL=<serial> sdk/scripts/bench.sh speed|extras|ladder" >&2; exit 2 ;;
esac

rounds="${LB_BENCH_ROUNDS:-1}"
case "$rounds" in '' | *[!0-9]* | 0) echo "LB_BENCH_ROUNDS must be a positive whole number" >&2; exit 2 ;; esac
models_dirs="${LB_BENCH_MODELS_DIR:-$HOME/Downloads/llamabro-test:$HOME/Downloads:$HOME/llamabro-models}"
cool_interval="${LB_BENCH_COOL_INTERVAL:-10}"
cool_tries="${LB_BENCH_COOL_TRIES:-60}"
work="${LB_BENCH_WORK:-$root/sdk/native/build/bench}"
out_dir="${LB_BENCH_OUT_DIR:-$root/research/bench}"
charts_dir="${LB_BENCH_CHARTS_DIR:-$root/assets/readme}"
gradle="${LB_BENCH_GRADLE:-$root/gradlew}"
say_cmd="${LB_BENCH_SAY:-say}"
afconvert_cmd="${LB_BENCH_AFCONVERT:-afconvert}"

PKG=com.suhel.llamabro.sample
QWEN=Qwen3.5-4B-Q4_K_M.gguf
QWEN_DRAFT=Qwen3.5-4B-DFlash-Q8_0.gguf
LFM=LFM3-Omni-2B-Q4_K_M.gguf
LFM_PROJECTOR=mmproj-LFM3-Omni-2B-Q8_0.gguf
CLIP=bench-question.pcm
LADDER_FILES="LFM2.5-230M-Q4_K_M.gguf Qwen3.5-0.8B-Q4_K_M.gguf LFM3-1.7B-Q4_K_M.gguf Llama-3.2-3B-Instruct-Q4_K_M.gguf gemma-3-4b-it-qat-UD-Q4_K_XL.gguf $QWEN"
EMBEDDER=bge-small-f16.gguf
RERANKER=jina-reranker-tiny-f16.gguf
# F170's question
QUESTION="Can you tell me a short story about a lighthouse keeper who finds a message in a bottle?"

die() { echo "bench: $*" >&2; exit 1; }

# the serial, kept out of every file and line: it comes from the environment or the one phone on adb
serial="${ANDROID_SERIAL:-}"
if [ -z "$serial" ]; then
    serial="$(command adb get-serialno 2> /dev/null | tr -d '\r' || true)"
    { [ -n "$serial" ] && [ "$serial" != unknown ]; } || die "no single phone on adb: connect one or set ANDROID_SERIAL"
fi
export ANDROID_SERIAL="$serial"
serial_re="$(printf '%s' "$serial" | sed 's/[][\.*^$/]/\\&/g')"
lock_re="$(printf '%s' "${serial//[^A-Za-z0-9._-]/_}" | sed 's/[][\.*^$/]/\\&/g')"
scrub() { sed -e "s/$serial_re/<serial>/g" -e "s/$lock_re/<serial>/g"; }
# a command that fails where the script has no message of its own (set -e) says where it stopped. The failures the
# script handles sit in conditions (if, ||, &&), where bash runs no ERR trap; -E carries the trap into functions, and
# a command substitution's failure is reported once, by the line that holds it. The line named is the script's own
# (the outermost call), since bash 3.2 misnumbers a function that fails by returning; the functions it was in follow.
on_err() {
    [ "$BASH_SUBSHELL" = 0 ] || return 0
    local n=${#FUNCNAME[@]} i inside=""
    for ((i = n - 2; i >= 1; i--)); do inside="$inside in ${FUNCNAME[$i]}"; done
    case "$1" in return*) set -- "" ;; esac  # bash 3.2 names the function's return, not what failed
    printf 'bench: stopped at line %s of sdk/scripts/bench.sh%s%s\n' "${BASH_LINENO[$((n - 2))]}" \
        "${inside:+,$inside}" "${1:+: $1}" | scrub >&2
}
set -E
trap 'on_err "$BASH_COMMAND"' ERR
mkdir -p "$work"
# every adb call goes through here, so its error text (which names the serial when the phone drops off) is filtered
adb() {
    local rc=0 err
    # stdout passes through; stderr is held in a variable (never in a file) and printed filtered
    { err="$(command adb "$@" 2>&1 1>&3 3>&-)" || rc=$?; } 3>&1
    [ -z "$err" ] || printf '%s\n' "$err" | scrub >&2
    return $rc
}

# python3 does what bash 3.2 does badly: JSON lines, the META and the clip
IFS= read -r -d '' PY << 'EOF' || true
import json, os, re, sys, wave

cmd = sys.argv[1]
serial = os.environ.get("ANDROID_SERIAL", "")


def clean(text):
    return text.replace(serial, "<serial>") if serial else text


def dump(obj):
    return clean(json.dumps(obj, separators=(",", ":")))


if cmd == "addbuild":  # LINES on stdin, SDK_COMMIT LLAMACPP_BUILD: each line gets the build it came from
    sdk, llama = sys.argv[2:4]
    for raw in sys.stdin:
        if raw.strip():
            line = json.loads(raw)
            line["build"] = {"sdkCommit": sdk, "llamaCppBuild": llama}
            print(dump(line))
elif cmd == "restore":  # ROUND PASSED SDK LLAMACPP LOG: the restore arm's line from SlotCacheDeviceTest's log
    rnd, passed, sdk, llama, path = sys.argv[2:7]
    text = open(path).read()
    again = re.findall(r"^read again: (\d+)ms in all", text, re.M)
    restore = re.findall(r"^restore: (\d+)ms in all", text, re.M)
    line = {"arm": "restore", "round": int(rnd), "ok": True, "error": None}
    if passed != "1":
        line.update(ok=False, error="SlotCacheDeviceTest did not pass")
    elif not again or not restore:
        line.update(ok=False, error="slotcache.log lacks the read again and restore lines")
    else:
        line["extra"] = {"readAgainMs": int(again[-1]), "restoreMs": int(restore[-1])}
    line["build"] = {"sdkCommit": sdk, "llamaCppBuild": llama}
    print(dump(line))
elif cmd == "has":  # PULLED ARM ROUND: exit 0 when the pulled lines hold that arm and round
    pulled, arm, rnd = sys.argv[2:5]
    for raw in open(pulled):
        if raw.strip():
            line = json.loads(raw)
            if line.get("arm") == arm and line.get("round") == int(rnd):
                sys.exit(0)
    sys.exit(1)
elif cmd == "failure":  # ARM ROUND SDK LLAMACPP OUTPUT: the line of an arm whose instrumentation crashed
    arm, rnd, sdk, llama, path = sys.argv[2:7]
    rows = [r.strip() for r in open(path).read().splitlines() if r.strip()]
    pick = [r for r in rows if re.search(r"INSTRUMENTATION_FAILED|crash|Exception|Error|FAILURES|shortMsg", r)]
    why = (pick or rows or ["the instrumentation reported no result"])[0][:160]
    print(dump({"arm": arm, "round": int(rnd), "ok": False, "error": "instrumentation failed: " + why,
                "build": {"sdkCommit": sdk, "llamaCppBuild": llama}}))
elif cmd == "said":  # ARM ROUND FILE: how that arm went in that round, in one line, from its recorded line
    arm, rnd, path = sys.argv[2:5]
    found = None
    for raw in open(path):
        if raw.strip():
            line = json.loads(raw)
            if line.get("arm") == arm and line.get("round") == int(rnd):
                found = line
    head = f"  {arm}, round {rnd}: "
    if found is None:
        print(clean(head + "FAILED: no line was recorded"))
    elif not found.get("ok"):
        why = (str(found.get("error") or "").strip().splitlines() or ["no reason given"])[0][:200]
        print(clean(head + "FAILED: " + why))
    else:
        words = lambda key: re.sub(r"[A-Z]", lambda m: " " + m.group(0).lower(), key)
        parts = []
        if found.get("loadMs") is not None:
            parts.append(f"load {found['loadMs'] / 1000:.1f} s")
        if found.get("promptTps") is not None:
            parts.append(f"prompt {found['promptTps']:g} tokens/s")
        if found.get("predictedTps") is not None:
            parts.append(f"reply {found['predictedTps']:g} tokens/s")
        for key, v in (found.get("extra") or {}).items():
            if key.endswith("Ms"):
                parts.append(f"{words(key[:-2])} {v:.0f} ms")
            elif key.endswith("Seconds"):
                parts.append(f"{words(key[:-7])} {v:.1f} s")
            else:
                parts.append(f"{words(key)} {v:.2f}")
        print(clean(head + (", ".join(parts) or "ok")))
elif cmd == "tally":  # FILE: the runs that went well and failed, and the arms that failed in every round
    runs = {}
    for raw in open(sys.argv[2]):
        if raw.strip():
            line = json.loads(raw)
            runs.setdefault(line["arm"], []).append(bool(line.get("ok")))
    ok = sum(r.count(True) for r in runs.values())
    failed = sum(r.count(False) for r in runs.values())
    text = f"{ok} arm runs ok, {failed} failed"
    every = [a for a, r in runs.items() if not any(r)]
    some = [a for a, r in runs.items() if any(r) and not all(r)]
    if every:
        text += "; failed in every round: " + " ".join(every)
    if some:
        text += "; failed in some rounds: " + " ".join(some)
    print(clean(text))
elif cmd == "build":  # FILE: "SDK_COMMIT LLAMACPP_BUILD" of the first line that has one
    for raw in open(sys.argv[2]):
        if raw.strip() and "build" in json.loads(raw):
            b = json.loads(raw)["build"]
            print(b.get("sdkCommit", ""), b.get("llamaCppBuild", ""))
            break
elif cmd == "meta":  # OUT SDK LLAMACPP PHONE SOC ANDROID DATE
    out, sdk, llama, phone, soc, android, date = sys.argv[2:9]
    meta = {"sdkCommit": sdk, "llamaCppBuild": llama, "phone": phone, "soc": soc, "android": android, "date": date}
    with open(out, "w") as f:
        f.write(clean(json.dumps(meta, indent=2)) + "\n")
elif cmd == "clip":  # WAV OUT: the WAV's samples as raw 16-bit little-endian mono PCM at 16 kHz, no header
    with wave.open(sys.argv[2]) as w:
        assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, 16000), "not 16 kHz 16-bit mono"
        data = w.readframes(w.getnframes())
    assert data, "the clip is empty"
    open(sys.argv[3], "wb").write(data)
EOF
py() { python3 -I -c "$PY" "$@"; }

# the build the lines come from
sdk_commit="$(git -C "$root" log -1 --format=%h -- sdk)" || die "cannot read the checkout's history"
[ -n "$sdk_commit" ] || die "no commit has touched sdk/"
llama_dir="$root/sdk/native/third_party/llama.cpp"
# its tag, or its commit when no tag is reachable (.gitmodules clones the submodule shallow, so a fresh clone may lack
# it); only from the submodule's own checkout, since in an empty folder git would answer for this repository
llama_build=""
if [ -e "$llama_dir/.git" ]; then
    llama_build="$(git -C "$llama_dir" describe --tags --abbrev=0 2> /dev/null ||
        git -C "$llama_dir" rev-parse --short HEAD 2> /dev/null || true)"
fi
[ -n "$llama_build" ] || die "cannot name the llama.cpp build: is the submodule checked out (git submodule update --init)?"
if [ -n "$(git -C "$root" status --porcelain -- sdk 2> /dev/null || true)" ]; then
    sdk_commit="$sdk_commit-dirty"
fi
# the other modes' kept lines must be from this build: found out now, not after an hour of phone time
for other in speed extras ladder; do
    [ "$other" != "$mode" ] && [ -f "$work/$other.jsonl" ] || continue
    kept="$(py build "$work/$other.jsonl")"
    if [ "$kept" != "$sdk_commit $llama_build" ]; then
        die "the $other lines in $work were measured on build ${kept% *} with llama.cpp ${kept#* }, this run is build $sdk_commit with llama.cpp $llama_build. Measure every mode on one build: delete $work/$other.jsonl and run sdk/scripts/bench.sh $other again after this one"
    fi
done

# the arms of the mode, and the files they read
if [ "$mode" = speed ]; then
    arms="qwen-npu qwen-gpu qwen-cpu lfm-npu lfm-gpu lfm-cpu"
    needs="$QWEN $LFM"
elif [ "$mode" = ladder ]; then
    arms=""
    for m in lfm230m qwen08b lfm17b llama3b gemma4b qwen4b; do
        for c in npu gpu cpu; do arms="$arms ladder-$m-$c"; done
    done
    for c in npu gpu cpu; do arms="$arms embed-$c rerank-$c"; done
    arms="${arms# }"
    needs="$LADDER_FILES $EMBEDDER $RERANKER"
else
    arms="json-off json-dflash edit-off edit-ngram-mtp stop voice restore"
    needs="$QWEN $QWEN_DRAFT $LFM $LFM_PROJECTOR"
fi
voice=1
if [ "$mode" = extras ] && ! { command -v "$say_cmd" > /dev/null 2>&1 && command -v "$afconvert_cmd" > /dev/null 2>&1; }; then
    voice=0
    arms="${arms/voice /}"
    needs="$QWEN $QWEN_DRAFT"  # LFM3-Omni and its projector are read by the voice arm only
    echo "voice arm skipped: its question clip is made with macOS say and afconvert, which this host lacks"
fi

# one phone, one session (F150): the lock is held until this script ends
lock=""
# armed only once the preflight has passed and the lock is held: a refused run must not touch another session's app
cleanup() {
    adb shell am force-stop "$PKG" > /dev/null 2>&1 || true
    rm -f "$lock"
}
if lock="$(bash "$root/sdk/scripts/device-preflight.sh" $$ 2> "$work/preflight.raw")"; then ok=1; else ok=0; lock=""; fi
scrub < "$work/preflight.raw" > "$work/preflight.log"; rm -f "$work/preflight.raw"
if [ "$ok" = 0 ]; then
    cat "$work/preflight.log" >&2
    die "the phone steps did not run"
fi
trap cleanup EXIT
windows="$(adb shell dumpsys window | tr -d '\r' || true)"  # not piped into grep -q: it would end the pipe early
case "$windows" in *isKeyguardShowing=false*) ;; *) die "the phone is locked: unlock it and run again" ;; esac

# every model file: on the phone already, or on this machine, or missing (listed together, nothing installed yet)
find_model() {  # find_model NAME: the first folder of LB_BENCH_MODELS_DIR that holds it
    local IFS=: dir
    for dir in $models_dirs; do
        if [ -f "$dir/$1" ]; then echo "$dir/$1"; return 0; fi
    done
    return 1
}
size_of() { wc -c < "$1" | tr -d ' '; }
phone_size() {  # the size of a file in the app's files, empty when absent (or the app is not installed)
    adb shell run-as "$PKG" stat -c %s "files/$1" 2> /dev/null | tr -d '\r' || true
}
missing=""
to_push=""
for f in $needs; do
    on_phone="$(phone_size "$f")"
    case "$on_phone" in '' | *[!0-9]*) on_phone="" ;; esac
    if here="$(find_model "$f")"; then
        [ "$on_phone" = "$(size_of "$here")" ] || to_push="$to_push $f"
    elif [ -z "$on_phone" ]; then
        missing="$missing $f"
    fi
done
if [ -n "$missing" ]; then
    echo "bench: $mode needs these model files, which are neither in the app's files on the phone nor in $models_dirs:" >&2
    for f in $missing; do echo "  $f" >&2; done
    exit 1
fi

# the clip first: a missing tool must not cost an install
if [ "$voice" = 1 ] && [ "$mode" = extras ]; then
    "$say_cmd" -o "$work/question.aiff" "$QUESTION"
    "$afconvert_cmd" -f WAVE -d LEI16@16000 -c 1 "$work/question.aiff" "$work/question.wav"
    py clip "$work/question.wav" "$work/$CLIP"
fi

# the full flavor includes the backend modules, whose native libraries build in Docker unless they are up to date
echo "installing the full flavor's debug app and its test app: this can take several minutes"
echo "  Gradle's output: tail -f $work/gradle.log"
if ! (cd "$root" && "$gradle" -p sdk :acceptance:backends:installFullDebug :acceptance:backends:installFullDebugAndroidTest) 2>&1 |
    scrub > "$work/gradle.log"; then
    echo "bench: the last lines of $work/gradle.log:" >&2
    tail -20 "$work/gradle.log" | scrub >&2
    echo "bench: the full flavor includes the NPU and GPU backend modules, whose native libraries build in Docker" \
        "(Qualcomm's Snapdragon toolchain image) unless they are up to date: see CONTRIBUTING.md" >&2
    die "the install failed (the log: $work/gradle.log)"
fi

push() {  # push HOST_FILE NAME: into the app's files through /data/local/tmp (check.sh's way for the sample app)
    adb push "$1" "/data/local/tmp/$2" > /dev/null
    adb shell run-as "$PKG" cp "/data/local/tmp/$2" "files/$2"
    adb shell rm -f "/data/local/tmp/$2"
}
for f in $to_push; do
    echo "pushing $f"
    push "$(find_model "$f")" "$f"
done
[ "$voice" = 1 ] && [ "$mode" = extras ] && push "$work/$CLIP" "$CLIP"

instr="$(adb shell pm list instrumentation | tr -d '\r' | sed -n "s/^instrumentation:\([^ ]*\) (target=$PKG)\$/\1/p" | head -1)"
[ -n "$instr" ] || die "no instrumentation targets $PKG (pm list instrumentation)"
adb shell run-as "$PKG" rm -f files/bench.jsonl files/slotcache.log

# the phone's temperature: every arm starts at thermal status 1 or lower, or the mode stops
cool() {
    local i status=none raw
    for ((i = 0; i < cool_tries; i++)); do
        raw="$(adb shell dumpsys thermalservice 2>&1)" || { echo "bench: could not read the phone's thermal status: ${raw%%$'\n'*}" >&2; return 1; }
        status="$(printf '%s\n' "$raw" | tr -d '\r' | sed -n 's/^Thermal Status: //p' | head -1 || true)"
        case "$status" in '' | *[!0-9]*) status=unreadable ;; esac
        if [ "$status" != unreadable ] && [ "$status" -le 1 ]; then return 0; fi
        [ "$i" -gt 0 ] || echo "  waiting for the phone to cool (thermal status $status)"
        sleep "$cool_interval"
    done
    echo "bench: the phone did not reach thermal status 1 in $cool_tries reads $cool_interval s apart (last: $status)" >&2
    return 1
}

run_instrument() {  # run_instrument CLASS [-e name value]...: succeeds when the runner reports OK
    local class="$1" out; shift
    out="$(adb shell am instrument -w -e class "$class" "$@" "$instr" 2>&1 | tr -d '\r' || true)"
    printf '%s\n' "$out" | scrub > "$work/last-instrument.txt"
    cat "$work/last-instrument.txt" >> "$work/instrument.log"
    grep -q '^OK (' <<< "$out"
}

collected="$work/$mode.jsonl.partial"
restores="$work/$mode.restore.partial"
failures="$work/$mode.failed.partial"
: > "$restores"; : > "$failures"; : > "$collected"; : > "$work/bench.pulled"
rm -f "$work/instrument.log"

# the lines measured so far: the phone's (each with its build), the failed arms' and the restore lines. Written after
# every arm, so a stop at any point keeps what was measured.
assemble() {
    # a failed pull (the file does not exist yet, or the phone is gone) keeps what was pulled before
    if adb shell run-as "$PKG" cat files/bench.jsonl 2> /dev/null | tr -d '\r' > "$work/bench.pulling"; then
        mv "$work/bench.pulling" "$work/bench.pulled"
    fi
    { py addbuild "$sdk_commit" "$llama_build" < "$work/bench.pulled"; cat "$failures" "$restores"; } > "$collected"
}
stop_mode() {  # stop_mode MESSAGE: ends the mode, results as they were, the measured lines kept
    echo "bench: $*" >&2
    if [ "$(grep -c . "$collected" || true)" != 0 ]; then
        echo "bench: the $(grep -c . "$collected") lines measured so far are kept in $collected" >&2
    fi
    exit 1
}
for ((round = 1; round <= rounds; round++)); do
    order="$arms"
    if [ $((round % 2)) = 0 ]; then
        order=""
        for a in $arms; do order="$a $order"; done
    fi
    for arm in $order; do
        echo "round $round of $rounds: $arm"
        state="$(adb get-state 2>&1 | tr -d '\r' | head -1 || true)"
        [ "$state" = device ] || stop_mode "the phone is no longer on adb (adb said: $state)"
        cool || stop_mode "the phone is not ready for $arm, round $round"
        if [ "$arm" = restore ]; then
            adb shell run-as "$PKG" rm -f files/slotcache.log
            passed=0
            run_instrument com.suhel.llamabro.sample.SlotCacheDeviceTest -e slotCache "$QWEN" && passed=1
            adb shell run-as "$PKG" cat files/slotcache.log 2> /dev/null | tr -d '\r' > "$work/slotcache.log" || true
            py restore "$round" "$passed" "$sdk_commit" "$llama_build" "$work/slotcache.log" >> "$restores"
            assemble
        else
            run_instrument com.suhel.llamabro.sample.BenchDeviceTest -e bench "$arm" -e benchRound "$round" || true
            assemble
            if ! py has "$work/bench.pulled" "$arm" "$round"; then
                echo "  $arm, round $round: no line was written; recorded as failed (the log: $work/instrument.log)"
                py failure "$arm" "$round" "$sdk_commit" "$llama_build" "$work/last-instrument.txt" >> "$failures"
                assemble
            fi
        fi
        # the test passes when its arm fails (a failed arm is a result), so the runner's OK says nothing: say how it went
        py said "$arm" "$round" "$collected" | scrub
    done
done
rm -f "$work/bench.pulled" "$work/bench.pulling" "$restores" "$failures"
[ "$(grep -c '"ok":true' "$collected" || true)" != 0 ] ||
    die "every arm failed in every round, so there is nothing to publish (the log: $work/instrument.log)"
tally="$(py tally "$collected" | scrub)"

# the META: the phone, never its serial
prop() { adb shell getprop "$1" | tr -d '\r'; }
py meta "$work/META.json.partial" "$sdk_commit" "$llama_build" "$(prop ro.product.model)" "$(prop ro.soc.model)" \
    "$(prop ro.build.version.release)" "$(date -u +%Y-%m-%d)"

# the results: into temporary files, moved into place only now that the whole mode finished
mv "$collected" "$work/$mode.jsonl"
mv "$work/META.json.partial" "$work/META.json"
tmp="$work/results.tmp"
rm -rf "$tmp"; mkdir -p "$tmp/charts"
python3 "$root/sdk/scripts/bench.py" merge "$work"/*.jsonl "$work/META.json" -o "$tmp/results.json"
python3 "$root/sdk/scripts/bench.py" charts "$tmp/results.json" -o "$tmp/charts"
mkdir -p "$out_dir" "$charts_dir"
mv "$tmp/results.json" "$out_dir/results.json"
cp "$tmp"/charts/*.svg "$charts_dir/"
rm -rf "$tmp"
echo "$mode finished: $tally; wrote $out_dir/results.json and the charts in $charts_dir"
case "$mode" in
    speed) echo "next: sdk/scripts/bench.sh extras" ;;
    extras) echo "next: sdk/scripts/bench.sh ladder" ;;
esac
exit 0
