# F50 A/B: `--load-mode auto` (mmap) vs `none`, CPU, OnePlus 13 (CPH2649, SM8750)

> **Parent:** [findings F58](../../../docs/sdk-v2/findings.md) · Script: [ab.sh](ab.sh) · 2026-11-05

llama-server from the P0 spike's `P` build (b12052 + delta-net patch, the same tree as the SDK), `-c 4096 -np 1 -dev none`,
default threads. Per run: wall time from launch to `/health` 200 (`ready_s`), `/proc/<pid>/smaps_rollup` after load, then
one 64-token greedy `/completion` (timings from the response) and PSS again. Arms alternate within a round; thermal
status 0 (Qwen) and 1 (Llama) throughout. The file was in the page cache for every run (warm loads; no root to drop it).

## Qwen3.5-4B-Q4_K_M (2.83 GB file)

| round | arm | ready_s | Pss MiB | Pss_Anon | Pss_File | Pss after 64 tok | decode t/s | prefill t/s |
|---|---|---|---|---|---|---|---|---|
| 0 | auto | 6.63 | 5486 | 2861 | 2625 | 5535 | 14.51 | 31.74 |
| 0 | none | 7.29 | 3395 | 3377 | 18 | 3458 | 15.41 | 35.51 |
| 1 | auto | 6.09 | 5456 | 2861 | 2595 | 5514 | 14.47 | 22.42 |
| 1 | none | 7.45 | 3395 | 3377 | 18 | 3458 | 13.67 | 32.97 |
| 2 | none | 7.64 | 3394 | 3377 | 17 | 3456 | 6.82 | 31.18 |
| 2 | auto | 6.18 | 5491 | 2861 | 2630 | 5271 | 6.51 | 32.59 |
| 3 | auto | 6.39 | 5426 | 2861 | 2565 | 5456 | 13.20 | 17.58 |
| 3 | none | 18.54 | 3391 | 3377 | 13 | 3454 | 14.16 | 28.04 |
| 4 | none | 8.16 | 3394 | 3377 | 16 | 3456 | 13.69 | 27.32 |
| 4 | auto | 5.88 | 5494 | 2861 | 2633 | 5557 | 13.82 | 29.93 |

Round 2's decode is low in both arms (something else on the phone); round 3's 18.5 s load is a single outlier.

## Llama-3.2-3B-Instruct-Q4_K_M (2.02 GB file)

A first set of rounds is discarded: a stray server from an earlier launch was still running, so the PSS reads failed.
These are the reruns with one server per arm checked.

| round | arm | ready_s | Pss MiB | Pss_Anon | Pss_File | decode t/s | prefill t/s |
|---|---|---|---|---|---|---|---|
| 1 | auto | 4.62 | 4363 | 2419 | 1944 | 21.86 | 44.29 |
| 1 | none | 5.47 | 2754 | 2728 | 25 | 22.21 | 42.01 |
| 2 | none | 5.50 | 2754 | 2728 | 25 | 21.76 | 50.18 |
| 2 | auto | 4.68 | 4364 | 2419 | 1944 | 21.80 | 49.24 |
| 3 | auto | 4.62 | 4364 | 2419 | 1944 | 21.10 | 49.28 |
| 3 | none | 5.55 | 2754 | 2728 | 26 | 21.66 | 47.68 |
| 4 | none | 5.58 | 2754 | 2728 | 25 | 20.62 | 47.34 |
| 4 | auto | 4.61 | 4364 | 2419 | 1944 | 21.97 | 43.57 |

## Three arms: `auto` vs `none` vs `dio` (F59), 2026-11-05

Same setup, `ARMS="auto none dio"`, the arm order rotated each round. Raw output: [dio-ab.out](dio-ab.out).

**Qwen3.5-4B** (4 rounds, thermal 0, one server per arm):

