#!/usr/bin/env python3
"""Run upstream's real download handler against a fresh fake Hub per scenario."""

import argparse
import json
import subprocess
from pathlib import Path

SCENARIOS = {
    "flat": [["-hf", "test/flat:Q8_0"]],
    "default-tag": [["-hf", "test/flat"]],
    "no-mmproj": [["-hf", "test/flat:Q8_0", "--no-mmproj"]],
    "split": [["-hf", "test/split:Q3_K_M"]],
    "dflash-same": [["-hf", "test/dflash:Q4_K_M", "--spec-type", "draft-dflash"]],
    "dflash-other": [["-hf", "test/main:Q4_K_M", "--spec-type", "draft-dflash", "-hfd", "test/draft"]],
    "draft-full": [["-hf", "test/main:Q4_K_M", "--spec-type", "draft-dflash", "-hfd", "test/draftfull:Q8_0"]],
    "two-tags": [["-hf", "test/flat:Q4_K_M"], ["-hf", "test/flat:Q8_0"]],
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--golden", type=Path, required=True)
    parser.add_argument("--fake", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    for name, requests in SCENARIOS.items():
        directory = args.out / name
        directory.mkdir(parents=True, exist_ok=True)
        with (directory / "server.log").open("w") as server_log:
            server = subprocess.Popen(
                ["python3", str(args.fake), "--corpus", str(args.corpus)],
                stdout=subprocess.PIPE, stderr=server_log, text=True,
            )
            try:
                ports = json.loads(server.stdout.readline())
                endpoint = f"http://127.0.0.1:{ports['api']}/"
                for request in requests:
                    command = [str(args.golden), "download", "--endpoint", endpoint,
                               "--cache", str(directory / "upstream"), "--", *request]
                    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
                    if result.returncode:
                        raise RuntimeError(f"{name}: upstream download failed ({result.returncode}): {result.stderr}")
                    paths = json.loads(result.stdout)
                    (directory / "paths.json").write_text(json.dumps(paths, indent=2) + "\n")
            finally:
                server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait()
        print(name, flush=True)


if __name__ == "__main__":
    main()
