#!/usr/bin/env bash
# W9: the cost of sustained speech per configuration: a fixed workload (the 151-word text sentence by sentence, twice:
# LB_REPS times, default 4: 32 /tts requests, ~210 s of audio), with an on-phone sampler every second logging thermal status, the skin, CPU and
# NPU temperatures, the CPU clocks and the battery current and voltage (meaningful on battery power, e.g. ADB over
# Wi-Fi; on USB the charger supplies the load). The phone has no per-rail energy meters. Research build (stages-research.patch) for the LB_* switches.
# usage: ANDROID_SERIAL=<serial> LB_DEVICE_TOOLS=<research device tools> tts-sustained.sh <label> '<ENV ...>' '<args>' ...
set -uo pipefail
label="$1"; shift
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/sustained-$label"; mkdir -p "$out"
tools="${LB_DEVICE_TOOLS:?set LB_DEVICE_TOOLS to the research device tools}"
remote=/data/local/tmp/lb-sustained
models=/data/local/tmp/lb-spike/models
port=18599
adb shell pkill -x llama-server 2>/dev/null || true
adb shell "rm -rf $remote && mkdir -p $remote/bin"
adb push -q "$tools/." "$remote/bin/" > /dev/null
adb push -q "$here/thermal-sampler.sh" "$remote/" > /dev/null
trap 'adb shell pkill -x llama-server 2>/dev/null; adb shell "pkill -f thermal-sampler" 2>/dev/null; adb forward --remove tcp:'$port' 2>/dev/null || true' EXIT
text="Tomorrow will be warmer, around eighteen degrees, and the sun should come out by the afternoon. If you are planning a trip to the museum, go early, because the queues grow quickly after ten o'clock. Bring an umbrella, a light jacket, and a bottle of water, and remember that most shops close early on Sunday. The train from the airport takes about thirty five minutes, and tickets cost eleven euros. The old town is best explored on foot, and most of the famous sights are within twenty minutes of each other. In the evening, the cafes along the river fill up quickly, so it is worth booking a table if you want to eat outside. Public transport runs until about half past midnight, and taxis are easy to find near the main squares. If it rains, the covered markets and the larger galleries make good places to spend a few hours."
n=0
while [ $# -ge 2 ]; do
  envs="$1"; args="$2"; shift 2; n=$((n+1))
  for _ in $(seq 1 90); do
    s="$(adb shell dumpsys thermalservice | sed -n 's/^Thermal Status: //p' | tr -d '\r')"
    sk="$(adb shell dumpsys thermalservice | sed -n '/Current temperatures from HAL/,/Current cooling/p' | sed -n 's/.*mValue=\([0-9.]*\), mType=3, mName=skin.*/\1/p' | head -1 | tr -d '\r')"
    [ "${s:-0}" -eq 0 ] && python3 -c "import sys; sys.exit(0 if float('${sk:-99}') < 36.0 else 1)" && break; sleep 10
  done
  adb shell input keyevent KEYCODE_WAKEUP  # keeps the screen on, the same for every configuration
  adb shell "cd $remote && $envs LD_LIBRARY_PATH=$remote/bin exec bin/llama-server -m $models/LFM3-Omni-2B-Q4_K_M.gguf \
    --mmproj $models/mmproj-LFM3-Omni-2B-Q8_0.gguf -c 4096 -np 1 --fit off -dev HTP0 -ngl 99 $args \
    --host 127.0.0.1 --port $port > s$n.log 2>&1" < /dev/null > /dev/null 2>&1 &
  adb forward tcp:$port tcp:$port > /dev/null
  for _ in $(seq 1 180); do curl -sf "http://127.0.0.1:$port/health" > /dev/null 2>&1 && break; sleep 1; done
  adb shell "sh $remote/thermal-sampler.sh > $remote/t$n.csv 2>&1 &" < /dev/null
  python3 - "$port" "$text" "$out/c$n-requests.csv" <<'PY'
import json, struct, sys, time, urllib.request
port, text, path = sys.argv[1], sys.argv[2], sys.argv[3]
sents = [s.strip() + "." for s in text.split(". ")]
sents[-1] = sents[-1].rstrip(".") + "."
t0 = time.time()
with open(path, "w") as f:
    f.write("t_start,t_end,audio_s,took_s\n")
    for rep in range(int(__import__('os').environ.get('LB_REPS', '4'))):
        for s in sents:
            body = json.dumps({"input": s, "voice": "female", "seed": 42, "response_format": "wav", "n_predict": 1200}).encode()
            a = time.time()
            b = urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{port}/tts", body, {"Content-Type": "application/json"}), timeout=300).read()
            e = time.time()
            audio = (len(b) - 44) / 2 / struct.unpack("<I", b[24:28])[0]
            f.write(f"{a - t0:.2f},{e - t0:.2f},{audio:.2f},{e - a:.2f}\n")
PY
  adb shell "pkill -f thermal-sampler" 2>/dev/null; adb shell pkill -x llama-server 2>/dev/null; sleep 1
  adb shell "cat $remote/t$n.csv" > "$out/c$n-thermal.csv"
  python3 - "$out/c$n-requests.csv" "$out/c$n-thermal.csv" "$envs $args" <<'PY'
import csv, sys
req = list(csv.DictReader(open(sys.argv[1])))
th = [r for r in csv.DictReader(open(sys.argv[2])) if r.get("skin")]
audio = sum(float(r["audio_s"]) for r in req); wall = float(req[-1]["t_end"])
first = [float(r["audio_s"]) / float(r["took_s"]) for r in req[:4]]; last = [float(r["audio_s"]) / float(r["took_s"]) for r in req[-4:]]
f = lambda k: [float(r[k]) for r in th if r.get(k) not in (None, "")]
print(f"== [{sys.argv[3]}] {audio:.1f} s of audio in {wall:.1f} s = {audio / wall:.2f}x; first 4 requests {sum(first)/4:.2f}x, last 4 {sum(last)/4:.2f}x")
print(f"   skin {f('skin')[0]:.1f} -> {f('skin')[-1]:.1f} C (max {max(f('skin')):.1f}); CPU max {max(f('cpu_max')):.1f} C; NPU max {max(f('nsp_max')):.1f} C; thermal status max {int(max(f('status')))}")
fr = f('f_p0'); fp = f('f_p6')
print(f"   CPU clock avg: cores 0-5 {sum(fr)/len(fr)/1000:.0f} MHz, cores 6-7 {sum(fp)/len(fp)/1000:.0f} MHz; samples {len(th)}")
ma = f('ma'); mv = f('mv'); uah = f('uah')
if ma and mv:
    w = sum(a * v for a, v in zip(ma, mv)) / len(ma) / 1e6
    dq = (uah[0] - uah[-1]) / 1000.0 if uah else 0.0
    dur = float(th[-1]['t']) - float(th[0]['t'])
    print(f"   battery: {sum(ma)/len(ma):.0f} mA avg x {sum(mv)/len(mv)/1000:.2f} V = {w:.2f} W avg; energy {w*wall:.0f} J for {audio:.0f} s of audio = {w*wall/audio:.2f} J per audio second; charge counter -{dq:.0f} mAh over {dur:.0f} s")
PY
done
