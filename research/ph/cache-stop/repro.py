#!/usr/bin/env python3
# Multi-turn chat against a running llama-server; prints prompt_n / cache_n per turn.
# Start the server first, for example:
#   llama-server -hf ggml-org-dev/tinyLFM3-Omni-for-testing-gguf:Q2_K -c 8192 -np 1 --port 8080
# then: python3 repro.py --port 8080
# Each assistant turn is a fixed "OK." so the tiny test model's output does not matter.
import argparse, json, urllib.request

p = argparse.ArgumentParser()
p.add_argument("--port", type=int, default=8080)
p.add_argument("--turns", type=int, default=6)
p.add_argument("--no-cache", action="store_true", help="send cache_prompt: false (reference run)")
a = p.parse_args()

long_text = " ".join(f"Note {i}: the kettle sits by window {i}." for i in range(40))
msgs = []
prev = 0
for t in range(1, a.turns + 1):
    text = f"Turn {t}: read this. {long_text}" if t == 1 else f"Turn {t}: name one fruit."
    msgs.append({"role": "user", "content": text})
    body = {"messages": msgs, "max_tokens": 8, "temperature": 0, "cache_prompt": not a.no_cache}
    req = urllib.request.Request(f"http://127.0.0.1:{a.port}/v1/chat/completions",
                                 data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    r = json.load(urllib.request.urlopen(req))
    tm = r["timings"]
    n = r["usage"]["prompt_tokens"]
    print(f"turn {t}: prompt tokens {n:5d}  cache_n {tm['cache_n']:5d}  prompt_n {tm['prompt_n']:5d}"
          + (f"  (previous prompt: {prev})" if t > 1 else ""))
    msgs.append({"role": "assistant", "content": "OK."})
    prev = n
