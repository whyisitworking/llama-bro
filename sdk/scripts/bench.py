#!/usr/bin/env python3
"""The README's benchmark: merge the phone's lines, draw the charts, check the README's numbers.

  bench.py merge LINES.jsonl... META.json... -o research/bench/results.json
  bench.py charts [research/bench/results.json] -o assets/readme/
  bench.py check-readme README.md [research/bench/results.json]

merge takes every .jsonl file as BenchDeviceTest lines (one per arm and round; the phone script adds `restore` and a
`build` field) and every .json file as a META.json from the phone script. Per arm it keeps the rounds with `ok`, and
writes the median and the [min, max] range of each number, `extra` included. It refuses lines or METAs from
different builds (sdkCommit, llamaCppBuild) and names them.

charts writes stats, speed, drafting and latency, each as -light.svg and -dark.svg. With no results file it draws the
same images in the "awaiting run" state.

check-readme reads every marker of the form

  <!-- bench:ARM.FIELD -->NUMBER<!-- /bench -->
  <!-- bench:ARM.FIELD/DIVISOR -->NUMBER<!-- /bench -->

ARM is an arm of results.json (qwen-npu, edit-ngram-mtp, voice, restore, ...); FIELD is a top-level field
(promptTps, predictedTps, loadMs, ...) or extra.NAME (extra.stopMs, extra.firstAudioMs, ...); DIVISOR, a whole
number, converts units (firstAudioMs/1000 for seconds). NUMBER is digits with optional thousands commas and decimals;
its number of decimals is the rounding: the arm's median, divided, rounded to those decimals, must equal it. Without
a results file (before the first run) every marker must hold exactly the placeholder "—".
"""
import argparse
import json
import os
import re
import statistics
import sys
from pathlib import Path
from xml.sax.saxutils import escape as xml_escape

META_KEYS = ["sdkCommit", "llamaCppBuild", "phone", "soc", "android", "date"]
BUILD_KEYS = ["sdkCommit", "llamaCppBuild"]
MODELS = [("qwen", "Qwen3.5 4B"), ("lfm", "LFM3-Omni 2B")]
CHIPS = [("npu", "NPU"), ("gpu", "GPU"), ("cpu", "CPU")]
# F163: the C API answered all 76 contract cases exactly as upstream llama-server did. A fixed fact, not a run's result.
CONTRACT_CASES = 76
PLACEHOLDER = "—"

FONT = "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Helvetica Neue', Helvetica, Arial, sans-serif"
# D131's neutral palette; the icon's violet-to-orange (sampled from ic_launcher_foreground) is the one accent
THEMES = {
    "light": {"surface": "#FBFBFC", "edge": "#E3E3E8", "ink": "#1D1D1F", "muted": "#65656A", "bar": "#8E8E93",
              "track": "#E6E6EB", "violet": "#7B44F6", "orange": "#F7883F"},
    "dark": {"surface": "#161618", "edge": "#2C2C2F", "ink": "#E3E3E6", "muted": "#98989D", "bar": "#6E6E73",
             "track": "#222224", "violet": "#8B5CF6", "orange": "#F7883F"},
}
# narrow enough that 17-unit text is 11 CSS px and 15-unit footers 9.9 px in a phone's 343 px README column
WIDTH = 520
PAD = 32


ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
NOT_XML = re.compile("[^\t\n\r\x20-\ud7ff\ue000-\ufffd\U00010000-\U0010ffff]")


def escape(text):
    """XML-escaped, with `$` kept from the colour tokens' substitution. A phone's error text can hold logcat colour
    codes and control characters, which XML 1.0 forbids: an SVG holding one is a broken image."""
    return xml_escape(NOT_XML.sub("", ANSI.sub("", text))).replace("$", "&#36;")


def fail(message):
    print(f"bench: {message}", file=sys.stderr)
    sys.exit(1)


def scrub(text):
    """Outputs are committed and published: a device serial or the host's home path must never reach them."""
    text = str(text)
    home = str(Path.home())
    if len(home) > 1:
        text = text.replace(home, "~")
    serial = os.environ.get("ANDROID_SERIAL", "")
    return text.replace(serial, "<serial>") if serial else text


