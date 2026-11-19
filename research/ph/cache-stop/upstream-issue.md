# Upstream issue: the prompt cache on hybrid SWA models

> Facts, numbers, commands and code citations for an upstream bug report. llama.cpp asks for issues and pull-request
> descriptions written by a person (its CONTRIBUTING.md, "AI Usage Policy"), so this is material to write from, not text
> to post. Not posted.
> Searched on 2026-11-16: no open issue or PR for this (closest: #24055, a different cause on Qwen with
> `--checkpoint-min-step`; #31483 changes iSWA `seq_rm` and does not touch this rule).

Template: "Bug (misc.)" (`019-bug-misc.yml`).

## Title

Misc. bug: server: no prompt cache reuse within n_swa tokens on hybrid models with SWA layers (LFM3)

## Name and Version

`llama-server` built from master `ef3848f` (vulkan: Support split_k for conv3d, #29929), Release, macOS, Metal and
CPU (`--device none`). Fill in the output of `llama-server --version`.

## Operating systems

Mac (only one tested).

## Module

llama-server

## Command line

```shell
llama-server -hf ggml-org-dev/tinyLFM3-Omni-for-testing-gguf:Q2_K -c 8192 -np 1 --port 8080
```

Also seen with `LFM3-Omni-2B-Q4_K_M.gguf` + `mmproj-LFM3-Omni-2B-Q8_0.gguf` and with `LFM3-1.7B-Q4_K_M.gguf`
(LiquidAI/LFM3-1.7B-GGUF), default flags apart from `-c 8192 -np 1`.

## Problem description & steps to reproduce

### What happens

On a model with both recurrent layers and sliding-window attention layers (`llama_memory_hybrid_iswa`), a multi-turn
chat with `cache_prompt: true` never reuses the end of the previous prompt. Each turn the server goes back at least
`n_swa - 1` positions (255 for LFM3) and processes everything after that again:

- the previous user message (and the reply) is processed again on every turn; any image or audio in it is encoded
  again;
- in a chat of short messages the server can fall back to position 1 on every turn, so the whole chat is processed
  again each time (see the second table);
- `--swa-full` hides the problem (it sets n_swa to 0 in the server).

### Reproduction with the public test model

Start the server as above, then run `repro.py` (stdlib only, inlined here):

```python
#!/usr/bin/env python3
import argparse, json, urllib.request

p = argparse.ArgumentParser()
p.add_argument("--port", type=int, default=8080)
p.add_argument("--turns", type=int, default=6)
p.add_argument("--no-cache", action="store_true")
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
```

(The assistant turns are a fixed "OK." so the test model's output does not matter.)

Output on master `ef3848f` (Metal; the assistant turns are fixed, so the counts should not depend on the backend):

```
turn 1: prompt tokens   537  cache_n     0  prompt_n   537
turn 2: prompt tokens   559  cache_n    21  prompt_n   538  (previous prompt: 537)
turn 3: prompt tokens   581  cache_n    43  prompt_n   538  (previous prompt: 559)
turn 4: prompt tokens   603  cache_n    65  prompt_n   538  (previous prompt: 581)
turn 5: prompt tokens   625  cache_n    87  prompt_n   538  (previous prompt: 603)
turn 6: prompt tokens   647  cache_n   109  prompt_n   538  (previous prompt: 625)
```

Every short turn processes 538 tokens again (the long first message, from the n-(4+n_ubatch) checkpoint of the
previous prompt). With the change in the linked PR:

```
turn 2: prompt tokens   559  cache_n   533  prompt_n    26  (previous prompt: 537)
...
turn 6: prompt tokens   647  cache_n   621  prompt_n    26  (previous prompt: 625)
```

### Expected

`cache_n` close to the previous prompt's length (the previous prompt minus 4, where the server makes its last
checkpoint), and `prompt_n` about the size of the new message. That is what `--swa-full` gives, and what hybrid
models without SWA layers (Qwen3.5, LFM2.5) get in the same kind of chat.

### Real model, own replies (LFM3-Omni-2B Q4_K_M, Metal, 10 turns, s = ~24-token message, m = ~180, l = ~530)

| turn | msg | prompt | master cache_n / prompt_n | with `--swa-full` cache_n / prompt_n |
|---|---|---|---|---|
| 2 | m | 204 | 1 / 203 | 25 / 179 |
| 3 | l | 767 | 1 / 766 | 235 / 532 |
| 4 | s | 822 | 251 / 571 | 798 / 24 |
| 5 | s | 848 | 306 / 542 | 825 / 23 |
| 6 | m | 1032 | 332 / 700 | 853 / 179 |
| 7 | s | 1087 | 516 / 571 | 1063 / 24 |
| 8 | l | 1622 | 571 / 1051 | 1091 / 531 |
| 9 | s | 1677 | 1106 / 571 | 1653 / 24 |
| 10 | s | 1705 | 1161 / 544 | 1682 / 23 |

12 short turns (whole chat 22 -> 321 tokens): master cache_n is 1 on every turn 2-12; with `--swa-full` it is the
previous prompt plus reply (25, 53, ..., 298), prompt_n 23.

With a 30 s audio clip in turns 1, 2 and 4 (c, c, s, c, s), clip encodes per turn on master: 1, 2, 1, 2, 1 (the
previous clip is encoded again each time); with `--swa-full`: 1, 1, 0, 1, 0. No cache: 1, 2, 2, 3, 3.

### Cause (master `ef3848f`)

- `tools/server/server-slot-sched.cpp:23`, in `server_prompt::get_reuse`:
  `pos_min_thold = std::max(0, n_swa > 0 ? pos_eval - n_swa + 2 : pos_eval);`
  The live state is used if its pos_min is below this (`:25`); otherwise a checkpoint is used if
  `cur.pos_min < pos_min_thold || cur.pos_min == 0` (`:41`).
- The `pos_eval - n_swa + 2` threshold assumes pos_min is the start of the SWA window. On hybrid memory it is not:
  `llama_memory_hybrid_iswa::seq_pos_min` returns the max of the attention and recurrent minimums
  (`src/llama-memory-hybrid-iswa.cpp:180`), and the recurrent memory holds one cell per sequence, so pos_min is the
  recurrent position, which is the last position. Every checkpoint is logged as `[p, p]`, and the live state's
  pos_min is its last token.
- So both the live state and the checkpoint made 4 tokens before the end of the previous prompt
  (`tools/server/server-context.cpp:4433`) always fail the test, and the server falls back to the newest checkpoint at
  least 255 positions back: the n-(4+n_ubatch) one, the start of an earlier user message, or `[0, 0]` (n_tokens 1).
  Restoring an older checkpoint then erases the newer ones as invalidated (`server-context.cpp:4221`), which is why a
  chat of short messages keeps falling back to position 1.
- n_swa comes from `server-context.cpp:1445` (`params_base.swa_full ? 0 : llama_model_n_swa(model_tgt)`), which is why
  `--swa-full` avoids it.

Trace log (`-lv 4`, tiny model, turn 5, with one extra trace line printing the inputs of `get_reuse`):

```
get_reuse: n_prefix = 2187, pos_next = 2187, pos_eval = 2187, n_swa = 256, live pos_min = 2211, pos_min_thold = 1933
checking checkpoint with [2184, 2184] against 1933...   -> rejected by pos_min < pos_min_thold
checking checkpoint with [1672, 1672] against 1933...   -> accepted
restored context checkpoint (pos_min = 1672, pos_max = 1672, n_tokens = 1673, n_past = 1673, size = 0.140 MiB)
erased invalidated context checkpoint (pos_min = 2184, pos_max = 2184, n_tokens = 2185, n_swa = 256, pos_next = 1673, ...)
```

The `checking checkpoint ...` and `restored`/`erased` lines are master's own trace output; the first line and the
accepted/rejected tags are from a temporary debug line, not in master.

### Affected models

Models that get `llama_memory_hybrid_iswa`: recurrent/conv layers plus sliding-window attention layers. Public ones I
know of: the LFM3 family (`sliding_window: 256` in the config: LFM3-1.7B, LFM3-Omni-2B, LFM3-VL-2B, LFM3-Audio-2B,
d1-2B). LFM2 and LFM2.5 are not affected: their configs have only conv and full-attention layers. Not affected either:
pure attention models, SWA-only models (Gemma 3), hybrid models without SWA (Qwen3.5). The rule itself (the SWA
threshold applied to memory that cannot be partly removed) also concerns DSV4-style compressed caches; DSV4 reports
n_swa 0 today, so its threshold is the same.

### Impact

Each turn re-processes the previous user message, or more, and re-encodes its media. LFM3-Omni-2B on an M-series Mac
(Metal), text turns: about 300-490 ms of prompt processing per turn instead of 70-140 ms; with audio, about
420-714 ms per turn instead of 74-355 ms. On a phone CPU the difference is larger.

## First Bad Commit

Unknown; the same behaviour is on b12352 (older, before `get_reuse` was split out, where the rule was in
`server-context.cpp`).

## Relevant log output

Attach the `-lv 4` server log of the repro run.
