# One real download from Hugging Face, killed and resumed

**Question.** Does the Hub's download path work against the real service: redirect to the CDN, `HEAD` and `Range`
requests, resume after the process is killed, hash verification, and a chat on the result? Does the file tree have
pagination?

## Method

`run.sh` drives the `samples/background-download` debug build (package `com.suhel.llamabro.download`, no endpoint override,
no token) on the OnePlus 13 over an unmetered Wi-Fi connection:

1. Collect headers only with `curl`: the tree listing's status and `Link` header, a `HEAD` on the resolve URL following
   redirects, and a one-byte `Range` request (`headers.txt`).
2. Uninstall and install the APK, clear logcat and record `LlamaBroHub` and `ModelDownloadWorker` output (`hub.log`).
3. Start the download of `unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M` (commit 6ab4614): the model file (532,517,120 bytes, SHA-256
   `bd258782...dc517`) and `mmproj-BF16.gguf` (207,346,528 bytes, `d312c4d0...935d6`), 739,863,648 bytes in all.
4. Poll the partial file's size with `run-as`; at half the plan's bytes, `kill -9` the app process, wait 2 s and relaunch.
5. Poll until both blobs exist under their SHA-256 names and no partial file remains (timeout 20 minutes); write
   `timeline.txt` (start, kill and bytes held, restart, done) and `blobs.txt`.

## How to run

```bash
ANDROID_SERIAL=<serial> research/p3/real-download/run.sh   # needs the sample's debug APK built; phone unlocked
```

## Result

| Step | Outcome |
|---|---|
| Start to kill | 36.8 s; the partial held 377,519,564 bytes (51%) |
| Relaunch to first request | 21 s (WorkManager re-runs the interrupted work); refs, tree and `HEAD` 302 then 200 |
| Resume | `GET` with `Range: bytes=378682828-`, 302 to the CDN, then 206 |
| Complete | 97.6 s from the start (about 10 MB/s); both blobs named by SHA-256, no partial left |
| Chat | "Hi, how can I help you today?", 6 s after tapping Chat, load included (thinking off) |

- The resolve URL answers 302 to `us.aws.cdn.hf.co/xet-bridge-us/...` (Hugging Face's Xet storage) with
  `accept-ranges: bytes` and `x-linked-size`. `HEAD` on the CDN answers 200 with the full `content-length`; `Range:
  bytes=0-0` answers 206 with `content-range: bytes 0-0/532517120`.
- The tree listing (`api/models/.../tree/main?recursive=true`) has no `Link` header, so there is no pagination for this
  repository, as upstream assumes.
- The first chat showed an empty line: the sample read only `content` and Qwen3.5 thinks by default. The sample now turns
  thinking off for its one-line greeting and falls back to the reasoning text.

Details: [results.md](results.md); headers in [headers.txt](headers.txt), blobs in [blobs.txt](blobs.txt), timestamps in
[timeline.txt](timeline.txt).

## Findings / decisions

F93 (one real download, killed and resumed, verified); related F92 (kill and resume on the phone), F82, F83; D39
(the hub is a port of `-hf`), D40 (every download is hashed), D43 (how downloads are tested). See
[findings](../../../docs/research/findings.md) and [decisions](../../../docs/decisions.md).

## Notes

Date: 2026-11-09. `hub.log` (the logcat capture) is not kept. The headers file contains only response headers, with query
strings removed from redirect locations.
