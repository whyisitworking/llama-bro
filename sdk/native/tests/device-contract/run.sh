#!/usr/bin/env bash
# Device contract: on the phone's NPU, the C API must answer exactly like upstream llama-server
# built from the same tree, with the same flags and `-dev HTP0 -ngl 99`. The host contract (tests/contract/run.sh)
# with its request set, http-run.py and compare.py, but both sides run on the phone: llama-server in the background
# (reached through adb forward, F20) and lb-run with the Hexagon backend passed as the app passes it. Passes only on
# "N/N identical". compare.py compares the rebuilt reply text, not checksums; the script also prints the first
# replies, since two identical garbage answers would pass (F71). One allowance: the NPU breaks a near-tie between two
# tokens either way from run to run, in either process (F149), so streamed chat requests without tools also ask for
# the top 2 logprobs, and a reply that matches token for token up to one near-tie (top-2 gap at most
# LB_CONTRACT_NEAR_TIE, default 0.25) and splits there passes as "near-tie".
#
# Needs: scripts/build-device-tools.sh, a phone on adb (ANDROID_SERIAL), and on it the model (and mmproj for the image
# request) in $LB_DEVICE_MODEL_DIR. Env: LB_DEVICE_MODEL (file name, default Qwen3.5-4B-Q4_K_M.gguf),
# LB_DEVICE_MMPROJ (default mmproj-F16.gguf, left out if missing), LB_DEVICE_DEV (default HTP0),
# LB_DEVICE_CONTRACT_PORT (the server's port on the phone, default 18182), LB_DEVICE_CONTRACT_HOST_PORT (this machine's
# end of the adb forward, default 0: adb picks a free one, so concurrent checks never collide, F159).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
native="$(cd "$here/../.." && pwd)"
contract="$native/tests/contract"
tools="$native/build/android-device-tools"
models="${LB_DEVICE_MODEL_DIR:-/data/local/tmp/lb-spike/models}"
model="$models/${LB_DEVICE_MODEL:-Qwen3.5-4B-Q4_K_M.gguf}"
dev="${LB_DEVICE_DEV:-HTP0}"
remote=/data/local/tmp/lb-contract
port="${LB_DEVICE_CONTRACT_PORT:-18182}"   # on the phone; the forward's host end is picked below
host_port="${LB_DEVICE_CONTRACT_HOST_PORT:-0}"
out="$native/build/device-contract"
requests="$out/requests.jsonl"

[ -x "$tools/llama-server" ] && [ -x "$tools/lb-run" ] || { echo "FAIL: run scripts/build-device-tools.sh first"; exit 1; }
adb shell true || { echo "FAIL: no phone on adb"; exit 1; }
adb shell "dumpsys deviceidle | grep -q 'mScreenLocked=false'" || { echo "FAIL: unlock the phone"; exit 1; }
adb shell "[ -f '$model' ]" || { echo "FAIL: $model missing on the phone"; exit 1; }
mmproj="$models/${LB_DEVICE_MMPROJ:-mmproj-F16.gguf}"
adb shell "[ -f '$mmproj' ]" || mmproj=""

# never alongside another llama-server or a test app (F27's runaway sample, and the phone overheating)
adb shell pkill -x llama-server 2>/dev/null || true
cool() {  # wait for thermal status <= 2, at most 10 minutes
    for _ in $(seq 1 60); do
        s="$(adb shell dumpsys thermalservice | sed -n 's/^Thermal Status: //p' | tr -d '\r')"
        [ "${s:-0}" -le 2 ] && return 0
        echo "thermal status $s, waiting"; sleep 10
    done
    echo "FAIL: the phone didn't cool down"; exit 1
}

rm -rf "$out" && mkdir -p "$out/upstream" "$out/lb"
python3 - "$contract/requests.jsonl" "$requests" "${mmproj:+mmproj}" <<'PY'
import json, sys
have = set(sys.argv[3:]) - {""}
with open(sys.argv[2], "w") as out:
    for line in open(sys.argv[1]):
        if not line.strip():
            continue
        spec = json.loads(line)
        if not set(spec.get("needs", [])) <= have:
            continue
        body = spec.get("body")
        # logprobs only for the near-tie check (F149): they don't change sampling, and their values aren't compared;
        # never with tools, which llama-server refuses ("logprobs is not supported with tools + stream")
        if (spec["endpoint"] == "chat" and isinstance(body, dict) and body.get("stream") and "logprobs" not in body
                and "tools" not in body):
            spec["body"] = dict(body, logprobs=True, top_logprobs=2)
            spec["near_tie_logprobs"] = True
        out.write(json.dumps(spec) + "\n")
PY
echo "$(wc -l < "$requests" | tr -d ' ') requests on $dev$( [ -n "$mmproj" ] || echo ", without the image ones (no mmproj)")"

