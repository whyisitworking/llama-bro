# Catalog pin

Read on 2026-11-10 with two read-only calls per repo: `https://huggingface.co/api/models/<repo>` (the commit `sha`)
and `.../tree/main` (file sizes, `size` of each LFS entry). Nothing was downloaded. All five repos and tags exist.

## How each tag resolved

The SDK hub's rule (`sdk/llamabro-hub/.../internal/FileChoice.kt`, a port of llama.cpp's `-hf` choice):

- **Model:** `findBestModel(files, tag)`: the first listed file that is a model gguf (not `mmproj`, `imatrix`,
  `mtp-`, `dflash-`, `eagle3-`, `dspark-`) whose path matches `<TAG>[.-]` (case-insensitive). Each tag matched exactly
  one file.
- **Projector:** `findBestSibling(files, model, "mmproj", tag)`: among `*mmproj*.gguf` in the same or a parent
  folder, an exact `-<TAG>.` match wins, else the smallest `|quantBits(file) - quantBits(tag)|`, a tie going to the
  first in listing order. The model tag has 4 bits; `mmproj-BF16`, `-F16` (16 bits each) and `-F32` (32) give
  differences 12, 12, 28, so the tie between `BF16` and `F16` goes to **`mmproj-BF16.gguf`**, which the tree lists
  first. That is the file F93 measured for Swift. Llama 3.2 has no `mmproj*` file.

## Pins

| Key | Commit | Model file | Model bytes | Projector | Projector bytes |
|---|---|---|---|---|---|
| `unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M` | `6ab461498e2023f6e3c1baea90a8f0fe38ab64d0` | `Qwen3.5-0.8B-Q4_K_M.gguf` | 532,517,120 | `mmproj-BF16.gguf` | 207,346,528 |
| `unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M` | `165f274ab15d1c7c8fa8ba7336e64666f4d4151c` | `gemma-3-4b-it-qat-Q4_K_M.gguf` | 2,489,894,016 | `mmproj-BF16.gguf` | 851,251,456 |
| `unsloth/Qwen3.5-4B-GGUF:Q4_K_M` | `e87f176479d0855a907a41277aca2f8ee7a09523` | `Qwen3.5-4B-Q4_K_M.gguf` | 2,740,937,888 | `mmproj-BF16.gguf` | 675,569,344 |
| `unsloth/Llama-3.2-3B-Instruct-GGUF:Q4_K_M` | `e7d0997e49c9cb00d88b4c1a6a16aa894b0bbc31` | `Llama-3.2-3B-Instruct-Q4_K_M.gguf` | 2,019,377,600 | none | 0 |
| `unsloth/Qwen3.5-9B-GGUF:Q4_K_M` | `3885219b6810b007914f3a7950a8d1b469d598a5` | `Qwen3.5-9B-Q4_K_M.gguf` | 5,680,522,464 | `mmproj-BF16.gguf` | 921,705,024 |

Swift's sizes equal F93's (532,517,120 and 207,346,528). None of the repos is gated.

> **Corrected 2026-11-12 (F112):** the Writer row was `UD-Q4_K_XL` (2,537,530,496). The size picker's Recommended tier is `Q4_K_M` whenever the repo has it, so a Writer fetched the normal way was `Q4_K_M` and the library could not name it. Size and the commit `165f274…` re-read from `https://huggingface.co/api/models/unsloth/gemma-3-4b-it-qat-GGUF/tree/main` on the same day (`gemma-3-4b-it-qat-Q4_K_M.gguf` 2,489,894,016).
