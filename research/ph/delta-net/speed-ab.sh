#!/bin/bash
# NPU prefill, three arms (base, split, gate from build-arms.sh), MTP on; the arm order rotates each round so no arm
# always runs first. Then MTP off for base and gate, where the gate patch must change nothing (K == 1).
# Same server flags, model and 2,071-token prompt as research/b12352/patch-ab.sh (F121). Waits for thermal status <= 1.
# Prints: round mode arm thermal prompt_n prompt_t/s
here="$(cd "$(dirname "$0")" && pwd)"; : "${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the phone}"; export ANDROID_SERIAL
pp="$here/../../b12352/pp.json"
model=/data/local/tmp/lb-spike/models/Qwen3.5-4B-Q4_K_M.gguf
mtp="--spec-type draft-mtp --spec-draft-n-max 3"
rounds="${ROUNDS:-3}"
thermal() { adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | grep -o '[0-9]'; }
cool() { for _ in $(seq 1 120); do t=$(thermal); [ "${t:-9}" -le 1 ] 2>/dev/null && return; sleep 15; done; echo "still hot ($t)" >&2; }
run() { # $1 arm, $2 extra flags
  D=/data/local/tmp/dn-$1
  adb shell 'for p in $(pgrep -x llama-server); do case "$(readlink /proc/$p/cwd)" in /data/local/tmp/dn-*) kill -9 $p;; esac; done; true'; sleep 2
  timeout 20 adb shell "cd $D && LD_LIBRARY_PATH=$D ADSP_LIBRARY_PATH=$D CDSP_LIBRARY_PATH=$D nohup ./llama-server -m $model -c 8192 -np 1 --load-mode none --fit off --device HTP0 -ngl 99 $2 --port 8188 > $D/ab.log 2>&1 </dev/null &"
  for _ in $(seq 1 120); do curl -s -m 2 http://127.0.0.1:8188/health 2>/dev/null | grep -q ok && break; sleep 2; done
  # the server answering must be the one just started from this arm's directory (a stale one could hold the port)
  [ "$(adb shell 'for p in $(pgrep -x llama-server); do readlink /proc/$p/cwd; done' | tr -d '\r')" = "$D" ] || { echo "not $1's server"; return; }
  curl -s -m 300 http://127.0.0.1:8188/completion -H 'Content-Type: application/json' -d @"$pp" |
    python3 -c 'import json,sys;t=json.load(sys.stdin)["timings"];print(t["prompt_n"], round(t["prompt_per_second"],1))'
}
# stop only this experiment's servers (/data/local/tmp/dn-*), and never start while another job uses the phone
[ -n "$(adb shell 'for p in $(pgrep -x llama-server; pgrep -x lb-run); do case "$(readlink /proc/$p/cwd)" in /data/local/tmp/dn-*) ;; *) echo $p;; esac; done' | tr -d '\r')" ] && { echo "the phone is in use by another session" >&2; exit 1; }
adb forward tcp:8188 tcp:8188 >/dev/null
orders=("base split gate" "split gate base" "gate base split")
for round in $(seq 1 "$rounds"); do
  for arm in ${orders[$(( (round - 1) % 3 ))]}; do cool; echo "$round mtp-on $arm $(thermal) $(run "$arm" "$mtp")"; done
done
for arm in base gate; do cool; echo "1 mtp-off $arm $(thermal) $(run "$arm" "")"; done
adb shell 'for p in $(pgrep -x llama-server); do case "$(readlink /proc/$p/cwd)" in /data/local/tmp/dn-*) kill -9 $p;; esac; done; true'
