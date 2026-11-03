#!/usr/bin/env python3
"""Test A correctness gate: compares per-token logprobs of two runs (e.g. U vs P, both MTP on).

For each request: are the generated tokens identical? The largest logprob difference over the
shared prefix (chosen token and top-5 alternatives). And if the tokens diverge, the gap between
the top two candidates at that position in run A: a small gap means a near-tie that floating-point
reordering can legitimately flip.

usage: lp-compare.py DIR_A DIR_B
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from compare import sse_events, rebuild_chat  # noqa: E402


def lp(d, rid):
    events, _ = sse_events(os.path.join(d, rid + ".jsonl"))
    return rebuild_chat(events)["logprobs"] if events is not None else []


def main(a, b):
    ids = [json.loads(l)["id"] for l in open(os.path.join(a, "summary.jsonl"))]
    worst = 0.0
    n_same = 0
    chosen_all = []
    for rid in ids:
        la, lb = lp(a, rid), lp(b, rid)
        n = min(len(la), len(lb))
        div = next((i for i in range(n) if la[i]["token"] != lb[i]["token"]), None)
        upto = div if div is not None else n
        dmax = 0.0
        dchosen = [abs(la[i]["logprob"] - lb[i]["logprob"]) for i in range(upto)]
        chosen_all.extend(dchosen)
        for i in range(upto):
            dmax = max(dmax, abs(la[i]["logprob"] - lb[i]["logprob"]))
            ta = {t["token"]: t["logprob"] for t in la[i].get("top_logprobs", [])}
            tb = {t["token"]: t["logprob"] for t in lb[i].get("top_logprobs", [])}
            for t in ta.keys() & tb.keys():
                dmax = max(dmax, abs(ta[t] - tb[t]))
        worst = max(worst, dmax)
        if div is None and len(la) == len(lb):
            n_same += 1
            print(f"SAME   {rid:<28} {n:>4} tokens  max |dlogprob| {dmax:.4f}")
        else:
            gap = None
            if div is not None:
                tops = sorted((t["logprob"] for t in la[div].get("top_logprobs", [])), reverse=True)
                gap = tops[0] - tops[1] if len(tops) > 1 else None
            print(f"DIVERGE {rid:<27} at token {div} of {n}  max |dlogprob| before {dmax:.4f}  "
                  f"top-2 gap at divergence {gap if gap is None else round(gap, 4)}")
    chosen_all.sort()
    med = chosen_all[len(chosen_all) // 2] if chosen_all else 0
    p95 = chosen_all[int(len(chosen_all) * 0.95)] if chosen_all else 0
    print(f"{n_same}/{len(ids)} identical token sequences; worst |dlogprob| on shared tokens {worst:.4f}; "
          f"chosen token |dlogprob| median {med:.4f} p95 {p95:.4f} max {max(chosen_all, default=0):.4f} (n={len(chosen_all)})")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
