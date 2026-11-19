#!/usr/bin/env bash
# Check for prepare-llama.sh: builds the patched copy twice into a temp dir, checks that the
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

grep -qF "(K == 1 || n_tokens > K)" \
    "$tmp/llama.cpp/ggml/src/ggml-hexagon/htp-kparams.cpp" || fail "0001-hexagon-gdn-long-batch.patch not applied"

(cd "$native" && bash scripts/prepare-llama.sh build/test-prepare) >/dev/null || fail "in-repo run failed"
grep -qF "(K == 1 || n_tokens > K)" \
    "$inrepo/llama.cpp/ggml/src/ggml-hexagon/htp-kparams.cpp" || fail "patch not applied when OUT_DIR is inside a git repo"

[ -z "$(git -C "$native/third_party/llama.cpp" status --porcelain)" ] || fail "submodule has changes"

echo PASS
