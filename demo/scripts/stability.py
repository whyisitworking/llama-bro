#!/usr/bin/env python3
"""The component library's stability gate (PD Reference H and K).

Reads the Compose compiler's reports for :designsystem and fails when
  - a public composable of src/main is restartable but not skippable (or missing from the report; one marked
    `@NonSkippableComposable` is exempt, since the source says so), or
  - any class is reported as `unstable class`.
Prints each offender. AGP 9's built-in Kotlin writes one `designsystem-composables.txt` / `-classes.txt` for the
module, overwritten by each variant's compile, so check.sh recompiles the release variant right before this runs;
the plan's `designsystem_release-*.txt` names are read instead when a compiler writes them.

usage: demo/scripts/stability.py [demo dir]
"""
import os
import re
import sys

demo = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), ".."))
module = os.path.join(demo, "designsystem")
reports = os.path.join(module, "build", "compose_compiler")
sources = os.path.join(module, "src", "main", "kotlin")


def report(kind):
    for name in (f"designsystem_release-{kind}.txt", f"designsystem-{kind}.txt"):
        path = os.path.join(reports, name)
        if os.path.exists(path):
            return path
    sys.exit(f"no {kind} report in {reports}: compile :designsystem (release) first")


def public_composables():
    """(package.Name) of every top-level @Composable fun in src/main that is neither private nor internal."""
    found = []
    for root, _, files in os.walk(sources):
        for file in files:
            if not file.endswith(".kt"):
                continue
            text = open(os.path.join(root, file), encoding="utf-8").read()
            package = re.search(r"^package\s+([\w.]+)", text, re.M).group(1)
            annotations = []
            for line in text.splitlines():
                stripped = line.strip()
                if stripped.startswith("@"):
                    annotations.append(stripped)
                    continue
                m = re.match(r"^((?:\w+\s+)*)fun\s+(?:<[^>]*>\s*)?(\w+)\s*\(", line)
                if m and any(a.startswith("@Composable") for a in annotations):
                    modifiers = m.group(1).split()
                    # @NonSkippableComposable says in the source that skipping is wrong here (the recomposition counter)
                    on_purpose = any(a.startswith("@NonSkippableComposable") for a in annotations)
                    if "private" not in modifiers and "internal" not in modifiers and not on_purpose:
                        found.append(f"{package}.{m.group(2)}")
                if stripped and not stripped.startswith("//"):
                    annotations = []
    return sorted(set(found))


def main():
    composables_path, classes_path = report("composables"), report("classes")
    print(f"reading {os.path.relpath(composables_path, demo)} and {os.path.relpath(classes_path, demo)}")
    entries = {}
    for line in open(composables_path, encoding="utf-8"):
        m = re.search(r"\bfun\s+([\w.]+)\(", line)
        if m:
            entries.setdefault(m.group(1), []).append(line.split(" fun ")[0])
    offenders = []
    publics = public_composables()
    for name in publics:
        heads = entries.get(name)
        if heads is None:
            offenders.append(f"{name}: not in the report")
            continue
        for head in heads:
            if head.startswith("restartable") and "skippable" not in head.split():
                offenders.append(f"{name}: {head}")
    for line in open(classes_path, encoding="utf-8"):
        if line.startswith("unstable class"):
            offenders.append(line.strip().rstrip("{").strip())
    for offender in offenders:
        print(f"  {offender}")
    print(f"{len(publics)} public composables checked; {len(offenders)} offender(s)")
    return 1 if offenders else 0


if __name__ == "__main__":
    sys.exit(main())
