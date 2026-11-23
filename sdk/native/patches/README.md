# llama.cpp patches

The SDK never edits the llama.cpp submodule. Every change to it is a patch here, applied in name order to a build copy
(`sdk/native/build/llama.cpp`) by `sdk/native/scripts/prepare-llama.sh`, which Gradle's `prepareLlama` task runs before
every native build. A patch that no longer applies stops the build. Each speed patch must have won an A/B on the phone
([D10](../../../docs/decisions.md)); a functional patch, such as `0003`, is checked by tests instead.

| Patch | Origin | Why | Evidence | Drop when |
|---|---|---|---|---|
| `0001-hexagon-gdn-long-batch.patch` | Ours; one line, the change to propose upstream ([upstream-notes.md](../../../research/ph/delta-net/upstream-notes.md)), nothing posted | NPU prefill with MTP: Hexagon's fast chunked delta-net kernel was gated on K = 1, so with MTP (K = 4) prompts fell to the slow recurrent kernel. The gate also accepts `n_tokens > K`, where ggml's contract only promises slot 0 (`ggml.h`), as CUDA's chunked kernel already does. +61% at b12352, as fast as the head/tail split it replaced, and drifts less than upstream's own MTP on/off; applies unchanged at b12479, where it gives +67% (F163), and at b12653 (F180) | F7, F8, F121, F149, F163, D80 | Upstream's gate accepts `n_tokens > K` (`htp-kparams.cpp`, `K == 1`), or GDN replay (PR #31669, +114% on the phone, F149) is on by default in the pin; re-measure at every upgrade |
| `0002-bf16-neon-vec-dot.patch` | Upstream PR [#30432](https://github.com/ggml-org/llama.cpp/pull/30432) (head `cde3115c`), unmerged at b12653 (2026-11-23) | ggml-cpu has no ARM path for BF16 dot products (issue [#29917](https://github.com/ggml-org/llama.cpp/issues/29917)); the CPU image encoder with a BF16 projector 16.5 s → 3.1 s | F123, F127 | #30432, or another ARM BF16 path, is in the pinned build |
| `0003-load-progress-stop-hook.patch` | Ours; written up as reference for upstream ([upstream-pr.md](../../../research/ph/load-cancel/upstream-pr.md)), nothing posted | A cancelled load can't stop reading weights: llama-server's load progress callback always says go on. The patch adds `server_context::set_load_abort_callback`, asked before the 200 ms throttle, for the text model, the draft and the image encoder ([D67](../../../docs/decisions.md)). Ported to b12479's server refactor without a change in behaviour: the same three load stages call the same callback (F163). Stages without a progress call (context creation, warm-up, kernel compile) can't be stopped, though with an mmproj or a draft a cancel during the text model's is caught at the next stage's first tick (`server-context.cpp` ~1315-1404 at b12479, patched). Functional, not speed: no A/B; checked by the `load_stops_*` native tests and the phone timing | F145, F163 | Upstream has an equivalent hook (or its progress callback lets an embedder stop the load) |
| `0005-lfm3a-depthformer-no-cpu-ops.patch` | Ours; upstream fact sheet to be written (`research/ph/speech-output/`), nothing posted | LFM3-Omni's depthformer (the part that samples each audio frame's codes) looked its codebook rows up through a flattened view of `a.gen.code.embd.weight`, the logits matmul's weight, and built its positions with `ggml_arange`. On Hexagon the first reads tiled bytes as rows (every `/tts` an empty WAV, F155) and the second is unsupported, so every pass hopped NPU → CPU → NPU. The patch reads each codebook's row from its own table (a 3-D `get_rows` on the weight itself, `inp_codes_cb`) and passes the positions in (`inp_pos_cb`). The graph then has no CPU op on the NPU: the depthformer 30.3 → 15.8 ms per frame. The same numbers on the CPU and Metal (byte-identical WAV). Applies unchanged at b12479 and b12653, where `lfm3a-gen.cpp` is untouched and Hexagon still has no `ARANGE`. Functional and speed: checked by `omni_tts_same_audio_as_upstream` (native, the CPU audio unchanged) and `check.sh --device`'s `device-speech` (audio on the NPU, no unsupported op, at least 1.5× real time) | F155, F156 | Upstream's LFM3-Audio graph reads the per-codebook tables directly and takes positions as input (`tools/mtmd/models/lfm3a-gen.cpp`, `build_gen_code`), or Hexagon supports `ARANGE` and reads views of tiled weights |
| `0007-hexagon-poll-noblock.patch` | Ours; upstream's own Windows branch made unconditional, fact sheet to be written, nothing posted | With polling on (`GGML_HEXAGON_OPPOLL=1`, `npuPolling`, D86), every empty poll was a zero-timeout `dspqueue_read`, which on Android has `libcdsprpc` log an error: tens of thousands of lines a second, and the phone's log quota then drops the app's own lines. `htp-drv.cpp` already used `dspqueue_read_noblock` for a zero timeout, but only `#ifdef _WIN32`; the patch does so wherever the symbol loaded. Speech on the NPU 2.26 → 2.27/2.26 times real time; error lines 2,900 → 0. Applies unchanged at b12479 and b12653 (`htp-drv.cpp` still reads without blocking only `#ifdef _WIN32`). `npuPolling` stays off by default (D90); the patch is what makes turning it on safe on Android | F162 | Upstream polls with the no-block read on Android too (`htp-drv.cpp`, `dspqueue_read`) |
| `0008-lfm3-speech-tail-guard.patch` | Ours; measured on 600 realtime replies (`research/voice/`), nothing posted | In a captioned (text-and-audio) reply, LFM3-Omni speaks the rest of its text with audio alone after `<|text_end|>` and in about 1.6% of replies never samples the end-of-audio code: it loses its place and plays near-silence with noise bursts, or garbled speech, until the context is full. Upstream's loop matches Liquid's reference, which relies on the caller's token cap. The patch watches the speech after the text ends in LFM3's helper: 3 s of near-silence (80 ms frames under RMS 0.004) or more than 5 audio frames per text token plus 13 turns the next frame into the end-of-audio frame and ends the response. Stopped all 10 failures in 600 replies (400 to choose the thresholds, 200 fresh) and cut no clean reply; clean replies are byte-identical. Functional: checked by the sweep and its replay (`realtime-sweep.sh`, `tail-guard-sim.py`), by `omni_tts_same_audio_as_upstream` (TTS untouched) and by ear | F167, D96 | The model ends its speech reliably (a later LFM3 release), or upstream bounds audio after the text itself |

