# Sourced by the check's scripts. The host servers listen on port 0 by default, so the OS hands each a free port:
# fixed ports (18181-18191) collided when two worktrees ran full checks at once (F159). These read the port back.

# server_port LOG PID: the port llama-server PID listens on, from its "listening on" line (logged once the model is
# loaded); fails if the server exits or does not listen within 120 s
server_port() {
    local log="$1" pid="$2" port
    for _ in $(seq 1 240); do
        port="$(sed -n 's|.*listening on http://127\.0\.0\.1:\([0-9][0-9]*\).*|\1|p' "$log" 2> /dev/null | head -1)"
        [ -n "$port" ] && { echo "$port"; return 0; }
        kill -0 "$pid" 2> /dev/null || return 1
        sleep 0.5
    done
    return 1
}

# fake_hub_ports LOG PID: "API CDN", the two ports fake_hub.py printed as its first line
fake_hub_ports() {
    local log="$1" pid="$2" ports
    for _ in $(seq 1 240); do
        ports="$(head -1 "$log" 2> /dev/null |
            python3 -c 'import json,sys; p=json.loads(sys.stdin.read()); print(p["api"], p["cdn"])' 2> /dev/null)"
        [ -n "$ports" ] && { echo "$ports"; return 0; }
        kill -0 "$pid" 2> /dev/null || return 1
        sleep 0.5
    done
    return 1
}

# where this file is, to find the fake hub whichever script sources it
ports_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# start_fake_hub LOG ARGS...: fake_hub.py (hub-fake/, beside this file) on ports the OS picks (F159), reached from the
# phone through adb reverse. The apps look for the hub at 127.0.0.1:18190 on the phone; the CDN's port is in the hub's
# redirects, so the phone uses the same one. Call it in a subshell: its EXIT trap removes the reverses and stops the
# server. Sets hub_api. Used by sdk/scripts/check.sh and demo/scripts/check.sh; adb talks to ANDROID_SERIAL's device.
start_fake_hub() {
    local log="$1" ports; shift
    python3 "$ports_dir/hub-fake/fake_hub.py" "$@" \
        --api-port "${LB_HUB_API_PORT:-0}" --cdn-port "${LB_HUB_CDN_PORT:-0}" > "$log" 2>&1 &
    hub_pid=$! hub_cdn=""
    trap 'adb reverse --remove tcp:18190 > /dev/null 2>&1
        [ -z "$hub_cdn" ] || adb reverse --remove "tcp:$hub_cdn" > /dev/null 2>&1
        kill "$hub_pid" > /dev/null 2>&1; wait "$hub_pid" > /dev/null 2>&1' EXIT
    ports="$(fake_hub_ports "$log" "$hub_pid")" || { cat "$log"; return 1; }
    read -r hub_api hub_cdn <<< "$ports"
    curl -sf "http://127.0.0.1:$hub_api/_log" > /dev/null || { cat "$log"; return 1; }
    adb reverse tcp:18190 "tcp:$hub_api" && adb reverse "tcp:$hub_cdn" "tcp:$hub_cdn"
}
