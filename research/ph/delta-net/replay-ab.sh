#!/bin/bash
# NPU prefill with MTP under upstream PR #31669 (GDN replay instead of K snapshots, opt-in with LLAMA_RS_REPLAY=1):
# does replay alone give Hexagon's fast kernel, as the one-line gate does? Arms, all MTP on, rotating order:
#   off  = #31669, replay off (snapshots, K = 4)       on = #31669, replay on (K = 1)
#   gate = #31669 + hexagon-gdn-gate.patch, replay off
# then MTP off with #31669 once. Same model, flags and 2,071-token prompt as speed-ab.sh. Waits for thermal <= 1.
# Build first: PATCHES="" research/b12352/build-at.sh pr31669 dn-pr; PATCHES=<gate patch> ... dn-prgate
# (pr31669 = a local tag on the PR head in the submodule). Prints: round label thermal prompt_n prompt_t/s rs_replay
here="$(cd "$(dirname "$0")" && pwd)"; : "${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the phone}"; export ANDROID_SERIAL
root="$(cd "$here/../../.." && pwd)"
pp="$root/research/b12352/pp.json"
model=/data/local/tmp/lb-spike/models/Qwen3.5-4B-Q4_K_M.gguf
mtp="--spec-type draft-mtp --spec-draft-n-max 3"
rounds="${ROUNDS:-3}"
own='for p in $(pgrep -x llama-server); do case "$(readlink /proc/$p/cwd)" in /data/local/tmp/dn-*) kill -9 $p;; esac; done; true'
thermal() { adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | grep -o '[0-9]'; }
cool() { for _ in $(seq 1 120); do t=$(thermal); [ "${t:-9}" -le 1 ] 2>/dev/null && return; sleep 15; done; echo "still hot ($t)" >&2; }
run() { # $1 arm dir suffix, $2 LLAMA_RS_REPLAY, $3 extra flags
  D=/data/local/tmp/dn-$1
  adb shell "$own"; sleep 2
  timeout 20 adb shell "cd $D && LLAMA_RS_REPLAY=$2 LD_LIBRARY_PATH=$D ADSP_LIBRARY_PATH=$D CDSP_LIBRARY_PATH=$D nohup ./llama-server -m $model -c 8192 -np 1 --load-mode none --fit off --device HTP0 -ngl 99 $3 --port 8188 > $D/ab.log 2>&1 </dev/null &"
  for _ in $(seq 1 120); do curl -s -m 2 http://127.0.0.1:8188/health 2>/dev/null | grep -q ok && break; sleep 2; done
  [ "$(adb shell 'for p in $(pgrep -x llama-server); do readlink /proc/$p/cwd; done' | tr -d '\r')" = "$D" ] || { echo "not $1's server"; return; }
  r=$(curl -s -m 300 http://127.0.0.1:8188/completion -H 'Content-Type: application/json' -d @"$pp" |
    python3 -c 'import json,sys;t=json.load(sys.stdin)["timings"];print(t["prompt_n"], round(t["prompt_per_second"],1))')
  echo "$r $(adb shell "grep -m1 -o 'rs_replay *= *[a-z]*' $D/ab.log" | tr -d '\r ')"
}
# never start while another session uses the phone (a dying process has no cwd: ours)
[ -n "$(adb shell 'for p in $(pgrep -x llama-server; pgrep -x lb-run); do case "$(readlink /proc/$p/cwd)" in /data/local/tmp/dn-*|"") ;; *) echo $p;; esac; done' | tr -d '\r')" ] && { echo "the phone is in use by another session" >&2; exit 1; }
pgrep -f 'check.sh --device|device-contract/run.sh' >/dev/null && { echo "a device check is running here" >&2; exit 1; }
for arm in pr prgate; do
  adb shell rm -rf "/data/local/tmp/dn-$arm"; adb push "$root/sdk/native/build/bisect/dn-$arm-out" "/data/local/tmp/dn-$arm" >/dev/null
done
adb forward tcp:8188 tcp:8188 >/dev/null
# replay on or off as the server itself reports it (llama_context logs rs_replay only at -lv 4; no timing from these)
confirm() { # $1 arm, $2 LLAMA_RS_REPLAY
  D=/data/local/tmp/dn-$1; adb shell "$own"; sleep 2
  timeout 20 adb shell "cd $D && LLAMA_RS_REPLAY=$2 LD_LIBRARY_PATH=$D ADSP_LIBRARY_PATH=$D CDSP_LIBRARY_PATH=$D nohup ./llama-server -m $model -c 8192 -np 1 --load-mode none --fit off --device HTP0 -ngl 99 $mtp -lv 4 --port 8188 > $D/confirm.log 2>&1 </dev/null &"
  for _ in $(seq 1 120); do curl -s -m 2 http://127.0.0.1:8188/health 2>/dev/null | grep -q ok && break; sleep 2; done
  echo "confirm $1 LLAMA_RS_REPLAY=$2: $(adb shell "grep -m1 -o 'rs_replay *= *[a-z]*' $D/confirm.log" | tr -d '\r')"
}
confirm pr 0; confirm pr 1; confirm prgate 0
trap 'adb shell "$own"; adb forward --remove tcp:8188 2>/dev/null' EXIT
args() { case "$1" in off) echo "pr 0";; on) echo "pr 1";; gate) echo "prgate 0";; esac; }  # bash 3.2: no maps
orders=("off on gate" "on gate off" "gate off on")
for round in $(seq 1 "$rounds"); do
  for l in ${orders[$(( (round - 1) % 3 ))]}; do
    set -- $(args "$l"); cool; echo "$round mtp-on-$l $(thermal) $(run "$1" "$2" "$mtp")"
  done
done
cool; echo "1 mtp-off $(thermal) $(run pr 0 "")"
