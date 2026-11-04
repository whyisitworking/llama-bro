#!/usr/bin/env bash
# Check for prepare-llama.sh (P1 T1): builds the patched copy twice into a temp dir, checks that the
# second run is a no-op, that the shipped patch is applied, and that the submodule is untouched.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
native="$(cd "$here/.." && pwd)"
tmp="$(mktemp -d)"
inrepo="$native/build/test-prepare"   # inside this git repo: git apply must not resolve paths against it
trap 'rm -rf "$tmp" "$inrepo"' EXIT

fail() { echo "FAIL: $*"; exit 1; }

bash "$here/prepare-llama.sh" "$tmp" || fail "first run failed"
second="$(bash "$here/prepare-llama.sh" "$tmp")" || fail "second run failed"
echo "$second" | grep -q "up to date" || fail "second run did not print 'up to date': $second"

grep -q "only the last K tokens need snapshots: run the head with K = 1" \
    "$tmp/llama.cpp/src/models/delta-net-base.cpp" || fail "0001-delta-net-head-tail-split.patch not applied"

(cd "$native" && bash scripts/prepare-llama.sh build/test-prepare) >/dev/null || fail "in-repo run failed"
grep -q "only the last K tokens need snapshots: run the head with K = 1" \
    "$inrepo/llama.cpp/src/models/delta-net-base.cpp" || fail "patch not applied when OUT_DIR is inside a git repo"

[ -z "$(git -C "$native/third_party/llama.cpp" status --porcelain)" ] || fail "submodule has changes"

echo PASS
