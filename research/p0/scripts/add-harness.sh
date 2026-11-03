#!/usr/bin/env bash
# Adds the lb-spike harness to a build copy (never the submodule): appends one add_subdirectory()
# to the copy's tools/server/CMakeLists.txt. Idempotent. Usage: add-harness.sh U [harness-path-as-seen-by-cmake]
set -euo pipefail

variant="${1:?usage: add-harness.sh U [harness dir]}"
here="$(cd "$(dirname "$0")/.." && pwd)"
harness="${2:-$here/harness}"
cm="$here/build/src-$variant/tools/server/CMakeLists.txt"
marker="# lb-spike harness"

grep -q "$marker" "$cm" && sed -i '' "/$marker/,\$d" "$cm"
cat >> "$cm" <<EOF
$marker
if(NOT DEFINED LB_HARNESS_DIR)
    set(LB_HARNESS_DIR "$harness")
endif()
set(LB_SERVER_DIR \${CMAKE_CURRENT_SOURCE_DIR})
add_subdirectory("\${LB_HARNESS_DIR}" "\${CMAKE_CURRENT_BINARY_DIR}/lb-spike")
EOF
tail -3 "$cm"
