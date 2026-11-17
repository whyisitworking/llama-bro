#!/usr/bin/env bash
# Contract test (P1 T5, design §12, the per-upgrade check): the C API must answer exactly like upstream
# llama-server built from the same tree with the same flags. Runs requests.jsonl through llama-server
# over HTTP (http-run.py) and through the C API (lb-run), then compares with compare.py (both copied
# from research/p0/scripts). Passes only on "N/N identical".
#
# Needs: the host build (cmake --preset host -S sdk/native && cmake --build sdk/native/build/host),
#        LB_TEST_MODEL, and LB_TEST_MMPROJ for the image requests. Requests: make-requests.py.
#        LB_CONTRACT_REQUESTS picks another requests file (audio.jsonl: with LB_TEST_CLIP, a WAV clip, put where a
#        request says "@clip"; check.sh runs it on upstream's audio test model).
#        LB_CONTRACT_PORT the server's port (default 0: the OS picks a free one, so concurrent checks never
#        collide, F159).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
native="$(cd "$here/../.." && pwd)"
# shellcheck source=../../../scripts/ports.sh
. "$native/../scripts/ports.sh"
bin="$native/build/host/bin"
model="${LB_TEST_MODEL:?set LB_TEST_MODEL to a GGUF file}"
mmproj="${LB_TEST_MMPROJ:-}"   # optional: without it, requests that need images are left out
port="${LB_CONTRACT_PORT:-0}"
out="$native/build/host/contract"
requests="$out/requests.jsonl"
source_requests="${LB_CONTRACT_REQUESTS:-$here/requests.jsonl}"
clip="${LB_TEST_CLIP:-}"   # optional: without it, requests that need the clip are left out

for exe in llama-server lb-run; do
    [ -x "$bin/$exe" ] || { echo "FAIL: $bin/$exe missing (build the host preset)"; exit 1; }
done

rm -rf "$out" && mkdir -p "$out/upstream" "$out/lb"
python3 - "$source_requests" "$requests" "$clip" "${mmproj:+mmproj}" "${clip:+clip}" <<'PY'
import json, sys
clip = sys.argv[3]
have = set(sys.argv[4:]) - {""}
with open(sys.argv[2], "w") as out:
    for line in open(sys.argv[1]):
        if line.strip() and not line.startswith("#") and set(json.loads(line).get("needs", [])) <= have:
            out.write(line.replace('"@clip"', json.dumps(clip)))
PY
echo "$(wc -l < "$requests" | tr -d ' ') requests$( [ -n "$mmproj" ] || echo ", without the image ones (no LB_TEST_MMPROJ)")"

# upstream: the same values the C API config below produces (phone defaults, design §8)
"$bin/llama-server" -m "$model" ${mmproj:+--mmproj "$mmproj"} -c 8192 -np 1 --cache-ram 1024 --ctx-checkpoints 8 --load-mode none --fit off \
    -b 512 -ub 512 --host 127.0.0.1 --port "$port" > "$out/llama-server.log" 2>&1 &
server_pid=$!
trap 'kill $server_pid 2>/dev/null || true; wait $server_pid 2>/dev/null || true' EXIT

port="$(server_port "$out/llama-server.log" $server_pid)" ||
    { echo "FAIL: llama-server exited or never listened (see $out/llama-server.log)"; exit 1; }
for _ in $(seq 1 120); do
    curl -sf "http://127.0.0.1:$port/health" >/dev/null 2>&1 && break
    kill -0 $server_pid 2>/dev/null || { echo "FAIL: llama-server exited (see $out/llama-server.log)"; exit 1; }
    sleep 1
done

python3 "$here/http-run.py" --url "http://127.0.0.1:$port" --requests "$requests" --out "$out/upstream"
kill $server_pid; wait $server_pid 2>/dev/null || true

config="$(python3 -c 'import json,sys; c={"model_path": sys.argv[1], "n_ctx": 8192}; sys.argv[2] and c.update(mmproj_path=sys.argv[2]); print(json.dumps(c))' "$model" "$mmproj")"
"$bin/lb-run" "$config" "$requests" "$out/lb" 2> "$out/lb-run.log"

result="$(python3 "$here/compare.py" "$out/upstream" "$out/lb" "$requests" || true)"
echo "$result"
# pass: the last line is "N/N identical" with N > 0
echo "$result" | tail -1 | awk -F'[/ ]' '$3 == "identical" && $1 == $2 && $1 > 0 { ok = 1 } END { exit !ok }' || { echo "FAIL"; exit 1; }
echo PASS
