#!/usr/bin/env bash
# PF T4: text to speech with upstream llama-server (Qwen3-TTS 1.7B Q4_K_M + mmproj Q8_0, ggml-org GGUF).
# Per chip and round: a fresh load; MemAvailable before and after the load (as F66) and the server's RSS;
# then for a 2-sentence and a 1-paragraph input, POST /tts non-streamed (wall time, audio seconds from the WAV header,
# real-time factor = audio s / wall s) and streamed (time to first audio byte). Reference voice: speaker.wav (macOS say).
# usage: [ROUNDS=3] [CHIPS="npu gpu cpu"] tts.sh
set -uo pipefail
export ANDROID_SERIAL="${ANDROID_SERIAL:-<serial>}"
root=/data/local/tmp/lb-spike; L=$root/P/lib; port=8099
here="$(cd "$(dirname "$0")" && pwd)"; mkdir -p "$here/wav" "$here/logs"
M=models/Qwen3-TTS-12Hz-1.7B-Base-Q4_K_M.gguf; MM=models/mmproj-Qwen3-TTS-12Hz-1.7B-Base-Q8_0.gguf
wait_adb() { for i in $(seq 1 120); do adb get-state >/dev/null 2>&1 && return 0; sleep 1; done; echo "adb gone"; exit 1; }
stop() { wait_adb; adb shell "pkill -x llama-server; sleep 2; pkill -9 -x llama-server" >/dev/null 2>&1; sleep 2; }
thermal() { adb shell dumpsys thermalservice | grep -m1 -oE 'Thermal Status: [0-9]+' | grep -oE '[0-9]+$'; }
# wait for thermal <= COOL (default 2, the user's call 2026-11-06: was 1), at most ~3 min
cool() { for i in $(seq 1 9); do [ "$(thermal)" -le "${COOL:-2}" ] && return; sleep 20; done; }
memavail() { adb shell grep MemAvailable /proc/meminfo | awk '{print int($2/1024)}'; }
start() {  # dev-args
  stop; wait_adb; adb forward tcp:$port tcp:$port >/dev/null
  local t0=$(python3 -c 'import time;print(time.time())')
  adb shell "cd $root && LD_LIBRARY_PATH=$L ADSP_LIBRARY_PATH=$L CDSP_LIBRARY_PATH=$L nohup $root/P/bin/llama-server -m $M -mm $MM -c 8192 -np 1 -b 512 -ub 512 --cache-ram 1024 --ctx-checkpoints 8 --load-mode none --fit off $1 ${EXTRA:-} --port $port > $root/out/pf4.log 2>&1" </dev/null >/dev/null 2>&1 &
  for i in $(seq 1 1200); do curl -s -o /dev/null -w '%{http_code}' localhost:$port/health 2>/dev/null | grep -q 200 && { echo "load_s=$(python3 -c "import time;print(round(time.time()-$t0,1))")"; return 0; }; sleep 0.5; done
  echo "server never ready"; return 1
}
ask() {  # label textkind(short|para) stream(0|1) outfile
  python3 - "$@" "$here/speaker.wav" "$port" <<'PY'
import base64, http.client, json, struct, sys, time
label, kind, stream, out, spk, port = sys.argv[1:7]
text = {"short": "The weather in the mountains changed quickly this afternoon. By evening, the valley was covered in a thin layer of snow.",
        "para": "Every morning the old lighthouse keeper climbed the spiral stairs to check the lamp. He had done it for thirty years, through storms and calm seas alike. The ships no longer needed him, since satellites now guided them safely past the rocks, but he kept the light burning anyway. To him it was a promise made to the sea, and he did not intend to break it while he still had the strength to climb."}[kind]
body = {"input": text, "lang": "en", "seed": 42, "response_format": "wav", "stream": stream == "1",
        "speaker_ref_b64": base64.b64encode(open(spk, "rb").read()).decode()}
c = http.client.HTTPConnection("localhost", int(port), timeout=1800)
t0 = time.time(); c.request("POST", "/tts", json.dumps(body), {"Content-Type": "application/json"})
r = c.getresponse(); first = None; data = b""
while True:
    b = r.read1(65536) if hasattr(r, "read1") else r.read(65536)
    if not b: break
    if first is None: first = time.time() - t0
    data += b
wall = time.time() - t0
if r.status != 200 or data[:4] != b"RIFF":
    print("%s %s stream=%s ERROR status=%d %r" % (label, kind, stream, r.status, data[:200])); sys.exit(0)
open(out, "wb").write(data)
ch, rate = struct.unpack("<HI", data[22:28]); bits = struct.unpack("<H", data[34:36])[0]
audio = (len(data) - 44) / (rate * ch * bits / 8)
print("%s %s stream=%s wall_s=%.2f first_audio_s=%.2f audio_s=%.2f rtf=%.2f rate=%d bits=%d bytes=%d ctype=%s" % (label, kind, stream, wall, first or -1, audio, audio / wall, rate, bits, len(data), r.getheader("Content-Type")))
PY
}
rounds=${ROUNDS:-3}
for chip in ${CHIPS:-npu gpu cpu}; do
  case $chip in cpu) dev="-dev none" ;; npu) dev="-dev HTP0 -ngl 99" ;; gpu) dev="-dev GPUOpenCL -ngl 99" ;; esac
  for r in $(seq 1 $rounds); do
    cool; stop; before=$(memavail)
    lt=$(start "$dev") || { adb shell tail -40 $root/out/pf4.log > "$here/logs/$chip-r$r-fail.log"; echo "!! $chip r$r failed to start"; continue; }
    sleep 3; after=$(memavail); rss=$(adb shell "grep VmRSS /proc/\$(pidof llama-server)/status" | awk '{print int($2/1024)}')
    echo "== $chip round $r thermal=$(thermal) $lt memavail_before_mb=$before after_mb=$after used_mb=$((before-after)) rss_mb=$rss"
    for k in short para; do
      ask "$chip r$r" $k 0 "$here/wav/$chip-r$r-$k.wav"
      ask "$chip r$r" $k 1 "$here/wav/$chip-r$r-$k-stream.wav"
      adb shell "pidof llama-server >/dev/null" || { echo "!! $chip r$r died after $k"; break; }
    done
    adb shell cat $root/out/pf4.log > "$here/logs/$chip-r$r.log"
  done
done
stop
