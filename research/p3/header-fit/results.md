# P3 T1: header-only memory projection and cache symlinks

2026-11-08. llama.cpp b12052; OnePlus 13 (HTP0); host and Android `llama-fit-params` built from the P3 worktree. `make_stub.py` used a Python environment (NumPy 1.26.4, PyYAML 6.0.3); no package was installed.

## Header sizes

`python research/p3/header-fit/make_stub.py SRC DST` copies through `GGUFReader.data_offset` and truncates to the original size. Exact output is in `sizes.out`.

| GGUF | Full bytes | Header bytes |
|---|---:|---:|
| Qwen3.5 4B Q4_K_M | 2,834,975,040 | 10,969,408 |
| Gemma 3 4B QAT UD Q4_K_XL | 2,537,530,496 | 6,541,184 |
| Llama 3.2 3B Q4_K_M | 2,019,377,696 | 7,837,984 |
| Qwen3.5 mmproj F16 | 672,423,488 | 17,984 |
| Qwen3.5 DFlash Q8_0 | 685,134,144 | 10,946,880 |

## Projection

Host command for each full model and stub at contexts 4096 and 16384:

```sh
sdk/native/build/host/bin/llama-fit-params -m FILE -c N --fit-print on --load-mode none -np 1 -b 512 -ub 512 --device none
```

Phone command, from `/data/local/tmp/lb-contract`, with `LD_LIBRARY_PATH` and `ADSP_LIBRARY_PATH` set to `/data/local/tmp/lb-contract/bin`, uses the same arguments and either `--device none` or `-dev HTP0 -ngl 99`. Exact output is in `host.out` and `phone.out`. For all three main models, every device line of the full file equals its stub: 6 host pairs and 12 phone pairs (CPU/NPU at both contexts). For example, Qwen at context 16384 gives `HTP0 2856 562 575` and `Host 497 0 36` for both files. Gemma gives `HTP0 2465 494 609` and `Host 677 0 67`; Llama gives `HTP0 2103 1792 290` and `Host 308 0 40`.

The original Step 3 also attempted the mmproj and DFlash as standalone `-m` models. Both original files and their stubs fail identically: upstream rejects a CLIP projector as a main model, and a DFlash draft needs `ctx_other`. The user approved testing these sidecars with their main model; T6–T7 cover their paired behavior because `llama-fit-params` itself does not accept their pairing flags. This is a correction to the T1 command, not a claim of sidecar projection parity yet.

## Sparse files on the phone

`adb push` allocated each entire stub. On the phone, `truncate -s HEADER_BYTES STUB` followed by `truncate -s FULL_BYTES STUB` restored the sparse tail. `stat -c '%s %b'` then reported size and 512-byte blocks:

| Stub | Size bytes | Blocks | Allocated bytes |
|---|---:|---:|---:|
| Qwen | 2,834,975,040 | 21,448 | 10,981,376 |
| Gemma | 2,537,530,496 | 12,784 | 6,545,408 |
| Llama | 2,019,377,696 | 15,328 | 7,847,936 |

## App-private symlink

With the sample installed and the phone unlocked, `run-as com.suhel.llamabro.sample` created `no_backup/t/blobs/x` and a relative `t/snapshots/c/x → ../../blobs/x`, read `hi`, renamed `t` to `t2`, read `hi` again, then removed `t2`. The remote command needed to be quoted as a single argument to `adb shell`; otherwise its inner `sh` ran as the adb user and failed to create the directory. No phone setting was changed.

Conclusion: T7 can use the header-only `Projection` method for ordinary main models. Sidecar behavior remains to be checked with the main model in T6–T7.

## Validation gate

`ANDROID_SERIAL=<serial> sdk/scripts/check.sh --device` passed build, native, contract, tools-ui, API, lint, JVM, host end-to-end, and AAR. The device stage reported the pre-existing Llama 3.2 tool-loop failure allowed by the gate, plus a sample UI timeout. The device contract then waited at thermal status 3 and ADB lost the phone. The phone is not currently listed by `adb devices`; the full device gate remains to rerun after reconnection. The separate T1 phone fit comparisons and symlink check above completed before this interruption.
