#!/usr/bin/env bash
# Device speech check (F155, F156, F162, patches 0005-0007, D86): LFM3-Omni-2B speaks through upstream llama-server's
# /tts with everything on the phone's NPU, polling on as with npuPolling (GGML_HEXAGON_OPPOLL=1). Passes when:
#   - a 55-word text gives at least 10 s of audio (about 19 s is normal); without patch 0005 Hexagon tiles the
#     depthformer's codebook table, which its lookup read through a view, and every request returned an empty WAV;
#   - the speech projector's warm-up reports no unsupported op (before patch 0005, ARANGE: an NPU -> CPU -> NPU hop in
#     every depthformer pass);
#   - it speaks at least 1.5 times faster than real time (about 2.3 is normal; the CPU hops gave 1.3-1.4).
# Measurement scripts and the clips: research/ph/speech-output/.
#
# Needs: scripts/build-device-tools.sh, a phone on adb (ANDROID_SERIAL), and LFM3-Omni-2B-Q4_K_M.gguf with
# mmproj-LFM3-Omni-2B-Q8_0.gguf on the phone in $LB_DEVICE_MODEL_DIR, or on this machine in $LB_TEST_MODELS_DIR (pushed once).
# Ports: LB_SPEECH_PORT (the server's port on the phone, default 18183), LB_SPEECH_HOST_PORT (this machine's end of the
# adb forward, default 0: adb picks a free one, so concurrent checks never collide, F159).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
native="$(cd "$here/../.." && pwd)"
tools="$native/build/android-device-tools"
models="${LB_DEVICE_MODEL_DIR:-/data/local/tmp/lb-spike/models}"
local_models="${LB_TEST_MODELS_DIR:-$HOME/Downloads/llamabro-test}"
remote=/data/local/tmp/lb-speech
port="${LB_SPEECH_PORT:-18183}"   # on the phone; the forward's host end is picked below
host_port="${LB_SPEECH_HOST_PORT:-0}"
out="$native/build/device-speech"

[ -x "$tools/llama-server" ] || { echo "FAIL: run scripts/build-device-tools.sh first"; exit 1; }
adb shell true || { echo "FAIL: no phone on adb"; exit 1; }
adb shell "mkdir -p $models"
for f in LFM3-Omni-2B-Q4_K_M.gguf mmproj-LFM3-Omni-2B-Q8_0.gguf; do
    adb shell "[ -f $models/$f ]" && continue
    [ -f "$local_models/$f" ] || { echo "FAIL: $f is neither on the phone ($models) nor in $local_models"; exit 1; }
    adb push "$local_models/$f" "$models/$f"
done

# never alongside another llama-server or a test app (F27)
adb shell pkill -x llama-server 2>/dev/null || true
for _ in $(seq 1 60); do
    s="$(adb shell dumpsys thermalservice | sed -n 's/^Thermal Status: //p' | tr -d '\r')"
    [ "${s:-0}" -le 2 ] && break
    echo "thermal status $s, waiting"; sleep 10
done
adb shell "rm -rf $remote && mkdir -p $remote/bin"
adb push -q "$tools/." "$remote/bin/" > /dev/null
rm -rf "$out" && mkdir -p "$out"

trap 'adb shell pkill -x llama-server 2>/dev/null || true
    [ "$host_port" = 0 ] || adb forward --remove "tcp:$host_port" 2>/dev/null || true' EXIT
adb shell "cd $remote && GGML_HEXAGON_OPPOLL=1 LD_LIBRARY_PATH=$remote/bin exec bin/llama-server -m $models/LFM3-Omni-2B-Q4_K_M.gguf \
    --mmproj $models/mmproj-LFM3-Omni-2B-Q8_0.gguf -c 4096 -np 1 --fit off -dev HTP0 -ngl 99 \
    --host 127.0.0.1 --port $port > server.log 2>&1" < /dev/null > /dev/null 2>&1 &
# with tcp:0 adb prints the host port it picked
forwarded="$(adb forward "tcp:$host_port" "tcp:$port" | tr -d '\r')" || { echo "FAIL: adb forward"; exit 1; }
[ "$host_port" != 0 ] || host_port="$forwarded"
for _ in $(seq 1 180); do curl -sf "http://127.0.0.1:$host_port/health" > /dev/null 2>&1 && break; sleep 1; done
if ! curl -sf "http://127.0.0.1:$host_port/health" > /dev/null; then
    adb shell "tail -20 $remote/server.log"
    echo "FAIL: llama-server did not start"; exit 1
fi

text="Tomorrow will be warmer, around eighteen degrees, and the sun should come out by the afternoon. If you are planning a trip to the museum, go early, because the queues grow quickly after ten o'clock. Bring an umbrella, a light jacket, and a bottle of water, and remember that most shops close early on Sunday."
body=$(python3 -c "import json,sys; print(json.dumps({'input':sys.argv[1],'voice':'female','seed':42}))" "$text")
read -r code took < <(curl -s -o "$out/speech.wav" -w '%{http_code} %{time_total}\n' -H 'Content-Type: application/json' \
    -d "$body" "http://127.0.0.1:$host_port/tts")
adb shell "cat $remote/server.log" > "$out/server.log" || true
[ "$code" = 200 ] || { echo "FAIL: /tts answered HTTP $code"; head -c 300 "$out/speech.wav"; echo; exit 1; }
if grep -a -q "mmproj graph uses unsupported operators" "$out/server.log"; then
    grep -a -A6 "mmproj graph uses unsupported operators" "$out/server.log" | sed -n '3,6p'
    echo "FAIL: the speech graph has an op the NPU lacks, so it hops to the CPU (F156: is patch 0005 applied?)"; exit 1
fi
python3 - "$out/speech.wav" "$took" <<'PY'
import struct, sys
b = open(sys.argv[1], 'rb').read()
took = float(sys.argv[2])
if len(b) < 44:
    sys.exit(f"FAIL: {len(b)} bytes, not a WAV")
rate, bits, ch = struct.unpack('<I', b[24:28])[0], struct.unpack('<H', b[34:36])[0], struct.unpack('<H', b[22:24])[0]
seconds = (len(b) - 44) / (bits // 8) / max(ch, 1) / rate
print(f"speech on the NPU: {seconds:.2f} s of audio in {took:.2f} s, {seconds / took:.2f} times real time")
if seconds < 10:
    sys.exit("FAIL: empty or short audio (F155: is patch 0005 applied?)")
if seconds / took < 1.5:
    sys.exit("FAIL: slower than 1.5 times real time (F156: patches 0005, 0006 and polling)")
PY
echo "PASS"
