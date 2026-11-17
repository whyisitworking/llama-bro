#!/usr/bin/env bash
# Before the phone steps: refuse when another session is using the phone. A second session probing the phone during a
# device check left a forward on a port the check used and moved the phone's free memory between two fit estimates
# (F150), so a device check needs the phone to itself. Refuses when:
#   - another check holds this phone's lock (a symlink in LB_DEVICE_LOCK_DIR, default /tmp, which every worktree
#     and session sees);
#   - an adb forward or reverse to the phone exists (a running script or one that died without cleaning up);
#   - a llama-server, lb-run or Llama Bro app process (com.suhel.llamabro*) runs on the phone.
# On success takes the lock for OWNER_PID and prints its path, which the owner removes when it ends; a lock whose
# owner is gone is taken over.
#
# usage: device-preflight.sh OWNER_PID   (uses ANDROID_SERIAL when set)
set -uo pipefail

owner="${1:?usage: device-preflight.sh OWNER_PID}"
refuse() { echo "the phone is in use: $*" >&2; exit 1; }

serial="${ANDROID_SERIAL:-}"
if [ -z "$serial" ]; then
    serial="$(adb get-serialno 2> /dev/null | tr -d '\r')"
    [ -n "$serial" ] && [ "$serial" != unknown ] ||
        { echo "no single phone on adb: connect one or set ANDROID_SERIAL" >&2; exit 1; }
fi
[ "$(adb -s "$serial" get-state 2> /dev/null | tr -d '\r')" = device ] ||
    { echo "$serial is not ready on adb" >&2; exit 1; }

# The lock is a symlink whose target is "pid:dir": ln -s creates it, or fails, in one step, so two sessions starting at
# once can't both take it (a directory with a pid file inside left a moment where the pid was not yet written).
lock="${LB_DEVICE_LOCK_DIR:-/tmp}/llamabro-device-${serial//[^A-Za-z0-9._-]/_}.lock"
take() { ln -s "$owner:$(pwd)" "$lock" 2> /dev/null; }
if ! take; then
    held="$(readlink "$lock" 2> /dev/null)" || held=""
    holder="${held%%:*}"
    if [ "$holder" != "$owner" ]; then
        if [ -n "$holder" ] && kill -0 "$holder" 2> /dev/null; then
            refuse "another check (pid $holder, from ${held#*:}) holds $lock"
        fi
        # the holder has ended: remove its lock and take it; if another session takes it first, ln fails and we refuse
        echo "taking over $lock from pid ${holder:-unknown}, which has ended" >&2
        rm -f "$lock"
        take || refuse "another check took $lock at the same moment"
    fi
fi
[ -L "$lock" ] || { echo "could not create the lock $lock" >&2; exit 1; }
release() { rm -f "$lock"; }

problems=()
forwards="$(adb forward --list 2> /dev/null | tr -d '\r' | awk -v s="$serial" '$1 == s')"
[ -z "$forwards" ] || problems+=("adb forwards: $(tr '\n' ';' <<< "$forwards")")
reverses="$(adb -s "$serial" reverse --list 2> /dev/null | tr -d '\r')"
[ -z "$reverses" ] || problems+=("adb reverses: $(tr '\n' ';' <<< "$reverses")")
processes="$(adb -s "$serial" shell ps -A -o NAME 2> /dev/null | tr -d '\r' |
    grep -E '^(llama-server|lb-run)$|^com\.suhel\.llamabro' | sort -u)"
[ -z "$processes" ] || problems+=("running on the phone: $(tr '\n' ' ' <<< "$processes")")

if [ ${#problems[@]} -gt 0 ]; then
    release
    printf '  %s\n' "${problems[@]}" >&2
    refuse "stop the other session; if none is running, clear the leftovers: adb -s $serial forward --remove-all;" \
        "adb -s $serial reverse --remove-all; adb -s $serial shell pkill -x llama-server; am force-stop each app"
fi
echo "$lock"
