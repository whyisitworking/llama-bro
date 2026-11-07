#!/usr/bin/env bash
# Fetches the build-only sources ggml-vulkan needs and the NDK lacks (P2 T5, F34): Vulkan-Headers (for vulkan.hpp)
# and SPIRV-Headers, at Vulkan 1.3.275, the NDK 29 sysroot's own vulkan_core.h version and the one upstream's
# Vulkan CI builds with (Ubuntu 24.04's libvulkan-dev). Official KhronosGroup release archives, checked by SHA-256
# (pinned from the first download, 2026-11-05). Nothing fetched here ships in an AAR.
# Output: sdk/native/build/deps/{Vulkan-Headers,SPIRV-Headers}. Usage: fetch-deps.sh
set -euo pipefail

deps="$(cd "$(dirname "$0")/.." && pwd)/build/deps"
mkdir -p "$deps"

fetch() {  # name url sha256
    local name=$1 url=$2 sha=$3 tgz="$deps/$1.tar.gz"
    if [ -f "$deps/$name/.lb-sha256" ] && [ "$(cat "$deps/$name/.lb-sha256")" = "$sha" ]; then return 0; fi
    curl -fsSL "$url" -o "$tgz.part"
    local got
    got="$(shasum -a 256 "$tgz.part" | cut -d' ' -f1)"
    if [ "$got" != "$sha" ]; then
        echo "$name: SHA-256 $got, expected $sha ($url)" >&2
        rm -f "$tgz.part"
        exit 1
    fi
    rm -rf "$deps/$name" && mkdir -p "$deps/$name"
    tar -xzf "$tgz.part" -C "$deps/$name" --strip-components 1
    rm -f "$tgz.part"
    echo "$sha" > "$deps/$name/.lb-sha256"
}

fetch Vulkan-Headers https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.3.275.tar.gz 7161da645dbd33fd4ea61eec08e0d77389a640010acbf4afc00234f84df9b314
fetch SPIRV-Headers https://github.com/KhronosGroup/SPIRV-Headers/archive/refs/tags/vulkan-sdk-1.3.275.0.tar.gz d46b261f1fbc5e85022cb2fada9a6facb5b0c9932b45007a77fe05639a605bd1
echo "deps in $deps"
