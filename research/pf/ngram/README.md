# n-gram speculation

**Question.** Does upstream's n-gram speculation (`ngram-mod`, `--spec-default`), which drafts from tokens already in the
context with no extra model, speed up Qwen3.5 4B without hurting other output, and does it combine with MTP?

## Method

- **Device and build.** OnePlus 13. Upstream llama-server from the P0 `P` build (b12052 plus the delta-net patch,
  [P0](../../p0/README.md)), Qwen3.5-4B-Q4_K_M, the SDK's load settings (`-c 8192 -np 1 -b 512 -ub 512 --cache-ram 1024
  --ctx-checkpoints 8 --load-mode none --fit off`), a fresh load per arm, `/v1/chat/completions` with thinking off,
  seed 42, 256-token replies. One round per chip; each arm starts at thermal status 2 or lower (a guard was added after
  the NPU reached status 4, about 105 degrees C on the NPU and 70 on the skin, in a first attempt). The GPU was skipped
  because of the OpenCL kernel fault (F71, F75).
- **Arms.** `off`; `mod` is `--spec-default` (ngram-mod, n_match 24, n_min 48, n_max 64); `k4v` is `ngram-map-k4v` with
  upstream's example values (size-n 8, size-m 8, min-hits 2, draft-n-max 64); `mtp` is `draft-mtp` with n-max 4 (NPU
  only); `modmtp` is `--spec-type ngram-mod,draft-mtp` (n-gram first, MTP when it has no draft; NPU only).
- **Output kinds.** The four of [p2/mtp](../../p2/mtp/README.md) (prose and JSON, greedy and sampled, about 2K-token
  prompt from `../../p2/mtp/source.txt`) plus a **code edit** (`code.cpp`, a 146-line source file returned with one function
  renamed; in 256 tokens the reply reaches only the file's header comment, so it measures copying) and **quoting** (three
  verbatim sentences from the document, 84 tokens).
- **`ng.sh`.** Environment: `ROUNDS` (default 3), `CHIPS` (default `npu cpu`), `ARMS` (default per chip), `COOL`,
  `ANDROID_SERIAL` (required). Replies go to `replies/`, per-arm draft statistics to `logs/`.

## How to run

```bash
ANDROID_SERIAL=<serial> ROUNDS=1 research/pf/ngram/ng.sh
ANDROID_SERIAL=<serial> ROUNDS=1 CHIPS=cpu ARMS="off mod" research/pf/ngram/ng.sh
```

## Result

Decode t/s (draft tokens accepted / drafted in parentheses where drafts were made):

| Output | NPU off | NPU mod | NPU mtp | NPU mod+mtp | CPU off | CPU mod |
|---|---|---|---|---|---|---|
| prose greedy | 14.98 | 14.63 | 13.54 | 13.59 | 10.53 | 11.90 |
| JSON greedy | 14.95 | 14.23 (5/152) | 21.88 | 22.12 | 10.18 | 9.43 (0/53) |
| JSON 0.2/0.95/20 | 14.50 | 21.08 (97/252) | 22.84 | 89.98* | 9.69 | 8.23 (66/266) |
| code edit | 14.57 | **105.05** (228/228) | 24.53 | **138.14** | 10.45 | **23.80** (228/228) |
| quoting | 14.36 | 20.26 (29/64) | 23.26 | 29.89 | 10.16 | 9.24 (29/64) |

\* The 0.2/0.95/20 JSON request follows the JSON-greedy one in the same load, and ngram-mod's pool is shared across requests, so it
drafts from the earlier reply; this reflects an app that repeats a task, not a first request.

- Code edits decode 7 times faster on the NPU and 2.3 times on the CPU with every draft token accepted; prose is
  untouched. On the CPU, partly accepted drafts of up to 64 tokens make JSON and quoting 10-15% slower; on the NPU the same
  drafts pay off.
- `k4v` drafted rarely and gained little (NPU JSON 15.0-15.7 against 14.5-15.0) and nothing on code.
- Every greedy reply under `mod` and `k4v` is byte-identical to `off` on both chips. With MTP (`mtp`, `modmtp`) greedy
  prose and JSON drift slightly, as with MTP alone; code and quoting stay identical. No arm crashed.

Full tables: [results.md](results.md).

## Findings / decisions

F72 (n-gram speculation); related F67, F68, F80 (drafting aborts OpenCL); D35 (n-gram is an opt-in, combined with MTP
on the NPU), D10. See [findings](../../../docs/research/findings.md) and [decisions](../../../docs/decisions.md).

## Notes

Raw outputs (`ng-npu-part1.out`, `ng.out`), replies and logs are not kept. The NPU run was split in two because it was
stopped for heat after the first three arms.
