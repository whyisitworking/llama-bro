# MTP: multi-token prediction per chip and per output kind

**Question.** Does Qwen3.5 4B's built-in MTP head make decoding faster on the CPU, the NPU or the GPU on this phone,
and does the answer depend on what the model writes? Should `Speculative.Auto` turn MTP on?

## Method

- **Device and build.** OnePlus 13 (SM8750). Upstream llama-server from the P0 `P` build (b12052 plus the delta-net
  patch, [P0](../../p0/README.md)) with the SDK's settings: `-c 8192 -np 1 -b 512 -ub 512 --cache-ram 1024
  --ctx-checkpoints 8 --load-mode none --fit off`. Model Qwen3.5-4B-Q4_K_M (with the MTP layer).
- **`source.txt`.** llama.cpp's `docs/build.md`, the document used for every prompt. `mtp.sh` and `mtp2.sh` tokenize it
  through the running server and cut it to 512 or 2048 tokens.
- **`mtp.sh` (T8).** MTP off against `--spec-type draft-mtp --spec-draft-n-max 3`, per chip (`cpu`, `npu`, `gpu`), prompts
  of 512 and 2048 tokens plus a summarise instruction, 256-token `/completion` replies at the model's default sampling,
  seed 42. MTP is a load setting, so each arm is a fresh load; the arm order alternates per round and the script waits for
  thermal status 1 or lower before each round. Environment: `ROUNDS` (default 5), `CHIPS`, `KEEP_LOG` (print the server
  log's tail), `ANDROID_SERIAL` (required). It prints prefill and decode t/s and drafts made and accepted.
- **`mtp2.sh` (follow-up).** Same server and settings, per output kind through `/v1/chat/completions` with thinking off:
  prose (default sampling and greedy) and JSON extraction (greedy and temperature 0.2, top-p 0.95, top-k 20), with arms
  MTP off, draft 3 and draft 4 rotated per round. Environment: `ROUNDS` (default 3), `CHIPS` (default `npu cpu`).

## How to run

```bash
ANDROID_SERIAL=<serial> research/p2/mtp/mtp.sh
ANDROID_SERIAL=<serial> ROUNDS=1 CHIPS=gpu KEEP_LOG=1 research/p2/mtp/mtp.sh   # shows the OpenCL abort
ANDROID_SERIAL=<serial> research/p2/mtp/mtp2.sh
```

## Result

`mtp.sh`, medians at a 2K prompt (summary, default sampling):

| Chip | Prefill off to on | Decode off to on | Drafts accepted |
|---|---|---|---|
| CPU | 43.3 to 40.7 t/s | 10.4 to 6.7 t/s | 46% |
| NPU | 818 to 691 t/s | 14.9 to 12.9 t/s | 42% |
| GPU (OpenCL) | 149 to crash | 13.5 to crash | none |

On the GPU the process aborts on the first drafted batch (`clEnqueueNDRangeKernel` error -54 at `ggml-opencl.cpp:1319`).
`mtp2.sh`, decode medians off, draft 3, draft 4 (t/s):

| Chip | Prose, default sampling | JSON, greedy | JSON, 0.2/0.95/20 |
|---|---|---|---|
| NPU | 14.7, 12.5, 11.6 | 14.4, 19.3, **21.7** (88%, 84% accepted) | 14.3, 19.1, **21.9** |
| CPU | 9.6, 5.8, 5.5 | 9.5, 9.6, 10.2 | 9.5, 11.6, 9.6 |

MTP loses on prose on both chips (about 45% of drafts accepted, so a four-token verify batch yields about 2.3 tokens) and
costs NPU prefill (5-16%). Structured output on the NPU gains 34-51%. llama-server cannot switch MTP per request (the
`speculative.*` request fields are compiled out), so it is a load-time choice. `Speculative.Auto` therefore stays off on
every chip; apps with mostly structured output on the NPU opt in (`Speculative.Mtp(4)`).

## Findings / decisions

F67 (MTP slower on CPU and NPU, crashes OpenCL), F68 (the gain depends on the output); D10, D35. See
[findings](../../../docs/research/findings.md) and [decisions](../../../docs/decisions.md).

## Notes

Raw output (`mtp.out`, `mtp2.out`) is not kept. Re-run `mtp.sh` when the llama.cpp pin moves.
