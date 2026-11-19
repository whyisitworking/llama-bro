# Vulkan on the Adreno 830, retried at b12479

**Question.** Why does ggml-vulkan fail on the test phone's Adreno 830 (F64, D31), and can a patch the SDK carries make
it run correctly? If it runs, how does it compare with OpenCL and the CPU?

**Answer.** Four patches make it run with correct output ([`patches/`](patches/), `vk1`-`vk4`), but it is slower than both. Prompts run about 4
times slower than on the CPU and about 14 times slower than on OpenCL. Replies are a little slower than on the CPU. It
does load fastest. Details and numbers are in F174.

## Method

- **Device.** OnePlus 13 (CPH2649, Android 16, build 16.0.10.600). Adreno 830, driver `V@0800.77` (from SurfaceFlinger's
  GLES line). ggml reports `warp size: 64 | shared memory: 32768 | int dot: 0 | matrix cores: none`.
- **Build.** [`build.sh`](build.sh) builds upstream llama.cpp at the pin (b12479) for the phone on the Mac: llama-server,
  llama-bench, test-backend-ops, the CPU variants and both GPU backends, with upstream's library names. It uses NDK
  29.0.14206865, Vulkan-Headers and SPIRV-Headers 1.3.275 from `fetch-deps.sh`, and the NDK's `glslc`. One directory
  serves all three arms. Builds compared:
  - `vk-base`: the pin with patches 0001-0008.
  - `vk-qc`: `vk-base` plus Qualcomm's three upstream pull requests (`vk1`-`vk3`).
  - `vk-fix`: `vk-qc` plus `vk4`, which is what `build.sh` builds by default.
- **Ops.** [`run.sh`](run.sh) runs one command on the phone under the device lock. Op tests use
  `test-backend-ops test -b Vulkan0 -o <OP> [-p <filter>]`, which compares each case with the CPU. A failed pipeline
  aborts the process, so each quant type ran on its own.
- **Bisection.** [`q4k-variant.py`](q4k-variant.py) writes variants of `mul_mat_vec_q4_k.comp`. Each variant was rebuilt
  (`cmake --build <build> --target ggml-vulkan`, about a second) and copied over the library on the phone. Then one
  `type_a=q4_K` MUL_MAT case was run.
- **Models.** [`speed.sh`](speed.sh) follows the method of [`p2/opencl-speed`](../p2/opencl-speed/README.md). The server
  runs with `-c 4096 -np 1 -b 512 -ub 512 --load-mode none`, and each arm adds its device flags: `-dev none` (CPU),
  `-dev GPUOpenCL -ngl 99` or `-dev Vulkan0 -ngl 99`. Each run measures the wall time to `/health`, then sends one
  668-token prompt and asks for a 128-token greedy reply. The arms rotate within each round, and the reply text is saved
  so replies can be compared word by word. Models: Llama 3.2 3B Q4_K_M, Qwen3.5 4B Q4_K_M and Phi-4-mini Q8_0.

## How to run

```bash
bash sdk/native/scripts/prepare-llama.sh sdk/native/build && bash research/vulkan-adreno/fetch-deps.sh
```

```bash
research/vulkan-adreno/build.sh vk-fix
```

```bash
ANDROID_SERIAL=<serial> research/vulkan-adreno/run.sh vk-fix './test-backend-ops test -b Vulkan0 -o MUL_MAT -p "type_a=q4_K,"'
```

```bash
ANDROID_SERIAL=<serial> BUILD=vk-fix ROUNDS=3 research/vulkan-adreno/speed.sh
```

To reproduce the failures, build without the patches: `research/vulkan-adreno/build.sh vk-base sdk/native/build/llama.cpp`.

The patches are kept here, not in `sdk/native/patches/`, because Vulkan is not in the SDK's build (D31). At a llama.cpp
upgrade they are not checked; a retry re-applies them by hand and drops any that upstream has merged.

## Steps and results

1. **The pin still fails, and worse than at b12052.** On `vk-base`:
   - q4_K aborts at its first case: `compute pipeline creation failed for mul_mat_vec_q4_k_f32_f32: ErrorUnknown`, then
     an uncaught `vk::UnknownError`.
   - f16, q4_0, q8_0, q5_K and q6_K also abort on their own mat-vec pipelines after a few cases. The cases that ran
     before the abort were wrong (error 0.6-2.1 against a limit of 0.0005).
