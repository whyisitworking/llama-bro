# Upstream issue: notes for the Hexagon bug report (not posted)

> **Read first.** llama.cpp's contributing guide forbids AI-written posts (bug reports, feature requests, pull request
> descriptions). These notes were prepared with an AI assistant. Use them as the facts, numbers, commands and code
> citations, and write the report in your own words; do not paste this file. Search first, and check master: this was
> found and tested only at b12352 (`5676dfb`), not on master. F155 and patch 0005 in this repo hold the details.

Template: "Bug (misc.)". Module: ggml-hexagon (and llama-server's `/tts` as where it shows).

## Title (suggestion)

ggml-hexagon: runtime repacking tiles a weight that GET_ROWS also reads (LFM3-Omni /tts returns empty audio on HTP)

## Version and setup

- llama.cpp b12352 (`5676dfb`), built in `ghcr.io/snapdragon-toolchain/arm64-android:v0.8`, Hexagon backend (`htp-v79`).
- Phone: Snapdragon 8 Elite (OnePlus 13), Android.
- Model: `LFM3-Omni-2B-Q4_K_M.gguf` with `mmproj-LFM3-Omni-2B-Q8_0.gguf`.

## Command line

```shell
llama-server -m LFM3-Omni-2B-Q4_K_M.gguf --mmproj mmproj-LFM3-Omni-2B-Q8_0.gguf -c 4096 -np 1 --fit off -dev HTP0 -ngl 99
curl -s -o out.wav -H 'Content-Type: application/json' \
  -d '{"input":"The weather in Paris is cloudy.","voice":"female","seed":42}' localhost:8080/tts
```

## What happens

- `out.wav` is 44 bytes: a WAV header, no samples. HTTP 200, no error or warning. The log shows the prompt encoded,
  then `stop processing: n_tokens = 0`.
- The same with `--no-mmproj-offload` (speech part on the CPU) speaks normally (about 1.7× real time).
- With everything on HTP but `GGML_HEXAGON_RT_REPACK=0`, it speaks. `GGML_HEXAGON_OPFILTER='MUL_MAT'` also makes it
  speak. `GGML_HEXAGON_OPFUSION=0` and `GGML_HEXAGON_OPBATCH=1` do not help.
- `test-backend-ops -o MUL_MAT -b HTP0` with the depthformer's exact shapes (q8_0 2017×1024 in 12 batches, the q8_0
  projections, the permuted/broadcast f32 attention products) passes, so each op is correct alone.

## Cause (as found)

- `ggml_hexagon_graph_repack` (`ggml/src/ggml-hexagon/ggml-hexagon.cpp`) tiles `src[0]` of every MUL_MAT in place.
- In LFM3-Audio's depthformer, `a.gen.code.embd.weight` (q8_0, 1024×2017×12) is both the weight of the logits MUL_MAT
  and the source of a GET_ROWS through a reshaped view (1024×24204) that embeds the sampled codes. After tiling,
  GET_ROWS reads tiled bytes as rows, the frame input is garbage, and the first sampled frame is the end-of-audio code.
- The text model has the same sharing (`token_embd`), but it sits in the HTP host buffer (`HTP0-HOST`), which is never
  tiled; the mmproj's weights are in the device buffer.
- Checking the consumers per graph is not enough: `ARANGE` is not supported on HTP (the warm-up warns), so the GET_ROWS
  and the MUL_MAT land in different graph splits.
- Verbose log (`GGML_HEXAGON_VERBOSE=1 -v`): `rt-repack a.gen.code.embd.weight|1024:2017:12:1|q8_0`, then
  `execute-op GET_ROWS|a.gen.code.embd.weight (reshaped) ... |flags 0x0`.

## A fix that works for us (for context; write any PR yourself)

Mark a weight that any op other than a matmul's `src[0]` reads, directly or through a view, as keep-flat on the
tensor (a host-only flag); unpack it if already tiled (`htp_unpack_tensor_tiled`); make `ggml_hexagon_tensor_can_tile`
refuse it. Note: Hexagon multiplies quantized weights only tiled, so that matmul then falls back to the CPU (the graph splits NPU → CPU → NPU); correct, not fast. A tiled-aware `GET_ROWS` (and views that carry their base's tiled flag) would be the better upstream fix. Only skipping the repack crashed the DSP
(`dspqueue_read failed: 0x2e`), because the kernel was still chosen for a tiled weight. With the fix: only
`a.gen.code.embd.weight` stays flat, the text model is unchanged, and `/tts` speaks on HTP at 1.33-1.39× real time.
Our patch: `sdk/native/patches/0005-hexagon-keep-shared-weights-flat.patch`.

## Possibly related (unchecked)

Our earlier notes (D37) saw Qwen3-TTS's codec abort on the NPU; not retested with this fix.