adb shell "rm -rf $remote && mkdir -p $remote/bin $remote/lb"
adb push -q "$tools/." "$remote/bin/" >/dev/null
adb push -q "$requests" "$remote/requests.jsonl" >/dev/null
env="cd $remote && LD_LIBRARY_PATH=$remote/bin"  # ggml-hexagon finds the skeleton beside itself (b12352)

# upstream: the host contract's flags plus the device
cool
# the cleanup first, so that any later failure still stops the server and removes the forward
trap 'adb shell pkill -x llama-server 2>/dev/null || true
    [ "$host_port" = 0 ] || adb forward --remove "tcp:$host_port" 2>/dev/null || true' EXIT
# the adb shell itself runs in the background here: one started in the background on the phone keeps adb waiting
adb shell "$env exec bin/llama-server -m '$model' ${mmproj:+--mmproj '$mmproj'} -c 8192 -np 1 --cache-ram 1024 \
    --ctx-checkpoints 8 --load-mode none --fit off -b 512 -ub 512 -dev $dev -ngl 99 -lv 4 \
    --host 127.0.0.1 --port $port > llama-server.log 2>&1" < /dev/null > /dev/null 2>&1 &
# with tcp:0 adb prints the host port it picked
forwarded="$(adb forward "tcp:$host_port" "tcp:$port" | tr -d '\r')" || { echo "FAIL: adb forward"; exit 1; }
[ "$host_port" != 0 ] || host_port="$forwarded"
for i in $(seq 1 180); do
    curl -sf "http://127.0.0.1:$host_port/health" >/dev/null 2>&1 && break
    # from the 5th second: the backgrounded adb shell may not have started the server yet
    [ "$i" -lt 5 ] || adb shell pidof llama-server >/dev/null || { adb pull -q $remote/llama-server.log "$out/"; echo "FAIL: llama-server exited (see $out/llama-server.log)"; exit 1; }
    [ "$i" = 180 ] && { echo "FAIL: llama-server not ready"; exit 1; }
    sleep 1
done
python3 "$contract/http-run.py" --url "http://127.0.0.1:$host_port" --requests "$requests" --out "$out/upstream"
adb shell pkill -x llama-server || true
adb pull -q "$remote/llama-server.log" "$out/" >/dev/null

# the C API: the same config the host contract gives lb-run, with the device as DevicePolicy sends it
cool
config="$(python3 -c 'import json,sys; c={"model_path": sys.argv[1], "n_ctx": 8192, "devices": [sys.argv[3]], "n_gpu_layers": 999}; sys.argv[2] and c.update(mmproj_path=sys.argv[2]); print(json.dumps(c))' "$model" "$mmproj" "$dev")"
backends="[{\"name\":\"hexagon\",\"files\":[\"libggml-hexagon.so\"],\"env\":{\"GGML_HEXAGON_LIBHTP_DIR\":\"$remote/bin\"}}]"
adb shell "$env LB_RUN_LOG_LEVEL=debug LB_RUN_BACKENDS='$backends' bin/lb-run '$config' requests.jsonl lb 2> lb-run.log" || { adb pull -q $remote/lb-run.log "$out/"; echo "FAIL: lb-run (see $out/lb-run.log)"; exit 1; }
adb pull -q "$remote/lb/." "$out/lb/" >/dev/null
adb pull -q "$remote/lb-run.log" "$out/" >/dev/null

# both really ran on the device: a CPU fallback would pass the comparison and prove nothing
# (-lv 4 and debug only add log lines; the replies are the same)
for log in llama-server lb-run; do
    grep -E "offloaded [0-9]+/[0-9]+ layers|$dev model buffer size" "$out/$log.log" | sed "s/^/$log: /" || true
    grep -q "$dev model buffer size" "$out/$log.log" || { echo "FAIL: $log didn't load on $dev"; exit 1; }
done

python3 - "$out/lb" <<'PY'   # read the replies, not only the comparison (F71)
import json, os, sys
for rid in ("t2-01-plain", "t2-03-list", "t2-12-json-schema"):
    path = os.path.join(sys.argv[1], rid + ".jsonl")
    if not os.path.exists(path): continue
    text = "".join(json.loads(l)["data"] for l in open(path))
    content = "".join(json.loads(p[6:])["choices"][0]["delta"].get("content") or ""
                      for p in text.split("\n") if p.startswith("data: {") and '"choices":[{' in p)
    print(f"{rid}: {content[:160]!r}")
PY

tie="${LB_CONTRACT_NEAR_TIE:-0.25}"
result="$(python3 "$contract/compare.py" --near-tie "$tie" "$out/upstream" "$out/lb" "$requests" || true)"
echo "$result"
echo "$result" | tail -1 | awk -F'[/ ]' '$3 == "identical" && $1 == $2 && $1 > 0 { ok = 1 } END { exit !ok }' || { echo "FAIL"; exit 1; }
echo PASS
