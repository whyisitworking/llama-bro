#!/usr/bin/env python3
"""Test B runner: sends GSM8K problems to a running llama-server under one sampler arm.

usage: b-run.py ARM N_PROBLEMS SEEDS OUT.jsonl [--port 8080] [--threads 4]
  ARM: U | B1 | U-full | B2-full   (B2-full must point at the S build's server)
  SEEDS: comma-separated, e.g. 1,2,3 (the same seeds in every arm)

Sampling is the Qwen3.5 model card's "non-thinking, reasoning tasks" row, with min_p set
explicitly to the card's 0 so that B1's reordering doesn't also change min_p.
Results are appended; rows already in OUT are skipped, so a run can resume.
"""
import argparse
import json
import os
import re
import threading
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "..", "data", "gsm8k", "test.jsonl")

CARD = {"temperature": 1.0, "top_p": 0.95, "top_k": 20, "min_p": 0.0, "presence_penalty": 1.5}
ARMS = {
    "U": {"repeat_last_n": 64},
    "B1": {"repeat_last_n": 64, "samplers": ["penalties", "temperature", "min_p", "top_k", "top_p"]},
    "U-full": {"repeat_last_n": 4096},
    "B2-full": {"repeat_last_n": 4096},
}
PROMPT = "{q}\nPlease reason step by step, and put your final answer within \\boxed{{}}."


def extract(text):
    boxed = re.findall(r"\\boxed\{([^{}]*)\}", text)
    cand = boxed[-1] if boxed else text
    nums = re.findall(r"-?\d[\d,]*\.?\d*", cand)
    if not nums:
        return None
    v = nums[-1].replace(",", "").rstrip(".")
    try:
        return float(v)
    except ValueError:
        return None


def ask(port, arm, prob, seed):
    body = {
        "messages": [{"role": "user", "content": PROMPT.format(q=prob["question"])}],
        "chat_template_kwargs": {"enable_thinking": False},
        "max_tokens": 2048, "seed": seed, "cache_prompt": False,
        **CARD, **ARMS[arm],
    }
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions",
                                 data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        res = json.load(r)
    ch = res["choices"][0]
    text = ch["message"].get("content") or ""
    got = extract(text)
    want = float(prob["answer"])
    return {"arm": arm, "i": prob["i"], "seed": seed, "correct": got is not None and abs(got - want) < 1e-6,
            "got": got, "want": want, "finish": ch.get("finish_reason"),
            "n_gen": res.get("usage", {}).get("completion_tokens"), "text": text}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("arm", choices=ARMS)
    ap.add_argument("n", type=int)
    ap.add_argument("seeds")
    ap.add_argument("out")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--threads", type=int, default=4)
    a = ap.parse_args()
    probs = [json.loads(l) for l in open(DATA)][: a.n]
    seeds = [int(s) for s in a.seeds.split(",")]
    done = set()
    if os.path.exists(a.out):
        for l in open(a.out):
            r = json.loads(l)
            done.add((r["arm"], r["i"], r["seed"]))
    jobs = [(p, s) for s in seeds for p in probs if (a.arm, p["i"], s) not in done]
    lock = threading.Lock()
    n_ok = n_all = 0
    with open(a.out, "a") as f, ThreadPoolExecutor(a.threads) as ex:
        for r in ex.map(lambda js: ask(a.port, a.arm, *js), jobs):
            with lock:
                f.write(json.dumps(r) + "\n")
                f.flush()
                n_all += 1
                n_ok += r["correct"]
                if n_all % 50 == 0:
                    print(f"{a.arm}: {n_all}/{len(jobs)} done, {n_ok / n_all:.3f} correct", flush=True)
    print(f"{a.arm}: finished {n_all} new rows, {n_ok}/{n_all} correct")


if __name__ == "__main__":
    main()
