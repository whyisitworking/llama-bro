# F75: why OpenCL answers Qwen3.5 4B with `8888…` (F71)

> **Parent:** [F75 in the findings register](../../../../../docs/research/findings.md) · **Related:** F71, [`../oc.sh`](../oc.sh)
> **Status:** done 2026-11-06. OnePlus 13 (CPH2649, Adreno 830, driver `0800.77`, compiler `E031.47.18.56`), b12052.

## Answer

ggml-opencl's **cooperative-K DP4A GEMM for Q4_K** (`kernel_gemm_coopk_q4_k_q8_1_dp4a`, added upstream in
ggml-org/llama.cpp#30741) gives wrong results on this phone. ggml-opencl runs it only for matmuls with **2 to 4
activation rows** (`ggml_cl_coopk_dp4a_narrow_on`: `ne1 >= 2 && ne1 <= 4`), on Adreno A8X/X2E unless the compiler is
E17. `GGML_OPENCL_COOPK_DP4A=0` turns it off, and then Qwen3.5 answers correctly on OpenCL.

Qwen3.5 hits it and Llama doesn't because of how llama-server splits the prompt. For a model with recurrent state,
llama-server sends the **last 4 prompt tokens as a separate batch** so it can save a checkpoint
(`tools/server/server-context.cpp`, "process the last few tokens of the prompt separately", offsets `{4 + n_ubatch, 4}`).
It does the same for SWA models, so Gemma 3 4B fails too (§5). So every Q4_K matmul in every layer of that 4-row batch is garbage, the first sampled token is already wrong, and
the recurrent state carries the error into the rest of the reply. Llama 3.2 (attention only) makes no such batch on a
plain prompt, so its only batches are 1 row (decode) or the whole prompt. Speculative decoding (MTP, n-gram, draft
models) verifies 2–4 tokens per step, so it would take the same kernel on **any** Q4_K model, Llama included.

The delta-net patch is not involved: unpatched b12052 (`lb-spike/U`) gives the same `8888…`.

## Evidence

### 1. The SDK does the same

`DeviceChoiceDeviceTest#onlyGpuLoadsAndIsDeterministic` (`DevicePolicy.Only(DeviceKind.Gpu)`, the SDK's own OpenCL
AAR), with the reply now logged: `Only(Gpu) greedy16 = "8888888888888888"`. The test passes, because it only checks
that two replies match.

### 2. Bisection on upstream llama-server ([`ob.sh`](ob.sh): short prompt, greedy, 32 tokens, the SDK's load settings)

`GGML_OPENCL_OPFILTER` is a regex of op names OpenCL refuses (they run on the CPU); `-ot` keeps named weights on the CPU.

| Round | Arm | Reply | Reading |
|---|---|---|---|
| 1 | `U` (unpatched b12052) / `P` (patched) | `8888…` / `8888…` | not the patch |
| 1 | OPFILTER `GATED_DELTA_NET`, `SSM_CONV`, all Qwen3.5-only ops; fusion off | `8888…` each | not the recurrent ops |
| 2 | OPFILTER `MUL_MAT` | "The sea is a vast, ever-changing expanse…" | **a matmul** |
| 2 | tiled Q6_K / Q4_K GEMV off; `-ot ssm_out` (Q5_K); `-ot ssm_alpha,ssm_beta` (F32) | `8888…`, `8888…`, half-coherent, `8888…` (the two `-ot` arms show as `(none)` in the raw file) | no single weight group |
| 3 | `ssm_out` on CPU plus one more group each | garbage of different kinds | several groups are wrong |
| 4 | every layer weight on CPU (only lm_head on GPU) | correct | lm_head fine |
| 4 | only `ffn_down` / `attn_qkv,attn_gate` / attention on GPU | `3` / `nih` / `back` | each breaks it |
| 4 | only `ssm_out` (Q5_K) on GPU | correct, the CPU's words | Q5_K fine |
| 5 | only the Q6_K `ffn_down` / Q6_K `attn_qkv` layers on GPU | correct | Q6_K fine |
| 5 | only Q4_K `ffn_down` / Q4_K `attn_qkv` / `attn_gate` (all Q4_K) on GPU | slightly off / slightly off / `pare` | **Q4_K** |
| 6 | `GGML_OPENCL_Q4_K_BIN_DP4A=0` (prefill GEMM), whole model and `attn_gate` alone | `8888…` / `pare` | not the prefill GEMM |

