#!/usr/bin/env python3
"""Pulls the server's own timings out of run folders (the last "timings" object in each stream).

usage: timings.py DIR [DIR ...]  -> one line per request: prompt tokens, prefill t/s, decode t/s,
                                    draft acceptance, client-side time to first chunk
"""
import json
import os
import sys


def last_timings(path):
    buf = "".join(json.loads(l)["data"] for l in open(path))
    found = None
    for part in buf.split("\n"):
        part = part.strip()
        if part.startswith("data: ") and part[6:] != "[DONE]":
            try:
                e = json.loads(part[6:])
            except json.JSONDecodeError:
                continue
            if "timings" in e:
                found = e["timings"]
    return found


def rows(d):
    for line in open(os.path.join(d, "summary.jsonl")):
        s = json.loads(line)
        t = last_timings(os.path.join(d, s["id"] + ".jsonl")) or {}
        acc = None
        if t.get("draft_n"):
            acc = t.get("draft_n_accepted", 0) / t["draft_n"]
        yield {
            "dir": os.path.basename(d), "id": s["id"],
            "prompt_n": t.get("prompt_n"), "cache_n": t.get("cache_n"),
            "prefill_tps": t.get("prompt_per_second"), "decode_tps": t.get("predicted_per_second"),
            "predicted_n": t.get("predicted_n"), "draft_accept": acc,
            "client_first_ms": s["t_first_us"] / 1000 if s["t_first_us"] > 0 else None,
        }


if __name__ == "__main__":
    for d in sys.argv[1:]:
        for r in rows(d):
            f = lambda v, p=1: "-" if v is None else f"{v:.{p}f}"
            print(f"{r['dir']:<22} {r['id']:<22} prompt {r['prompt_n']!s:>5} cache {r['cache_n']!s:>5} "
                  f"prefill {f(r['prefill_tps']):>7} t/s  decode {f(r['decode_tps']):>6} t/s  "
                  f"gen {r['predicted_n']!s:>4}  accept {f(r['draft_accept'], 2):>4}  first {f(r['client_first_ms'], 0):>6} ms")