# merge


def merge(paths, out):
    lines, metas = [], []
    for path in map(Path, paths):
        if path.suffix == ".jsonl":
            lines += [json.loads(row) for row in path.read_text().splitlines() if row.strip()]
        else:
            metas.append(json.loads(path.read_text()))
    if not lines or not metas:
        fail("merge needs at least one LINES.jsonl and one META.json")
    meta = {key: scrub(metas[0].get(key, "")) for key in META_KEYS}
    meta["date"] = max(m.get("date", "") for m in metas)
    for key in BUILD_KEYS:
        builds = sorted({m.get(key, "") for m in metas} | {ln["build"][key] for ln in lines
                                                           if key in ln.get("build", {})})
        if len(builds) > 1:
            fail(f"mixed builds: {key} is {' and '.join(builds)}; merge one build's run only")
    arms = {}
    for line in lines:
        arms.setdefault(line["arm"], []).append(line)
    results = {"meta": meta, "arms": {name: merge_arm(rows) for name, rows in arms.items()}}
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(results, indent=2, ensure_ascii=False) + "\n")
    print(f"merged {len(lines)} lines into {len(arms)} arms")


def merge_arm(rows):
    good = [row for row in rows if row.get("ok")]
    failed = [row for row in rows if not row.get("ok")]
    arm = {"ok": bool(good), "rounds": len(good), "roundsFailed": len(failed)}
    for key in ("model", "device"):
        if any(key in row for row in rows):
            arm[key] = next(row[key] for row in rows if key in row)
    if failed:
        arm["error"] = scrub(failed[0].get("error") or "no reason given")
    if good:
        arm["median"], arm["range"] = summarise(good)
        extras = [row.get("extra") or {} for row in good]
        arm["median"]["extra"], arm["range"]["extra"] = summarise(extras)
    return arm


def summarise(rows):
    median, spread = {}, {}
    keys = dict.fromkeys(k for row in rows for k, v in row.items()
                         if isinstance(v, (int, float)) and not isinstance(v, bool) and k != "round")
    for key in keys:
        values = [row[key] for row in rows if isinstance(row.get(key), (int, float))]
        median[key] = statistics.median(values)
        spread[key] = [min(values), max(values)]
    return median, spread


# reading results


def value(results, arm, field, divisor=1):
    entry = (results or {}).get("arms", {}).get(arm, {})
    if not entry.get("ok"):
        return None
    median = entry["median"]
    if field.startswith("extra."):
        median, field = median.get("extra", {}), field[len("extra."):]
    found = median.get(field)
    return None if found is None else found / divisor


def missing_reason(results, arm):
    entry = results["arms"].get(arm)
    if not entry:
        return "not measured in this run"
    return entry.get("error") or "its rounds have no such number"


def number(v, unit=""):
    decimals = 0 if v >= 100 or unit == "ms" else (2 if v < 1 else 1)
    return f"{v:,.{decimals}f}"


# drawing


def text_width(s, size, weight=400):
    """A sans's advance, estimated: no font is embedded, so the layout leaves room for the widest system stacks."""
    em = 0.0
    for ch in s:
        if ch in "il.,:;'|!·":
            em += 0.28
        elif ch == " ":
            em += 0.28
        elif ch in "mwMW—":
            em += 0.86
        elif ch.isdigit():
            em += 0.58
        elif ch.isupper():
            em += 0.68
        else:
            em += 0.54
    return em * size * (1.05 if weight >= 600 else 1.0)


def fit(s, size, width):
    if text_width(s, size) <= width:
        return s
    while s and text_width(s + "…", size) > width:
        s = s[:-1]
    return s.rstrip() + "…"


