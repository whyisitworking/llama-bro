#!/usr/bin/env python3
"""Fails on an unused Kotlin import.

ktlint 1.x has no unused-import rule, and detekt's needs type resolution, which the quality gate doesn't run. An
import counts as used when its name (or alias) appears in the file outside the import lines. Operator functions are
used without their names (`by` calls getValue/setValue, `a[i]` calls get, `x in y` calls contains), so they are
never reported. On the tree this agreed with detekt's type-resolved UnusedImport.

Usage: check-imports.py [root]; prints each unused import and exits 1 if there is any.
"""

import re
import subprocess
import sys

OPERATORS = {
    "getValue", "setValue", "provideDelegate", "invoke", "get", "set", "contains", "iterator", "next", "hasNext",
    "plus", "minus", "times", "div", "rem", "rangeTo", "rangeUntil", "compareTo", "unaryMinus", "unaryPlus", "not",
    "inc", "dec", "plusAssign", "minusAssign", "timesAssign", "divAssign", "remAssign",
}
COMPONENT = re.compile(r"component\d+")
IMPORT = re.compile(r"^import ([\w.`]+)(?: as (\w+))?\s*$", re.M)
HEADER = re.compile(r"^(?:import|package) .*$", re.M)


def unused(text: str) -> list[str]:
    body = HEADER.sub("", text)
    found = []
    for m in IMPORT.finditer(text):
        name = (m.group(2) or m.group(1).split(".")[-1]).strip("`")
        if name == "*" or name in OPERATORS or COMPONENT.fullmatch(name):
            continue
        if not re.search(r"(?<![\w])" + re.escape(name) + r"(?![\w])", body):
            found.append(m.group(0).strip())
    return found


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    # new files not yet added count too, so a pass here is a pass on CI once they are committed
    files = subprocess.check_output(
        ["git", "-C", root, "ls-files", "--cached", "--others", "--exclude-standard", "*.kt", "*.kts"], text=True
    ).split()
    bad = 0
    for path in files:
        with open(f"{root}/{path}", encoding="utf-8") as f:
            for line in unused(f.read()):
                print(f"{path}: {line}")
                bad += 1
    if bad:
        print(f"{bad} unused import(s)", file=sys.stderr)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
