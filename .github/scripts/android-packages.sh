#!/usr/bin/env bash
# Installs Android SDK packages on a CI runner with the SDK's own sdkmanager. The runner image ships the Android SDK
# (ANDROID_HOME) with its command-line tools; an action that also asks for the long-gone "tools" package fails there.
# usage: android-packages.sh "ndk;<version>" "cmake;<version>" ...
set -euo pipefail

sdk="${ANDROID_HOME:?ANDROID_HOME is not set: this runner has no Android SDK}"
sdkmanager="$(ls -d "$sdk"/cmdline-tools/*/bin/sdkmanager 2> /dev/null | sort | tail -1)"
[ -x "$sdkmanager" ] || { echo "no sdkmanager under $sdk/cmdline-tools" >&2; exit 1; }

# sdkmanager closes its input once it has every answer, which ends `yes` with SIGPIPE: not a failure
set +o pipefail
yes | "$sdkmanager" --licenses > /dev/null
yes | "$sdkmanager" "$@" > /dev/null
status=${PIPESTATUS[1]}
set -o pipefail
[ "$status" = 0 ] || { echo "sdkmanager could not install: $*" >&2; exit "$status"; }
"$sdkmanager" --list_installed | grep -E "^ *(ndk|cmake);" || true