class Drawing:
    """SVG with colours as $token names, so the light and dark files are one layout with two palettes."""

    def __init__(self):
        self.parts = []

    def text(self, x, y, s, size, colour="ink", weight=400, anchor="start", tail=None):
        """`tail` is (text, colour) or a list of them, drawn after `s` at weight 400."""
        spans = escape(s)
        for part, tone in [tail] if isinstance(tail, tuple) else tail or []:
            # SVG collapses runs of spaces, so a part's leading spaces become a measured gap
            gap = (len(part) - len(part.lstrip(" "))) * 0.28 * size
            dx = f' dx="{gap:g}"' if gap > 0.28 * size else ""
            shown = escape(part.lstrip(" ") if dx else part)
            spans += f'<tspan{dx} fill="${tone}" font-weight="400">{shown}</tspan>'
        attrs = f' font-weight="{weight}"' if weight != 400 else ""
        attrs += f' text-anchor="{anchor}"' if anchor != "start" else ""
        self.parts.append(f'<text x="{x:g}" y="{y:g}" font-size="{size}" fill="${colour}"{attrs}>{spans}</text>')

    def bar(self, x, y, width, thickness, fill, arm=None):
        """A data bar: square at the baseline, a 4 px round at its end."""
        r = 4
        width = max(width, r)
        tag = f' data-arm="{arm}"' if arm else ""
        self.parts.append(
            f'<path{tag} d="M{x:g} {y:g}h{width - r:g}a{r} {r} 0 0 1 {r} {r}v{thickness - 2 * r:g}'
            f'a{r} {r} 0 0 1 -{r} {r}h-{width - r:g}z" fill="{fill}"/>')

    def rect(self, x, y, width, height, fill, rx=0):
        """`fill` is a colour token or a url(#...) paint."""
        paint = fill if fill.startswith("url(") else f"${fill}"
        self.parts.append(f'<rect x="{x:g}" y="{y:g}" width="{width:g}" height="{height:g}" rx="{rx}" fill="{paint}"/>')

    def line(self, x1, y1, x2, y2, colour="edge"):
        self.parts.append(f'<path d="M{x1:g} {y1:g}L{x2:g} {y2:g}" stroke="${colour}" stroke-width="1"/>')

    def render(self, width, height, title, desc, theme):
        body = "\n".join(self.parts)
        svg = (
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" width="{width}" '
            f'height="{height}" role="img" aria-labelledby="title desc" font-family="{FONT}">\n'
            f'<title id="title">{escape(title)}</title>\n<desc id="desc">{escape(desc)}</desc>\n'
            '<defs><linearGradient id="accent" x1="0" y1="0" x2="1" y2="0">'
            '<stop offset="0" stop-color="$violet"/><stop offset="1" stop-color="$orange"/></linearGradient></defs>\n'
            f'<rect x="0.5" y="0.5" width="{width - 1}" height="{height - 1}" rx="16" fill="$surface" '
            'stroke="$edge"/>\n'
            f"{body}\n</svg>\n"
        )
        colours = THEMES[theme]
        return re.sub(r"\$(\w+)", lambda m: colours[m.group(1)], svg)


def header(d, title, subtitle):
    d.text(PAD, PAD + 22, title, 24, weight=600)
    d.text(PAD, PAD + 50, subtitle, 17, "muted")
    return PAD + 84


def footer(d, y, results, notes, method):
    y += 4
    for note in notes:
        # a caption, not a log: the whole reason stays in results.json
        d.text(PAD, y + 16, fit(" ".join(ANSI.sub("", note).split()), 15, WIDTH - 2 * PAD), 15, "muted")
        y += 24
    d.line(PAD, y + 8, WIDTH - PAD, y + 8)
    if results is None:
        lines = ["Awaiting the benchmark run.", "Every number here comes from one run of the SDK on a phone."]
    else:
        m = results["meta"]
        lines = [f"{m['phone']} · {m['soc']} · Android {m['android']} · {m['date']}",
                 f"Llama Bro {m['sdkCommit']} · llama.cpp {m['llamaCppBuild']} · {method}"]
    for i, s in enumerate(lines):
        d.text(PAD, y + 34 + 22 * i, fit(s, 15, WIDTH - 2 * PAD), 15, "muted" if i or results else "ink",
               weight=600 if results is None and i == 0 else 400)
    return y + 34 + 22 * (len(lines) - 1) + PAD - 6


def rounds_word(results, arms):
    counts = {results["arms"][a]["rounds"] for a in arms if results["arms"].get(a, {}).get("ok")}
    if not counts:
        return "no rounds"
    return f"median of {max(counts)} rounds" if len(counts) == 1 else f"median of up to {max(counts)} rounds"


