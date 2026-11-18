#!/usr/bin/env python3
"""F167: replays patch 0008's guard over recorded realtime turns (realtime-turn.py's .json and .wav, unpatched server).

After the last caption token, each 80 ms frame is near-silent when its RMS is under 0.004; the guard stops at 38
near-silent frames in a row (about 3 s), or once the reply has more than 5 frames per text token plus 13.
usage: tail-guard-sim.py RESULT_DIR...   (prints every reply the guard would stop, with where and its full length)
"""
import glob, json, sys, wave
import numpy as np

def stop_at(prefix):
    js = json.load(open(prefix + ".json"))
    ev = js["events"]
    audio = [e["t"] for e in ev if e["type"] == "response.output_audio.delta"]
    text = [e["t"] for e in ev if e["type"] == "response.output_text.delta"]
    if not audio or not text:
        return None, js["summary"]
    n_text, first_tail = len(text), sum(1 for t in audio if t <= text[-1])
    w = wave.open(prefix + ".wav")
    x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    fl = int(0.08 * w.getframerate()); nf = len(x) // fl
    rms = np.sqrt((x[: nf * fl].reshape(nf, fl) ** 2).mean(1))
    quiet = 0
    for f in range(first_tail, nf):
        quiet = quiet + 1 if rms[f] < 0.004 else 0
        if quiet >= 38 or f + 1 > n_text * 5 + 13:
            return round((f + 1) * 0.08, 1), js["summary"]
    return None, js["summary"]

n = 0
for d in sys.argv[1:]:
    for j in sorted(glob.glob(f"{d}/s*.json")):
        n += 1
        at, s = stop_at(j[:-5])
        if at is not None:
            print(f"{j}: stops at {at} s of {s['audio_s']} s ({len(s['text'].split())} words)")
print(f"{n} replies")