2. **Qualcomm's upstream fixes.** All three pull requests come from one Qualcomm engineer, and all apply cleanly:
   - [#30824](https://github.com/ggml-org/llama.cpp/pull/30824): RMS_NORM with a runtime loop count. Merged after the
     pin as `401563f`.
   - [#30826](https://github.com/ggml-org/llama.cpp/pull/30826): no subgroup arithmetic on Qualcomm's driver. Open and
     approved; head `3bae17b`.
   - [#30821](https://github.com/ggml-org/llama.cpp/pull/30821): no 8-bit vector pack or unpack. Open; head `8672ff2`.

   With them (`vk-qc`), MUL_MAT passes for f16 (298/298), q4_0 (104/104), q8_0 (105/105), q5_K (85/85) and q6_K
   (63/63). q4_K still fails to create its pipeline, in both MUL_MAT and MUL_MAT_ID.
3. **Bisecting the q4_K shader.** Each row is one variant of `mul_mat_vec_q4_k.comp`.

   | Variant | Pipeline |
   |---|---|
   | 32-bit A loads replaced by 16-bit loads (as in q5_K) | fails |
   | The 16-deep `smin` fma chain replaced by q5_K's form | fails |
   | No `smin`; or no `sx..sw`; or no scale unpack; or no quant unpack; or the row loop not unrolled | fails |
   | Empty loop body | compiles |
   | Reads only A (`dm`, or one 16-bit scale) | compiles |
   | Reads only one `data_b_v4` element of B | **fails** |
   | B read as two `data_b_v2` loads (`b2`) or four scalar `data_b` loads (`b1`) | **compiles; q4_K MUL_MAT 116/116, MUL_MAT_ID 110/110** |

   The driver fails on this shader's `vec4` load of B. Issue [#28635](https://github.com/ggml-org/llama.cpp/issues/28635)
   found that it compiles with a workgroup size below 32 or with no specialization constants, and that the same binary
   works on an Adreno 840 with driver 0.842.46. The fix keeps the vectorized load as two `vec2` loads, as q5_K already
   reads B.
4. **First model run: the device is lost.** Llama 3.2 3B loaded in 3.4 s. Then the prompt ended in
   `ErrorDeviceLost`: the first 512-token batch ran at 56 t/s, and the next one ran past the driver's limit.
   - Large matmuls run at about 115-145 GFLOPS (`test-backend-ops perf`, m=4096, n=512, k=14336: about 0.5 s each). At
     that speed, upstream's 200 GFLOP per submission takes about 1.7 s.
   - llama-bench with `-p 512`, flash attention off: the default loses the device, `-ub 128` works, and at most 4
     nodes per submit (`GGML_VK_MAX_NODES_PER_SUBMIT=4`) works at `-ub 512`.
   - Qwen3.5 4B lost the device even with a 20 GFLOP cap. ggml counts only matmul FLOPs, and the gated delta net counts
     as none. By node cap, at `-p 512`: 100 nodes lose the device, while 64, 32, 16, 8 and 1 work. Decode for 32
     tokens: 13.26 t/s at 100 nodes, 13.16 at 16, 13.09 at 8, 11.76 at 1.
   - FLASH_ATTN_EXT fails 12 prompt-sized cases (32-64 queries over 1024-2048 keys, error about 1.0) and then loses the
     device inside one op. A decode case (1 query, 4096 keys) takes 0.35 ms.
5. **`vk4-adreno-830.patch`.** For Qualcomm's proprietary driver:
   - B is read as two `vec2` loads in the q4_K mat-vec (for every vendor; q5_K already reads it that way);
   - at most 20 GFLOP and 16 nodes per submission;
   - FLASH_ATTN_EXT reported unsupported, so llama.cpp's automatic setting turns flash attention off.

   All other ops a Llama or Qwen3.5 graph uses pass on `vk-fix`: GET_ROWS 240, RMS_NORM 61, MUL 94, ADD 116, ROPE 510,
   SET_ROWS 319, CPY 258, SOFT_MAX 214, CONT 91, SCALE 4, RMS_NORM_MUL_ADD 45, ADD_RMS_NORM 25, MUL_MAT_ID 1162,
   GATED_DELTA_NET 43, SSM_CONV 60 and L2_NORM 31 cases.
6. **Models on `vk-fix`.** Llama 3.2 3B Q4_K_M, 3 rounds, phone at thermal status 1-2.

   | Arm | Ready | Prefill t/s | Decode t/s | Reply |
   |---|---|---|---|---|
   | CPU | 5.9-6.2 s | 82-84 | 18.1-18.5 | `986602e7` every round |
   | OpenCL | 12.9-14.8 s (68 s on the first load) | 269-271 | 19.7-20.0 | `4cee8908` every round |
   | Vulkan | 3.9-4.5 s | 19.5-19.6 | 16.6-16.8 | `4cee8908` every round, the same reply as OpenCL |

   These rounds ran before the 16-node cap was added. That cap costs about 1% of decode (step 4). Re-run with the cap,
   Vulkan's replies were unchanged, but the phone had throttled: CPU decode fell to 3.4-7.7 t/s, so those rounds aren't
   used.

   One round each of the other models:

   | Model | Arm | Ready | Prefill t/s | Decode t/s | Reply |
   |---|---|---|---|---|---|
   | Qwen3.5 4B Q4_K_M | CPU | 8.5 s | 62 | 13.3 | thinks, then summarizes |
   | Qwen3.5 4B Q4_K_M | Vulkan | 7.8 s | 14.5 | 10.9 | coherent; the same first 38 words as the CPU |
   | Qwen3.5 4B Q4_K_M | OpenCL | | | | `8888…` (F71's wrong kernel) |
   | Phi-4-mini Q8_0 | CPU | 12.8 s | 82.5 | 10.9 | coherent |
   | Phi-4-mini Q8_0 | Vulkan | 8.0 s | 12.5 | 7.5 | coherent; the same first 37 words as the CPU (F64's `edlyedly…` is gone) |

   Llama's GPU replies keep the CPU's first 26 words and then choose other, equally sensible words.

## Findings / decisions

F174 (this experiment). It follows F64, which led to D31 (Vulkan out of 1.0). Whether Vulkan returns to the build is
open: it now runs correctly, but it is slower than both shipped GPU and CPU routes.

## Notes

Raw output is not kept: server logs, replies and test-backend-ops output. The builds, the phone's `/data/local/tmp/lb-vk`
directory and the models are not part of this folder. On the phone, the models came from `/data/local/tmp/lb-spike/models`
and Phi-4-mini from `/sdcard/Download`.