class Panel:
    """One question of a chart: rows of ("group", name) or (arm, label, value); `best` ("max", "min" or None) picks
    the bar that gets the accent."""

    def __init__(self, title, aside, unit, rows, best=None):
        self.title, self.aside, self.unit, self.rows, self.best = title, aside, unit, rows, best

    def data(self):
        return [r for r in self.rows if r[0] != "group"]

    def name(self, row):
        group = None
        for r in self.rows:
            if r[0] == "group":
                group = r[1]
            if r is row:
                break
        if group:
            return f"{group} on {row[1]}"
        shared = sum(r[0] == row[0] for r in self.data()) > 1
        return self.title if shared else row[1]


def bars(d, y, panels, results, shared=False):
    """Horizontal bars, each value printed at its bar's tip. Each panel has its own scale unless `shared`: two
    measures never share an axis. Returns the new y, the footnotes and the description's sentences."""
    rows = [r for p in panels for r in p.data()]
    x0 = PAD + max(text_width(r[1], 17) for r in rows) + 16
    tips = {id(r): value_tip(r, p.unit) for p in panels for r in p.data()}
    room = WIDTH - PAD - x0 - 8 - max(text_width(tips[id(r)][0], 17, 600) + text_width(tips[id(r)][1], 17)
                                      for r in rows)
    every = [r[2] for r in rows if r[2] is not None]
    notes, said, marks = [], [], {}
    for p in panels:
        d.text(PAD, y + 18, p.title, 18, weight=600, tail=(f"   {p.aside}", "muted") if p.aside else None)
        if p.aside:
            said.append(f"{p.title}, {p.aside}")
        y += 36
        values = [r[2] for r in p.data() if r[2] is not None]
        top = max((every if shared else values) or [1])
        pick = {"max": max, "min": min}.get(p.best)
        winner = pick(values) if pick and values and results else None
        first = y
        for row in p.rows:
            if row[0] == "group":
                if y > first:
                    d.line(x0 + 0.5, first, x0 + 0.5, y)
                    y += 8
                d.text(PAD, y + 19, row[1], 16, "muted", weight=600)
                y += 28
                first = y
                continue
            d.text(PAD, y + 21, row[1], 17)
            if results is None:
                d.rect(x0, y + 6, room, 20, "track", rx=4)
                d.text(x0 + room + 8, y + 21, PLACEHOLDER, 17, "muted")
            elif row[2] is None:
                # an arm missing from two panels (its reading and its writing) is one failure, one footnote
                if row[0] not in marks:
                    marks[row[0]] = superscript(len(marks) + 1)
                    reason = missing_reason(results, row[0])
                    notes.append(f"{marks[row[0]]} {p.name(row)}: {reason}")
                    said.append(f"{p.name(row)} did not run: {reason}")
                d.text(x0 + 10, y + 21, f"did not run{marks[row[0]]}", 17, "muted")
            else:
                width = max(room * row[2] / top, 6)
                d.bar(x0, y + 6, width, 20, "url(#accent)" if row[2] == winner else "$bar", row[0])
                shown, unit = tips[id(row)]
                d.text(x0 + width + 8, y + 21, shown, 17, weight=600, tail=(unit, "muted"))
                said.append(f"{p.name(row)} {shown}{unit}")
            y += 32
        d.line(x0 + 0.5, first, x0 + 0.5, y)
        y += 20
    return y, notes, said


def value_tip(row, unit):
    """The value and its unit, as printed at the bar's tip."""
    if row[2] is None:
        return PLACEHOLDER, ""
    return number(row[2], unit), f" {unit}"


def superscript(n):
    return "".join("⁰¹²³⁴⁵⁶⁷⁸⁹"[int(c)] for c in str(n))


def chip_rows(results, field, divisor=1):
    rows = []
    for prefix, name in MODELS:
        rows.append(("group", name))
        for chip, label in CHIPS:
            rows.append((f"{prefix}-{chip}", label, value(results, f"{prefix}-{chip}", field, divisor)))
    return rows


