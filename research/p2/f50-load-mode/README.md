# F50 load mode: mmap, `--load-mode none`, direct I/O and an unmap patch

**Question.** With upstream's default load mode (mmap), a CPU-loaded Qwen3.5 4B holds about 5.5 GB PSS: the mapped GGUF
and the CPU backend's repacked copy of the weights (F50). Which load mode keeps one copy of the weights, and what does
it cost in load time and speed?

## Method

- **Device and build.** OnePlus 13 (CPH2649, SM8750). llama-server from the P0 `P` build (b12052 plus the delta-net
  patch, see [P0](../../p0/README.md)) in `/data/local/tmp/lb-spike/P`, `-c 4096 -np 1 -dev none`, default threads.
- **Models.** Qwen3.5-4B-Q4_K_M (2.83 GB) and Llama-3.2-3B-Instruct-Q4_K_M (2.02 GB).
- **`ab.sh`.** Per arm: stop any server (by exact process name), start `llama-server -lm MODE`, record wall time to
  `/health` 200 (`ready_s`), the `smaps_rollup` split (Rss, Pss, Pss_Anon, Pss_File), peak RSS (`VmHWM`), then one
  64-token greedy `/completion` (decode and prefill t/s, MD5 of the reply) and PSS again. Arms rotate by one position per
  round. Environment: `MODEL` (file under `models/`), `ROUNDS` (default 3), `ARMS` (default `auto none`), `V` (build
  directory, default `P`). An arm may be `VARIANT:MODE`, which selects another build directory on the phone.
- **Arms.** `auto` (mmap), `none` (read, no mapping), `dio` (direct I/O), and with the patch build `M`: `M:auto`.
  The file was in the page cache for every run, so loads are warm; the cache could not be dropped without root.

## How to run

```bash
ANDROID_SERIAL=<serial> ROUNDS=5 research/p2/f50-load-mode/ab.sh
ANDROID_SERIAL=<serial> ARMS="auto none dio" ROUNDS=4 research/p2/f50-load-mode/ab.sh
ANDROID_SERIAL=<serial> ARMS="P:auto P:none M:auto" MODEL=Llama-3.2-3B-Instruct-Q4_K_M.gguf research/p2/f50-load-mode/ab.sh
```

`M` is the `P` tree with `unmap-copied-weights.patch` applied (only `llama-model-loader.cpp` differs).

## Result

| Qwen3.5 4B, CPU | Load (mean) | PSS | Peak RSS |
|---|---|---|---|
| mmap (`auto`) | 5.6-5.8 s | 5,426-5,497 MiB | 5,503 MiB |
| `none` | 6.9-7.0 s | 3,391-3,402 MiB | 3,403 MiB |
| `dio` | 7.4 s | 3,395-3,403 MiB | not recorded |
| mmap + unmap patch v2 | 5.8 s | 3,383-3,395 MiB | 5,370-5,398 MiB |

- Llama 3.2 3B: PSS 4,355 (mmap) against 2,746-2,754 MiB (`none`, patch); load 4.66 s, 5.45 s and 4.74 s.
- `none` adds about 1 s of load and removes 1.6-2.1 GB of resident memory; decode and prefill are equal within noise.
  The extra anonymous memory is about 0.5 GB (Qwen) and 0.3 GB (Llama), the tensors that are not repacked.
- `dio` is slower than `none` at the same memory. In mmap mode each repacked tensor is copied straight from mapped
  pages; without mmap it is read into a staging buffer first, one more pass over 2.8 GB.
- The first patch draft (unmap each tensor after its copy) segfaulted on every load: both models tie `output` to
  `token_embd`, so one file range is loaded twice, and unmapping the copy removed the embedding's pages. Version 2
  records ranges copied out of the mapping and ranges still read from it and unmaps the difference after the last load.
  All reply MD5s match across arms (`3ec14de6` Qwen, `a4e51c12` Llama).
- Round-to-round outliers (one 18.5 s load, one low-decode round) are single events. Leftover servers from a
  `pkill -f` that killed its own shell invalidated some Llama rounds; those were discarded and the script now matches
  the process name exactly.

Full tables: [results.md](results.md).

## Findings / decisions

F50 (second copy), F58 (`--load-mode none`), F59 (`dio` loses; patch idea), F60 (patch v2 and its tied-embedding
trap); D30 (the SDK loads with `none`; the patch is not shipped and is kept in `unmap-copied-weights.patch` as a
candidate for an upstream change). See [findings](../../../docs/research/findings.md) and
[decisions](../../../docs/decisions.md).

## Notes

The raw output files (`*.out`) are not kept. The patch is not part of `sdk/native/patches/`; only
`0001-delta-net-head-tail-split.patch` is applied to the shipped build.
