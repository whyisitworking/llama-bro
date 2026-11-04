#!/usr/bin/env bash
# Builds the patched llama.cpp copy the SDK compiles from (P1 T1). The submodule is never edited:
#   OUT_DIR/llama.cpp = `git archive` of the submodule's HEAD, plus every patches/[0-9]*.patch applied
#   in name order. OUT_DIR/llama.cpp/.lb-stamp records the submodule commit and each patch's SHA-256;
#   when it matches, nothing is done. Otherwise the copy is rebuilt from scratch.
# Usage: prepare-llama.sh OUT_DIR
set -euo pipefail

out="${1:?usage: prepare-llama.sh OUT_DIR}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
native="$(cd "$(dirname "$0")/.." && pwd)"
sub="$native/third_party/llama.cpp"
patches="$native/patches"

commit="$(git -C "$sub" rev-parse HEAD)"
stamp="commit $commit"
for p in "$patches"/[0-9]*.patch; do
    [ -e "$p" ] || continue
    stamp+=$'\n'"$(basename "$p") $(shasum -a 256 "$p" | cut -d' ' -f1)"
done

copy="$out/llama.cpp"
if [ -f "$copy/.lb-stamp" ] && [ "$(cat "$copy/.lb-stamp")" = "$stamp" ]; then
    echo "llama.cpp copy up to date ($copy)"
    exit 0
fi

echo "building llama.cpp copy at $copy (submodule $commit)"
rm -rf "$copy"
mkdir -p "$copy"
git -C "$sub" archive --format=tar HEAD | tar -x -C "$copy"

for p in "$patches"/[0-9]*.patch; do
    [ -e "$p" ] || continue
    # The copy isn't a git repo, but it may sit inside one (sdk/native/build). Stop git from finding that
    # outer repo, or git apply resolves paths against its root and silently skips every hunk.
    if ! (cd "$copy" && GIT_CEILING_DIRECTORIES="$out" git apply --whitespace=nowarn "$p"); then
        echo "error: patch does not apply: $(basename "$p")" >&2
        exit 1
    fi
    # git apply can succeed while applying nothing (paths outside its view): check every hunk is in place
    if ! (cd "$copy" && GIT_CEILING_DIRECTORIES="$out" git apply --check --reverse "$p" 2>/dev/null); then
        echo "error: patch reported applied but is not in place: $(basename "$p")" >&2
        exit 1
    fi
    echo "applied $(basename "$p")"
done

printf '%s' "$stamp" > "$copy/.lb-stamp"
echo "done"
