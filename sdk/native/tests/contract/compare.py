#!/usr/bin/env python3
"""Compares two run folders (lb-spike or http-run.py output) request by request.

For chat streams it rebuilds the final message: content, reasoning_content, tool calls (name +
arguments; ids are random, server-common.cpp) and finish_reason. id / created / timings / usage
timing fields are ignored. Non-stream responses are compared as JSON minus those fields.

usage: compare.py DIR_A DIR_B [REQUESTS.jsonl]   -> one line per request, exits 1 on any difference.
REQUESTS gives per-request options (see make-requests.py): compare=skip|metrics_names|events, compare_timings.
"""
import json
import os
import re
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


# random per process or per call: the media marker in /props, tool call ids (server-common.cpp) anywhere in a body
RANDOM_ANYWHERE = {"media_marker"}


def drop_random(obj):
    if isinstance(obj, dict):
        out = {k: drop_random(v) for k, v in obj.items() if k not in RANDOM_ANYWHERE}
        if obj.get("type") == "function" and "function" in obj:
            out.pop("id", None)
        return out
    if isinstance(obj, list):
        return [drop_random(v) for v in obj]
    return obj


def strip(obj):
    if isinstance(obj, dict):
        return drop_random({k: v for k, v in obj.items() if k not in IGNORED})
    return obj


def normalized(path):
    events, raw = sse_events(path)
    if events is not None:
        return rebuild_chat(events)
    try:
        return strip(json.loads(raw))
    except json.JSONDecodeError:
        return raw


def raw_text(path):
    return "".join(json.loads(line)["data"] for line in open(path))


def timings_of(path, fields):
    """The last `timings` object in a response (stream events or a whole body), restricted to `fields`."""
    events, raw = sse_events(path)
    found = None
    for e in events if events is not None else [json.loads(raw)]:
        if isinstance(e, dict) and isinstance(e.get("timings"), dict):
            found = e["timings"]
    return {f: (found or {}).get(f) for f in fields}


def metric_names(path):
    """Prometheus text without values: the metric names and their HELP/TYPE lines."""
    return [line if line.startswith("#") else line.split(" ")[0] for line in raw_text(path).splitlines() if line.strip()]


# compare="events": the Responses and Anthropic shapes (event: lines, ids and wall-clock times nested anywhere).
# Every data payload is compared, without random ids (server-common.cpp's random_string) and timestamps.
WALL_CLOCK = {"created", "created_at", "completed_at"}
RANDOM_ID = re.compile(r"^(resp|msg|chatcmpl|fc|rs|call)[_-][A-Za-z0-9]{20,}$")


def without_random(obj):
    if isinstance(obj, dict):
        return {k: without_random(v) for k, v in obj.items() if k not in WALL_CLOCK and k != "timings"}
    if isinstance(obj, list):
        return [without_random(v) for v in obj]
    if isinstance(obj, str) and RANDOM_ID.match(obj):
        return "<id>"
    return obj


def all_events(path):
    raw = raw_text(path)
    payloads = re.findall(r"^data: (.*)$", raw, re.M) or [raw]
    out = []
    for p in payloads:
        try:
            out.append(without_random(json.loads(p)))
        except json.JSONDecodeError:
            out.append(p)
    return out


def main(a, b, requests=None):
    specs = {}
    if requests:
        for line in open(requests):
            if line.strip() and not line.startswith("#"):
                spec = json.loads(line)
                specs[spec["id"]] = spec
    ids = [json.loads(l)["id"] for l in open(os.path.join(a, "summary.jsonl"))]
    bad = 0
    for rid in ids:
        spec = specs.get(rid, {})
        pa, pb = os.path.join(a, rid + ".jsonl"), os.path.join(b, rid + ".jsonl")
        if not os.path.exists(pb):
            print(f"MISSING {rid}")
            bad += 1
            continue
        if spec.get("compare") == "skip":
            print(f"SKIP    {rid} (timing-dependent output; compared through the next request)")
            continue
        if spec.get("compare") == "metrics_names":
            na, nb = metric_names(pa), metric_names(pb)
        elif spec.get("compare") == "events":
            na, nb = all_events(pa), all_events(pb)
        else:
            na, nb = normalized(pa), normalized(pb)
            fields = spec.get("compare_timings")
            if fields:
                na, nb = {"response": na, "timings": timings_of(pa, fields)}, {"response": nb, "timings": timings_of(pb, fields)}
        if na == nb:
            print(f"SAME    {rid}")
        else:
            bad += 1
            print(f"DIFF    {rid}\n  A: {json.dumps(na)[:400]}\n  B: {json.dumps(nb)[:400]}")
    compared = len(ids) - sum(1 for r in ids if specs.get(r, {}).get("compare") == "skip")
    print(f"{compared - bad}/{compared} identical")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None))
