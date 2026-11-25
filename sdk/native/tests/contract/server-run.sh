#!/usr/bin/env bash
# Server contract: llamabro-server must answer exactly like upstream llama-server built from the same tree with the
# same flags. Runs server-requests.jsonl through llama-server over HTTP (http-run.py), then through LlamaBroServer
# over the real core on this machine (HostServerContractTest, which serves on a free port until told to stop), and
# compares the bodies with compare.py and the statuses here. Passes only on "N/N identical" with every status equal.
# Then the official OpenAI and Anthropic Python clients run against the same server, started with a key
# (sdk/scripts/tests/server-clients.py, in a venv under build/host/server-contract/venv).
#
# Needs: the host build (cmake --preset host -S sdk/native && cmake --build sdk/native/build/host), LB_TEST_MODEL.
#        LB_SERVER_TASK=embed runs the requests marked "task": "embed" on an embedding model (upstream with
#        --embeddings, the server with ModelTask.Embedding), without the clients.
#        LB_CONTRACT_PORT upstream's port (default 0: the OS picks a free one, F159); the server always takes a free one.
#        The clients' venv is made, and the pinned clients installed, on the first run (needs the network once).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
native="$(cd "$here/../.." && pwd)"
sdk="$(cd "$native/.." && pwd)"
root="$(cd "$sdk/.." && pwd)"
# shellcheck source=../../../scripts/ports.sh
. "$sdk/scripts/ports.sh"
bin="$native/build/host/bin"
model="${LB_TEST_MODEL:?set LB_TEST_MODEL to a GGUF file}"
task="${LB_SERVER_TASK:-generate}"
port="${LB_CONTRACT_PORT:-0}"
base="$native/build/host/server-contract"
out="$base/$task"
venv="$base/venv"
requirements="$sdk/scripts/tests/server-clients-requirements.txt"
requests="$out/requests.jsonl"

[ -x "$bin/llama-server" ] || { echo "FAIL: $bin/llama-server missing (build the host preset)"; exit 1; }

rm -rf "$out" && mkdir -p "$out/upstream" "$out/server" "$out/serve"
python3 - "$here/server-requests.jsonl" "$requests" "$task" <<'PY'
import json, sys
with open(sys.argv[2], "w") as out:
    for line in open(sys.argv[1]):
        if line.strip() and not line.startswith("#") and json.loads(line).get("task", "generate") == sys.argv[3]:
            out.write(line)
PY
echo "$(wc -l < "$requests" | tr -d ' ') requests ($task)"

key=""
if [ "$task" = generate ]; then
    # the clients' venv, remade when the pins change
    if ! cmp -s "$requirements" "$venv/requirements.txt" 2> /dev/null; then
        rm -rf "$venv"
        python3 -m venv "$venv" && "$venv/bin/pip" install -q --disable-pip-version-check -r "$requirements" ||
            { echo "FAIL: could not install the clients ($requirements)"; exit 1; }
        cp "$requirements" "$venv/requirements.txt"
    fi
    key="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
fi

embeddings=""
[ "$task" = embed ] && embeddings="--embeddings"

# upstream: run.sh's flags
"$bin/llama-server" -m "$model" $embeddings -c 8192 -np 1 --cache-ram 1024 --ctx-checkpoints 8 --load-mode none \
    --fit off --seed 42 -b 512 -ub 512 --host 127.0.0.1 --port "$port" > "$out/llama-server.log" 2>&1 &
server_pid=$!
gradle_pid=""
cleanup() {
    kill $server_pid 2> /dev/null || true; wait $server_pid 2> /dev/null || true
    touch "$out/serve/stop"
    [ -z "$gradle_pid" ] || wait $gradle_pid 2> /dev/null || true
}
trap cleanup EXIT

port="$(server_port "$out/llama-server.log" $server_pid)" ||
    { echo "FAIL: llama-server exited or never listened (see $out/llama-server.log)"; exit 1; }
for _ in $(seq 1 120); do
    curl -sf "http://127.0.0.1:$port/health" > /dev/null 2>&1 && break
    kill -0 $server_pid 2> /dev/null || { echo "FAIL: llama-server exited (see $out/llama-server.log)"; exit 1; }
    sleep 1
done
python3 "$here/http-run.py" --url "http://127.0.0.1:$port" --requests "$requests" --out "$out/upstream"
kill $server_pid; wait $server_pid 2> /dev/null || true

# llamabro-server: the same model and settings, in the Gradle test; --rerun so an unchanged run is not up to date
(cd "$root" && ./gradlew -p sdk :llamabro-server:testDebugUnitTest \
    --tests 'com.suhel.llamabro.server.HostServerContractTest' --rerun -i \
    -Pllamabro.host.lib="$bin" -Pllamabro.host.model="$model" -Pllamabro.server.dir="$out/serve" \
    -Pllamabro.server.task="$task" ${key:+-Pllamabro.server.key="$key"}) > "$out/gradle.log" 2>&1 &
gradle_pid=$!
for _ in $(seq 1 1200); do
    [ -f "$out/serve/port" ] && break
    kill -0 $gradle_pid 2> /dev/null || { tail -30 "$out/gradle.log"; echo "FAIL: the server test ended before serving"; exit 1; }
    sleep 0.5
done
[ -f "$out/serve/port" ] || { echo "FAIL: the server never wrote its port"; exit 1; }
read -r open_port keyed_port < "$out/serve/port"

python3 "$here/http-run.py" --url "http://127.0.0.1:$open_port" --requests "$requests" --out "$out/server"
clients=0
if [ -n "$key" ]; then
    "$venv/bin/python" "$sdk/scripts/tests/server-clients.py" "http://127.0.0.1:$keyed_port" "$key" || clients=1
fi

touch "$out/serve/stop"
gradle_status=0
wait $gradle_pid || gradle_status=1
gradle_pid=""
results="$sdk/llamabro-server/build/test-results/testDebugUnitTest/TEST-com.suhel.llamabro.server.HostServerContractTest.xml"
grep -q 'skipped="0"' "$results" 2> /dev/null && [ $gradle_status -eq 0 ] ||
    { tail -30 "$out/gradle.log"; echo "FAIL: HostServerContractTest failed or was skipped (see $out/gradle.log)"; exit 1; }

# the statuses, which compare.py doesn't read
statuses="$(python3 - "$out/upstream/summary.jsonl" "$out/server/summary.jsonl" <<'PY'
import json, sys
a, b = ({s["id"]: s["status"] for s in map(json.loads, open(p))} for p in sys.argv[1:])
bad = [f"{rid}: {a[rid]} / {b.get(rid)}" for rid in a if a[rid] != b.get(rid)]
print("statuses differ: " + "; ".join(bad) if bad else f"{len(a)}/{len(a)} statuses equal")
sys.exit(1 if bad else 0)
PY
)" || { echo "$statuses"; echo FAIL; exit 1; }
echo "$statuses"

result="$(python3 "$here/compare.py" "$out/upstream" "$out/server" "$requests" || true)"
echo "$result"
# pass: the last line is "N/N identical" with N > 0
echo "$result" | tail -1 | awk -F'[/ ]' '$3 == "identical" && $1 == $2 && $1 > 0 { ok = 1 } END { exit !ok }' || { echo "FAIL"; exit 1; }
[ $clients -eq 0 ] || { echo "FAIL: the clients"; exit 1; }
echo PASS
