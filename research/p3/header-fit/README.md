# Header-only memory projection and cache symlinks

**Question.** Can the Hub tell before a download whether a model fits the phone by fetching only the GGUF header and
running llama.cpp's own memory projection on a sparse file of the full length? Do relative symlinks, upstream's cache
layout, work in an app's `noBackupFilesDir`?

## Method

- **Stubs.** `make_stub.py SRC DST` copies the bytes of a GGUF up to `GGUFReader.data_offset` (metadata plus tensor
  infos) and truncates the destination to the original size, giving a sparse file whose tensor data is absent. It loads
  `gguf-py` from `sdk/native/third_party/llama.cpp/gguf-py`.
- **Projection.** For each full model and its stub, at contexts 4096 and 16384, with llama.cpp b12052's
  `llama-fit-params` (host build and Android build):

  ```sh
  llama-fit-params -m FILE -c N --fit-print on --load-mode none -np 1 -b 512 -ub 512 --device none
  ```

  On the phone (OnePlus 13) the same arguments run from a directory holding the binaries, with `LD_LIBRARY_PATH` and
  `ADSP_LIBRARY_PATH` set to it, for both `--device none` and `-dev HTP0 -ngl 99`.
- **Models.** Qwen3.5 4B Q4_K_M, Gemma 3 4B QAT UD Q4_K_XL, Llama 3.2 3B Q4_K_M (main models); the Qwen3.5 mmproj F16
  and the Qwen3.5 DFlash Q8_0 as sidecars.
- **Phone sparse files.** `adb push` allocates the whole stub, so the sparse tail is restored on the device with
  `truncate -s HEADER_BYTES STUB` then `truncate -s FULL_BYTES STUB`, and checked with `stat -c '%s %b'`.
- **Symlink.** In the sample app's `noBackupFilesDir`, create `t/blobs/x` and a relative `t/snapshots/c/x` pointing to
  `../../blobs/x`, read it, rename `t` to `t2`, read again, remove. The remote command is passed as one quoted argument
  to `adb shell` so the inner shell runs as the app's user.

## How to run

```bash
python3 research/p3/header-fit/make_stub.py SRC.gguf DST.gguf
sdk/native/build/host/bin/llama-fit-params -m DST.gguf -c 16384 --fit-print on --load-mode none -np 1 -b 512 -ub 512 --device none
```

Phone commands are as described under Method; set `ANDROID_SERIAL=<serial>` for adb.

## Result

| GGUF | Full bytes | Header bytes |
|---|---:|---:|
| Qwen3.5 4B Q4_K_M | 2,834,975,040 | 10,969,408 |
| Gemma 3 4B QAT UD Q4_K_XL | 2,537,530,496 | 6,541,184 |
| Llama 3.2 3B Q4_K_M | 2,019,377,696 | 7,837,984 |
| Qwen3.5 mmproj F16 | 672,423,488 | 17,984 |
| Qwen3.5 DFlash Q8_0 | 685,134,144 | 10,946,880 |

- Every device line of each full main model equals its stub's: 6 host pairs and 12 phone pairs (CPU and NPU, both
  contexts). Qwen at 16384: `HTP0 2856 562 575`, `Host 497 0 36`; Gemma: `HTP0 2465 494 609`, `Host 677 0 67`; Llama:
  `HTP0 2103 1792 290`, `Host 308 0 40`.
- A CLIP projector and a DFlash draft fail as standalone `-m` models, full file and stub alike (upstream rejects a
  projector as a main model; a draft needs `ctx_other`). Sidecars are therefore checked together with their main model
  in the SDK's tests, not inferred from this experiment.
- Restored sparse stubs on the phone allocated 10,981,376 (Qwen), 6,545,408 (Gemma) and 7,847,936 (Llama) bytes.
- The relative symlink read `hi` before and after the rename.

Details: [results.md](results.md).

## Findings / decisions

F84 (the hypothesis, from source), F85 (stub projections equal full-file projections), F86 (relative symlinks survive a
cache-tree move); D39 (cache layout), D42 (a fit check before download). See
[findings](../../../docs/research/findings.md) and [decisions](../../../docs/decisions.md).

## Notes

Date: 2026-11-08. The raw outputs (`sizes.out`, `host.out`, `phone.out`) are not kept. The stub script needs NumPy
and PyYAML in the Python environment.
