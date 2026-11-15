# llama.cpp b12352

**Questions.** After the upgrade from b12052 to b12352 (D52): does the delta-net patch still pay on the NPU? Does
drafting run on the GPU now? Why does the phone's CPU encode a BF16 image projector so much slower than the P0 spike
did, and does upstream's open fix (PR #30432) help?

## Method

Run on 2026-11-13, on the OnePlus 13 at thermal status 1 or lower before every run (the scripts wait for it), with
upstream `llama-server` built for the phone. Arms alternate within a round.

- **Patch A/B** (`patch-ab.sh`): b12352's device tools (`sdk/native/scripts/build-device-tools.sh`) with and without
  `0001-delta-net-head-tail-split.patch`, each pushed to its own directory. Qwen3.5 4B Q4_K_M on `HTP0`, MTP on
  (`--spec-type draft-mtp --spec-draft-n-max 3`), the 2,071-token prompt in `pp.json` through `/completion` with
  `n_predict` 1, three rounds.
- **GPU drafting**: b12352's device tools with the OpenCL backend (`LB_EXTRA_TARGETS=ggml-opencl`,
  `LB_EXTRA_CMAKE_ARGS='"-DLB_BACKENDS=hexagon;opencl"'`), `-dev GPUOpenCL -ngl 99`, `GGML_OPENCL_COOPK_DP4A=0`, as
  [p2/gpu-drafting](../p2/gpu-drafting/README.md): MTP, DFlash, and n-gram (`--spec-default`) on a prompt long enough
  for it to draft (a 12-function block repeated twice, 600 tokens).
- **CPU encoder** (`encoder-time.sh`): Qwen3.5 0.8B Q4_K_M on `HTP0` with its BF16 projector on the CPU
  (`--no-mmproj-offload`), the 640x480 red and blue picture in `red-blue.json`. Builds made by `build-at.sh` (upstream's
  CMake, `GGML_CPU_ALL_VARIANTS`, like the SDK) at b12052 and at b12352; then b12352 with its CPU variants rebuilt with
  `-DCMAKE_C_FLAGS=-ffp-model=fast -fno-finite-math-only` (and the same for C++), without the Hexagon backend, whose
  CMake rejects those flags, and copied over the default build's CPU libraries.

## How to run

```bash
export ANDROID_SERIAL=<serial>
research/b12352/build-at.sh b12352            # -> sdk/native/build/bisect/<sha>-out; push it to /data/local/tmp/<dir>
research/b12352/encoder-time.sh /data/local/tmp/<dir> <tag>   # models in /data/local/tmp/lb-b12352/m (see the script)
research/b12352/patch-ab.sh                   # needs the patched and unpatched device tools on the phone
research/b12352/cpu-flags-ab.sh               # needs both CPU builds in /data/local/tmp/cpu-default, cpu-fpfast
research/b12352/bf16-neon-ab.sh               # b12352 with and without patch 0002 in /data/local/tmp/ab-base, ab-neon
```

## Result

| Question | Result |
|---|---|
| Patch, NPU prefill with MTP | 644 t/s patched, 387 t/s unpatched (657/631/644 vs 387/392/382): +66% |
| GPU drafting | MTP, DFlash and n-gram each abort on the first drafted batch: error -54 at `ggml-opencl.cpp:1366`; without drafting the server answers |
| CPU encoder, same build at both commits | b12052 18.3 s, b12352 18.1 s: no upstream change |
| CPU encoder, P0 spike build (upstream's Snapdragon preset) | 5.4 s |
| CPU encoder, b12352 with `-ffp-model=fast` CPU variants | 5.2 s (twice), answers unchanged |
| CPU encoder, b12352 without and with patch 0002 (`bf16-neon-ab.sh`) | BF16 16.5 vs 3.1 s; F16 2.16 s both; CPU text replies identical |
| CPU text, default vs `-ffp-model=fast` (`cpu-flags-ab.sh`) | prefill 63.2 vs 61.2 t/s, decode 14.7 vs 14.3 t/s; 3 of 5 greedy replies differ |

Vulkan was not run: the two upstream fixes [F64](../../docs/research/findings.md) names for a retry are not in b12352.

## Findings and decisions

[F121](../../docs/research/findings.md) (patch), [F123](../../docs/research/findings.md) (CPU encoder),
[F125](../../docs/research/findings.md) (GPU drafting, Vulkan), [F126](../../docs/research/findings.md) (CPU flags), [F127](../../docs/research/findings.md) (patch 0002); [D52](../../docs/decisions.md), [D55](../../docs/decisions.md).