Dropped: `0004-hybrid-swa-checkpoint-restore.patch` (F153, D91), at b12653 (F180, D137). Upstream fixed the same bug in
#32434 (`52975342f`): llama-server now passes `get_reuse` an `n_swa_reuse` of 0 for recurrent and hybrid models
(`server-context.cpp`), where the patch passed 0 for memory that cannot be partly removed. The two rules agree on every
model we ship (the LFM3 family is hybrid, and full-attention and SWA-only models are neither), and the
`hybrid_swa_cache_*` native tests, which fail without the fix, pass at b12653 without the patch.

Dropped: `0005-hexagon-keep-shared-weights-flat.patch` (F155), which kept any weight read by an op other than a matmul
out of Hexagon's tiled layout. Patch 0005 above removes the only such read in the models we ship, and the keep-flat
version sent that matmul to the CPU. It is kept at `research/ph/speech-output/hexagon-keep-shared-weights-flat.patch`
for a model that needs it.

Dropped: `0006-embd-only-output-skips-lm-head.patch` (F156, F165, D111), which left LFM3-Omni's text head out of
speech-only frames: 38.3 → 33.8 ms per frame on the NPU, speech 2.0 → 2.3 times real time. Its 297 lines changed
`llama.h`'s output flags across nine files, the costliest patch to carry at each upgrade, for headroom speech no
longer needs. It is kept at `research/ph/speech-output/embd-only-output-skips-lm-head.patch`; drop it from there too
once upstream makes the two output flags differ.

Kept outside the build: the four Vulkan patches that make ggml-vulkan run on the Adreno 830 (Qualcomm's upstream PRs
#30824, #30826 and #30821, and ours) are in [`research/vulkan-adreno/patches/`](../../../research/vulkan-adreno/patches/),
because Vulkan is not in the build and is slower than the CPU and OpenCL there (D31, F174).

**At every llama.cpp upgrade** (the checklist in [CONTRIBUTING.md](../../../CONTRIBUTING.md#upgrading-llamacpp)), check
each row's "Drop when" first. For a patch from an upstream PR:
`gh pr view <number> --repo ggml-org/llama.cpp --json state,mergeCommit` and, if merged,
`git -C sdk/native/third_party/llama.cpp merge-base --is-ancestor <mergeCommit> HEAD`. If the fix is in the new pin,
delete the patch and its row, and record it in the upgrade's finding. If the PR changed before merging, use the merged
version, not the one carried here.
