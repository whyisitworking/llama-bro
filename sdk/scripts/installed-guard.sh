#!/usr/bin/env bash
# Before a device run installs an app under test: refuse when a build of that app the run can't replace is installed.
# Gradle's connected tests install the app under test and uninstall it when they end, also when their own install
# failed; an installed build signed with another key (a Play or release build) or with a higher version code can't
# be replaced, so the run would end by deleting it and its data (chats, downloaded models). It happened once: this
# guard makes the person back the app up or uninstall it themselves first.
#
# usage: installed-guard.sh PACKAGE TEST_APK   (uses ANDROID_SERIAL; APKSIGNER and AAPT2 may name the tools)
set -uo pipefail

package="${1:?usage: installed-guard.sh PACKAGE TEST_APK}"
apk="${2:?usage: installed-guard.sh PACKAGE TEST_APK}"
serial="${ANDROID_SERIAL:?set ANDROID_SERIAL to the adb serial of the phone}"
tools="$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/* 2> /dev/null | sort -V | tail -1)"
apksigner="${APKSIGNER:-$tools/apksigner}"
aapt2="${AAPT2:-$tools/aapt2}"

refuse() { echo "refused: $*" >&2; exit 1; }

[ -f "$apk" ] || refuse "no test build at $apk"
path="$(adb -s "$serial" shell pm path "$package" 2> /dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)"
[ -z "$path" ] && exit 0  # nothing installed: nothing to lose

signer() { "$apksigner" verify --print-certs "$1" 2> /dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | head -1; }
installed_code="$(adb -s "$serial" shell dumpsys package "$package" 2> /dev/null | tr -d '\r' |
    sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1)"
test_code="$("$aapt2" dump badging "$apk" 2> /dev/null | sed -n "s/.*versionCode='\([0-9][0-9]*\)'.*/\1/p" | head -1)"

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
adb -s "$serial" pull "$path" "$work/installed.apk" > /dev/null 2>&1 || refuse "can't read the installed $package"
installed_signer="$(signer "$work/installed.apk")"
test_signer="$(signer "$apk")"
[ -n "$installed_signer" ] && [ -n "$test_signer" ] || refuse "can't read the signing keys of $package"

why=""
[ "$installed_signer" != "$test_signer" ] && why="it is signed with another key (a Play or release build)"
[ -n "$installed_code" ] && [ -n "$test_code" ] && [ "$installed_code" -gt "$test_code" ] &&
    why="${why:+$why, and }its version code $installed_code is above the test build's $test_code"
[ -z "$why" ] && exit 0
refuse "$package is installed and the test build can't replace it: $why. The device run would end by uninstalling it" \
    "and its data (Gradle removes the app it tests). Back it up or uninstall it yourself, then run again."
