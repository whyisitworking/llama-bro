#!/usr/bin/env python3
"""Kill a sample download mid-stream and prove the next GET resumes its verified blob."""

import argparse
import json
import re
import subprocess
import time
from pathlib import Path
from urllib.request import Request, urlopen


def request_json(url):
    with urlopen(url, timeout=10) as response:
        return json.load(response)


def reset_log(endpoint):
    with urlopen(Request(endpoint + "_reset", data=b"{}", method="POST"), timeout=10):
        pass


def adb(*args, check=True):
    result = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=30)
    if check and result.returncode:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stdout.strip()} {result.stderr.strip()}")
    return result


def app_stat(package, path):
    result = adb("shell", "run-as", package, "stat", "-c", "%s", path, check=False)
    if result.returncode:
        return None
    try:
        return int(result.stdout.strip())
    except ValueError:
        return None


def app_list(package, path):
    result = adb("shell", "run-as", package, "ls", path, check=False)
    return result.stdout.split() if result.returncode == 0 else []


def start_sample(package, endpoint):
    adb("shell", "am", "start", "-W", "-n", f"{package}/.MainActivity",
        "--es", "endpoint", endpoint, "--es", "ref", "test/big:Q4_K_M",
        "--ez", "autostart", "true")


def cdn_gets(endpoint, oid):
    return [row for row in request_json(endpoint + "_log")
            if row["host"] == "cdn" and row["method"] == "GET"
            and row["path"] == f"/blobs/{oid}"]


def wait_until(predicate, timeout_seconds, label, interval=1):
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        value = predicate()
        if value is not None:
            return value
        time.sleep(interval)
    raise TimeoutError(f"timed out waiting for {label}")


def reset_sample(package, apk):
    # A fresh install resets only this test app's data and WorkManager database, as pm clear
    # would (which some phones deny to shell). -g grants its notification permission: left
    # unanswered, the prompt stays on top after a force-stop, the relaunch never reaches
    # MainActivity, the app counts as background, its foreground service is refused and an
    # OEM freezer stalls the download.
    adb("uninstall", package, check=False)
    adb("install", "-g", str(apk))


def run_case(mode, endpoint, host_endpoint, package, oid, size, out, apk):
    blob_dir = "no_backup/llamabro/hub/models--test--big/blobs"
    partial = f"{blob_dir}/{oid}.downloadInProgress"
    threshold = (size * 30 + 99) // 100
    reset_log(host_endpoint)
    reset_sample(package, apk)
    start_sample(package, endpoint)

    def progressed():
        gets = cdn_gets(host_endpoint, oid)
        sent = sum(row["bytes"] for row in gets)
        written = app_stat(package, partial) or 0
        return (sent, written) if sent >= threshold and written >= threshold else None

    sent, written = wait_until(progressed, 180, f"30% of {mode} download")
    before = len(cdn_gets(host_endpoint, oid))
    if mode == "kill-9":
        pids = adb("shell", "pidof", package).stdout.split()
        if not pids:
            raise RuntimeError("sample process vanished before kill")
        adb("shell", "run-as", package, "kill", "-9", pids[0])
    else:
        adb("shell", "am", "force-stop", package)

    start_sample(package, endpoint)

    def first_resumed():
        gets = cdn_gets(host_endpoint, oid)
        return gets[before] if len(gets) > before else None

    resumed = wait_until(first_resumed, 120, f"{mode} resumed CDN GET")
    match = re.fullmatch(r"bytes=(\d+)-", resumed.get("range") or "")
    if match is None or int(match.group(1)) < threshold:
        raise AssertionError(f"{mode}: expected resumed Range >= {threshold}, got {resumed.get('range')}")

    def completed():
        names = app_list(package, blob_dir)
        return names if oid in names and not any(name.endswith(".downloadInProgress") for name in names) else None

    names = wait_until(completed, 600, f"{mode} verified blob", interval=5)
    report = {"mode": mode, "oid": oid, "size": size, "threshold": threshold,
              "sent_at_stop": sent, "partial_at_stop": written,
              "resumed_range": resumed["range"], "blob_names": names}
    (out / f"{mode}.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"{mode}: resumed at {resumed['range']}; verified {oid}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default="http://127.0.0.1:18190/")  # the hub as the app on the phone sees it
    # the same hub from this machine, when the adb reverse maps the phone's port to another one here (F159)
    parser.add_argument("--host-endpoint")
    parser.add_argument("--package", default="com.suhel.llamabro.download")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    host_endpoint = args.host_endpoint or args.endpoint
    tree = request_json(host_endpoint + "api/models/test/big/tree/" + "a" * 40 + "?recursive=true")
    files = [entry for entry in tree if entry.get("path") == "model-Q4_K_M.gguf"]
    if len(files) != 1:
        raise RuntimeError("test/big model missing from fake Hub")
    oid, size = files[0]["lfs"]["oid"], files[0]["size"]
    for mode in ("kill-9", "force-stop"):
        run_case(mode, args.endpoint, host_endpoint, args.package, oid, size, args.out, args.apk)


if __name__ == "__main__":
    main()
