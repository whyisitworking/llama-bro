#!/usr/bin/env python3
"""Prints each failed instrumented test's first lines and the test-file line it failed on."""
import glob
import re
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "demo/app"
for path in glob.glob(f"{root}/build/outputs/androidTest-results/connected/**/TEST-*.xml", recursive=True):
    text = open(path).read()
    for case in re.finditer(r'<testcase name="(\w+)"[^>]*?>\s*<failure[^>]*>(.*?)</failure>', text, re.S):
        body = case.group(2).replace("&#10;", "\n")
        lines = [ln for ln in body.splitlines() if ln.strip()]
        where = [ln.strip() for ln in lines if "demo.journey" in ln or "demo.ui" in ln][:2]
        print(case.group(1), "\n  ", "\n   ".join(lines[:6])[:900], "\n  at", where, "\n")
