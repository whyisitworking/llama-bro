# P0 session 1: in-process server-context (H) vs llama-server (U)

> **Parent:** [P0 spike plan](../../../docs/sdk-v2/plans/p0-spike.md) · **Summarised in:** [findings](../../../docs/sdk-v2/findings.md) (D12 tests T1–T7; findings F1–F7)

Date: 2026-11-03. Phone: OnePlus 13 (SM8750, Hexagon v79, Android 16), charging, room AC.
Build: llama.cpp b12052, `arm64-android-snapdragon-release` preset, same build tree for U and H.
Model: Qwen3.5-4B Q4_K_M (with MTP layer), mmproj F16 for the image request. Flags identical for U and H:
`-c 16384 -t 6 -tb 6`, CPU: `-dev none -ngl 0`, NPU: `-dev HTP0 -ngl 99` (verified: 34/34 layers on HTP0, 2.86 GB weights).
Both start with upstream defaults: 4 slots, unified KV.

## Verdict: D12 passes on every test run so far

| Test | Result | Pass? |
|---|---|---|
| T1 size | H adds 1.27 MB stripped (llama-server's HTTP layer: 6.25 MB) | PASS |
| T2 output, CPU | 20/20 byte-identical (content, reasoning, tool calls, JSON schema, image, logprobs, completion tokens, token ids) | PASS |
| T2 output, NPU | U vs U: 20/20 identical (NPU is repeatable). U vs H: 20/20 identical | PASS |
| T3 speed, NPU | H within -0.4% .. +1.8% of U in every cell (table below), inside run-to-run spread | PASS |
| T4 memory, NPU | Peak: U 744 / H 742 MB (MTP off), U 891 / H 889 MB (MTP on) | PASS |
| T5 cancel, NPU | Decode: stream returns at once, slot free in 136 ms (U) / 64 ms (H). Prefill: slot free after 1.67 s (U) / 2.10 s (H), one run each | PASS, see note |
| T6 JSON cost | No measurable cost: H's decode t/s is equal to or above U's | PASS |
| T7 cache | 13/13 turns: identical prompt_n / cache_n, identical answers | PASS |

## T3 detail (NPU; median [min-max]; MTP off n=4 rounds, MTP on n=3)

| MTP | Prompt tokens | Prefill t/s U | Prefill t/s H | Decode t/s U | Decode t/s H |
|---|---|---|---|---|---|
| off | 564 | 724 [716-740] | 730 [728-738] | 13.9 | 14.0 |
| off | 2368 | 835 [830-859] | 836 [828-862] | 14.3 | 14.4 |
| off | 9883 | 717 [715-741] | 719 [714-739] | 12.4 | 12.6 |
| on | 564 | 377 [362-381] | 384 [381-387] | 16.2 | 16.4 |
| on | 2368 | 433 [430-440] | 431 [430-435] | 13.6 | 13.6 |
| on | 9883 | 402 [398-409] | 404 [402-407] | 12.7 | 12.6 |

MTP draft acceptance: 0.5-0.6, identical for U and H. T3 was stopped after round 3/4 to save time.

## Findings for the design (same in U and H, so upstream behaviour)

1. **MTP halves NPU prefill** (724 -> 377 t/s at 564 tokens) and only helps decode on short prompts (+17% at 564 tokens, -5% at 2368). This is what the delta-net patch targets: test A in session 2.
2. **Cancel during prefill takes 1.7-2.1 s on the NPU.** The server applies the cancel between passes of up to `n_batch` (2048) tokens, about 2.8 s of work at 720 t/s. That's slow for a "stop" button in an app. Options: a smaller `n_batch` (costs prefill speed; test C measures it), or a `should_stop` check inside the bridge, or an upstream change.
3. **Switching back to an earlier conversation reprocessed it** (t7-a-back: 409 tokens processed, 334 cached, out of 743). The RAM cache / slot selection didn't restore conversation A after one turn of conversation B. To investigate before §8's cache defaults are set.
4. **A one-off CPU comparison was misleading** (H looked 25% slower because it ran second on a warmer phone). Alternating repeated runs removed it.

Raw data: `spike/p0/results/raw/` (cpu-t2-*, npu-t2-*, npu-t3-*, npu-t5-*, npu-t7-*).
