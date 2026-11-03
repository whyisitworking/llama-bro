#!/usr/bin/env python3
"""Test B pilot analysis.

usage: b-analyze.py RESULTS.jsonl

Per arm: accuracy, runaway rate (hit max_tokens). For each registered comparison (B1 vs U,
B2-full vs U-full) and the seed-to-seed baseline: how often a (problem, seed) answer flips,
the paired difference with a 95% bootstrap interval (problems as the unit), and the number of
problems needed to detect a 2-point difference with 80% power at alpha 0.025 (Holm, first of two).
"""
import json
import random
import statistics
import sys
from collections import defaultdict

Z = 2.2414 + 0.8416  # z(1 - 0.025/2) + z(0.80)


def load(path):
    rows = defaultdict(dict)  # arm -> (i, seed) -> row
    for l in open(path):
        r = json.loads(l)
        rows[r["arm"]][(r["i"], r["seed"])] = r
    return rows


def per_problem(arm_rows):
    acc = defaultdict(list)
    for (i, _), r in arm_rows.items():
        acc[i].append(r["correct"])
    return {i: sum(v) / len(v) for i, v in acc.items()}


def compare(name, a, b, rows, seeds):
    ra, rb = rows[a], rows[b]
    keys = sorted(ra.keys() & rb.keys())
    flips = sum(ra[k]["correct"] != rb[k]["correct"] for k in keys)
    pa, pb = per_problem({k: ra[k] for k in keys}), per_problem({k: rb[k] for k in keys})
    probs = sorted(pa)
    d = [pa[i] - pb[i] for i in probs]
    mean = statistics.mean(d)
    rng = random.Random(0)
    boots = sorted(statistics.mean(rng.choices(d, k=len(d))) for _ in range(10000))
    lo, hi = boots[250], boots[9749]
    sd = statistics.stdev(d)
    n_need = (Z * sd / 0.02) ** 2
    print(f"{name:<28} n={len(keys):>4}  flips {flips / len(keys):.3f}  "
          f"diff {100 * mean:+.2f} pts [{100 * lo:+.2f}, {100 * hi:+.2f}]  "
          f"problems needed for 2 pts at {seeds} seeds: {n_need:,.0f}")


def main(path):
    rows = load(path)
    print("Accuracy per arm:")
    for arm in ["U", "B1", "U-full", "B2-full"]:
        if arm not in rows:
            continue
        r = list(rows[arm].values())
        acc = sum(x["correct"] for x in r) / len(r)
        runaway = sum(x["finish"] == "length" for x in r) / len(r)
        gen = statistics.median(x["n_gen"] for x in r)
        print(f"  {arm:<8} n={len(r):>4}  accuracy {100 * acc:.1f}%  hit token limit {100 * runaway:.1f}%  median tokens {gen:.0f}")
    seeds = len({s for arm in rows.values() for (_, s) in arm})
    print("\nComparisons (paired on the same problem and seed):")
    for name, a, b in [("B1 vs U (primary)", "B1", "U"), ("B2-full vs U-full (primary)", "B2-full", "U-full"),
                       ("U-full vs U (window only)", "U-full", "U")]:
        if a in rows and b in rows:
            compare(name, a, b, rows, seeds)
    # seed-to-seed baseline: same arm, seed s vs seed s+1
    if "U" in rows:
        u = rows["U"]
        pairs = [(u[(i, s)], u[(i, s + 1)]) for (i, s) in u if (i, s + 1) in u]
        if pairs:
            print(f"{'U seed vs next seed':<28} n={len(pairs):>4}  flips {sum(x['correct'] != y['correct'] for x, y in pairs) / len(pairs):.3f}"
                  "  (the noise floor for flips)")


if __name__ == "__main__":
    main(sys.argv[1])
