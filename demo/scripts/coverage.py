#!/usr/bin/env python3
"""The coverage gate: the unit and the device JaCoCo reports of :app merged line by line, against the floors of
Reference L.

  90% lines and 80% branches in com.suhel.llamabro.demo.{data,models,chat}
  85% lines in every *ViewModel
  75% lines overall

Excluded: generated Room code, BuildConfig and the @Preview functions (the files named *Previews.kt, *PreviewData.kt).
Prints each group against its floor and, for a group below one, the files that are furthest from it with the line
numbers nobody ran. Exit 1 when a floor is missed.

usage: coverage.py DEMO_DIR
"""

import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

PACKAGE = "com/suhel/llamabro/demo"
REPORTS = [
    "app/build/reports/coverage/test/debug/report.xml",
    "app/build/reports/coverage/androidTest/debug/connected/report.xml",
]
EXCLUDED_FILE = re.compile(r"(Previews?\.kt|PreviewData\.kt|BuildConfig\.java|_Impl\.java|_Impl\.kt)$")
# (name, matcher over (package, file), line floor, branch floor or None)
GROUPS = [
    ("data", lambda pkg, _: pkg == f"{PACKAGE}/data", 90, 80),
    ("models", lambda pkg, _: pkg == f"{PACKAGE}/models", 90, 80),
    ("chat", lambda pkg, _: pkg == f"{PACKAGE}/chat", 90, 80),
    ("view models", lambda _, file: file.endswith("ViewModel.kt"), 85, None),
    ("overall", lambda pkg, _: pkg.startswith(PACKAGE), 75, None),
]


def merge(demo: Path):
    """(package, file) -> {line: [covered instructions, missed, covered branches, missed branches]}, both reports."""
    merged = defaultdict(dict)
    found = 0
    for report in REPORTS:
        path = demo / report
        if not path.exists():
            print(f"missing report: {path}")
            continue
        found += 1
        # the DTD reference is not fetched
        root = ET.fromstring(re.sub(r"<!DOCTYPE[^>]*>", "", path.read_text()))
        for package in root.iter("package"):
            for source in package.iter("sourcefile"):
                key = (package.get("name"), source.get("name"))
                lines = merged[key]
                for line in source.iter("line"):
                    nr = int(line.get("nr"))
                    ci, mi, cb, mb = (int(line.get(a)) for a in ("ci", "mi", "cb", "mb"))
                    old = lines.get(nr)
                    if old is None:
                        lines[nr] = [ci, mi, cb, mb]
                    else:  # covered by either run is covered
                        old[0] = max(old[0], ci)
                        old[1] = min(old[1], mi)
                        old[2] = max(old[2], cb)
                        old[3] = min(old[3], mb)
    if found == 0:
        sys.exit("no coverage reports: run the unit and device tests first")
    return merged


def is_covered(counts) -> bool:
    return counts[0] > 0


def main() -> int:
    demo = Path(sys.argv[1])
    merged = {k: v for k, v in merge(demo).items() if not EXCLUDED_FILE.search(k[1])}
    failed = False
    for name, matches, line_floor, branch_floor in GROUPS:
        files = {k: v for k, v in merged.items() if matches(*k)}
        lines = sum(len(v) for v in files.values())
        hit = sum(1 for v in files.values() for c in v.values() if is_covered(c))
        branches = sum(c[2] + c[3] for v in files.values() for c in v.values())
        branch_hit = sum(c[2] for v in files.values() for c in v.values())
        line_pct = 100.0 * hit / lines if lines else 100.0
        branch_pct = 100.0 * branch_hit / branches if branches else 100.0
        ok = line_pct >= line_floor and (branch_floor is None or branch_pct >= branch_floor)
        branch_text = f", {branch_pct:5.1f}% branches (floor {branch_floor}%)" if branch_floor else ""
        print(f"{'ok  ' if ok else 'FAIL'} {name:12} {line_pct:5.1f}% lines (floor {line_floor}%){branch_text}")
        if not ok:
            failed = True
            worst = sorted(files.items(), key=lambda kv: sum(1 for c in kv[1].values() if not is_covered(c)), reverse=True)
            for (pkg, file), cs in worst[:8]:
                missed = sorted(n for n, c in cs.items() if not is_covered(c))
                if missed:
                    print(f"       {pkg.split('/')[-1]}/{file}: {len(missed)} lines not run, e.g. {missed[:12]}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
