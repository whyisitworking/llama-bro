# OpenMP: does it change CPU speed?

**Question.** The SDK built the CPU backend with llama.cpp's default `GGML_OPENMP=ON`, which needs `libomp.so` (0.97 MB in
the AAR). Upstream's Snapdragon preset, which built every llama-server the phone measurements compared against, turns it
off. Does OpenMP change CPU prefill or decode speed on this phone?

## Method

- **Builds.** Two device-tool builds from the same tree that differ only in `-DGGML_OPENMP`, produced by
  `sdk/native/scripts/build-device-tools.sh` (`LB_DEVICE_TOOLS_NAME` names the output directory, `LB_EXTRA_CMAKE_ARGS` adds
  CMake options). `bench.sh` expects `sdk/native/build/android-device-tools` (OpenMP on) and
  `sdk/native/build/android-device-tools-noomp` (off). The SDK's default is now off (D38), so the "on" arm needs
  `LB_EXTRA_CMAKE_ARGS=-DGGML_OPENMP=ON`.
- **Run.** Qwen3.5-4B-Q4_K_M on the CPU (`-dev none`), `-c 8192 -np 1 --cache-ram 0 --load-mode none --fit off -b 512
  -ub 512`, a fresh load per arm, arms alternating by round, thermal status 2 or lower before each load. Prompt: the first
  8,000 characters of [`../mtp/source.txt`](../mtp/source.txt) (about 2.2K tokens) with a summarise instruction, thinking off,
  128 greedy tokens through `/v1/chat/completions`. Output: one line per arm (round, arm, prompt tokens, prefill t/s,
  predicted tokens, decode t/s).
- Device: OnePlus 13 (CPH2649).

## How to run

```bash
ANDROID_SERIAL=<serial> research/p2/openmp/bench.sh 6      # the argument is the number of rounds (default 2)
```

The script pushes both builds to `/data/local/tmp/lb-omp/{omp,noomp}`, starts `llama-server` on port 18184 over
`adb forward`, and removes the forward on exit.

## Result

Six rounds, medians (range):

| | Prefill t/s | Decode t/s |
|---|---|---|
| OpenMP on | 41.7 (26.9-49.0) | 10.6 (6.1-11.4) |
| OpenMP off | 40.7 (32.5-43.6) | 10.4 (9.2-11.3) |

The medians are within noise. The only two slow runs (decode 6.1 and 6.9 t/s, one with prefill 26.9) were both with OpenMP
on; off never went below 9.2. Two outliers in six runs do not prove that OpenMP causes slow runs. `llvm-readelf -d` on
`libggml-base.so` shows a `libomp.so` dependency only in the default build.

## Findings / decisions

F77 (the SDK's CPU path used OpenMP and the phone baseline did not), with F25 (AAR size), F67 and F72 (CPU comparisons
hold at the median); D38 (`GGML_OPENMP` off, as in upstream's Android preset, dropping `libomp.so`). See
[findings](../../../docs/research/findings.md) and [decisions](../../../docs/decisions.md).

## Notes

Raw output (`bench.out`, `bench2.out`) is not kept. Date of the run: 2026-11-07.
