#!/usr/bin/env python3
"""bench.py against the fixture run in fixtures/bench: merge, charts and the README check, with no phone.

usage: python3 sdk/scripts/tests/bench-test.py
"""
import json
import os
import re
import statistics
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
BENCH = HERE.parent / "bench.py"
FIXTURES = HERE / "fixtures" / "bench"
LINES = FIXTURES / "lines.jsonl"
META = FIXTURES / "meta.json"
SVG = "{http://www.w3.org/2000/svg}"
IMAGES = ["stats", "stats-narrow", "speed", "drafting", "latency", "ladder", "board", "board-narrow"]
# a phone's README column, and the smallest text that reads there: body text 10 CSS px, footers and footnotes 9
PHONE_WIDTH = 343
WIDE_ONLY = {"stats", "board"}  # the 1x4 strip and the two-column board; phones get the -narrow ones


def bench(*args, env=None):
    return subprocess.run(
        [sys.executable, str(BENCH), *map(str, args)],
        capture_output=True, text=True, env={**os.environ, **(env or {})},
    )


def fixture_lines():
    return [json.loads(line) for line in LINES.read_text().splitlines() if line.strip()]


class BenchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def merged(self, *inputs):
        out = self.tmp / "results.json"
        run = bench("merge", *(inputs or (LINES, META)), "-o", out)
        self.assertEqual(run.returncode, 0, run.stderr)
        return json.loads(out.read_text())

    def write_lines(self, name, lines):
        path = self.tmp / name
        path.write_text("".join(json.dumps(line) + "\n" for line in lines))
        return path

    def test_median_and_range(self):
        results = self.merged()
        rows = [line for line in fixture_lines() if line["arm"] == "qwen-npu"]
        tps = [line["promptTps"] for line in rows]
        arm = results["arms"]["qwen-npu"]
        self.assertTrue(arm["ok"])
        self.assertEqual(arm["rounds"], 3)
        self.assertEqual(arm["median"]["promptTps"], statistics.median(tps))
        self.assertEqual(arm["range"]["promptTps"], [min(tps), max(tps)])
        self.assertEqual(arm["model"], "Qwen3.5-4B-Q4_K_M.gguf")
        # the extras are medians too, and an arm's failed round is dropped, not averaged in
        stop = [line["extra"]["stopMs"] for line in fixture_lines() if line["arm"] == "stop"]
        self.assertEqual(results["arms"]["stop"]["median"]["extra"]["stopMs"], statistics.median(stop))
        self.assertEqual(results["arms"]["stop"]["range"]["extra"]["stopMs"], [min(stop), max(stop)])
        voice = results["arms"]["voice"]
        self.assertEqual((voice["rounds"], voice["roundsFailed"]), (2, 1))
        self.assertEqual(voice["median"]["extra"]["firstAudioMs"], statistics.median([312.0, 298.0]))
        self.assertEqual(results["arms"]["restore"]["median"]["extra"]["restoreMs"], 5040.0)
        self.assertEqual(
            results["meta"],
            {"sdkCommit": "b6d1681", "llamaCppBuild": "b9123", "phone": "SM-S938B", "soc": "Snapdragon 8 Elite",
             "android": "16", "date": "2026-11-22"},
        )

    def test_merge_refuses_mixed_builds(self):
        lines = fixture_lines()
        lines[0]["build"] = {"sdkCommit": "0ddba11"}
        run = bench("merge", self.write_lines("mixed.jsonl", lines), META, "-o", self.tmp / "r.json")
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("0ddba11", run.stderr)
        self.assertIn("b6d1681", run.stderr)
        self.assertFalse((self.tmp / "r.json").exists())
        # two runs (speed, extras) each bring a META; they must name one llama.cpp build
        other = self.tmp / "meta-other.json"
        other.write_text(json.dumps({**json.loads(META.read_text()), "llamaCppBuild": "b9200"}))
        run = bench("merge", LINES, META, other, "-o", self.tmp / "r.json")
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("b9123", run.stderr)
        self.assertIn("b9200", run.stderr)

    def test_failed_arm_is_omitted_with_note(self):
        results = self.merged()
        arm = results["arms"]["lfm-gpu"]
        self.assertFalse(arm["ok"])
        self.assertEqual(arm["rounds"], 0)
        self.assertIn("unsupported op", arm["error"])
        self.assertNotIn("median", arm)
        results_path = self.tmp / "results.json"
        out = self.tmp / "charts"
        run = bench("charts", results_path, "-o", out)
        self.assertEqual(run.returncode, 0, run.stderr)
        speed = (out / "speed-light.svg").read_text()
        self.assertNotIn('data-arm="lfm-gpu"', speed)
        self.assertIn('data-arm="lfm-npu"', speed)
        self.assertIn("did not run", speed)
        self.assertIn("unsupported op", speed)

    def charts(self, results=None):
        out = self.tmp / ("charts" if results else "awaiting")
        run = bench("charts", *([results] if results else []), "-o", out)
        self.assertEqual(run.returncode, 0, run.stderr)
        return out

    def assert_valid_images(self, out):
        for name in IMAGES:
            themes = {}
            for theme in ("light", "dark"):
                text = (out / f"{name}-{theme}.svg").read_text()
                root = ET.fromstring(text)
                self.assertEqual(root.tag, SVG + "svg")
                self.assertRegex(root.get("viewBox"), r"^0 0 \d+ \d+$")
                self.assertTrue(root.find(SVG + "title").text.strip())
                self.assertTrue(root.find(SVG + "desc").text.strip())
                self.assertTrue(list(root.iter(SVG + "text")))
                # GitHub shows README images through <img>: nothing that runs, loads or depends on the viewer's theme
                for banned in ("<script", "href=", "@media", "@import", "url(http", "@font-face", "<foreignObject"):
                    self.assertNotIn(banned, text, f"{name}-{theme}")
                themes[theme] = text
            # the themes differ only in colour tokens
            strip = lambda s: re.sub(r"#[0-9A-Fa-f]{6}\b", "#", s)
            self.assertEqual(strip(themes["light"]), strip(themes["dark"]), name)
            self.assertNotEqual(themes["light"], themes["dark"], name)

    def test_charts_are_valid_xml_in_both_themes(self):
        self.merged()
        out = self.charts(self.tmp / "results.json")
        self.assert_valid_images(out)
        # the fastest decode carries the one accent; F163's fixed fact sits in the strip
        self.assertIn("138", (out / "stats-light.svg").read_text())
        self.assertIn("76 of 76", (out / "stats-light.svg").read_text())
        drafting = (out / "drafting-light.svg").read_text()
        self.assertEqual(drafting.count('fill="url(#accent)"'), 1)
        self.assertRegex(drafting, r'data-arm="edit-ngram-mtp"[^>]*fill="url\(#accent\)"')

    def test_awaiting_state_without_results(self):
        out = self.charts()
        self.assert_valid_images(out)
        for name in IMAGES:
            text = (out / f"{name}-light.svg").read_text()
            self.assertIn("Awaiting", text)
            self.assertNotIn("t/s</text>", text)
            self.assertNotIn('fill="url(#accent)"', text)
        readme = self.tmp / "README.md"
        readme.write_text("NPU reads <!-- bench:qwen-npu.promptTps -->—<!-- /bench --> tokens a second.\n")
        self.assertEqual(bench("check-readme", readme).returncode, 0)
        readme.write_text("NPU reads <!-- bench:qwen-npu.promptTps -->912<!-- /bench --> tokens a second.\n")
        run = bench("check-readme", readme)
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("qwen-npu.promptTps", run.stdout + run.stderr)

    def test_check_readme_catches_drift(self):
        self.merged()
        results = self.tmp / "results.json"
        good = (
            "NPU <!-- bench:qwen-npu.promptTps -->912<!-- /bench --> t/s, decode "
            "<!-- bench:edit-ngram-mtp.predictedTps -->138.4<!-- /bench -->, load "
            "<!-- bench:qwen-npu.loadMs -->4,120<!-- /bench --> ms, first audio "
            "<!-- bench:voice.extra.firstAudioMs/1000 -->0.305<!-- /bench --> s, stop "
            "<!-- bench:stop.extra.stopMs -->41<!-- /bench --> ms.\n"
        )
        readme = self.tmp / "README.md"
        readme.write_text(good)
        run = bench("check-readme", readme, results)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        for drifted, name in (
            ("138.4", "edit-ngram-mtp.predictedTps"),
            ("4,120", "qwen-npu.loadMs"),
            ("0.305", "voice.extra.firstAudioMs/1000"),
        ):
            readme.write_text(good.replace(drifted, drifted[:-1] + str((int(drifted[-1]) + 1) % 10)))
            run = bench("check-readme", readme, results)
            self.assertNotEqual(run.returncode, 0, drifted)
            self.assertIn(name, run.stdout + run.stderr)
        # a placeholder left behind after the run, and a marker naming nothing, both fail
        readme.write_text(good.replace(">912<", ">—<"))
        self.assertNotEqual(bench("check-readme", readme, results).returncode, 0)
        readme.write_text(good + "<!-- bench:lfm-gpu.promptTps -->1<!-- /bench -->\n")
        run = bench("check-readme", readme, results)
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("lfm-gpu.promptTps", run.stdout + run.stderr)

    def test_no_serial_or_home_path_in_output(self):
        serial = "R5CX90ABCDE"
        home = str(Path.home())
        meta = self.tmp / "meta.json"
        meta.write_text(json.dumps({**json.loads(META.read_text()), "serial": serial, "home": home}))
        lines = fixture_lines()
        lines[4]["error"] = f"model not found at {home}/llamabro-models on {serial}"
        lines_path = self.write_lines("lines.jsonl", lines)
        out = self.tmp / "results.json"
        env = {"ANDROID_SERIAL": serial}
        runs = [bench("merge", lines_path, meta, "-o", out, env=env)]
        runs.append(bench("charts", out, "-o", self.tmp / "charts", env=env))
        runs.append(bench("charts", "-o", self.tmp / "awaiting", env=env))
        for run in runs:
            self.assertEqual(run.returncode, 0, run.stderr)
        texts = [out.read_text()] + [run.stdout + run.stderr for run in runs]
        texts += [path.read_text() for path in sorted(self.tmp.glob("*/*.svg"))]
        self.assertEqual(len(texts), 4 + 32)
        for text in texts:
            self.assertNotIn(serial, text)
            self.assertNotIn(home, text)

    def test_odd_error_text_still_parses(self):
        lines = fixture_lines()
        for line in lines:
            if line["arm"] == "lfm-gpu":
                line["error"] = '\x1b[31mboom <a & "q" $5\x1b[0m\x00 ' + "and a very long tail " * 20
        out = self.tmp / "results.json"
        self.assertEqual(bench("merge", self.write_lines("odd.jsonl", lines), META, "-o", out).returncode, 0)
        charts = self.charts(out)
        self.assert_valid_images(charts)
        speed = ET.parse(charts / "speed-light.svg").getroot()
        footnotes = [t for t in ("".join(e.itertext()) for e in speed.iter(SVG + "text")) if t.startswith("¹")]
        self.assertEqual(len(footnotes), 1)
        self.assertIn('boom <a & "q" $5', footnotes[0])
        self.assertTrue(footnotes[0].endswith("…"), "a footnote is one short line")
        desc = speed.find(SVG + "desc").text
        self.assertIn('did not run: boom <a & "q" $5 and a very long tail', desc)
        for text in (footnotes[0], desc):
            self.assertNotIn("[31m", text)
            self.assertNotIn("[0m", text)

    def test_ok_arm_without_the_charted_field(self):
        lines = [line for line in fixture_lines() if line["arm"] != "stop"]
        lines.append({"arm": "stop", "round": 1, "ok": True, "error": None, "model": "m", "device": "Npu",
                      "loadMs": 4000, "extra": {"cancelReturnMs": 3.0}})
        out = self.tmp / "results.json"
        self.assertEqual(bench("merge", self.write_lines("nofield.jsonl", lines), META, "-o", out).returncode, 0)
        latency = (self.charts(out) / "latency-light.svg").read_text()
        self.assertNotIn('data-arm="stop"', latency)
        self.assertIn("Stop a reply did not run", latency)

    def test_ladder_marks_each_rows_fastest_chip(self):
        build = {"sdkCommit": "b6d1681", "llamaCppBuild": "b9123"}
        lines = []
        for model in ("lfm230m", "qwen08b", "lfm17b", "llama3b", "gemma4b", "qwen4b"):
            for chip, speed in (("npu", 30.0), ("gpu", 20.0), ("cpu", 10.0)):
                ok = (model, chip) != ("gemma4b", "npu")
                line = {"arm": f"ladder-{model}-{chip}", "round": 1, "ok": ok, "build": build,
                        "error": None if ok else "unsupported op GLU", "model": "m", "device": chip}
                if ok:
                    line.update(promptTps=speed * 10, predictedTps=speed)
                lines.append(line)
        for kind in ("embed", "rerank"):
            for chip, rate in (("npu", 50.0), ("gpu", 40.0), ("cpu", 60.0)):
                lines.append({"arm": f"{kind}-{chip}", "round": 1, "ok": True, "build": build, "model": "m",
                              "device": chip, "extra": {"itemsPerSecond": rate}})
        out = self.tmp / "results.json"
        run = bench("merge", self.write_lines("ladder.jsonl", lines), META, "-o", out)
        self.assertEqual(run.returncode, 0, run.stderr)
        ladder = (self.charts(out) / "ladder-light.svg").read_text()
        # six rows in each speed table and two in the search table, one accent each; Gemma's falls to the GPU
        self.assertEqual(ladder.count('fill="url(#accent)"'), 14)
        self.assertIn("Gemma 3 4B on NPU: unsupported op GLU", ladder)
        self.assertIn("one run per arm", ladder)

    def test_board_holds_the_four_charts_with_one_footer(self):
        self.merged()
        out = self.charts(self.tmp / "results.json")
        for name, columns in (("board", 2), ("board-narrow", 1)):
            board = (out / f"{name}-light.svg").read_text()
            root = ET.fromstring(board)
            self.assertEqual(int(root.get("viewBox").split()[2]), 520 * columns, name)
            for title in ("Every size, every chip", "Speed on each chip", "Drafting", "Waiting times"):
                self.assertIn(title, board, name)
            self.assertEqual(board.count("llama.cpp b9123"), 1, f"{name}: one footer for the four charts")
            self.assertEqual(board.count('id="accent"'), 1, f"{name}: one gradient")

    def test_text_is_legible_on_a_phone(self):
        self.merged()
        for out in (self.charts(self.tmp / "results.json"), self.charts()):
            for name in IMAGES:
                if name in WIDE_ONLY:
                    continue
                root = ET.parse(out / f"{name}-light.svg").getroot()
                scale = PHONE_WIDTH / int(root.get("viewBox").split()[2])
                sizes = [float(t.get("font-size")) * scale for t in root.iter(SVG + "text")]
                self.assertGreaterEqual(min(sizes), 9, name)
                body = [px for px in sizes if px < 10]
                # only footers and footnotes (15 units, the smallest size used) may sit between 9 and 10 px
                self.assertTrue(all(abs(px - 15 * scale) < 1e-6 for px in body), (name, body))


if __name__ == "__main__":
    unittest.main()
