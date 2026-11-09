#!/usr/bin/env python3
"""Copy a GGUF header into a full-length sparse file for fit measurements."""

import json
import sys
from pathlib import Path


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: make_stub.py SRC DST")

    source = Path(sys.argv[1])
    destination = Path(sys.argv[2])
    gguf_py = Path(__file__).resolve().parents[3] / "sdk/native/third_party/llama.cpp/gguf-py"
    sys.path.insert(0, str(gguf_py))
    from gguf import GGUFReader

    offset = int(GGUFReader(source).data_offset)
    size = source.stat().st_size
    destination.parent.mkdir(parents=True, exist_ok=True)
    with source.open("rb") as src, destination.open("wb") as dst:
        remaining = offset
        while remaining:
            chunk = src.read(min(1024 * 1024, remaining))
            if not chunk:
                raise EOFError("GGUF ended before its tensor data offset")
            dst.write(chunk)
            remaining -= len(chunk)
        dst.truncate(size)
    print(json.dumps({"file": str(destination), "size": size, "data_offset": offset}))


if __name__ == "__main__":
    main()
