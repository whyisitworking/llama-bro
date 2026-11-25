#!/usr/bin/env bash
# Exercises device-preflight.sh (F150) against a stub adb (stub-adb/adb, driven by STUB_* variables): no phone needed.
# usage: bash sdk/scripts/tests/device-preflight-test.sh
here="$(cd "$(dirname "$0")" && pwd)"; pf="$here/../device-preflight.sh"
S="$(mktemp -d)"; trap 'kill $(jobs -p) 2> /dev/null; rm -rf "$S"' EXIT
export PATH="$here/stub-adb:$PATH" LB_DEVICE_LOCK_DIR="$S/locks" STUB_LOG="$S/adb.log"
rm -rf "$S/locks"; mkdir -p "$S/locks"; unset ANDROID_SERIAL
fails=0
expect() {  # expect 0|1 NAME [ENV=...]...: runs the preflight with the env, checks its exit code
    local want="$1" name="$2"; shift 2
    local out; out="$(env "$@" bash "$pf" $$ 2>"$S/err")"; local got=$?
    if [ "$got" = "$want" ]; then echo "ok   $name"; else echo "FAIL $name (exit $got, want $want)"; fails=1; fi
    sed 's/^/       /' "$S/err"; [ -n "$out" ] && echo "       stdout: $out"
}
expect 0 "a quiet phone: lock taken"
[ -L "$S/locks/llamabro-device-PHONE1.lock" ] && echo "ok   lock written" || { echo "FAIL lock missing"; fails=1; }
expect 0 "the same owner again (re-entry)"
sleep 300 & other=$!; rm -f "$S/locks/llamabro-device-PHONE1.lock"; ln -s "$other:/elsewhere" "$S/locks/llamabro-device-PHONE1.lock"
expect 1 "another live check holds the lock"
kill $other; wait $other 2>/dev/null
expect 0 "the lock's owner has ended: taken over"
rm -rf "$S/locks"/*
expect 1 "a stray forward to this phone" STUB_FORWARDS='PHONE1 tcp:18183 tcp:18183\r\n'
[ -z "$(ls "$S/locks")" ] && echo "ok   refusal releases the lock" || { echo "FAIL refusal kept the lock"; fails=1; }
expect 0 "a forward to another phone only" STUB_FORWARDS='OTHER tcp:18183 tcp:18183\r\n'
rm -rf "$S/locks"/*
expect 1 "a reverse left on the phone" STUB_REVERSES='UsbFfs tcp:18190 tcp:18190\r\n'
expect 1 "llama-server running" STUB_PS='llama-server\r\n'
expect 1 "lb-run running" STUB_PS='lb-run\r\n'
expect 1 "a test app running" STUB_PS='com.suhel.llamabro.sample\r\ncom.suhel.llamabro.sample:remote\r\n'
expect 1 "the demo's debug build running" STUB_PS='com.suhel.llamabro.demo.debug\r\n'
expect 0 "the person's own release demo running (D149)" STUB_PS='com.suhel.llamabro.demo\r\ncom.suhel.llamabro.demo:remote\r\n'
expect 0 "an unrelated llama-ish name" STUB_PS='com.example.llama-server-ui\r\n'
rm -rf "$S/locks"/*
expect 1 "no phone (no serial)" STUB_SERIAL=''
expect 0 "ANDROID_SERIAL names the phone" ANDROID_SERIAL=PHONE2
[ -L "$S/locks/llamabro-device-PHONE2.lock" ] && echo "ok   lock per serial" || { echo "FAIL no PHONE2 lock"; fails=1; }
grep -q -- "-s PHONE2 shell ps" "$S/adb.log" && echo "ok   adb -s used" || { echo "FAIL adb -s missing"; fails=1; }
# a lock with an empty target counts as ended and is taken over; two takers at once: one wins
rm -rf "$S/locks"/*; ln -s ":" "$S/locks/llamabro-device-PHONE1.lock"
expect 0 "an unreadable lock is taken over"
rm -rf "$S/locks"/*
sleep 300 & a=$!; sleep 300 & b=$!
( bash "$pf" $a > /dev/null 2>&1; echo $? > "$S/ra" ) & p1=$!; ( bash "$pf" $b > /dev/null 2>&1; echo $? > "$S/rb" ) & p2=$!; wait $p1 $p2
[ "$(cat "$S/ra")$(cat "$S/rb")" = "01" ] || [ "$(cat "$S/ra")$(cat "$S/rb")" = "10" ] && echo "ok   two at once: one wins" || { echo "FAIL two at once: $(cat "$S/ra") $(cat "$S/rb")"; fails=1; }
kill $a $b 2>/dev/null
echo; [ $fails = 0 ] && echo "PREFLIGHT TESTS PASS" || echo "PREFLIGHT TESTS FAILED"; exit $fails
