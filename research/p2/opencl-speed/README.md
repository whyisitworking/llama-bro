# OpenCL and Vulkan speed against the CPU

**Question.** How does llama-server on the Adreno 830 (OpenCL, and Vulkan) compare with the CPU in load time, prefill
and decode, with the settings the SDK uses?

## Method

- **Device and build.** OnePlus 13 (CPH2649, SM8750). Upstream llama-server from the P0 `P` build (b12052 plus the
  delta-net patch, [P0](../../p0/README.md)), whose `libggml-opencl.so` has the same defaults as the SDK's
  (embedded kernels, Adreno kernels, OpenCL 3.0). The Vulkan arm uses build `V` (same source, `LB_BACKENDS=vulkan`,
  upstream library names, Vulkan-Headers and SPIRV-Headers 1.3.275).
- **Settings.** `-c 4096 -np 1 -b 512 -ub 512 --load-mode none`; `cpu` is `-dev none`, `opencl` is
  `-dev GPUOpenCL -ngl 99`, `vulkan` is `-dev Vulkan0 -ngl 99`.
- **`speed.sh`.** Per arm and round: stop servers, start a fresh server, record wall time to `/health` 200, then one
  668-token prompt (a repeated paragraph plus a summarise instruction) with a 128-token greedy reply and print the
  server's prefill and decode t/s, an MD5 of the reply and the offloaded layer count. Arms rotate per round. Environment:
  `MODEL` (default `Qwen3.5-4B-Q4_K_M.gguf`), `ROUNDS` (default 3), `ARMS` (default `cpu opencl`; `vulkan` is also
  accepted), `ANDROID_SERIAL` (required).
- The phone warmed during the run (thermal status 1 to 3, battery 40.9 to 43.5 degrees C), so absolute figures are for a
  warm phone and are compared within a round.

## How to run

```bash
ANDROID_SERIAL=<serial> research/p2/opencl-speed/speed.sh
ANDROID_SERIAL=<serial> MODEL=Llama-3.2-3B-Instruct-Q4_K_M.gguf research/p2/opencl-speed/speed.sh
ANDROID_SERIAL=<serial> ARMS="cpu opencl vulkan" research/p2/opencl-speed/speed.sh
```

## Result

| Model | Arm | Load | Prefill t/s | Decode t/s |
|---|---|---|---|---|
| Qwen3.5 4B | CPU | 7.7-8.0 s | 39-61 | 13.0-13.7 |
| Qwen3.5 4B | OpenCL | 17.9-18.9 s (58.9 s the first time) | 131-174 | 13.8-15.2 |
| Llama 3.2 3B | CPU | 6.6-6.8 s | 56-71 | 12.2-16.4 |
| Llama 3.2 3B | OpenCL | 16.4-16.6 s | 174-175 | 16.1-16.2 |

- OpenCL gives about 3 times the CPU's prefill and the same decode, at roughly 2.5 times the load time. The first load
  includes driver kernel compilation.
- Reply checksums repeat within OpenCL; for Qwen3.5 they differ from the CPU's. That difference was later traced to a
  wrong kernel (see [pf/backend-sampling](../../pf/backend-sampling/README.md)); this experiment compared checksums only.
- **Vulkan.** The device reports 19,203 MiB on a 15.1 GB phone (an overcount). Every Q4_K_M load aborts, 6 of 6
  (`mul_mat_vec_q4_k_f32_f32` pipeline creation fails with `ErrorUnknown`, then an uncaught `vk::UnknownError`), with
  none of the `GGML_VK_DISABLE_*` switches helping alone or together. A Q8_0 model (Qwen3-0.6B) loads but its output is
  wrong (`'edlyedlyedly...'` against sensible text on the CPU).

Full tables: [results.md](results.md).

## Findings / decisions

F63 (OpenCL speed and load cost), F64 (Vulkan on Adreno 830); D31 (Vulkan out of 1.0), D23, D24, D32. See
[findings](../../../docs/research/findings.md) and [decisions](../../../docs/decisions.md).

## Notes

`speed.out` and `speed-vulkan.out` are not kept. The Vulkan build and the phone's `V` directory are not part of this
folder.
