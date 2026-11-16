#!/bin/bash
# Correctness: the 19 greedy requests of research/p0/requests/a-lp.jsonl (logprobs, top 5) through one arm on the NPU.
# usage: [REPLAY=0|1] lp-run.sh <arm: base|split|gate|pr|prgate> <on|off> -> results/lp-<arm>-mtp<on|off>[-replay<n>]/<id>.json
# REPLAY sets LLAMA_RS_REPLAY for upstream PR #31669's builds (replay-ab.sh), and the log must then confirm it.
# Compare two runs with lp-diff.py. Waits for thermal status <= 1 before starting the server.
here="$(cd "$(dirname "$0")" && pwd)"; : "${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the phone}"; export ANDROID_SERIAL
arm="${1:?arm}"; mode="${2:?on|off}"
D=/data/local/tmp/dn-$arm
model=/data/local/tmp/lb-spike/models/Qwen3.5-4B-Q4_K_M.gguf
flags=""; [ "$mode" = on ] && flags="--spec-type draft-mtp --spec-draft-n-max 3"
renv=""; tag=""; [ -n "${REPLAY:-}" ] && { renv="LLAMA_RS_REPLAY=$REPLAY"; tag="-replay$REPLAY"; }
out="$here/results/lp-$arm-mtp$mode$tag"; rm -rf "$out"; mkdir -p "$out"
for _ in $(seq 1 120); do t=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | grep -o '[0-9]'); [ "${t:-9}" -le 1 ] 2>/dev/null && break; sleep 15; done
# stop only this experiment's servers (/data/local/tmp/dn-*), and never start while another job uses the phone
[ -n "$(adb shell 'for p in $(pgrep -x llama-server; pgrep -x lb-run); do case "$(readlink /proc/$p/cwd)" in /data/local/tmp/dn-*) ;; *) echo $p;; esac; done' | tr -d '\r')" ] && { echo "the phone is in use by another session" >&2; exit 1; }
adb forward tcp:8188 tcp:8188 >/dev/null
adb shell 'for p in $(pgrep -x llama-server); do case "$(readlink /proc/$p/cwd)" in /data/local/tmp/dn-*) kill -9 $p;; esac; done; true'; sleep 2
timeout 20 adb shell "cd $D && $renv LD_LIBRARY_PATH=$D ADSP_LIBRARY_PATH=$D CDSP_LIBRARY_PATH=$D nohup ./llama-server -m $model -c 16384 -np 1 --load-mode none --fit off --device HTP0 -ngl 99 $flags ${REPLAY:+-lv 4} --port 8188 > $D/lp.log 2>&1 </dev/null &"
for _ in $(seq 1 120); do curl -s -m 2 http://127.0.0.1:8188/health 2>/dev/null | grep -q ok && break; sleep 2; done
[ "$(adb shell 'for p in $(pgrep -x llama-server); do readlink /proc/$p/cwd; done' | tr -d '\r')" = "$D" ] || { echo "not $arm's server" >&2; exit 1; }
if [ -n "$renv" ]; then  # the server must say replay is what we asked for
  want=$([ "$REPLAY" = 1 ] && echo true || echo false)
  got=$(adb shell "grep -m1 -o 'rs_replay *= *[a-z]*' $D/lp.log" | tr -d '\r ' | cut -d= -f2)
  echo "rs_replay = $got"; [ "$got" = "$want" ] || { echo "replay is $got, not $want" >&2; exit 1; }
fi
python3 - "$here/../../p0/requests/a-lp.jsonl" "$out" <<'EOF'
import json, sys, urllib.request
src, out = sys.argv[1], sys.argv[2]
for line in open(src):
    req = json.loads(line)
    body = dict(req["body"], stream=False)
    r = urllib.request.Request("http://127.0.0.1:8188/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=600) as resp:
            json.dump(json.load(resp), open(f"{out}/{req['id']}.json", "w"))
        print(req["id"])
    except Exception as e:
        print(f"FAILED {req['id']}: {e}")
EOF
adb shell 'for p in $(pgrep -x llama-server); do case "$(readlink /proc/$p/cwd)" in /data/local/tmp/dn-*) kill -9 $p;; esac; done; true'
adb forward --remove tcp:8188 2>/dev/null || true
