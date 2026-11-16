#!/bin/bash
# Why does the device contract's t2-14-image differ between the SDK and upstream llama-server with the gate (K = 1, so
# plain upstream)? Same binary, same flags as the contract, the image request alone on a fresh server, three times,
# then after the contract's 13 earlier chat requests. If the reply depends on what ran before, the kernel's result
# depends on process state, not on the request.
# Needs sdk/native/build/android-device-tools (scripts/build-device-tools.sh) and the contract's requests.jsonl.
# usage: [CACHE_RAM=1024] [HEXAGON_ENV=...] [RUNS=3] state-probe.sh -> prints one reply tail per run
# e.g. CACHE_RAM=0 (no prompt cache), HEXAGON_ENV=GGML_HEXAGON_OPFILTER=GATED_DELTA_NET (delta-net off the NPU)
here="$(cd "$(dirname "$0")" && pwd)"; : "${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the phone}"; export ANDROID_SERIAL
root="$(cd "$here/../../.." && pwd)"
tools="$root/sdk/native/build/android-device-tools"
requests="$root/sdk/native/build/device-contract/requests.jsonl"
D=/data/local/tmp/dn-probe
models=/data/local/tmp/lb-contract-models
port=18183
cache_ram="${CACHE_RAM:-1024}"; henv="${HEXAGON_ENV:-}"; runs="${RUNS:-3}"
cool() { for _ in $(seq 1 60); do s=$(adb shell dumpsys thermalservice | sed -n 's/^Thermal Status: //p' | tr -d '\r'); [ "${s:-9}" -le 2 ] && return; sleep 10; done; echo "still hot ($s)" >&2; }
# only ever stop our own server (cwd $D): another job may be using the phone
stop_own() { adb shell 'for p in $(pgrep -x llama-server); do [ "$(readlink /proc/$p/cwd)" = '"$D"' ] && kill -9 $p; done; true'; }
busy() { # another job's server or test on the phone, or a check.sh on this machine (a dying process has no cwd: ours)
  [ -n "$(adb shell 'for p in $(pgrep -x llama-server; pgrep -x lb-run); do c=$(readlink /proc/$p/cwd); [ -z "$c" ] || [ "$c" = '"$D"' ] || echo $p; done' | tr -d '\r')" ] ||
    pgrep -f 'check.sh --device|device-contract/run.sh' >/dev/null
}
start() {
  stop_own; sleep 2; cool
  timeout 20 adb shell "cd $D && LD_LIBRARY_PATH=$D/bin $henv nohup bin/llama-server -m $models/Qwen3.5-4B-Q4_K_M.gguf --mmproj $models/mmproj-F16.gguf -c 8192 -np 1 --cache-ram $cache_ram --ctx-checkpoints 8 --load-mode none --fit off -b 512 -ub 512 -dev HTP0 -ngl 99 --host 127.0.0.1 --port $port > $D/probe.log 2>&1 </dev/null &"
  for _ in $(seq 1 120); do curl -s -m 2 "http://127.0.0.1:$port/health" 2>/dev/null | grep -q ok && break; sleep 2; done
  [ "$(adb shell 'for p in $(pgrep -x llama-server); do readlink /proc/$p/cwd; done' | tr -d '\r')" = "$D" ] || { echo "not the probe's server" >&2; exit 1; }
}
send() { # $@: request ids; prints the tail of the last reply
  python3 - "$requests" "$port" "$@" <<'EOF'
import json, sys, urllib.request
src, port, ids = sys.argv[1], sys.argv[2], sys.argv[3:]
reqs = {r["id"]: r for r in map(json.loads, open(src))}
for rid in ids:
    body = dict(reqs[rid]["body"], stream=False)
    r = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
    text = json.load(urllib.request.urlopen(r, timeout=300))["choices"][0]["message"]["content"]
print(repr(text[340:420]))
EOF
}
busy && { echo "the phone is in use by another session; not starting" >&2; exit 1; }
trap 'stop_own; adb forward --remove tcp:'$port' 2>/dev/null' EXIT
adb shell "rm -rf $D && mkdir -p $D/bin"; adb push -q "$tools/." "$D/bin/" >/dev/null
adb forward tcp:$port tcp:$port >/dev/null
earlier=$(python3 -c 'import json,sys; ids=[json.loads(l)["id"] for l in open(sys.argv[1])]; print(" ".join(ids[:ids.index("t2-14-image")]))' "$requests")
echo "cache-ram $cache_ram, env [${henv}]"
start; for i in $(seq 1 "$runs"); do echo "fresh, alone, run $i: $(send t2-14-image)"; done
start; echo "fresh, after the 13 earlier requests: $(send $earlier t2-14-image)"
