# P2 T4: CPU vs OpenCL (Adreno 830), OnePlus 13 (CPH2649, SM8750)

> **Parent:** [findings F63](../../../docs/sdk-v2/findings.md) · Script: [speed.sh](speed.sh) · raw: [speed.out](speed.out) · 2026-11-05

Upstream llama-server from the P0 spike's `P` build (b12052 + delta-net patch, the SDK's source; its
`libggml-opencl.so` uses the same defaults as `libllamabro-ggml-opencl.so`: embedded kernels, Adreno kernels,
OpenCL 3.0). The SDK's load settings: `-c 4096 -np 1 -b 512 -ub 512 --load-mode none`; `cpu` = `-dev none`,
`opencl` = `-dev GPUOpenCL -ngl 99`. Per run: wall time to `/health` 200, then a 668-token prompt and a 128-token
greedy reply. Arms alternate within each round. **The phone heated up during the run** (thermal status 1 → 3,
battery 40.9 → 43.5 °C), so absolute numbers are on a warm phone.

## Qwen3.5-4B-Q4_K_M

| round (thermal) | arm | ready_s | prefill t/s | decode t/s | reply MD5 |
|---|---|---|---|---|---|
| 1 (1) | cpu | 7.66 | 61.1 | 13.69 | 378807f3 |
| 1 (1) | opencl | **58.90** | 173.8 | 15.18 | fac4e66d |
| 2 (2) | opencl | 17.93 | 173.9 | 14.93 | fac4e66d |
| 2 (2) | cpu | 8.02 | 38.5 | *2.00* | 378807f3 |
| 3 (2) | cpu | 22.09 | 50.0 | 12.97 | 378807f3 |
| 3 (2) | opencl | 18.94 | 130.8 | 13.83 | fac4e66d |

## Llama-3.2-3B-Instruct-Q4_K_M

| round (thermal) | arm | ready_s | prefill t/s | decode t/s | reply MD5 |
|---|---|---|---|---|---|
| 1 (2) | cpu | 6.81 | 70.9 | 12.19 | 4cee8908 |
| 1 (2) | opencl | 16.63 | 174.9 | 16.10 | 4cee8908 |
| 2 (3) | opencl | 16.54 | 174.9 | 16.18 | 4cee8908 |
| 2 (3) | cpu | 6.64 | 56.4 | 16.38 | 4cee8908 |
| 3 (3) | cpu | 6.79 | 56.4 | 16.33 | 4cee8908 |
| 3 (3) | opencl | 16.41 | 174.1 | 16.12 | 4cee8908 |

Italic: a CPU run during throttling. The first OpenCL load of the session took 58.9 s, later ones 16–19 s.

## Vulkan (P2 T5, F64), 2026-11-05

A Vulkan llama-server (`V` on the phone: the SDK's source, `LB_BACKENDS=vulkan`, upstream library names, Vulkan-Headers
and SPIRV-Headers 1.3.275) reports `Vulkan0: Adreno (TM) 830 (19203 MiB, 19203 MiB free)` on a phone with
15.1 GB of RAM. Raw: [speed-vulkan.out](speed-vulkan.out).

- **Q4_K_M (Qwen3.5 4B, Llama 3.2 3B): every load aborts**, 6 of 6: `ggml_vulkan: compute pipeline creation failed for
  mul_mat_vec_q4_k_f32_f32: vk::Device::createComputePipeline: ErrorUnknown`, then `terminating due to uncaught
  exception of type vk::UnknownError`. None of `GGML_VK_DISABLE_INTEGER_DOT_PRODUCT`, `_F16`, `_COOPMAT`, `_MMVQ`,
  `_DOT2`, `_BFLOAT16`, alone or all together, avoids it.
- **Q8_0 (`Qwen3-0.6B-Q8_0.gguf`): loads, but the output is wrong.** Prompt "Hello", 32 greedy tokens: Vulkan
  `'edlyedlyedly…'` (34.8 t/s), CPU `" Question! I'm a bit confused about the problem. Let me try "` (70.1 t/s).

The CPU and OpenCL arms of the same rounds (cooler phone, thermal status 1):

| model | arm | ready_s | prefill t/s | decode t/s |
|---|---|---|---|---|
| Qwen3.5 4B | cpu | 7.9–8.1 | 37–61 | 11.5–13.8 |
| Qwen3.5 4B | opencl | 17.0–18.1 | 173.3 | 15.2–15.3 |
| Llama 3.2 3B | cpu | 5.3–5.5 | 77–84 | 15.3–19.1 |
| Llama 3.2 3B | opencl | 11.8–14.5 | 259–268 | 18.9–19.7 |
