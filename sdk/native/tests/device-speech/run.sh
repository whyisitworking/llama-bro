#!/usr/bin/env bash
# Device speech check (F155, patch 0005): LFM3-Omni-2B speaks through upstream llama-server's /tts with everything on
# the phone's NPU. Without patch 0005 Hexagon tiles the depthformer's codebook table, which a GET_ROWS also reads, and
# every request returns an empty WAV with no error; this fails then. Passes when a short text gives at least 1.5 s of
# audio (about 4 s is normal). Measurement scripts and the clips: research/ph/speech-output/.
#
# Needs: scripts/build-device-tools.sh, a phone on adb (ANDROID_SERIAL), and LFM3-Omni-2B-Q4_K_M.gguf with
# mmproj-LFM3-Omni-2B-Q8_0.gguf on the phone in $LB_DEVICE_MODEL_DIR, or on this machine in $LB_TEST_MODELS_DIR (pushed once).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
native="$(cd "$here/../.." && pwd)"
tools="$native/build/android-device-tools"
models="${LB_DEVICE_MODEL_DIR:-/data/local/tmp/lb-spike/models}"
local_models="${LB_TEST_MODELS_DIR:-$HOME/Downloads/llamabro-test}"
remote=/data/local/tmp/lb-speech
port="${LB_SPEECH_PORT:-18183}"
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

trap 'adb shell pkill -x llama-server 2>/dev/null || true; adb forward --remove tcp:'"$port"' 2>/dev/null || true' EXIT
adb shell "cd $remote && LD_LIBRARY_PATH=$remote/bin exec bin/llama-server -m $models/LFM3-Omni-2B-Q4_K_M.gguf \
    --mmproj $models/mmproj-LFM3-Omni-2B-Q8_0.gguf -c 4096 -np 1 --fit off -dev HTP0 -ngl 99 \
    --host 127.0.0.1 --port $port > server.log 2>&1" < /dev/null > /dev/null 2>&1 &
adb forward "tcp:$port" "tcp:$port" > /dev/null
for _ in $(seq 1 180); do curl -sf "http://127.0.0.1:$port/health" > /dev/null 2>&1 && break; sleep 1; done
if ! curl -sf "http://127.0.0.1:$port/health" > /dev/null; then
    adb shell "tail -20 $remote/server.log"
    echo "FAIL: llama-server did not start"; exit 1
fi

code=$(curl -s -o "$out/speech.wav" -w '%{http_code}' -H 'Content-Type: application/json' \
    -d '{"input":"The weather in Paris is cloudy, with light rain after lunch.","voice":"female","seed":42}' \
    "http://127.0.0.1:$port/tts")
adb shell "cat $remote/server.log" > "$out/server.log" || true
[ "$code" = 200 ] || { echo "FAIL: /tts answered HTTP $code"; head -c 300 "$out/speech.wav"; echo; exit 1; }
python3 - "$out/speech.wav" <<'PY'
import struct, sys
b = open(sys.argv[1], 'rb').read()
if len(b) < 44:
    sys.exit(f"FAIL: {len(b)} bytes, not a WAV")
rate, bits, ch = struct.unpack('<I', b[24:28])[0], struct.unpack('<H', b[34:36])[0], struct.unpack('<H', b[22:24])[0]
seconds = (len(b) - 44) / (bits // 8) / max(ch, 1) / rate
print(f"speech on the NPU: {seconds:.2f} s of audio")
sys.exit(0 if seconds >= 1.5 else "FAIL: empty or near-empty audio (F155: is patch 0005 applied?)")
PY
echo "PASS"