def bar_chart(results, title, subtitle, about, panels, shared=False):
    d = Drawing()
    y, notes, said = bars(d, header(d, title, subtitle), panels, results, shared)
    arms = [r[0] for p in panels for r in p.data()]
    height = footer(d, y, results, notes, results and rounds_word(results, arms))
    return d, height, title, f"{about} " + ("; ".join(said) + "." if said else "Awaiting the benchmark run.")


def chart_speed(results):
    return bar_chart(results, "Speed on each chip", "Tokens per second, more is better",
                     "Prompt reading and reply writing speed of Qwen3.5 4B and LFM3-Omni 2B on the NPU, GPU and CPU.",
                     [Panel("Reading the prompt", "2,048 tokens", "t/s", chip_rows(results, "promptTps"), "max"),
                      Panel("Writing the reply", "up to 128 tokens", "t/s", chip_rows(results, "predictedTps"), "max")])


def chart_drafting(results):
    def panel(title, plain, drafted, how):
        without, faster = value(results, plain, "predictedTps"), value(results, drafted, "predictedTps")
        # the speed-up heads the panel: a phone-width chart has no room for it beside the bar
        aside = f"{faster / without:.1f}× faster with {how}" if without and faster else f"with {how}"
        return Panel(title, aside, "t/s", [(plain, "Without", without), (drafted, "Drafted", faster)])

    panels = [panel("JSON extraction", "json-off", "json-dflash", "DFlash"),
              panel("Code edit", "edit-off", "edit-ngram-mtp", "n-gram and MTP")]
    # one scale for both: the same measure, so the accent goes to the fastest bar of the chart
    fastest = max((r[2] for p in panels for r in p.data() if r[2] is not None), default=None)
    for p in panels:
        p.best = "max" if any(r[2] == fastest for r in p.data()) else None
    return bar_chart(results, "Drafting", "Qwen3.5 4B on the NPU, writing tokens per second",
                     "Reply writing speed with and without speculative drafting.", panels, shared=True)


def chart_latency(results):
    return bar_chart(
        results, "Waiting times", "Less is better",
        "Model load time per chip, the time to stop a reply, to the first spoken audio, and to come back to a chat.",
        # one accent, on the SDK's own answer to waiting: restoring a saved slot instead of reading the chat again
        [Panel("Loading the model", "", "s", chip_rows(results, "loadMs", 1000)),
         Panel("Stopping and speaking", "", "ms", [("stop", "Stop a reply", value(results, "stop", "extra.stopMs")),
                                                   ("voice", "First spoken audio",
                                                    value(results, "voice", "extra.firstAudioMs"))]),
         Panel("Back to a 3,000-token chat", "", "s",
               [("restore", "Read it again", value(results, "restore", "extra.readAgainMs", 1000)),
                ("restore", "Restore its slot", value(results, "restore", "extra.restoreMs", 1000))], "min")])


