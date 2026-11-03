#!/usr/bin/env python3
"""Sends a lb-spike requests file to a running llama-server over HTTP and writes the same output
format as lb-spike (OUT/<id>.jsonl with timestamped chunks, OUT/summary.jsonl), so the two can be
compared directly. Standard library only.

usage: http-run.py --url http://127.0.0.1:18080 --requests FILE --out DIR
"""
import argparse
import http.client
import json
import os
import threading
import time
import urllib.parse

PATHS = {
    "chat": ("POST", "/v1/chat/completions"),
    "completion": ("POST", "/completion"),
    "completion_oai": ("POST", "/v1/completions"),
    "control": ("POST", "/v1/chat/completions/control"),
    "embeddings": ("POST", "/v1/embeddings"),
    "rerank": ("POST", "/v1/rerank"),
    "tokenize": ("POST", "/tokenize"),
    "detokenize": ("POST", "/detokenize"),
    "count_tokens": ("POST", "/v1/chat/completions/input_tokens"),
    "apply_template": ("POST", "/apply-template"),
    "props": ("GET", "/props"),
    "slots": ("GET", "/slots"),
    "metrics": ("GET", "/metrics"),
}


def now_us():
    return time.monotonic_ns() // 1000


def any_slot_busy(host, port):
    c = http.client.HTTPConnection(host, port, timeout=30)
    c.request("GET", "/slots")
    slots = json.loads(c.getresponse().read())
    c.close()
    return any(s.get("is_processing", False) for s in slots)


def run(url, requests_path, out_dir):
    u = urllib.parse.urlparse(url)
    host, port = u.hostname, u.port
    os.makedirs(out_dir, exist_ok=True)
    with open(requests_path) as f, open(os.path.join(out_dir, "summary.jsonl"), "w") as summary:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            spec = json.loads(line)
            rid, endpoint = spec["id"], spec["endpoint"]
            cancel_ms = spec.get("cancel_after_ms", 0)
            cancel_chunks = spec.get("cancel_after_chunks", 0)
            method, path = PATHS[endpoint]

            conn = http.client.HTTPConnection(host, port, timeout=3600)
            t_cancel = [0]
            closed = threading.Event()

            def cancel():
                if not closed.is_set():
                    t_cancel[0] = now_us()
                    closed.set()
                    try:
                        conn.sock.shutdown(2)  # the server sees the socket close = should_stop
                    except Exception:
                        pass

            timer = threading.Timer(cancel_ms / 1000.0, cancel) if cancel_ms > 0 else None

            t0 = now_us()
            body = json.dumps(spec["body"]) if method == "POST" else None
            conn.request(method, path, body=body, headers={"Content-Type": "application/json"})
            if timer:
                timer.start()
            n_chunks, t_first = 0, 0
            status = 0
            with open(os.path.join(out_dir, f"{rid}.jsonl"), "w") as out:
                try:
                    resp = conn.getresponse()
                    status = resp.status
                    while True:
                        data = resp.read1(65536) if hasattr(resp, "read1") else resp.read(65536)
                        if not data:
                            break
                        t = now_us()
                        if n_chunks == 0:
                            t_first = t
                        n_chunks += 1
                        out.write(json.dumps({"t_us": t - t0, "data": data.decode("utf-8", "replace")}) + "\n")
                        if cancel_chunks and n_chunks >= cancel_chunks:
                            cancel()
                            break
                except Exception:
                    pass  # a cancelled stream ends with a socket error
            t_returned = now_us()
            closed.set()
            if timer:
                timer.cancel()
            conn.close()

            t_idle = 0
            if t_cancel[0]:
                while any_slot_busy(host, port):
                    time.sleep(0.002)
                t_idle = now_us()

            tc = t_cancel[0]
            summary.write(json.dumps({
                "id": rid, "endpoint": endpoint, "status": status, "n_chunks": n_chunks,
                "t_first_us": t_first - t0 if t_first else -1,
                "t_returned_us": t_returned - t0,
                "t_cancel_us": tc - t0 if tc else -1,
                "cancel_to_return_us": t_returned - tc if tc else -1,
                "cancel_to_idle_us": t_idle - tc if tc else -1,
            }) + "\n")
            summary.flush()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--requests", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    run(a.url, a.requests, a.out)
