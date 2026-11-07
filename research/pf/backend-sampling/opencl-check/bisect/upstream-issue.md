<!-- Draft for ggml-org/llama.cpp (not posted). Title: -->
# Eval bug: OpenCL coopk DP4A Q4_K GEMM gives wrong results for n = 2–4 on Adreno 830 (Qwen3.5 / Gemma 3 answer garbage)

### Name and Version

b12052 (84d9ce0), also no coopk changes since up to 52c9bad94. Android arm64, built with the Snapdragon toolchain
image `ghcr.io/snapdragon-toolchain/arm64-android:v0.8` and `docs/backend/snapdragon/CMakeUserPresets.json`
(`arm64-android-snapdragon-release`).

### Operating systems

Android 16 (OnePlus 13, CPH2649)

### GGML backends

OpenCL

### Hardware

Snapdragon 8 Elite, `QUALCOMM Adreno(TM) 830`, `OpenCL 3.0 QUALCOMM build: 0800.77 Compiler E031.47.18.56`

### Problem description & steps to reproduce

`kernel_gemm_coopk_q4_k_q8_1_dp4a` (added in #30741), used for Q4_K matmuls with 2–4 activation rows
(`ggml_cl_coopk_dp4a_narrow_on`), returns wrong results on this device. `test-backend-ops -o MUL_MAT -b GPUOpenCL`
with Q4_K × F32 cases at real model shapes fails for every case with n = 2, 3, 4 (NMSE 0.63–0.98), and passes for
n = 1, 8, 19, 64, 512. All Q5_K and Q6_K cases pass. Cases used (added to `make_test_cases_eval`):

```cpp
for (ggml_type t : {GGML_TYPE_Q4_K, GGML_TYPE_Q6_K, GGML_TYPE_Q5_K})
  for (int n : {1, 2, 3, 4, 8, 19, 64, 512})
    for (auto [m, k] : {std::pair{4096, 2560}, {8192, 2560}, {9216, 2560}, {1024, 2560}, {2560, 9216}, {2560, 4096},
                        {3072, 3072}, {1024, 3072}, {8192, 3072}, {3072, 8192}})
      test_cases.emplace_back(new test_mul_mat(t, GGML_TYPE_F32, m, n, k, {1, 1}, {1, 1}));
```

Example: `MUL_MAT(type_a=q4_K,type_b=f32,m=4096,n=2,k=2560): ERR = 0.960919935 > 0.000500000 FAIL`.

- `GGML_OPENCL_COOPK_DP4A=0`: 240/240 pass.
- `GGML_OPENCL_COOPK_Q4_K_BIN=0` and/or `GGML_OPENCL_Q4_K_BIN_DP4A=0`: the same n = 2–4 failures.

Effect in llama-server: for models that make context checkpoints (recurrent, e.g. Qwen3.5 4B Q4_K_M; SWA, e.g.
Gemma 3 4B), the server decodes the last 4 prompt tokens as a separate batch, so these models answer garbage on
OpenCL (`8888888…` for Qwen3.5, an empty reply for Gemma 3) while the CPU is correct. Llama 3.2 3B is correct (no
2–4-token batches for a plain prompt). Speculative decoding would hit it on any Q4_K model.

```
llama-server -m Qwen3.5-4B-Q4_K_M.gguf -dev GPUOpenCL -ngl 99 -c 8192 -b 512 -ub 512 --ctx-checkpoints 8
# "Write one sentence about the sea." (greedy) -> "88888888888888888888888888888888"
GGML_OPENCL_COOPK_DP4A=0 llama-server ...
# -> "The sea is a vast, ever-shifting expanse of blue that breathes rhythmically against the shore, ..."
```

Possibly another compiler version to exclude, like the existing E17 check in `ggml_cl_coopk_dp4a_enabled`.

### First Bad Commit

Presumably #30741 (1b29621ba); not bisected by commit.
