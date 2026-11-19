#!/usr/bin/env bash
# A fake-Hub listing backed by the same GGUFs used for the device fit comparison.
set -euo pipefail

out="${1:?usage: make-device-corpus.sh OUTPUT_JSON [MODEL_PATH] [MMPROJ_PATH]}"
models_dir="${LB_MODELS_DIR:-$HOME/Downloads}"
model="${2:-$models_dir/Qwen3.5-4B-Q4_K_M.gguf}"
mmproj="${3:-$models_dir/mmproj-F16.gguf}"
[ -f "$model" ] || { echo "missing model: $model" >&2; exit 2; }
[ -f "$mmproj" ] || { echo "missing mmproj: $mmproj" >&2; exit 2; }
mkdir -p "$(dirname "$out")"
python3 - "$out" "$model" "$mmproj" <<'PY'
import json
import sys
from pathlib import Path

out, model, mmproj = map(Path, sys.argv[1:])
corpus = {"repos": {
    "test/qwen": {"files": [
        {"path": model.name, "source": str(model.resolve())},
        {"path": mmproj.name, "source": str(mmproj.resolve())},
    ]},
    "test/big": {"files": [
        {"path": "model-Q4_K_M.gguf", "size": 1610612736, "seed": 7},
    ]},
    "test/parallel": {"files": [
        {"path": f"model-Q4_K_M-{part:05d}-of-00004.gguf", "size": 400 * 1048576, "seed": 40 + part}
        for part in range(1, 5)
    ]},
    "test/verify": {"files": [
        {"path": "model-Q4_K_M.gguf", "size": 3 * (1 << 30), "seed": 50},
    ]},
}}
out.write_text(json.dumps(corpus, indent=2) + "\n")
PY
