#!/usr/bin/env bash
# Voice plan step 4 (D95, D98): upstream's /v1/realtime conversation with LFM3-Omni-2B on the phone's NPU, as the SDK's
# voice session runs it in memory (F168), driven over adb forward by phone-turns.py. Prints JSON lines; results and the
# server logs go to sdk/native/build/voice-step4/ (not committed).
#   timings      one slot: turns sent at once and at real time, cancels, a transcription alone, and whether a
#                transcription waits while a session is open; memory of the server after load and after the turns
#   two-slots    two slots (twice the context, the same per slot): memory, a plain turn, the turn transcribed before
#                its reply, and transcribed while the reply is made
#   memory       the phone's available memory with no server, then one slot and two slots, each after load and after
#                one reply
#   seeds A B    patch 0008 on the NPU (D96, F167): one captioned reply per server seed A..B, each from a fresh server,
#                with whether the guard ended its speech
# Needs: sdk/native/scripts/build-device-tools.sh, the phone lock (sdk/scripts/device-preflight.sh), ANDROID_SERIAL or
# one phone, the two LFM3-Omni-2B files on the phone (as device-speech/run.sh) and Q.wav (16 kHz mono 16-bit).
# usage: phone-step4.sh Q.wav timings | two-slots | seeds A B
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
native="$here/../../sdk/native"
tools="$native/build/android-device-tools"
models="${LB_DEVICE_MODEL_DIR:-/data/local/tmp/lb-spike/models}"
remote=/data/local/tmp/lb-voice
port="${LB_SPEECH_PORT:-18183}"
out="$native/build/voice-step4"
question="$1"; mode="$2"
mkdir -p "$out"
[ -x "$tools/llama-server" ] || { echo "run sdk/native/scripts/build-device-tools.sh first"; exit 1; }
adb shell "[ -f $models/LFM3-Omni-2B-Q4_K_M.gguf ] && [ -f $models/mmproj-LFM3-Omni-2B-Q8_0.gguf ]" ||
    { echo "the LFM3-Omni-2B files are not in $models on the phone"; exit 1; }

host_port=
stop() {
    adb shell pkill -x llama-server 2> /dev/null || true
    [ -z "$host_port" ] || adb forward --remove "tcp:$host_port" 2> /dev/null || true
    host_port=
}
trap stop EXIT

cool() {
    for _ in $(seq 1 60); do
        s="$(adb shell dumpsys thermalservice | sed -n 's/^Thermal Status: //p' | tr -d '\r')"
        [ "${s:-0}" -le 1 ] && return
        echo "thermal status $s, waiting" >&2; sleep 10
    done
}

# start NP CTX SEED LOGNAME
start() {
    stop; cool
    adb shell "cd $remote && GGML_HEXAGON_OPPOLL=1 LD_LIBRARY_PATH=$remote/bin exec bin/llama-server \
        -m $models/LFM3-Omni-2B-Q4_K_M.gguf --mmproj $models/mmproj-LFM3-Omni-2B-Q8_0.gguf -c $2 -np $1 --fit off \
        -dev HTP0 -ngl 99 --seed $3 --host 127.0.0.1 --port $port > $4.log 2>&1" < /dev/null > /dev/null 2>&1 &
    host_port="$(adb forward tcp:0 "tcp:$port" | tr -d '\r')"
    local t0=$SECONDS
    for _ in $(seq 1 240); do curl -sf "http://127.0.0.1:$host_port/health" > /dev/null 2>&1 && break; sleep 0.5; done
    curl -sf "http://127.0.0.1:$host_port/health" > /dev/null || { adb shell "tail -20 $remote/$4.log"; exit 1; }
    echo "{\"mode\":\"load\",\"np\":$1,\"ctx\":$2,\"seed\":$3,\"ready_s\":$((SECONDS - t0))}"
}

# memory of the server: resident now and at peak (MiB), and the PSS dumpsys reports
mem() {
    local pid
    pid="$(adb shell pidof llama-server | tr -d '\r')"
    adb shell "grep -E 'VmRSS|VmHWM' /proc/$pid/status" | tr -d '\r' | awk -v at="$1" '
        /VmHWM/ {hwm=$2} /VmRSS/ {rss=$2}
        END {printf "{\"mode\":\"memory\",\"at\":\"%s\",\"rss_mib\":%d,\"peak_rss_mib\":%d", at, rss/1024, hwm/1024}'
    adb shell "dumpsys meminfo $pid" | tr -d '\r' | awk '/TOTAL PSS:/ {printf ",\"pss_mib\":%d", $3/1024; f=1}'
    # the weights live in buffers shared with the NPU, which RSS and PSS leave out: the phone's available memory shows them
    adb shell cat /proc/meminfo | tr -d '\r' | awk '/MemAvailable/ {printf ",\"available_mib\":%d}\n", $2/1024}'
}
available() { adb shell cat /proc/meminfo | tr -d '\r' | awk -v at="$1" '/MemAvailable/ {printf "{\"mode\":\"memory\",\"at\":\"%s\",\"available_mib\":%d}\n", at, $2/1024}'; }

run() { local m=$1; shift; python3 "$here/phone-turns.py" "$m" "127.0.0.1:$host_port" "$question" "$@"; }

adb shell "rm -rf $remote && mkdir -p $remote/bin"
adb push -q "$tools/." "$remote/bin/" > /dev/null

case "$mode" in
memory)
    # available memory with no server, then each setup after load and after one reply
    for setup in "1 4096" "2 8192"; do
        stop; sleep 5; available "no-server"
        start $setup 42 mem > /dev/null
        mem "np${setup% *}-after-load"
        run turn paced > /dev/null
        mem "np${setup% *}-after-turn"
    done
    ;;
timings)
    start 1 4096 42 timings
    mem after-load
    run turn                # warm-up and the F166 comparison: the whole question at once
    run turn paced; run turn paced; run turn paced
    run cancel; run cancel
    run transcribe; run transcribe; run transcribe
    run blocked
    mem after-turns
    adb shell "cat $remote/timings.log" > "$out/timings-server.log"
    ;;
two-slots)
    start 2 8192 42 two
    mem after-load
    run turn paced; run turn paced
    run before; run before
    run parallel; run parallel
    mem after-turns
    adb shell "cat $remote/two.log" > "$out/two-slots-server.log"
    ;;
seeds)
    for seed in $(seq "$3" "$4"); do
        start 1 4096 "$seed" "seed$seed" > /dev/null
        RT_SAVE="$out/seed$seed.wav" run turn | python3 -c "
import json, sys
d = json.loads(sys.stdin.read()); d['seed'] = $seed
print(json.dumps({k: d[k] for k in ('seed', 'audio_s', 'took_s', 'first_audio_s', 'rtf', 'tokens', 'status', 'text')}))"
        adb shell "grep -a 'runs past its text' $remote/seed$seed.log" | tr -d '\r' | sed "s/^/seed $seed guard: /" || true
    done
    ;;
*) echo "unknown mode $mode"; exit 1 ;;
esac
