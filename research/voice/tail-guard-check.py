#!/usr/bin/env python3
"""F173 (F167 follow-up): does patch 0008's guard cut speech that belongs to the caption? An independent listener
(whisper.cpp) transcribes each reply the guard stops, whole and up to the stop, and counts the caption's words
(characters in Japanese and Chinese) heard in each. A failure's lost tail is noise or garble, so stopping it loses no
caption; a clean reply stopped early loses the caption's last words.

usage: tail-guard-check.py [--all] [--per-token 5] [--extra 13] [--quiet 38] LANG:RESULT_DIR...
  LANG is the reply's language for whisper (en, de, es, ja, ...). Prints one JSON line per reply: where the guard stops,
  how much of the caption the whole reply and the part before the stop say, and the caption units lost by the stop.
  --all checks every reply, not only the stopped ones (the caption coverage of every reply, to find failures that end
  by themselves).
env: WHISPER_DIR (default ~/software/whisper.cpp), WHISPER_MODEL (default its models/ggml-small.bin; multilingual)
"""
import argparse, difflib, glob, importlib.util, json, os, re, subprocess, sys, tempfile, wave

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("sim", os.path.join(here, "tail-guard-sim.py"))
sim = importlib.util.module_from_spec(spec); spec.loader.exec_module(sim)

WHISPER_DIR = os.path.expanduser(os.environ.get("WHISPER_DIR", "~/software/whisper.cpp"))
WHISPER_CLI = os.path.join(WHISPER_DIR, "build/bin/whisper-cli")
MODEL = os.environ.get("WHISPER_MODEL", os.path.join(WHISPER_DIR, "models/ggml-small.bin"))
CJK = re.compile(r"[぀-ヿ㐀-鿿가-힯]")


def units(text, lang):
    text = re.sub(r"\[[^\]]*\]|\([^)]*\)", " ", text.lower())  # whisper's [BLANK_AUDIO], (music) ...
    if lang in ("ja", "zh"):
        return CJK.findall(text)
    return re.findall(r"\w+", text)


def coverage(caption, heard):
    """Caption units heard, in order."""
    m = difflib.SequenceMatcher(None, caption, heard, autojunk=False)
    return sum(b.size for b in m.get_matching_blocks())


def transcribe(wav, lang, until_s=None):
    with tempfile.TemporaryDirectory() as tmp:
        w16 = os.path.join(tmp, "in.wav")
        cmd = ["ffmpeg", "-loglevel", "error", "-y", "-i", wav]
        if until_s is not None:
            cmd += ["-t", str(until_s)]
        subprocess.run(cmd + ["-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", w16], check=True)
        out = os.path.join(tmp, "out")
        subprocess.run([WHISPER_CLI, "-m", MODEL, "-f", w16, "-l", lang, "-sns", "-np", "-oj", "-of", out],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        segs = json.load(open(out + ".json"))["transcription"]
    spoken = [s for s in segs if s["text"].strip() and not re.fullmatch(r"\s*[\[(].*[\])]\s*", s["text"])]
    return " ".join(s["text"].strip() for s in segs), (spoken[-1]["offsets"]["to"] / 1000 if spoken else 0.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--per-token", type=int, default=5)
    ap.add_argument("--extra", type=int, default=13)
    ap.add_argument("--quiet", type=int, default=38)
    ap.add_argument("dirs", nargs="+")
    a = ap.parse_args()
    for spec_ in a.dirs:
        lang, d = spec_.split(":", 1)
        for j in sorted(glob.glob(f"{d}/s*.json")):
            prefix = j[:-5]
            stop, s, ratio, longest, rule = sim.replay(prefix, a.per_token, a.extra, a.quiet)
            if stop is None and not a.all:
                continue
            cap = units(s["text"], lang)
            heard, speech_end = transcribe(prefix + ".wav", lang)
            r = {"dir": os.path.basename(d.rstrip("/")), "seed": int(os.path.basename(prefix)[1:]),
                 "audio_s": s["audio_s"], "stop_s": stop, "rule": rule, "caption_units": len(cap),
                 "heard_whole": coverage(cap, units(heard, lang)), "speech_end_s": round(speech_end, 1)}
            if stop is not None:
                cut_heard, _ = transcribe(prefix + ".wav", lang, stop)
                r["heard_before_stop"] = coverage(cap, units(cut_heard, lang))
                r["lost_units"] = r["heard_whole"] - r["heard_before_stop"]
                r["heard_after_stop"] = transcribe_tail(prefix + ".wav", lang, stop)
            r["heard"] = heard[:400]
            print(json.dumps(r, ensure_ascii=False), flush=True)


def transcribe_tail(wav, lang, from_s):
    """What the listener hears after the stop: the speech the guard removes."""
    with tempfile.TemporaryDirectory() as tmp:
        tail = os.path.join(tmp, "tail.wav")
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", str(from_s), "-i", wav, tail], check=True)
        return transcribe(tail, lang)[0][:300]


if __name__ == "__main__":
    main()