| arm | ready_s per round | PSS MiB | decode t/s |
|---|---|---|---|
| auto | 6.21, 5.83, 5.31, 5.71 (mean 5.77) | 5489–5495 | 14.2–15.1 |
| none | 8.18, 6.61, 6.53, 6.38 (mean 6.93) | 3395–3402 | 14.4–15.4 |
| dio | 7.30, 7.45, 7.70, 7.25 (mean 7.43) | 3395–3403 | 14.1–14.8 |

**Llama-3.2-3B** (3 rounds, thermal 1). Indicative only: an idle Qwen server, stuck after SIGTERM with its port
closed, stayed resident throughout (the script now force-stops leftovers). PSS was read from the newest server.

| arm | ready_s per round | PSS MiB |
|---|---|---|
| auto | 5.52, 4.59, 4.32 | 4343–4349 |
| none | 6.75, 5.18, 5.18 | 2738–2740 |
| dio | 5.52, 5.86, 5.51 | 2738–2740 |

## The unmap patch: mmap, then unmap the copied weights (F60), 2026-11-05

Patched build `M` = the spike's `P` tree plus [unmap-copied-weights.patch](unmap-copied-weights.patch) (now also
`sdk/native/patches/0002-unmap-copied-weights.patch`); only `llama-model-loader.cpp` recompiled. Arms `P:auto`
(mmap), `P:none`, `M:auto` (mmap + patch), rotated per round; the script also records peak RSS (`VmHWM`) and an MD5
of each greedy reply.

**First draft (5 lines, unmap each tensor right after its copy): crashed.** Every `M:auto` load segfaulted at the
first decode, both models. Both tie `output` to `token_embd`: the same file range is loaded twice
(`TENSOR_DUPLICATED`), once copied into `CPU_REPACK` (output) and once used in place from the mapping
(`token_embd`). Unmapping the copy's range pulled the embedding's pages out from under it. Raw: [patch-ab.out](patch-ab.out).

**v2 (14 lines): record the ranges copied out of the mapping and the ranges still read from it; after the last
load, unmap copied minus read.**

Qwen3.5-4B, 5 rounds, thermal 0–1, one server per arm ([patch-v2-qwen.out](patch-v2-qwen.out)):

| arm | ready_s per round | mean | PSS MiB | Pss_File | peak RSS | reply MD5 |
|---|---|---|---|---|---|---|
| P:auto | 6.13, 5.26, 5.51, 5.66, 5.59 | 5.63 | 5495–5497 | 2634 | 5503 | 3ec14de6 |
| P:none | 7.39, 7.00, 6.79, 6.87, 6.95 | 7.00 | 3396–3397 | 19 | 3403 | 3ec14de6 |
| M:auto | 6.46, 5.86, 5.62, 5.64, 5.61 | 5.84 | 3383–3395 | 522–534 | 5370–5398 | 3ec14de6 |

Llama-3.2-3B, 4 rounds, thermal 2, one server per arm ([patch-v2-llama.out](patch-v2-llama.out)):

| arm | ready_s per round | mean | PSS MiB | Pss_File | peak RSS | reply MD5 |
|---|---|---|---|---|---|---|
| P:auto | 4.63, 4.77, 4.56, 4.69 | 4.66 | 4355–4356 | 1935 | 4362 | a4e51c12 |
| P:none | 5.42, 5.48, 5.42, 5.46 | 5.45 | 2746 | 17 | 2753 | a4e51c12 |
| M:auto | 4.74, 4.71, 4.79, 4.71 | 4.74 | 2747 | 327 | 3926 | a4e51c12 |

Decode speed is equal across arms within each round. The first full run's Llama rounds ([patch-v2-ab.out](patch-v2-ab.out),
second half) are discarded: a leftover server stayed resident and, after the PIDs wrapped, the script read its
memory. Cause of every leftover in this folder: `pkill -f llama-server` matched the `sh -c` running it and killed
that shell before its `pkill -9`. The script now matches the exact name and skips an arm if two servers run.
