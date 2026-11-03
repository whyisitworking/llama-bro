#!/usr/bin/env python3
"""Compares two run folders (lb-spike or http-run.py output) request by request.

For chat streams it rebuilds the final message: content, reasoning_content, tool calls (name +
arguments; ids are random, server-common.cpp) and finish_reason. id / created / timings / usage
timing fields are ignored. Non-stream responses are compared as JSON minus those fields.

usage: compare.py DIR_A DIR_B   -> prints one line per request, exits 1 on any difference
"""
import json
import os
import sys

# ignored only at the top level of a response or stream event; nested "id" (e.g. token ids) is compared
IGNORED = {"id", "created", "timings", "system_fingerprint"}


def sse_events(path):
    buf = ""
    with open(path) as f:
        for line in f:
            buf += json.loads(line)["data"]
    if not buf.lstrip().startswith("data:") and not buf.lstrip().startswith("error:"):
        return None, buf  # not a stream
    events = []
    for part in buf.split("\n"):
        part = part.strip()
        if part.startswith("data: "):
            payload = part[6:]
            if payload != "[DONE]":
                events.append(json.loads(payload))
        elif part.startswith("error: "):
            events.append({"error": json.loads(part[7:])})
    return events, buf


def rebuild_chat(events):
    msg = {"content": "", "reasoning_content": "", "tool_calls": {}, "finish_reason": None, "errors": [],
           "logprobs": [], "completion_tokens": [], "stop_type": None}
    for e in events:
        if "error" in e:
            msg["errors"].append(e["error"])
            continue
        if "choices" not in e:  # /completion stream (llama.cpp native format)
            msg["content"] += e.get("content") or ""
            msg["completion_tokens"] += e.get("tokens") or []
            if e.get("stop"):
                msg["stop_type"] = e.get("stop_type")
            continue
        for c in e.get("choices", []):
            d = c.get("delta", {})
            msg["content"] += d.get("content") or ""
            msg["reasoning_content"] += d.get("reasoning_content") or ""
            for tc in d.get("tool_calls") or []:
                slot = msg["tool_calls"].setdefault(tc.get("index", 0), {"name": "", "arguments": ""})
                fn = tc.get("function", {})
                slot["name"] += fn.get("name") or ""
                slot["arguments"] += fn.get("arguments") or ""
            lp = (c.get("logprobs") or {}).get("content") or []
            msg["logprobs"] += lp
            if c.get("finish_reason"):
                msg["finish_reason"] = c["finish_reason"]
    msg["tool_calls"] = [msg["tool_calls"][k] for k in sorted(msg["tool_calls"])]
    return msg


def strip(obj):
    if isinstance(obj, dict):
        return {k: v for k, v in obj.items() if k not in IGNORED}
    return obj


def normalized(path):
    events, raw = sse_events(path)
    if events is not None:
        return rebuild_chat(events)
    try:
        return strip(json.loads(raw))
    except json.JSONDecodeError:
        return raw


def main(a, b):
    ids = [json.loads(l)["id"] for l in open(os.path.join(a, "summary.jsonl"))]
    bad = 0
    for rid in ids:
        pa, pb = os.path.join(a, rid + ".jsonl"), os.path.join(b, rid + ".jsonl")
        if not os.path.exists(pb):
            print(f"MISSING {rid}")
            bad += 1
            continue
        na, nb = normalized(pa), normalized(pb)
        if na == nb:
            print(f"SAME    {rid}")
        else:
            bad += 1
            print(f"DIFF    {rid}\n  A: {json.dumps(na)[:400]}\n  B: {json.dumps(nb)[:400]}")
    print(f"{len(ids) - bad}/{len(ids)} identical")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
