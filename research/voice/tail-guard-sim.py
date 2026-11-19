#!/usr/bin/env python3
"""F167, F173: replays patch 0008's guard over recorded realtime turns (realtime-turn.py's .json and .wav, from an
unpatched server).

After the last caption token, each 80 ms frame is near-silent when its RMS is under 0.004; the guard stops at 38
near-silent frames in a row (about 3 s), or once the reply has more than 5 frames per text token plus 13. One audio
delta is one frame; one text delta is counted as one text token.
usage: tail-guard-sim.py [--per-token 5] [--extra 13] [--quiet 38] [--rms 0.004] [--runaway 80] [--stats] RESULT_DIR...
  default: prints every reply the guard would stop, with where and its full length
  --stats: a row per directory: replies, runaways (over --runaway s of audio) and how many the guard stops, the other
           replies it stops and how much audio they lose, their lengths, and their closest approach to each rule
           (frames over the length limit, the longest near-silent run after the text)
"""
import argparse, glob, json, wave
import numpy as np


def replay(prefix, per_token=5, extra=13, quiet_frames=38, rms_quiet=0.004):
    """(stop_s or None, summary, frames/limit at the end or the stop, longest quiet run after the text, the rule that
    stopped it: "quiet", "length" or None)."""
    js = json.load(open(prefix + ".json"))
    ev = js["events"]
    audio = [e["t"] for e in ev if e["type"] == "response.output_audio.delta"]
    text = [e["t"] for e in ev if e["type"] == "response.output_text.delta"]
    if not audio or not text:
        return None, js["summary"], 0.0, 0, None
    n_text, first_tail = len(text), sum(1 for t in audio if t <= text[-1])
    w = wave.open(prefix + ".wav")
    x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    fl = int(0.08 * w.getframerate()); nf = len(x) // fl
    rms = np.sqrt((x[: nf * fl].reshape(nf, fl) ** 2).mean(1))
    limit = n_text * per_token + extra
    quiet = longest = 0
    for f in range(first_tail, nf):
        quiet = quiet + 1 if rms[f] < rms_quiet else 0
        longest = max(longest, quiet)
        if quiet >= quiet_frames or f + 1 > limit:
            return (round((f + 1) * 0.08, 1), js["summary"], (f + 1) / limit, longest,
                    "quiet" if quiet >= quiet_frames else "length")
    return None, js["summary"], nf / limit, longest, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-token", type=int, default=5)
    ap.add_argument("--extra", type=int, default=13)
    ap.add_argument("--quiet", type=int, default=38)
    ap.add_argument("--rms", type=float, default=0.004)
    ap.add_argument("--runaway", type=float, default=80)
    ap.add_argument("--stats", action="store_true")
    ap.add_argument("dirs", nargs="+")
    a = ap.parse_args()
    if a.stats:
        print("dir | replies | runaways (stopped) | others stopped (s lost) | others' audio s median / max | "
              "others' frames/limit max | others' quiet run max (frames)")
    n = 0
    for d in a.dirs:
        rows = []
        for j in sorted(glob.glob(f"{d}/s*.json")):
            at, s, ratio, longest, rule = replay(j[:-5], a.per_token, a.extra, a.quiet, a.rms)
            rows.append((at, s, ratio, longest))
            n += 1
            if not a.stats and at is not None:
                print(f"{j}: stops at {at} s of {s['audio_s']} s ({len(s['text'].split())} words, {rule})")
        if a.stats and rows:
            run = [r for r in rows if r[1]["audio_s"] > a.runaway]
            rest = [r for r in rows if r[1]["audio_s"] <= a.runaway]
            cut = [r for r in rest if r[0] is not None]
            lens = [r[1]["audio_s"] for r in rest]
            lost = ", ".join(f"{r[1]['audio_s'] - r[0]:.1f}" for r in cut)
            print(f"{d.rstrip('/').split('/')[-1]} | {len(rows)} | {len(run)} ({sum(r[0] is not None for r in run)}) | "
                  f"{len(cut)}{f' ({lost})' if cut else ''} | {np.median(lens):.1f} / {max(lens):.1f} | "
                  f"{max(r[2] for r in rest):.2f} | {max(r[3] for r in rest)}")
    if not a.stats:
        print(f"{n} replies")


if __name__ == "__main__":
    main()
