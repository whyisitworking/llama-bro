# Drafting on the GPU (OpenCL)

**Question.** After F75's switch made OpenCL answer correctly, do MTP and n-gram speculation run on the Adreno 830?

## Method

Run on 2026-11-07.

Upstream `llama-server` from the P0 `P` build (b12052 plus the delta-net patch, see [P0](../../p0/README.md)) in
`/data/local/tmp/lb-spike/P`, on the OnePlus 13 (Adreno 830), Qwen3.5 4B Q4_K_M:

```
-dev GPUOpenCL -ngl 99 -c 4096 -np 1 --load-mode none --fit off
```

with `GGML_OPENCL_COOPK_DP4A=0` in the environment (F75's switch, as the SDK sets it) and one chat request each:

- MTP: `--spec-type draft-mtp --spec-draft-n-max 4`, a short JSON request.
- n-gram: `--spec-default` (ngram-mod), a "repeat this code twice" request.

There is no script; the two commands are the whole experiment. Environment for the device is the one used by
[`../mtp/mtp.sh`](../mtp/mtp.sh) (`LD_LIBRARY_PATH` set to the build's `lib`).

## How to run

```bash
# on the phone, from /data/local/tmp/lb-spike (ANDROID_SERIAL=<serial> for adb):
GGML_OPENCL_COOPK_DP4A=0 LD_LIBRARY_PATH=P/lib P/bin/llama-server -m models/Qwen3.5-4B-Q4_K_M.gguf \
  -dev GPUOpenCL -ngl 99 -c 4096 -np 1 --load-mode none --fit off --spec-type draft-mtp --spec-draft-n-max 4
# repeat with --spec-default in place of the --spec-type pair; then send one /v1/chat/completions request
```

## Result

Both runs abort at `ggml-opencl.cpp:1319` (`clEnqueueNDRangeKernel` error -54, `CL_INVALID_WORK_GROUP_SIZE`) on the
first drafted batch, as F67 found for MTP before F75's switch existed. The switch does not prevent it. A process abort cannot be caught, so drafting is an NPU and CPU feature until
ggml-opencl is fixed. The SDK skips the GPU when drafting is configured (reason in `loadReport`) and `Only(Gpu)` throws
`DeviceUnavailable`. Retry with each llama.cpp upgrade.

## Findings / decisions

F80 (drafting aborts the GPU backend), related F67, F72, F75; D26, D33, D35. See
[findings](../../../docs/research/findings.md) and [decisions](../../../docs/decisions.md).

## Notes

The server logs of both aborts (`mtp-gpu.log`, `ngram-gpu.log`) are not kept.
