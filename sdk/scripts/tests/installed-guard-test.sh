#!/usr/bin/env bash
# Exercises installed-guard.sh against a stub adb and stub apksigner/aapt2: a fake APK is a text file whose first
# line is its signer and second line its version code. No phone needed.
# usage: bash sdk/scripts/tests/installed-guard-test.sh
here="$(cd "$(dirname "$0")" && pwd)"; guard="$here/../installed-guard.sh"
S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT
printf '#!/usr/bin/env bash\necho "Signer #1 certificate SHA-256 digest: $(sed -n 1p "${@: -1}")"\n' > "$S/apksigner"
printf '#!/usr/bin/env bash\necho "package: name=x versionCode='"'"'$(sed -n 2p "${@: -1}")'"'"' versionName=x"\n' > "$S/aapt2"
chmod +x "$S/apksigner" "$S/aapt2"
export PATH="$here/stub-adb:$PATH" APKSIGNER="$S/apksigner" AAPT2="$S/aapt2" ANDROID_SERIAL=PHONE1 STUB_LOG="$S/adb.log"
printf 'debugkey\n1\n' > "$S/test.apk"
fails=0
expect() {  # expect 0|1 NAME [ENV=...]...
    local want="$1" name="$2"; shift 2
    env "$@" bash "$guard" com.suhel.llamabro.demo "$S/test.apk" 2> "$S/err"; local got=$?
    if [ "$got" = "$want" ]; then echo "ok   $name"; else echo "FAIL $name (exit $got, want $want)"; fails=1; fi
    sed 's/^/       /' "$S/err"
}
expect 0 "nothing installed"
printf 'debugkey\n1\n' > "$S/same.apk"
expect 0 "the same debug build installed" STUB_INSTALLED_APK="$S/same.apk" STUB_INSTALLED_CODE=1
printf 'playkey\n1027\n' > "$S/play.apk"
expect 1 "a Play build (another key, higher version)" STUB_INSTALLED_APK="$S/play.apk" STUB_INSTALLED_CODE=1027
printf 'debugkey\n5\n' > "$S/newer.apk"
expect 1 "a newer debug build (same key, higher version)" STUB_INSTALLED_APK="$S/newer.apk" STUB_INSTALLED_CODE=5
printf 'playkey\n1\n' > "$S/other.apk"
expect 1 "another key at the same version" STUB_INSTALLED_APK="$S/other.apk" STUB_INSTALLED_CODE=1
rm -f "$S/test.apk"; expect 1 "missing test build" STUB_INSTALLED_APK="$S/same.apk"
grep -q -- "-s PHONE1 shell pm path com.suhel.llamabro.demo" "$S/adb.log" && echo "ok   adb -s used" || { echo "FAIL adb -s missing"; fails=1; }
echo; [ $fails = 0 ] && echo "INSTALLED GUARD TESTS PASS" || echo "INSTALLED GUARD TESTS FAILED"; exit $fails
