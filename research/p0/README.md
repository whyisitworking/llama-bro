# P0: in-process engine, delta-net patch, phone defaults, sampler order

**Question.** Can llama-server's core (`server-context`) run in-process without the HTTP layer and keep its behaviour,
speed and memory (tests T1-T7)? Does a patch to the Hexagon delta-net path remove the NPU prefill penalty that
multi-token prediction (MTP) causes (test A)? Which `n_batch`, `n_ubatch` and cache budget suit a phone (test C)?
Does a different sampler order or prompt-free repetition penalty change quality (test B)?

## Method

- **Device and build.** OnePlus 13 (SM8750, Hexagon v79, Android 16), llama.cpp b12052, preset
  `arm64-android-snapdragon-release`, model Qwen3.5-4B Q4_K_M (with MTP layer). Flags identical across arms:
  `-c 16384 -t 6 -tb 6`; CPU `-dev none -ngl 0`, NPU `-dev HTP0 -ngl 99` (34/34 layers on HTP0).
- **Variants.** `U` is upstream `llama-server`; `H` is `harness/lb-spike.cpp`, which mirrors `server.cpp` `main()` and
  calls the route handlers directly (`--spike-requests FILE`, `--spike-out DIR`); `P` is `U` plus
  `sdk/native/patches/0001-delta-net-head-tail-split.patch` (only `libllama.so` differs); `S` is `U` plus
  `patches/spike-B2-penalties-generated-only.patch` (penalties count generated tokens only; macOS only).
- **Scripts.** `scripts/add-harness.sh` adds the harness to a build copy; `build-android.sh U|P|S` builds in the
  Snapdragon toolchain image; `build-macos.sh U|S` builds with Metal; `phone.sh` pushes builds and models to
  `/data/local/tmp/lb-spike` and checks thermal status; `session.sh u|h NAME REQFILE FLAGS...` runs one request file
  through `llama-server` (`u`, over `adb forward`) or `lb-spike` (`h`) and writes `results/raw/NAME/`; `session.sh cool`
  waits for thermal status 0 or 1. `make-requests.py` generates `requests/` (T2 20 fixed greedy requests, T3 prompts of
  about 512/2048/8192 tokens, T5 cancel, T7 ten-turn chat with tool calls, then a switch and back). `compare.py`
  compares two run folders request by request; `lp-compare.py` compares logprobs; `timings.py` extracts server timings;
  `http-run.py` replays a request file over HTTP.
- **Protocol.** The phone reaches thermal status 3 after about 4 minutes of NPU work, so builds or settings alternate
  within a round and are compared only inside that round. `run-t3.sh` (5 rounds, U and H), `run-a.sh` (3 rounds, U and P,
  MTP on and off), `run-c.sh` and `run-c2.sh` (batch sizes, checkpoint trace, CPU check of the patch), `run-b-pilot.sh`
  (test B on the Mac: GSM8K, 200 problems, seeds 1-3, arms U / B1 / U-full / B2-full via `b-run.py`, analysed by
  `b-analyze.py`).

## How to run

```bash
research/p0/scripts/build-android.sh U            # needs build/src-U (a b12052 copy) and Docker; also P, S
research/p0/scripts/phone.sh push U               # ANDROID_SERIAL=<serial> in the environment
research/p0/scripts/phone.sh push-model Qwen3.5-4B-Q4_K_M.gguf
research/p0/scripts/run-t3.sh                     # also run-a.sh, run-c2.sh
python3 research/p0/scripts/compare.py DIR_A DIR_B
```

`build/` and `data/gsm8k/test.jsonl` are local inputs and are not kept.

## Result

| Test | Outcome |
|---|---|
| T1 size | In-process engine adds 1.27 MB stripped; llama-server's HTTP layer is 6.25 MB ([results/t1-sizes.md](results/t1-sizes.md)) |
| T2 output | 20/20 requests byte-identical, U vs H, on CPU and NPU; NPU runs repeat exactly |
| T3/T4 speed, memory | H within -0.4% to +1.8% of U; peak 742 vs 744 MB (MTP off) |
| T5 cancel | Decode: slot free in 64-136 ms. Prefill: 1.7-2.1 s at `n_batch` 2048 |
| T7 cache | 13/13 turns identical; switching back to chat A re-read 409 of 743 tokens |
| A delta-net patch | NPU prefill with MTP +56/+69/+63% at 564/2368/9883 tokens; CPU +18 to +38%; no change with MTP off; logprob drift p95 0.038 vs 0.041 for upstream's own kernel switch |
| C batch sizes | `n_batch` 512 frees the slot after 0.37-0.44 s instead of 2.0-2.6 s at equal prefill speed; `n_ubatch` above 512 is no faster and 2048 costs 136 MB |
| C cache | One checkpoint is 50.25 MiB; a ten-turn chat saved 1,080 MiB; upstream's worst case is about 14 GB |
| B sampler | At temperature 1.0 and `min_p` 0 the order is a no-op; GSM8K 91.8% (487 runs), 5.2% seed-to-seed flips; test stopped |

Full tables: [results/session1.md](results/session1.md), [results/session2.md](results/session2.md).

## Findings / decisions

F1-F15; D12 (in-process engine, passed), D10 (patch ships), D17 (phone defaults: one slot, 8 checkpoints, 1024 MiB
RAM cache, `n_batch` 512, `n_ubatch` 512), D18 (sampler chain stays upstream's). See
[findings](../../docs/research/findings.md) and [decisions](../../docs/decisions.md).

## Notes

Raw run output (`results/raw/`: chunk logs, replies, server logs, `smaps_rollup`, `pilot.jsonl`) is not kept. The
request files, scripts, harness, patches and the two result files are the record. The scripts resolve paths relative to themselves.
