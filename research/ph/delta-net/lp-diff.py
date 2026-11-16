#!/usr/bin/env python3
"""Compares two lp-run.sh runs the way P0 session 2 test A did (F8): identical sequences, the chosen token's
|logprob difference| over the shared prefix (p95, max), and the top-2 gap in run A where the sequences diverge.
Also sums MTP draft acceptance when the server reports it.

usage: lp-diff.py DIR_A DIR_B
"""
import glob
import json
import os
import sys


def load(d):
    return {os.path.basename(p)[:-5]: json.load(open(p)) for p in glob.glob(os.path.join(d, "*.json"))}


def tokens(resp):
    return resp["choices"][0]["logprobs"]["content"]


def acceptance(runs):
    n = sum(r.get("timings", {}).get("draft_n", 0) for r in runs.values())
    a = sum(r.get("timings", {}).get("draft_n_accepted", 0) for r in runs.values())
    return f"{a / n:.2f}" if n else "n/a"


def main(da, db):
    a, b = load(da), load(db)
    ids = sorted(set(a) & set(b))
    same, deltas, gaps = 0, [], []
    for rid in ids:
        ta, tb = tokens(a[rid]), tokens(b[rid])
        n = min(len(ta), len(tb))
        div = next((i for i in range(n) if ta[i]["token"] != tb[i]["token"]), None)
        if div is None and len(ta) == len(tb):
            same += 1
        upto = n if div is None else div
        deltas += [abs(ta[i]["logprob"] - tb[i]["logprob"]) for i in range(upto)]
        if div is not None:
            top = sorted((t["logprob"] for t in ta[div]["top_logprobs"]), reverse=True)
            gaps.append(top[0] - top[1] if len(top) > 1 else float("nan"))
    deltas.sort()
    p95 = deltas[int(0.95 * (len(deltas) - 1))] if deltas else float("nan")
    print(f"identical {same}/{len(ids)}  chosen |dlogprob| p95 {p95:.3f} max {max(deltas, default=0):.3f}")
    print(f"top-2 gap at divergences: {', '.join(f'{g:.3f}' for g in gaps) or 'none'}")
    print(f"MTP acceptance A {acceptance(a)}  B {acceptance(b)}")


if __name__ == "__main__":
    main(*sys.argv[1:3])