def chart_stats(results, columns=4):
    """The headline numbers: a 1x4 strip for wide screens, or 2x2 for a phone, where a strip's labels would be
    too small to read."""
    d = Drawing()
    accent = "edit-ngram-mtp"
    width, rows = (760, 1) if columns == 4 else (480, 2)
    tile_h = 104
    height = 2 * PAD + rows * tile_h + (rows - 1) * 28
    tiles = [("qwen-npu", value(results, "qwen-npu", "promptTps"), "t/s", "Prompt reading", "Qwen3.5 4B, NPU"),
             (accent, value(results, accent, "predictedTps"), "t/s", "Drafted code edit", "Qwen3.5 4B, NPU"),
             ("voice", value(results, "voice", "extra.firstAudioMs", 1000), "s", "First spoken audio",
              "LFM3-Omni 2B"),
             ("contract", CONTRACT_CASES, f"of {CONTRACT_CASES}", "Contract cases", "same as llama-server")]
    column = (width - 2 * PAD) / columns
    said = []
    for i, (arm, v, unit, label, sub) in enumerate(tiles):
        col, top = i % columns, PAD + (i // columns) * (tile_h + 28)
        x = PAD + col * column + (24 if col else 0)
        if col:
            d.line(PAD + col * column + 0.5, top, PAD + col * column + 0.5, top + tile_h)
        if i >= columns and col == 0:
            d.line(PAD, top - 14.5, width - PAD, top - 14.5)
        shown = PLACEHOLDER if v is None else (str(v) if arm == "contract" else number(v, unit))
        d.text(x, top + 44, shown, 44, weight=600, tail=(f" {unit}", "muted") if v is not None else None)
        if v is None:
            d.text(x + text_width(shown, 44) + 10, top + 38, "awaiting", 16, "muted")
        if arm == accent and v is not None:
            d.rect(x, top + 58, text_width(shown, 44), 4, "url(#accent)", rx=2)
        d.text(x, top + 82, label, 16, weight=600)
        d.text(x, top + 104, sub, 16, "muted")
        said.append(f"{label} ({sub}): {shown} {unit}")
    about = ("Awaiting the benchmark run. " if results is None else "") + \
        f"{CONTRACT_CASES} of {CONTRACT_CASES} contract cases identical to upstream llama-server (F163). "
    return d, height, "Llama Bro at a glance", about + "; ".join(said) + ".", width


def charts(results_path, out):
    results = json.loads(Path(results_path).read_text()) if results_path else None
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    images = (("stats", chart_stats), ("stats-narrow", lambda r: chart_stats(r, columns=2)),
              ("speed", chart_speed), ("drafting", chart_drafting), ("latency", chart_latency))
    for name, draw in images:
        d, height, title, desc, *width = draw(results)
        for theme in THEMES:
            (out / f"{name}-{theme}.svg").write_text(d.render((width or [WIDTH])[0], height, title, desc, theme))
    print(f"wrote {2 * len(images)} images" + ("" if results else ", awaiting the run"))


# check-readme

MARKER = re.compile(r"<!--\s*bench:([\w-]+)\.((?:extra\.)?\w+)(?:/(\d+))?\s*-->(.*?)<!--\s*/bench\s*-->", re.S)
NUMBER = re.compile(r"\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?")


def check_readme(readme, results_path):
    text = Path(readme).read_text()
    results = json.loads(Path(results_path).read_text()) if results_path else None
    markers = list(MARKER.finditer(text))
    problems = []
    if len(markers) != text.count("<!-- bench:"):
        problems.append("a bench: marker is malformed or has no <!-- /bench -->")
    for m in markers:
        arm, field, divisor, shown = m.group(1), m.group(2), int(m.group(3) or 1), m.group(4).strip()
        name = f"{arm}.{field}" + (f"/{divisor}" if divisor != 1 else "")
        if results is None:
            if shown != PLACEHOLDER:
                problems.append(f"{name} holds {shown!r}; before the run it must hold {PLACEHOLDER!r}")
            continue
        v = value(results, arm, field, divisor)
        if v is None:
            problems.append(f"{name}: the results have no such number")
        elif not NUMBER.fullmatch(shown):
            problems.append(f"{name} holds {shown!r}, not a number (results: {v:g})")
        else:
            decimals = len(shown.partition(".")[2])
            expected = f"{v:,.{decimals}f}"
            if shown.replace(",", "") != expected.replace(",", ""):
                problems.append(f"{name} says {shown}, the results say {expected}")
    for problem in problems:
        print(f"check-readme: {problem}", file=sys.stderr)
    if problems:
        sys.exit(1)
    print(f"check-readme: {len(markers)} numbers match" + ("" if results else " (awaiting the run)"))


def main():
    parser = argparse.ArgumentParser(prog="bench.py", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    m = commands.add_parser("merge", help="LINES.jsonl and META.json files into one results.json")
    m.add_argument("inputs", nargs="+")
    m.add_argument("-o", "--out", required=True)
    c = commands.add_parser("charts", help="the README's SVGs, light and dark; awaiting the run without results")
    c.add_argument("results", nargs="?")
    c.add_argument("-o", "--out", required=True)
    r = commands.add_parser("check-readme", help="every bench: marker against the results (see above)")
    r.add_argument("readme")
    r.add_argument("results", nargs="?")
    args = parser.parse_args()
    if args.command == "merge":
        merge(args.inputs, args.out)
    elif args.command == "charts":
        charts(args.results, args.out)
    else:
        check_readme(args.readme, args.results)


if __name__ == "__main__":
    main()
