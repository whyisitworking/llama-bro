#!/usr/bin/env python3
"""The requirement map's gate

Reads the map's table in demo/requirements.md, takes every test it names (a backticked name after U, S, C,
J or R), and looks each one up in the JUnit XML results of the last run: the JVM tests of :app and :designsystem and the
instrumented tests of both. A named test that did not run, or failed, is an open item. Names ending in `Test` stand for
the whole class ("MarkdownTest (all)"): at least one of its tests must have run and none may have failed.

usage: demo/scripts/map_check.py [--list]      (--list prints every test, not only the problems)
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
plan = os.path.join(root, "demo", "requirements.md")
result_globs = [
    "demo/app/build/test-results/testDebugUnitTest/*.xml",
    "demo/designsystem/build/test-results/testDebugUnitTest/*.xml",
    "demo/app/build/outputs/androidTest-results/connected/debug/*.xml",
    "demo/designsystem/build/outputs/androidTest-results/connected/debug/*.xml",
    # check.sh keeps each connected run's results, which the next run overwrites
    "demo/build/check/results/*/*.xml",
]


def results():
    """({test name: 'pass' | 'fail'}, {class simple name: [test names]}, {skipped test names}) over every result file."""
    by_name, by_class, skipped_names = {}, {}, set()
    for pattern in result_globs:
        for path in glob.glob(os.path.join(root, pattern)):
            for case in ET.parse(path).getroot().iter("testcase"):
                name = case.get("name", "").split("[")[0]
                cls = case.get("classname", "").rsplit(".", 1)[-1]
                failed = any(child.tag in ("failure", "error") for child in case)
                skipped = any(child.tag == "skipped" for child in case)
                if skipped:
                    skipped_names.add(name)
                    continue
                state = "fail" if failed else "pass"
                if by_name.get(name) != "fail":
                    by_name[name] = state
                by_class.setdefault(cls, []).append(state)
    return by_name, by_class, skipped_names


def mapped_tests():
    """[(requirement, kind, name)] from the map's table rows."""
    text = open(plan, encoding="utf-8").read()
    start = text.index("| Design |")
    end = len(text)
    rows = []
    for line in text[start:end].splitlines():
        if not line.startswith("|") or line.startswith("|---") or line.startswith("| Design"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 3:
            continue
        requirement, tests = cells[0], cells[-1]
        for segment in tests.split(";"):
            m = re.match(r"^\s*([USCJR])\s+(.*)$", segment)
            if not m:
                continue
            kind = m.group(1)
            for name in re.findall(r"`([A-Za-z][A-Za-z0-9]*)`", m.group(2)):
                if name[0].islower() or name.endswith("Test"):
                    rows.append((requirement, kind, name))
    return rows


def main():
    listing = "--list" in sys.argv
    allow_skipped = "--allow-skipped" in sys.argv
    by_name, by_class, skipped_names = results()
    if not by_name:
        sys.exit("no JUnit results found: run demo/scripts/check.sh first (unit, device)")
    problems, warnings, seen = [], [], set()
    for requirement, kind, name in mapped_tests():
        if (kind, name) in seen:
            continue
        seen.add((kind, name))
        if name.endswith("Test") and name[0].isupper():
            states = by_class.get(name, [])
            state = "fail" if "fail" in states else ("pass" if states else None)
        else:
            state = by_name.get(name)
        if state != "pass" and name in skipped_names and allow_skipped and name not in by_name:
            warnings.append((requirement, kind, name))  # skipped on purpose: this phone lacks what it needs
        elif state != "pass":
            problems.append((requirement, kind, name, "FAILED" if state == "fail" else "did not run"))
        elif listing:
            print(f"pass  {kind} {name}")
    for requirement, kind, name, why in problems:
        print(f"{why.upper():12} {kind} {name}   ({requirement})")
    for requirement, kind, name in warnings:
        print(f"SKIPPED      {kind} {name}   ({requirement}) -- not proven on this device; run it on the phone")
    print(f"{len(seen)} mapped tests, {len(problems)} not passing")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
