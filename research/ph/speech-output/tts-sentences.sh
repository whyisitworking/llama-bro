#!/usr/bin/env bash
# W9.2: does one fixed seed hold LFM3-Omni-2B's voice steady from sentence to sentence? (measurement only)
# Speaks the 151-word text of tts-measure.sh a sentence per /tts request, voice "female", once per variant:
#   random  - no seed (the server's default, a new random seed per request), as in F154
#   seedN   - the same seed N for every sentence
# and joins each variant's sentences into one WAV (0.3 s of silence between) to listen to.
# usage: ANDROID_SERIAL=<serial> tts-sentences.sh <label> [seed ...]
# env: LB_DEVICE_TOOLS another device-tools build; LB_TTS_ARGS replaces --no-mmproj-offload (the split setup: model on
# the NPU, mmproj on the CPU), e.g. LB_TTS_ARGS= for everything on the NPU
set -uo pipefail
label="$1"; shift
seeds=("$@"); [ ${#seeds[@]} -gt 0 ] || seeds=(42)
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
  --mmproj $models/mmproj-LFM3-Omni-2B-Q8_0.gguf -c 4096 -np 1 --fit off -dev HTP0 -ngl 99 ${LB_TTS_ARGS---no-mmproj-offload} \
  --host 127.0.0.1 --port $port > server.log 2>&1" < /dev/null > /dev/null 2>&1 &
adb forward tcp:$port tcp:$port >/dev/null
for _ in $(seq 1 180); do curl -sf "http://127.0.0.1:$port/health" >/dev/null 2>&1 && break; sleep 1; done
curl -sf "http://127.0.0.1:$port/health" >/dev/null || { echo "server did not start"; adb shell cat $remote/server.log | tail -30; exit 1; }

python3 - "$out" "$port" "${seeds[@]}" <<'PY'
import json, struct, sys, time, urllib.request
out, port, seeds = sys.argv[1], sys.argv[2], [int(s) for s in sys.argv[3:]]
text = ("Tomorrow will be warmer, around eighteen degrees, and the sun should come out by the afternoon. "
        "If you are planning a trip to the museum, go early, because the queues grow quickly after ten o'clock. "
        "Bring an umbrella, a light jacket, and a bottle of water, and remember that most shops close early on Sunday. "
        "The train from the airport takes about thirty five minutes, and tickets cost eleven euros. "
        "The old town is best explored on foot, and most of the famous sights are within twenty minutes of each other. "
        "In the evening, the cafes along the river fill up quickly, so it is worth booking a table if you want to eat outside. "
        "Public transport runs until about half past midnight, and taxis are easy to find near the main squares. "
        "If it rains, the covered markets and the larger galleries make good places to spend a few hours.")
sentences = [s.strip() + "." for s in text.split(". ")]
sentences[-1] = sentences[-1].rstrip(".") + "."

def speak(sentence, seed):
    body = {"input": sentence, "response_format": "wav", "voice": "female", "n_predict": 1200}
    if seed is not None:
        body["seed"] = seed
    req = urllib.request.Request(f"http://127.0.0.1:{port}/tts", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    t = time.time(); b = urllib.request.urlopen(req, timeout=300).read(); dt = time.time() - t
    sr = struct.unpack("<I", b[24:28])[0]; bits = struct.unpack("<H", b[34:36])[0]
    return b[:44], b[44:], sr, bits, dt

for variant, seed in [("random", None)] + [(f"seed{s}", s) for s in seeds]:
    header, pcm_all, audio, total = None, b"", 0.0, 0.0
    for i, sentence in enumerate(sentences, 1):
        header, pcm, sr, bits, dt = speak(sentence, seed)
        dur = len(pcm) / (bits // 8) / sr
        audio += dur; total += dt
        print(f"{variant} s{i} words={len(sentence.split())} audio={dur:.2f}s took={dt:.2f}s")
        pcm_all += pcm + b"\0" * (int(0.3 * sr) * (bits // 8))
    hh = bytearray(header); struct.pack_into("<I", hh, 4, 36 + len(pcm_all)); struct.pack_into("<I", hh, 40, len(pcm_all))
    open(f"{out}/{variant}.wav", "wb").write(bytes(hh) + pcm_all)
    print(f"{variant}: {audio:.1f}s of audio in {total:.1f}s, {audio/total:.2f}x real time")
PY
