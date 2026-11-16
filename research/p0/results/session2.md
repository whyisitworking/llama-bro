# P0 session 2: delta-net patch (test A) and phone defaults (test C)

> **Parent:** [P0](../README.md) · **Summarised in:** [findings](../../../docs/research/findings.md) (tests A and C; findings F7–F11, decisions D10 and D17)

Date: 2026-11-03. Same phone, model and flags as session 1 (`-dev HTP0 -ngl 99 -c 16384 -t 6 -tb 6`).
MTP: `--spec-type draft-mtp --spec-draft-n-max 3`. P = U + `research/ph/delta-net/delta-net-head-tail-split.patch` (then the SDK's 0001)
(only `libllama.so` differs). The phone reaches thermal status 3 after about 4 minutes of continuous NPU work,
even in AC. Comparisons are therefore always made within a round (alternating builds or settings), never across rounds.

## Decisions

| | Decision | Evidence |
|---|---|---|
| **A** | **Ship the delta-net patch** | NPU prefill +56-69% with MTP on, CPU prefill +23-38%; no change with MTP off; numeric drift no larger than upstream's own |
| **C1** | `n_ubatch`: **keep upstream (512)** | 1024/2048 aren't faster beyond noise; 2048 costs +136 MB |
| **C2** | `n_batch`: **512 instead of 2048** (proposed) | Prefill-cancel 0.37-0.44 s instead of 2.0-2.6 s, same prefill speed and memory within a round |
| **C3** | Budget **B: 8 checkpoints, 1 slot, 1024 MiB RAM cache** (chosen) | 50.25 MiB per checkpoint; worst case ~1.4 GB + model vs ~14 GB upstream (arithmetic below) |

## Test A: delta-net patch

**Why it matters (from source).** Upstream's fast HMX chunked delta-net kernel on Hexagon requires `K == 1`
(`ggml-hexagon/htp-kparams.cpp:551-556`). With MTP, K = n_max + 1 = 4, so the whole prompt falls back to the HVX
recurrent kernel. The patch runs the prompt head with K = 1.

**NPU speed** (llama-server, 3 rounds alternating U/P; values per round):

| MTP | Prompt | Prefill U | Prefill P | P vs U | Decode P vs U | MTP acceptance U -> P |
|---|---|---|---|---|---|---|
| on | 564 | 397 / 388 / 383 | 610 / 604 / 583 | **+56%** | +0.3% | 0.62 -> 0.60 |
| on | 2368 | 440 / 438 / 433 | 741 / 753 / 736 | **+69%** | +8.0% | 0.50 -> 0.56 |
| on | 9883 | 436 / 416 / 409 | 682 / 678 / 646 | **+63%** | -2.6% | 0.53 -> 0.50 |
| off | all | | | -1.7 .. +2.3% | within noise | n/a |

**CPU speed** (MTP on, 2 rounds alternating; phone throttled, so compare within the table only):

| Prompt | Prefill U | Prefill P | Decode U | Decode P |
|---|---|---|---|---|
| 564 | 27.7 / 24.4 | 34.0 / 34.1 (**+38%**) | 6.9 / 7.0 | 8.8 / 8.8 |
| 2368 | 25.1 / 25.6 | 29.3 / 30.3 (**+18%**) | 6.4 / 6.3 | 6.2 / 6.2 |

**Correctness gate** (logprobs, 19 requests, greedy, NPU). The plan's KL-divergence check via llama-perplexity can't
exercise the patch (perplexity never keeps speculative snapshots), so the server's own logprobs were compared against
upstream's own variation between its two kernels (MTP off = chunked, MTP on = recurrent):

| Comparison | Identical sequences | Chosen-token abs dlogprob p95 | max |
|---|---|---|---|
| U MTP off vs U MTP on (upstream's own kernel switch) | 16/19 | 0.041 | 0.32 |
| U MTP on vs P MTP on | 16/19 | 0.038 | 0.39 |
| U MTP off vs P MTP on | 15/19 | 0.036 | 0.21 |

All divergences happen at near-ties (top-2 gap 0.006-0.09 nats). Acceptance moves both ways: drift, not loss.

## Test C: phone defaults (NPU, U, MTP off, 2 rounds)

| n_batch / n_ubatch | Prefill t/s 564 / 2368 / 9883 (round 1; round 2) | Prefill cancel -> slot free | Peak |
|---|---|---|---|
| 2048 / 512 (upstream) | 761 / 895 / 802; 704 / 822 / 712 | 1.97 s; 2.58 s | 744 MB |
| 2048 / 1024 | 796 / 855 / 760; 773 / 820 / 706 | 2.20 s; 2.59 s | 788 MB |
| 2048 / 2048 | 824 / 768 / 704; 776 / 732 / 666 | 2.60 s; 2.96 s | 879 MB |
| 1024 / 1024 | 767 / 827 / 723; 822 / 824 / 712 | 1.04 s; 1.17 s | 789 MB |
| **512 / 512** | 668 / 822 / 714; 707 / 839 / 714 | **0.37 s; 0.44 s** | 743 MB |

Round 1 ran in the order listed while the phone heated from status 1 to 3, so the last rows are disadvantaged.
Round 2 ran entirely at status 3, and there 512/512 equals upstream (707/839/714 vs 704/822/712).
So `n_batch = 512` makes "stop" during a long prompt about 6x faster at no measured cost. It's a phone-specific reason
backed by a measurement, as the reference rule requires. Proposed as the SDK default.

### Checkpoints and RAM cache (trace-level run of the T7 conversation)
- One checkpoint = **50.25 MiB** for Qwen3.5-4B (the recurrent state; fixed per model, independent of length).
- A 10-turn chat created **2 checkpoints per turn**. Saving it to the RAM cache took **1,080 MiB** (790 tokens, 20 checkpoints).
- Upstream worst case on this phone: 4 slots x 32 checkpoints x 50.25 MiB = **6.4 GB**, plus `cache_ram` up to
  **8 GB**, plus the model at 2.8 GB. That's about 17 GB, more than the phone's 15.5 GB. On a mainstream 8 GB phone, a
  long agent session would be killed by Android well before that.

| Budget option | Checkpoints per slot | Slots | RAM cache | Worst case |
|---|---|---|---|---|
| upstream | 32 | 4 | 8192 MiB | ~14.4 GB + model |
| A | 8 | 4 | 1024 MiB | 1.6 GB + 1 GB = 2.6 GB + model |
| B | 8 | 1 | 1024 MiB | 0.4 GB + 1 GB = 1.4 GB + model |

Fewer checkpoints mean longer re-processing after an edit far back in the conversation. Checkpoints are taken at
user-message starts, so 8 covers about the last 4 turns.

## Also found: why switching back to a conversation re-read it (session 1, finding 3)

Upstream logic, `server-context.cpp:1850-1900`. Conversation B shares A's system prompt and tools, so slot selection
by prompt overlap put B into A's slot (similarity 0.957). A kept under 50%, so A was saved to the RAM cache. On the
way back, A matched that slot at 79% (over 50%), so the RAM cache wasn't consulted and only the shared prefix was
reused. Design input for `Conversation`: llama-server's `id_slot`, `--slot-prompt-similarity` or `--cache-idle-slots`.

Raw data: `research/p0/results/raw/` (npu-a-*, cpu-a-*, npu-c-*).
