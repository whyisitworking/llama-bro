# PF T4: text to speech, Qwen3-TTS 1.7B (F74)

`ggml-org/Qwen3-TTS-12Hz-1.7B-Base-GGUF`: Q4_K_M model (1.04 GB) + mmproj Q8_0 (446 MB, the codec), download
approved by the maintainer 2026-11-06 (`gguf.sha256`). Upstream `llama-server` (`/data/local/tmp/lb-spike/P`), `-m … -mm …`,
the SDK's load settings, `POST /tts` with `lang: en`, `seed: 42`, `response_format: wav` and a reference voice
(`speaker.wav`, 7.3 s, macOS `say -v Samantha`, sent as `speaker_ref_b64`). Inputs: two sentences, and a five-sentence
paragraph. One load per chip, one round. Script `tts.sh`; raw `tts-cpu.out`, `tts-npu-gpu.out`,
`tts-npu-cpucodec.out`; WAVs in `wav/`; logs in `logs/`.

## Results

| Chip | Load s | Memory MB | 2 sentences (7.9 s audio) | Paragraph (22 s audio) | First streamed audio |
|---|---|---|---|---|---|
| CPU | 3.3 | 2336 | 18.1–39.7 s wall, RTF 0.20–0.44 | 44–48 s, RTF 0.48–0.52 | 2.4–2.5 s |
| NPU, codec on the NPU | 2.7 | 2731 | **aborts**: `ggml-hexagon.cpp:1734: dspqueue_read failed` in `clip_encode` ← `mtmd_gen_audio_process` | — | — |
| NPU, codec on the CPU (`--no-mmproj-offload`) | 3.2 | 2349 | 16.9–21.1 s, RTF 0.37–0.46 | 37–39 s, RTF 0.56–0.59 | **0.3–0.5 s** |
| GPU (OpenCL) | 11.7 | 3563 | **19.4 min** for 41 s of audio (runs past the text), stopped | — | — |

RTF = audio seconds per wall second; above 1 is faster than real time. The CPU's first request (39.7 s) includes
warm-up. Streaming returns raw WAV bytes in chunks (no SSE wrapper; `server-context.cpp` `post_tts`).

A listener found the CPU output clear: "perfectly clear, although not very human like" (the reference voice is a
synthetic macOS voice; Qwen3-TTS clones the reference).

## What the SDK would need (Step 1, reading)

- **No load keys.** In llama-server the `--tts-lang`, `--tts-voice` and `--tts-speaker-file` flags don't apply (they're
  `LLAMA_EXAMPLE_TTS`, the `llama-tts` tool only). Everything is per request: `input`, `lang`, `voice`,
  `speaker_ref_b64` (or a multipart `speaker_ref` file), `response_format` (`wav` or `pcm` float32), `stream`,
  `top_k`, `top_p`, `seed`, `n_predict`, `repeat_penalty` (`server-schema.cpp` `make_tts_schema`). Backend sampling is
  skipped for TTS (`server-context.cpp:2073`).
- **A byte path (Step 3).** `Model.rawRequest` returns `Flow<String>`: a non-streamed reply goes out as
  `whole.toString(UTF_8)` (`Model.kt:216`). `utf8-check.py` does the same round trip on `wav/npu-r1-short.wav`: 376,364
  bytes come back as 677,981 with 154,247 replacement characters, so the WAV is lost. Checked by that equivalent round
  trip on the Mac, not through the SDK on the phone.
- **The codec off the NPU.** The SDK puts the mmproj on the model's device (F65, `mmproj_device`); on the NPU that
  aborts, so it would need a way to keep the mmproj on the CPU.