### 3. Activations are normal ([`actstat/`](actstat/), CPU on the Mac)

The Q8_1 activation quantizer stores each 32-value block sum as `half`; overflow (> 65504) would also give garbage.
The largest block sum of any matmul input is 149 for Qwen3.5 (`ffn_down`) and 630 for Llama, so no overflow. Not the
cause.

### 4. `test-backend-ops`, OpenCL vs CPU, op by op ([`tbo-cases.cpp.txt`](tbo-cases.cpp.txt))

Cases added to a scratch copy of b12052's `tests/test-backend-ops.cpp` (enabled by `LB_F72=1`, the name the runs
used): Q4_K, Q6_K, Q5_K × F32 with Qwen3.5's and Llama's matmul shapes, n = 1, 2, 3, 4, 8, 19, 64, 512.
Built with the Snapdragon toolchain image and preset like the phone's server, run in `lb-spike/T`.

| Run | Result |
|---|---|
| default | **210/240**: the 30 failures are every Q4_K case with **n = 2, 3, 4**, Llama's shapes too (NMSE 0.63–0.98). n = 1, 8, 19, 64, 512 pass; every Q5_K and Q6_K case passes |
| `GGML_OPENCL_COOPK_Q4_K_BIN=0`, `GGML_OPENCL_Q4_K_BIN_DP4A=0`, both | the same n = 2–4 failures (the next kernel in line fails too) |
| `GGML_OPENCL_COOPK_DP4A=0` | **240/240** |

### 5. The fix on the full model

| Run | Reply |
|---|---|
| Qwen3.5, OpenCL, `GGML_OPENCL_COOPK_DP4A=0`, short | "The sea is a vast, ever-shifting expanse of blue that breathes rhythmically against the shore…" |
| Qwen3.5, OpenCL, same, ~2K-token prompt | "The provided document outlines the process for building the `llama.cpp` project locally, which serves as the core librar…", **word for word the CPU's** |
| Llama 3.2 3B, OpenCL, same, short and long | as before the switch (F71) |
| Qwen3.5, OpenCL, `GGML_OPENCL_COOPK_Q4_K_BIN=0` | `8888…` |
| Gemma 3 4B (UD-Q4_K_XL), OpenCL, default | `''` (empty: broken too; SWA models also get the 4-token checkpoint batch) |
| Gemma 3 4B, OpenCL, `GGML_OPENCL_COOPK_DP4A=0` | "The vast, restless sea stretches out to the horizon, a mesmerizing blend of blue and green." |
| Gemma 3 4B, CPU | "The sea stretches endlessly, a vast and restless expanse of blue mystery." |

### 6. Upstream

`gemm_coopk_q4_k_q8_1_dp4a.cl` has one commit upstream (#30741, 2026-10-19). None of the seven ggml-opencl commits
after b12052 (to 52c9bad94, 2026-11-06) touches coopk. No upstream issue found (searched "coopk" and
"opencl qwen3.5 garbage").

## Files

- `ob.sh`: one arm per argument (`BIN=U`, `DEV=…`, `EXTRA=…`, `LONG=1`). Port 8097; kills only the server it started.
  (`round1-invalid-port-reuse.out` is a first try where the old server kept the port: ignore it.)
- `round1.sh`…`round6.sh` and their `.out`, `smoke.out`, `coopk-*.out`.
- `tbo-*.out`, `tbo-cases.cpp.txt`: the `test-backend-ops` runs and the added cases.
- `actstat/`: the activation-statistics tool (eval callback) and its output for both models.
