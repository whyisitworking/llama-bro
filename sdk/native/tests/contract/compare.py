#!/usr/bin/env python3
"""Compares two run folders (lb-spike or http-run.py output) request by request.

For chat streams it rebuilds the final message: content, reasoning_content, tool calls (name +
arguments; ids are random, server-common.cpp) and finish_reason. id / created / timings / usage
timing fields are ignored. Non-stream responses are compared as JSON minus those fields.

usage: compare.py [--near-tie GAP] DIR_A DIR_B [REQUESTS.jsonl]   -> one line per request, exits 1 on any difference.
REQUESTS gives per-request options (see make-requests.py): compare=skip|metrics_names|events, compare_timings.

--near-tie GAP (the device contract only, F149/D80): the phone's NPU breaks a near-tie between two tokens either way
from run to run, in either process. A chat reply whose request carries `near_tie_logprobs` (logprobs added only for
this check) passes when both sides chose the same tokens up to one position, and there each side's token is in the
other's top 2 with both sides' top-2 gaps at most GAP; the logprob values themselves are not compared.
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


# random per process or per call: the media marker in /props, tool call ids (server-common.cpp; OpenAI function calls
# and Anthropic tool_use blocks) anywhere in a body
RANDOM_ANYWHERE = {"media_marker"}


def drop_random(obj):
    if isinstance(obj, dict):
        out = {k: drop_random(v) for k, v in obj.items() if k not in RANDOM_ANYWHERE}
        if obj.get("type") == "function" and "function" in obj or obj.get("type") == "tool_use":
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
        # an Anthropic tool_use block's id is a bare random string (no prefix for RANDOM_ID to match)
        return {k: "<id>" if k == "id" and obj.get("type") == "tool_use" and isinstance(v, str) else without_random(v)
                for k, v in obj.items() if k not in WALL_CLOCK and k != "timings"}
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


# compare="realtime" (D97): every server event of a /v1/realtime session, without the random ids
# (server-realtime.cpp: event_id, the session's, items', responses' and calls' ids)
REALTIME_IDS = {"event_id", "id", "item_id", "response_id", "previous_item_id", "call_id"}


def realtime_events(path):
    def walk(obj):
        if isinstance(obj, dict):
            return {k: "<id>" if k in REALTIME_IDS and isinstance(v, str) else walk(v) for k, v in obj.items()}
        if isinstance(obj, list):
            return [walk(v) for v in obj]
        return obj
    return [walk(json.loads(json.loads(line)["data"])) for line in open(path)]


def tokens_only(msg):
    """The rebuilt reply with the logprob values dropped and only the chosen tokens kept."""
    return dict(msg, logprobs=[e.get("token") for e in msg["logprobs"]])


def near_tie(na, nb, gap):
    """(position, token A, token B, largest top-2 gap) where the replies first split, if it is a near-tie; else None."""
    la, lb = na["logprobs"], nb["logprobs"]
    i = next((k for k in range(min(len(la), len(lb))) if la[k]["token"] != lb[k]["token"]), None)
    if i is None:
        return None
    ta, tb = la[i]["token"], lb[i]["token"]
    top_a = {t["token"]: t["logprob"] for t in la[i].get("top_logprobs") or []}
    top_b = {t["token"]: t["logprob"] for t in lb[i].get("top_logprobs") or []}
    if not {ta, tb} <= top_a.keys() or not {ta, tb} <= top_b.keys():
        return None
    worst = max(top_a[ta] - top_a[tb], top_b[tb] - top_b[ta])
    return (i, ta, tb, worst) if worst <= gap else None


def main(a, b, requests=None, tie_gap=None):
    specs = {}
    if requests:
        for line in open(requests):
            if line.strip() and not line.startswith("#"):
                spec = json.loads(line)
                specs[spec["id"]] = spec
    ids = [json.loads(l)["id"] for l in open(os.path.join(a, "summary.jsonl"))]
    bad = ties = 0
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
        elif spec.get("compare") == "realtime":
            na, nb = realtime_events(pa), realtime_events(pb)
        elif spec.get("compare") == "events":
            na, nb = all_events(pa), all_events(pb)
        else:
            na, nb = normalized(pa), normalized(pb)
            with_lp = all(isinstance(n, dict) and "logprobs" in n for n in (na, nb))
            if tie_gap is not None and spec.get("near_tie_logprobs") and with_lp:
                if tokens_only(na) != tokens_only(nb):
                    tie = near_tie(na, nb, tie_gap)
                    if tie:
                        ties += 1
                        print(f"NEAR-TIE {rid}: token {tie[0]} {tie[1]!r} / {tie[2]!r}, top-2 gap {tie[3]:.3f}")
                        continue
                na, nb = tokens_only(na), tokens_only(nb)
            fields = spec.get("compare_timings")
            if fields:
                na, nb = {"response": na, "timings": timings_of(pa, fields)}, {"response": nb, "timings": timings_of(pb, fields)}
        if na == nb:
            print(f"SAME    {rid}")
        else:
            bad += 1
            print(f"DIFF    {rid}\n  A: {json.dumps(na)[:400]}\n  B: {json.dumps(nb)[:400]}")
    compared = len(ids) - sum(1 for r in ids if specs.get(r, {}).get("compare") == "skip")
    print(f"{compared - bad}/{compared} identical" + (f" ({ties} near-tie)" if ties else ""))
    return 1 if bad else 0


if __name__ == "__main__":
    args = sys.argv[1:]
    gap = None
    if args[:1] == ["--near-tie"]:
        gap, args = float(args[1]), args[2:]
    sys.exit(main(args[0], args[1], args[2] if len(args) > 2 else None, gap))
