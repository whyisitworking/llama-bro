#!/usr/bin/env python3
"""Checks the Macrobenchmark results against the performance budgets (docs/sdk-v2/plans/pd-demo-app.md, Reference I).

usage: perf.py RESULTS.json... [--report FILE]

Each argument is a benchmarkData.json written by `:demo:benchmark:connectedBenchmarkReleaseAndroidTest`. Prints the
measured value of every budget next to the budget and exits 1 if any is missed. The startup run without the Baseline
Profile is shown and not gated.
"""
import json
import statistics
import sys

# (class, test, label, how to read the value, budget: "<=" a limit)
# reads: ("metric", name, "median") | ("sampled", name, "P90") | ("per_call", sum_metric, count_metric)
BUDGETS = [
    ("StartupBenchmark", "coldWithTheBaselineProfile", "cold start, time to initial display (median)", ("metric", "timeToInitialDisplayMs", "median"), 450),
    ("StartupBenchmark", "coldWithTheBaselineProfile", "cold start, time to full display (median)", ("metric", "timeToFullDisplayMs", "median"), 700),
    ("ScrollBenchmark", "flingTheChatList", "fling the Chat list of 300 chats, frameOverrunMs P90", ("sampled", "frameOverrunMs", "P90"), 0),
    ("ScrollBenchmark", "flingTheChatList", "fling the Chat list of 300 chats, frameOverrunMs P99", ("sampled", "frameOverrunMs", "P99"), 4),
    ("ScrollBenchmark", "flingAChatOf300Messages", "fling a chat of 300 messages, frameOverrunMs P90", ("sampled", "frameOverrunMs", "P90"), 0),
    ("ScrollBenchmark", "flingAChatOf300Messages", "fling a chat of 300 messages, frameDurationCpuMs P90", ("sampled", "frameDurationCpuMs", "P90"), 6),
    ("StreamingBenchmark", "streamAReply", "streaming 60 tokens/s for 20 s, frameOverrunMs P90", ("sampled", "frameOverrunMs", "P90"), 0),
    ("StreamingBenchmark", "streamAReply", "ChatEngine.onDelta per call (median, ms)", ("per_call", "ChatEngine.onDeltaSumMs", "onDeltaCountCount"), 0.5),
    ("StreamingBenchmark", "streamAReply", "Markdown.parse per call (median, ms)", ("per_call", "Markdown.parseSumMs", "parseCountCount"), 0.5),
    ("SheetAndLayoutBenchmark", "switchTheModelSheetBetweenBasicAndAdvanced", "model sheet Basic/Advanced x10, frameOverrunMs P90", ("sampled", "frameOverrunMs", "P90"), 0),
    ("SheetAndLayoutBenchmark", "switchTheLayoutBackAndForth", "layout switch x10, frameOverrunMs P90", ("sampled", "frameOverrunMs", "P90"), 0),
    ("StreamingBenchmark", "memoryWhileStreaming", "Java heap held while streaming in 300 messages (median of the run peaks, MB)", ("heap_alloc_mb", None, None), 64),
]
INFO = [
    ("StreamingBenchmark", "memoryWhileStreaming", "Java heap footprint ART set aside while streaming (median, MB)", ("heap_mb", "memoryHeapSizeMaxKb", None)),
    ("StartupBenchmark", "coldWithoutTheBaselineProfile", "cold start without the profile, time to initial display (median)", ("metric", "timeToInitialDisplayMs", "median")),
    ("StartupBenchmark", "coldWithoutTheBaselineProfile", "cold start without the profile, time to full display (median)", ("metric", "timeToFullDisplayMs", "median")),
]


def load(paths):
    found = {}
    for path in paths:
        data = json.load(open(path))
        if "javaHeapAllocatedPeakKb" in data:  # written by HeapSampler, beside the benchmark's own results
            found["heap_alloc"] = data["javaHeapAllocatedPeakKb"]
            continue
        for b in data["benchmarks"]:
            found[(b["className"].split(".")[-1], b["name"])] = b
    return found


def read(b, how):
    kind, a, c = how
    if kind == "metric":
        return b["metrics"][a][c]
    if kind == "sampled":
        return b["sampledMetrics"][a][c]
    if kind == "per_call":
        runs = [s / n for s, n in zip(b["metrics"][a]["runs"], b["metrics"][c]["runs"])]
        return statistics.median(runs)
    if kind == "heap_mb":
        return statistics.median(b["metrics"][a]["runs"]) / 1024


def main(args):
    report = None
    if "--report" in args:
        i = args.index("--report")
        report = args[i + 1]
        del args[i : i + 2]
    only = None  # --classes A,B: score only those classes' budgets (a partial run; the rest are reported as not run)
    if "--classes" in args:
        i = args.index("--classes")
        only = set(args[i + 1].split(","))
        del args[i : i + 2]
    found = load(args)
    lines, missed = [], 0
    for cls, test, label, how, limit in BUDGETS:
        if only is not None and cls not in only:
            lines.append(f"NOT RUN  {label}: {cls} was not run in this partial run")
            continue
        b = found.get((cls, test))
        if how[0] == "heap_alloc_mb":
            peaks = found.get("heap_alloc")
            if not peaks:
                lines.append(f"MISSING  {label}: no heap-alloc.json")
                missed += 1
                continue
            value = statistics.median(peaks) / 1024
            ok = value <= limit
            missed += not ok
            lines.append(f"{'PASS' if ok else 'MISS':8} {label}: {value:.3f} (budget <= {limit})")
            continue
        if b is None:
            lines.append(f"MISSING  {label}: no result for {cls}.{test}")
            missed += 1
            continue
        value = read(b, how)
        ok = value <= limit
        missed += not ok
        lines.append(f"{'PASS' if ok else 'MISS':8} {label}: {value:.3f} (budget <= {limit})")
    for cls, test, label, how in INFO:
        b = found.get((cls, test))
        if b is not None:
            lines.append(f"INFO     {label}: {read(b, how):.1f}")
    text = "\n".join(lines)
    print(text)
    if report:
        open(report, "w").write(text + "\n")
    not_run = any(l.startswith("NOT RUN") for l in lines)
    if missed:
        return 1
    return 77 if not_run else 0  # 77: a partial run (some classes were not run), not a full pass


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
