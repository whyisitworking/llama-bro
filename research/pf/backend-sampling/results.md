# PF T1: backend sampling, off vs on (F70), and the OpenCL side check (F71)

Upstream `llama-server` from `/data/local/tmp/lb-spike/P` (b12052 + delta-net patch), Qwen3.5 4B Q4_K_M, the SDK's
load settings, ~2K-token prompt from `research/p2/mtp/source.txt`, 256-token replies via `/v1/chat/completions`, thinking
off, seed 42. `backend_sampling` is a request field, so one load serves both arms; the arm order alternates per round
and the arms alternate per request. NPU 3 rounds (thermal ≤ 1), GPU and CPU 1 round (thermal ≤ 2). Script `bs.sh`; raw `bs-npu.out` (NPU), `bs.out` (GPU, CPU); replies in
`replies/`, server logs in `logs/` (`npu-debug-lv4.log`: the sampler chain at `-lv 4`). `smoke.out`: the first
1-round NPU try.

## What runs where

`-lv 4` prints the chain with a `+` on each sampler offloaded: on the NPU every sampler in the default chain is
(`+top-k -> +top-p -> +min-p -> +temp-ext -> +dist`); with the field false none is. Warnings name what falls back:

| Chip | Falls back to the CPU |
|---|---|
| NPU (HTP0) | nothing (no warning) |
| GPU (OpenCL) | `top-p`: "device 'GPUOpenCL' does not have support for op ARGSORT" (every request) |
| CPU | n/a (the sampler graph runs on the CPU either way) |

## Speed (t/s; NPU: median [min–max] of 3)

| Chip | Output | Decode off | Decode on | Prefill off | Prefill on |
|---|---|---|---|---|---|
| NPU | prose greedy | 14.93 [14.79–15.02] | 14.86 [14.67–14.89] | 890 [799–900] | 795 [793–879] |
| NPU | prose default | 14.79 [14.57–14.82] | 14.40 [14.38–14.53] | 742 [703–746] | 711 [708–746] |
| NPU | JSON greedy | 14.43 [14.38–14.50] | 14.16 [14.13–14.18] | 689 [670–692] | 677 [676–691] |
| NPU | JSON agent | 14.29 [14.14–14.32] | 13.87 [13.83–14.12] | 655 [650–660] | 652 [652–661] |
| GPU | prose greedy | 14.79 | 13.86 | 167 | 125 |
| GPU | prose default | 12.48 | 12.71 | 107 | 105 |
| GPU | JSON greedy | 12.53 | 12.71 | 106 | 105 |
| GPU | JSON agent | 11.59 | 10.91 | 105 | 95 |
| CPU | prose greedy | 10.81 | 10.20 | 37 | 42 |
| CPU | prose default | 9.93 | 10.23 | 42 | 42 |
| CPU | JSON greedy | 9.88 | 10.29 | 42 | 41 |
| CPU | JSON agent | 8.80 | 8.28 | 26 | 30 |

Prefill follows the request's place in the round (the first is fastest, whichever arm), not the setting.

## Output

- NPU: 12 of 12 pairs byte-identical, greedy and sampled (same seed), 3 rounds.
- CPU: 4 of 4 identical.
- GPU: all 4 differ, but **both arms are garbage**: off gives `8888…`, on gives `otareotare…`. Not backend sampling:
  see the side check.

## Side check: OpenCL output (`opencl-check/oc.sh`, raw `oc.out`)

Greedy, 64 tokens, backend sampling off, a short prompt (19 tokens) and the 2K prompt:

| Model | OpenCL | CPU |
|---|---|---|
| Qwen3.5 4B Q4_K_M | `8888…` for both prompts | correct text for both |
| Llama 3.2 3B Q4_K_M | correct text for both | correct text for both |
