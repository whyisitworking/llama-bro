# Text to speech: Qwen3-TTS 1.7B on llama-server

**Question.** Does llama-server's `/tts` endpoint run Qwen3-TTS on this phone's CPU, NPU or GPU, at what speed and memory,
and what would the SDK need in order to expose it?

## Method

- **Models.** `ggml-org/Qwen3-TTS-12Hz-1.7B-Base-GGUF`: Q4_K_M model (1.04 GB) and the mmproj Q8_0 that holds the audio
  codec (446 MB); checksums in `gguf.sha256`. Both go to `/data/local/tmp/lb-spike/models/`.
- **Build and settings.** OnePlus 13; upstream llama-server from the P0 `P` build (b12052 plus the delta-net patch,
  [P0](../../p0/README.md)), `-m ... -mm ...`, the SDK's load settings (`-c 8192 -np 1 -b 512 -ub 512 --cache-ram 1024
  --ctx-checkpoints 8 --load-mode none --fit off`).
- **`tts.sh`.** Per chip and round: a fresh load with `MemAvailable` before and after, the server's RSS, then `POST /tts`
  (`lang: en`, `seed: 42`, `response_format: wav`, a reference voice as `speaker_ref_b64`) for a two-sentence input and a
  five-sentence paragraph, each non-streamed (wall time, audio seconds from the WAV header, real-time factor = audio seconds
  per wall second) and streamed (time to the first audio byte). The reference voice is a 7.3 s `speaker.wav` made with
  macOS `say -v Samantha`, placed next to the script. `EXTRA` adds server flags (for example `--no-mmproj-offload`).
  Environment: `ROUNDS` (default 3), `CHIPS` (default `npu gpu cpu`), `ANDROID_SERIAL` (required). One load per chip, one
  round was run.
- **`utf8-check.py FILE.wav`.** Simulates the SDK's string-typed reply path: decode the bytes as UTF-8 with replacement
  and re-encode, to see whether a WAV survives.

## How to run

```bash
ANDROID_SERIAL=<serial> ROUNDS=1 CHIPS=cpu research/pf/tts/tts.sh
ANDROID_SERIAL=<serial> ROUNDS=1 CHIPS=npu EXTRA=--no-mmproj-offload research/pf/tts/tts.sh
python3 research/pf/tts/utf8-check.py path/to/reply.wav
```

## Result

| Chip | Load | Memory | Two sentences (7.9 s audio) | Paragraph (22 s audio) | First streamed audio |
|---|---|---|---|---|---|
| CPU | 3.3 s | 2,336 MB | 18.1-39.7 s, RTF 0.20-0.44 | 44-48 s, RTF 0.48-0.52 | 2.4-2.5 s |
| NPU, codec on the NPU | 2.7 s | 2,731 MB | aborts: `ggml-hexagon.cpp:1734: dspqueue_read failed` in `clip_encode` | | |
| NPU, codec on the CPU | 3.2 s | 2,349 MB | 16.9-21.1 s, RTF 0.37-0.46 | 37-39 s, RTF 0.56-0.59 | 0.3-0.5 s |
| GPU (OpenCL) | 11.7 s | 3,563 MB | 19.4 min for 41 s of audio (ran past the text); stopped | | |

- No chip reaches real time (RTF above 1), so streamed speech would stall about every second. The CPU's first request
  includes warm-up. A listener found the CPU output clear but not very human-like; the reference voice was synthetic.
- **API.** All TTS settings are per request (`input`, `lang`, `voice`, `speaker_ref_b64` or a multipart `speaker_ref`,
  `response_format` `wav` or `pcm`, `stream`, `top_k`, `top_p`, `seed`, `n_predict`, `repeat_penalty`); the `--tts-*` flags
  belong to the `llama-tts` tool only, so no load keys are needed. Streaming returns raw WAV bytes in chunks.
- **SDK gaps.** `Model.rawRequest` returns a string flow and converts a non-streamed body with `toString(UTF_8)`;
  `utf8-check.py` on a 376,364-byte WAV returns 677,981 bytes with 154,247 replacement characters. The mmproj goes to the
  model's device, which aborts on the NPU, so a way to keep it on the CPU would be needed. The round trip was checked on the
  Mac, not through the SDK on the phone.

Full tables: [results.md](results.md).

## Findings / decisions

F74 (TTS works below real time on CPU and NPU with a CPU codec; the NPU codec aborts; the SDK cannot return audio bytes),
related F65; D37 (text to speech is out of 1.0). See [findings](../../../docs/research/findings.md) and
[decisions](../../../docs/decisions.md).

## Notes

Date: 2026-11-06. Raw outputs (`tts-cpu.out`, `tts-npu-gpu.out`, `tts-npu-cpucodec.out`), the generated WAV files, the
reference voice file and the logs are not kept; `speaker.wav` must be supplied to re-run. The model downloads were
fetched from the ggml-org repository.
