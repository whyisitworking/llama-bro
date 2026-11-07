# PF T2: n-gram speculation (F72)

Upstream `llama-server` (`/data/local/tmp/lb-spike/P`), Qwen3.5 4B Q4_K_M, the SDK's load settings, a fresh load per
arm, 256-token replies via `/v1/chat/completions`, thinking off, seed 42. One round per chip (the user's proof-first
call); each arm starts at thermal ≤ 2 (`guard()`, added after the NPU reached status 4, 105 °C NPU / 70 °C skin, in the
first try). GPU skipped: OpenCL answers garbage for Qwen3.5 (F71). Script `ng.sh`; raw `ng-npu-part1.out` (NPU off,
mod, k4v; then stopped for heat), `ng.out` (NPU mtp, modmtp; CPU off, mod, k4v); replies in `replies/`, per-arm draft
statistics in `logs/`.

Arms: `off`; `mod` = `--spec-default` (ngram-mod, n_match 24, n_min 48, n_max 64); `k4v` = ngram-map-k4v, upstream's
example (size-n 8, size-m 8, min-hits 2, draft-n-max 64); `mtp` = draft-mtp n-max 4 (F68); `modmtp` = ngram-mod first,
MTP when it has no draft (`--spec-type ngram-mod,draft-mtp`, the priority order in `common/speculative.cpp`).

Output kinds: F68's four, plus **code edit** (`code.cpp`, a 146-line SDK source, back with `release` renamed to
`release_request`; in 256 tokens the reply only reaches the file's header comment, so this measures copying) and
**quoting** (three verbatim sentences from the document, 84 tokens).

## Decode t/s (draft tokens accepted / drafted)

| Output | NPU off | NPU mod | NPU k4v | NPU mtp | NPU mod+mtp | CPU off | CPU mod | CPU k4v |
|---|---|---|---|---|---|---|---|---|
| prose greedy | 14.98 | 14.63 (—) | 14.46 (—) | 13.54 | 13.59 | 10.53 | 11.90 (—) | 10.65 (—) |
| prose default | 15.00 | 14.41 (—) | 14.10 (—) | 11.55 | 12.28 | 10.48 | 10.03 (—) | 10.57 (—) |
| JSON greedy | 14.95 | 14.23 (5/152) | 15.00 (30/64) | 21.88 | 22.12 | 10.18 | 9.43 (0/53) | 9.33 (23/57) |
| JSON agent | 14.50 | 21.08 (97/252) | 15.70 (49/88) | 22.84 | 89.98* | 9.69 | 8.23 (66/266) | 9.29 (34/71) |
| code edit | 14.57 | **105.05** (228/228) | 14.32 (—) | 24.53 | **138.14** | 10.45 | **23.80** (228/228) | 10.49 (—) |
| quoting | 14.36 | 20.26 (29/64) | 14.40 (—) | 23.26 | 29.89 | 10.16 | 9.24 (29/64) | 10.47 (—) |

(—) = no draft made. \* The JSON-agent request follows the JSON-greedy one in the same load, and ngram-mod's pool is
shared across requests (`docs/speculative.md`), so it drafts from the earlier reply: real for an app that repeats a
task, not for a first request. The mod arm's 21.08 has the same effect, smaller.

## Output

Every greedy reply under `mod` and `k4v` is byte-identical to `off`, on both chips (prose, JSON, code, quoting).
With MTP (`mtp`, `modmtp`) greedy prose and JSON drift slightly from `off` (e.g. one JSON item's platform list
loses "iOS"), still sensible; code and quoting stay identical. No arm crashed.
