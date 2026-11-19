#!/usr/bin/env bash
# Vulkan on the Adreno 830 against OpenCL and the CPU, with upstream llama-server from one build (build.sh) that has
# all three backends. The method of research/p2/opencl-speed/speed.sh (F63): per run, wall time to /health ok, then one
# ~670-token prompt with a 128-token greedy reply (llama-server's own timings); arms alternate within each round (F6).
# Each reply is saved to OUT/<model>-<arm>-r<round>.txt so replies can be compared token by token.
# Takes the device lock (sdk/scripts/device-preflight.sh) for the whole run.
# usage: BUILD=vk-fix [MODEL=file.gguf in lb-spike/models, or an absolute path on the phone] [ROUNDS=n] [ARMS="cpu opencl vulkan"] [OUT=dir] speed.sh
set -uo pipefail
export ANDROID_SERIAL="${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the device}"
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
build="${BUILD:?set BUILD to an output name of build.sh, e.g. vk-fix}"
src="$repo/sdk/native/build/$build"
models=/data/local/tmp/lb-spike/models
model=${MODEL:-Llama-3.2-3B-Instruct-Q4_K_M.gguf}
mpath=$models/$model; [[ $model == /* ]] && { mpath=$model; model=$(basename "$model"); }
rounds=${ROUNDS:-3}
out="${OUT:-$repo/sdk/native/build/vulkan-adreno-out}"
mkdir -p "$out"
dir=/data/local/tmp/lb-vk/$build
port=8099

lock="$(bash "$repo/sdk/scripts/device-preflight.sh" $$)" || exit 1
cleanup() { adb shell "pkill -x llama-server" >/dev/null 2>&1; adb forward --remove tcp:$port >/dev/null 2>&1; rm -f "$lock"; }
trap cleanup EXIT

stamp="$(cd "$src" && ls -l | shasum | cut -c1-12)"
if [ "$(adb shell cat $dir/.stamp 2>/dev/null | tr -d '\r')" != "$stamp" ]; then
  adb shell "rm -rf $dir && mkdir -p $dir" && adb push -q "$src/." "$dir/" >/dev/null && adb shell "echo $stamp > $dir/.stamp"
fi
adb forward tcp:$port tcp:$port >/dev/null

para="The lighthouse stood on a narrow spit of rock, and every evening the keeper climbed its spiral stairs to light the lamp. Ships passed in the dark, guided by the beam that swept across the water. "
prompt=$(python3 -c "print(('$para' * 16).strip())")
req=$(python3 -c 'import json,sys; print(json.dumps({"prompt": sys.argv[1] + "\n\nSummarise the story above in a few sentences.", "n_predict": 128, "temperature": 0, "cache_prompt": False}))' "$prompt")

stop_servers() {
  adb shell "pkill -x llama-server; sleep 2; pkill -9 -x llama-server" >/dev/null 2>&1
  for _ in $(seq 1 25); do adb shell pidof llama-server >/dev/null || return 0; sleep 0.2; done
  echo "WARNING: a server survived SIGKILL"
}

run_arm() {
  local arm=$1 r=$2 dev
  case $arm in cpu) dev="-dev none" ;; opencl) dev="-dev GPUOpenCL -ngl 99" ;; vulkan) dev="-dev Vulkan0 -ngl 99" ;; esac
  stop_servers
  local log="$dir/server-$arm.log"
  local t0; t0=$(python3 -c 'import time;print(time.time())')
  adb shell "cd $dir && LD_LIBRARY_PATH=$dir nohup ./llama-server -m $mpath -c 4096 -np 1 -b 512 -ub 512 --load-mode none $dev --port $port > $log 2>&1" </dev/null >/dev/null 2>&1 &
  local ok=0
  for _ in $(seq 1 1800); do
    curl -s -o /dev/null -w '%{http_code}' localhost:$port/health 2>/dev/null | grep -q 200 && { ok=1; break; }
    adb shell pidof llama-server >/dev/null || break
    sleep 0.1
  done
  local t1; t1=$(python3 -c 'import time;print(time.time())')
  [[ $ok == 1 ]] || { echo "$arm: never ready"; adb shell tail -5 "$log"; return; }
  local name="$out/${model%.gguf}-$arm-r$r.txt"
  local gen
  gen=$(curl -s localhost:$port/completion -d "$req" | python3 -c '
import sys, json, hashlib
j = json.load(sys.stdin); t = j["timings"]
open(sys.argv[1], "w").write(j["content"])
print("prompt_n=%d prefill_tps=%.1f decode_tps=%.2f reply_md5=%s" % (t["prompt_n"], t["prompt_per_second"], t["predicted_per_second"], hashlib.md5(j["content"].encode()).hexdigest()[:8]))' "$name")
  local off; off=$(adb shell "grep -oE 'offloaded [0-9]+/[0-9]+ layers' $log" | tr -d '\r')
  printf "%s ready_s=%.2f %s %s\n" "$arm" "$(python3 -c "print($t1-$t0)")" "$gen" "$off"
}

for r in $(seq 1 "$rounds"); do
  echo "== $model round $r ($(adb shell dumpsys thermalservice | grep -m1 -oE 'Thermal Status: [0-9]+'), battery $(adb shell dumpsys battery | grep -m1 -E '^\s*temperature:' | awk '{print $2/10}') C)"
  read -ra arms <<< "${ARMS:-cpu opencl vulkan}"
  n=${#arms[@]}
  for k in $(seq 0 $((n-1))); do run_arm "${arms[$(( (k + r) % n ))]}" "$r"; done
done
stop_servers
