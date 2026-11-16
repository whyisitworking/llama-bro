#!/usr/bin/env bash
# W9.2: LFM3-Omni-2B speech output on the phone through upstream llama-server's /tts (measurement only).
# usage: ANDROID_SERIAL=<serial> tts-measure.sh <label> [extra llama-server args]   (env: LB_DEVICE_TOOLS another device-tools build)
set -uo pipefail
label="$1"; shift
extra="$*"
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/tts-$label"; rm -rf "$out"; mkdir -p "$out"
tools=${LB_DEVICE_TOOLS:-$(git rev-parse --show-toplevel)/sdk/native/build/android-device-tools}
remote=/data/local/tmp/lb-tts
models=/data/local/tmp/lb-spike/models
port=18597
M=${LB_TEST_MODELS_DIR:-$HOME/Downloads/llamabro-test}
for f in LFM3-Omni-2B-Q4_K_M.gguf mmproj-LFM3-Omni-2B-Q8_0.gguf; do
  adb shell "[ -f $models/$f ]" || adb push -q "$M/$f" "$models/$f"
done
adb shell pkill -x llama-server 2>/dev/null || true
adb shell "rm -rf $remote && mkdir -p $remote/bin"
adb push -q "$tools/." "$remote/bin/" >/dev/null
for _ in $(seq 1 60); do
  s="$(adb shell dumpsys thermalservice | sed -n 's/^Thermal Status: //p' | tr -d '\r')"
  [ "${s:-0}" -le 2 ] && break; echo "thermal $s, waiting"; sleep 10
done
trap 'adb shell pkill -x llama-server 2>/dev/null || true; adb forward --remove tcp:'$port' 2>/dev/null || true' EXIT
adb shell "cd $remote && LD_LIBRARY_PATH=$remote/bin exec bin/llama-server -m $models/LFM3-Omni-2B-Q4_K_M.gguf \
  --mmproj $models/mmproj-LFM3-Omni-2B-Q8_0.gguf -c 4096 -np 1 --fit off -dev HTP0 -ngl 99 $extra \
  --host 127.0.0.1 --port $port > server.log 2>&1" < /dev/null > /dev/null 2>&1 &
adb forward tcp:$port tcp:$port >/dev/null
for _ in $(seq 1 180); do curl -sf "http://127.0.0.1:$port/health" >/dev/null 2>&1 && break; sleep 1; done
curl -sf "http://127.0.0.1:$port/health" >/dev/null || { echo "server did not start"; adb shell cat $remote/server.log | tail -30; exit 1; }
curl -s "http://127.0.0.1:$port/props" | python3 -c "import sys,json; d=json.load(sys.stdin); print('voices:', d.get('voices')); print('modalities:', d.get('modalities'))"

short="The weather in Paris is cloudy, with light rain after lunch."
para="Tomorrow will be warmer, around eighteen degrees, and the sun should come out by the afternoon. If you are planning a trip to the museum, go early, because the queues grow quickly after ten o'clock. Bring an umbrella, a light jacket, and a bottle of water, and remember that most shops close early on Sunday."
long="$para The train from the airport takes about thirty five minutes, and tickets cost eleven euros. The old town is best explored on foot, and most of the famous sights are within twenty minutes of each other. In the evening, the cafes along the river fill up quickly, so it is worth booking a table if you want to eat outside. Public transport runs until about half past midnight, and taxis are easy to find near the main squares. If it rains, the covered markets and the larger galleries make good places to spend a few hours."
i=0
for text in "$short" "$para" "$long"; do
  i=$((i+1))
  for stream in false true; do
    body=$(python3 -c "import json,sys; print(json.dumps({'input':sys.argv[1],'response_format':'wav','n_predict':1200,'stream':sys.argv[2]=='true'}))" "$text" "$stream")
    f="$out/text$i-stream-$stream.wav"
    timing=$(curl -s -o "$f" -w "%{time_starttransfer} %{time_total} %{http_code}" -H 'Content-Type: application/json' -d "$body" "http://127.0.0.1:$port/tts")
    python3 - "$f" "$timing" "$i" "$stream" "$(echo "$text" | wc -w | tr -d ' ')" <<'PY'
import sys, struct, os
f, timing, i, stream, words = sys.argv[1:6]
ttfb, total, code = timing.split()
b = open(f, 'rb').read()
if code != '200' or len(b) < 44:
    print(f"text{i} words={words} stream={stream} HTTP {code}: {b[:300]!r}"); sys.exit()
sr = struct.unpack('<I', b[24:28])[0]; ch = struct.unpack('<H', b[22:24])[0]; bits = struct.unpack('<H', b[34:36])[0]
n = (len(b) - 44) // (bits // 8) // max(ch, 1)
dur = n / sr
print(f"text{i} words={words} stream={stream} sr={sr} ch={ch} bits={bits} audio={dur:.2f}s total={float(total):.2f}s first_byte={float(ttfb):.2f}s rtf={dur/float(total):.2f}x realtime bytes={len(b)}")
if stream == 'true':
    # fix the streamed header's unknown sizes so the file plays
    bb = bytearray(b); struct.pack_into('<I', bb, 4, len(bb) - 8); struct.pack_into('<I', bb, 40, len(bb) - 44); open(f, 'wb').write(bb)
PY
  done
done
adb shell "grep -iE 'tts|audio|error|abort|HTP|offload' $remote/server.log | tail -15"
