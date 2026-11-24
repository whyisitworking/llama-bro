#!/usr/bin/env python3
"""Every decision or finding the SDK's sources link to has its anchor, and every record heading has one.

KDoc cites a record as `.../docs/decisions.md#d<n>` or `.../docs/research/findings.md#f<n>`, the short anchor on the line
above the record's heading (`<a id="d<n>"></a>`), so a retitled heading breaks no link.

usage: python3 sdk/scripts/tests/anchor-links-test.py
"""
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RECORDS = {"d": ROOT / "docs" / "decisions.md", "f": ROOT / "docs" / "research" / "findings.md"}
LINK = re.compile(r"/docs/(?:research/findings|decisions)\.md#([a-z0-9_-]+)")


def anchors(kind):
    return set(re.findall(rf'<a id="({kind}\d+)"></a>', RECORDS[kind].read_text(encoding="utf-8")))


def sources():
    names = subprocess.check_output(["git", "-C", str(ROOT), "ls-files", "sdk/*.kt", "demo/*.kt"], text=True).split()
    return [ROOT / n for n in names if "third_party" not in n]


class AnchorLinks(unittest.TestCase):
    def test_every_heading_has_its_short_anchor(self):
        for kind, path in RECORDS.items():
            lines = path.read_text(encoding="utf-8").split("\n")
            for number, line in enumerate(lines):
                heading = re.match(rf"### ({kind.upper()}\d+)\. ", line)
                if heading:
                    expected = f'<a id="{heading.group(1).lower()}"></a>'
                    self.assertEqual(expected, lines[number - 1], f"{path.name}:{number + 1} {heading.group(1)}")

    def test_every_link_in_the_sources_is_a_short_anchor_of_an_existing_record(self):
        known = anchors("d") | anchors("f")
        self.assertTrue(known)
        checked = 0
        for source in sources():
            for number, line in enumerate(source.read_text(encoding="utf-8").split("\n"), 1):
                for anchor in LINK.findall(line):
                    checked += 1
                    self.assertIn(anchor, known, f"{source.relative_to(ROOT)}:{number} links to #{anchor}")
        self.assertGreater(checked, 0)


if __name__ == "__main__":
    unittest.main()
