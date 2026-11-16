#!/usr/bin/env bash
# Writes the audio tests' clip: a short spoken sentence as 16 kHz mono 16-bit WAV, made with macOS `say` and
# `afconvert` at check time, so no audio is committed (D84, F55). Skips the work when the clip is already there.
# usage: sdk/scripts/make-test-clip.sh <out.wav>
set -euo pipefail
out="$1"
[ -s "$out" ] && exit 0
mkdir -p "$(dirname "$out")"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
say -o "$tmp/clip.aiff" "The quick brown fox jumps over the lazy dog."
afconvert -f WAVE -d LEI16@16000 -c 1 "$tmp/clip.aiff" "$tmp/clip.wav"
mv "$tmp/clip.wav" "$out"
