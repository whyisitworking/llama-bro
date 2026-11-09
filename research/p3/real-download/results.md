# P3 T9: one real download from Hugging Face

2026-11-09. OnePlus 13 over Wi-Fi (unmetered). `samples/background-download` debug build with no
endpoint override (huggingface.co), no token. The user approved `unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M` at commit 6ab4614:
`Qwen3.5-0.8B-Q4_K_M.gguf` (532,517,120 bytes, sha256 bd258782…dc517) and `mmproj-BF16.gguf` (207,346,528 bytes,
sha256 d312c4d0…935d6), 739,863,648 bytes in all. `run.sh` produced `hub.log`, `timeline.txt` and `headers.txt`.

| Step | Result |
|---|---|
| Start → kill -9 | 36.8 s; the model file's partial held 377,519,564 bytes (51% of the plan) |
| Relaunch → first request | 21 s (WorkManager re-runs the interrupted work); refs, tree, HEAD 302 → 200 |
| Resume | `GET … Range: bytes=378682828-` → 302 → CDN **206** |
| Done | 97.6 s from start (about 10 MB/s each way); both blobs named by their SHA-256, no partial left |
| Chat | "Hi, how can I help you today?", 6 s after tapping Chat, including the load (thinking off) |

What the CDN does (`headers.txt`, headers only):
- The resolve URL answers **302 to `us.aws.cdn.hf.co/xet-bridge-us/…`** (Hugging Face's Xet storage behind an S3-style
  bridge), with `accept-ranges: bytes` and `x-linked-size`.
- **HEAD** on the CDN answers 200 with `accept-ranges: bytes` and the full `content-length`.
- **Range** `bytes=0-0` answers 206 with `content-range: bytes 0-0/532517120`.
- The tree (`api/models/…/tree/main?recursive=true`) has **no `Link` header**: no pagination for this repo, as upstream
  assumes.

The first chat attempt showed an empty line: the sample read only `content`, and Qwen3.5 thinks by default. The
sample now turns thinking off for its one-line greeting and falls back to the reasoning text.
