# Backend sampling, and the OpenCL output problem it exposed

**Question.** Does moving the sampler chain into the backend graph (`backend_sampling`, a per-request field) speed up
decoding on the NPU, GPU or CPU, with the same replies? A side check then asked why the GPU's replies were garbage in both
arms, which led to a bisection of the OpenCL backend ([opencl-check](opencl-check/), [bisect](opencl-check/bisect/)).

## Method

- **Device and build.** OnePlus 13 (SM8750, Adreno 830, Hexagon v79). Upstream llama-server from the P0 `P` build
  (b12052 plus the delta-net patch, [P0](../../p0/README.md)) in `/data/local/tmp/lb-spike/P`, Qwen3.5-4B-Q4_K_M, the
  SDK's load settings (`-c 8192 -np 1 -b 512 -ub 512 --cache-ram 1024 --ctx-checkpoints 8 --load-mode none --fit off`).
- **`bs.sh`.** One load per chip and round serves both arms (the setting is per request). Four output kinds through
  `/v1/chat/completions`, thinking off, seed 42, 256-token replies, about 2K-token prompt from
  `../../p2/mtp/source.txt`: prose greedy, prose default sampling, JSON greedy, JSON at 0.2/0.95/20. For each, one request
  with `backend_sampling` false and one with true; arm order alternates per round. Replies are saved to `replies/` and
  compared byte for byte. Environment: `ROUNDS` (default 3), `CHIPS` (default `npu gpu cpu`), `COOL` (thermal status to
  wait for, default 2), `ANDROID_SERIAL` (required). NPU ran 3 rounds (thermal 1 or lower), GPU and CPU 1 round.
- **`opencl-check/oc.sh`.** For Qwen3.5 4B and Llama 3.2 3B, OpenCL and CPU, a 19-token prompt and the 2K-token prompt,
  greedy, 64 tokens, backend sampling off; prints the start of each reply.
- **Bisection** ([bisect/results.md](opencl-check/bisect/results.md)). `bisect/ob.sh [model] 'ENV=...' ...` loads one
  server per environment setting on port 8097 (`BIN` selects the build directory, `DEV`, `EXTRA`, `LONG=1` the long prompt)
  and prints a 32-token greedy reply; `round1.sh` to `round6.sh` are the six rounds. They use `GGML_OPENCL_OPFILTER` (op
  names OpenCL refuses, so they run on the CPU), `-ot` tensor overrides, and the kernel switches. `actstat/` is a CPU tool
  (an eval callback; build with `cmake -S actstat -B build -DLLAMA_SRC=<llama.cpp>`) that reports each matmul input's
  largest 32-value block sum, to rule out fp16 overflow in the Q8_1 quantizer. `tbo-cases.cpp.txt` holds the cases added to
  `tests/test-backend-ops.cpp` (enabled by `LB_F72=1`): Q4_K, Q5_K and Q6_K by F32 at both models' matmul shapes with
  n = 1, 2, 3, 4, 8, 19, 64, 512, built in the Snapdragon image and run on the phone. `upstream-issue.md` is a draft
  issue with the reproduction.

## How to run

```bash
ANDROID_SERIAL=<serial> research/pf/backend-sampling/bs.sh
ANDROID_SERIAL=<serial> research/pf/backend-sampling/opencl-check/oc.sh
ANDROID_SERIAL=<serial> research/pf/backend-sampling/opencl-check/bisect/round2.sh
# one arm with the switch (each argument after the model is one environment setting; "-" means none):
ANDROID_SERIAL=<serial> research/pf/backend-sampling/opencl-check/bisect/ob.sh Qwen3.5-4B-Q4_K_M - GGML_OPENCL_COOPK_DP4A=0
```

## Result

- **Where it runs.** On the NPU every sampler of the default chain is offloaded (`-lv 4` marks each with `+`). On OpenCL
  `top-p` falls back to the CPU (`ARGSORT` unsupported). On the CPU nothing moves.
- **Speed.** NPU decode is lower with it on for every output kind, by 0.5% (prose greedy, 14.86 against 14.93 t/s) to 3%
  (JSON at 0.2/0.95/20, 13.87 against 14.29); never higher. CPU and GPU differ in both directions within noise. NPU replies: 12 of
  12 pairs byte-identical, sampled ones too; CPU 4 of 4.
- **GPU output.** Both arms answered garbage (`8888...` off, `otareotare...` on), and so did every OpenCL reply for
  Qwen3.5 (Llama 3.2 3B was correct). The cause is ggml-opencl's cooperative-K DP4A Q4_K kernel
  (`kernel_gemm_coopk_q4_k_q8_1_dp4a`, used only for matmuls with 2 to 4 activation rows; compiler E031.47.18.56 on this
  phone). llama-server sends the last 4 prompt tokens as their own batch for models with checkpoints (recurrent or
  sliding-window), so Qwen3.5 and Gemma 3 break; Llama 3.2 never makes such a batch. `test-backend-ops` failed exactly the
  30 Q4_K cases with n = 2, 3, 4 (210 of 240 passed); `GGML_OPENCL_COOPK_DP4A=0` gave 240 of 240 and, on the full model,
  correct replies (Qwen3.5 word for word the CPU's; Gemma 3 from empty to correct). The delta-net patch, the recurrent ops,
  fusion, the other Q4_K kernels and fp16 overflow (largest block sum 149 for Qwen3.5, 630 for Llama) were ruled out.
- No upstream commit after b12052 (to 52c9bad94, 2026-11-06) touches the kernel and no issue was found.

## Findings / decisions

F70 (backend sampling never beats off; defaults unchanged), F71 (OpenCL answers garbage for Qwen3.5), F75 (the cause and
the switch); D32 (device kinds). The SDK sets `GGML_OPENCL_COOPK_DP4A=0` before loading OpenCL. See
[findings](../../../docs/research/findings.md) and [decisions](../../../docs/decisions.md).

## Notes

Raw outputs are not kept: `bs.out`, `bs-npu.out`, `smoke.out`, replies, server logs, and in the bisection the
`round*.out`, `tbo-*.out`, `coopk-*.out`, `gemma.out` files. The `actstat` result files for both models
(`llama-cpu.txt`, `qwen-cpu.txt`) are kept. The issue text is a draft and was not posted. Date: 2026-11-06.
