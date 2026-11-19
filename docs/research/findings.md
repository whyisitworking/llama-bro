# Findings

> **Related:** the SDK v2 design document (decisions D*), the phase plans, and the experiment results in [`research/p0/results/`](../../research/p0/results/session1.md).
> **Status:** living document, updated 2026-11-19 (P1: F22–F31; P2 planning: F32–F35; API review: F37–F44; PA: F45–F54, F57; P4 reading: F55–F56; P2: F36, F58–F69, F71, F75–F80; PF: F70, F72–F74; P3: F81–F95, F98–F99; PD: F96–F97, F100–F119; after PD: F120–F138; PH: F139–F162; voice mode: F163–F173; Vulkan retry: F174)

A finding is something learned from a measurement or from reading upstream source. Every finding has a stable ID
(`F<n>`, numbered in the order found, never reused). Other documents cite findings by ID, for example "per F10".
Each entry has the same fields:

- **Finding:** one sentence.
- **Evidence:** the measurement or source location.
- **Means:** what changes for the SDK.
- **Feeds:** decisions (D*), design sections (§) and plan tasks this finding supports.

A wrong finding is never deleted. It is marked **Superseded by F<n>** and keeps its text. Entries are grouped by
topic, and within a topic newest first. The index lists every ID.

---

## Vulkan on the Adreno 830, retried (F174)

### F174. At b12479 ggml-vulkan still fails on the Adreno 830, now on every mat-vec type; Qualcomm's three upstream fixes and a patch of ours make it run correctly, but prompts are 4 times slower than on the CPU and 14 times slower than on OpenCL, and replies are 10% slower than on the CPU

- **Finding:**
  - **The pin fails, and worse than at b12052 (F64).** Phone: OnePlus 13, driver `V@0800.77`, which is newer than the
    0.800.74 in [#28635](https://github.com/ggml-org/llama.cpp/issues/28635). ggml reports `int dot: 0 | matrix cores: none`.
    The pin is b12479 with patches 0001-0008. In `test-backend-ops -o MUL_MAT`:
    - q4_K aborts at its first case: `mul_mat_vec_q4_k_f32_f32` pipeline creation returns `ErrorUnknown`, which ends in
      an uncaught `vk::UnknownError`.
    - f16, q4_0, q8_0, q5_K and q6_K abort on their own mat-vec pipelines after a few cases. The cases that ran before
      the abort were wrong (error 0.6-2.1).
  - **Qualcomm's upstream fixes cure all of them but q4_K.** One Qualcomm engineer wrote three Adreno fixes, and all
    apply cleanly. Patches `vk1`-`vk3` carry them:
    - [#30824](https://github.com/ggml-org/llama.cpp/pull/30824): RMS_NORM with a runtime loop count; merged after the
      pin.
    - [#30826](https://github.com/ggml-org/llama.cpp/pull/30826): no subgroup arithmetic on Qualcomm's driver; open,
      approved.
    - [#30821](https://github.com/ggml-org/llama.cpp/pull/30821): no 8-bit vector pack or unpack; open.

    With them, MUL_MAT passes every case for f16 (298), q4_0 (104), q8_0 (105), q5_K (85) and q6_K (63). The q4_K
    pipelines (MUL_MAT and MUL_MAT_ID) still fail to compile.
  - **The q4_K trigger is the shader's `vec4` load of B.** A bisection of `mul_mat_vec_q4_k.comp` found:
    - Variants without the scale unpack, the quant unpack, `smin`, `sx..sw`, with 16-bit A loads, or with q5_K's
      `smin` all still fail.
    - An empty body, or one that reads only A, compiles.
    - A body that reads only one `data_b_v4` element fails.
    - Reading B as two `data_b_v2` loads (as q5_K does) or as scalars compiles. Then q4_K passes MUL_MAT 116/116 and
      MUL_MAT_ID 110/110.

    This is a driver bug. In #28635, others found that the same SPIR-V compiles with a workgroup size below 32, on
    Mesa/Turnip, and on an Adreno 840 with a newer driver.
  - **Long submissions lose the device.** Without matrix cores or integer dot, a large matmul runs at about 115-145
    GFLOPS (about 0.5 s for m=4096, n=512, k=14336). Upstream submits every 200 GFLOP, which is about 1.7 s here, and
    Qualcomm's driver then returns `ErrorDeviceLost`.
    - Llama 3.2 3B: a 512-token prompt batch loses the device. `-ub 128` works, and so does `-ub 512` with at most 4
      nodes per submission.
    - Qwen3.5 4B: a 20 GFLOP cap is not enough, because ggml counts only matmul FLOPs and the gated delta net counts as
      none. With `-p 512`, 100 nodes per submission lose the device and 64 do not.
    - Decode for 32 tokens on Qwen3.5: 13.26 t/s at 100 nodes, 13.16 at 16, 11.76 at 1.
    - A prompt-sized FLASH_ATTN_EXT (32-64 queries over 1-2K keys) runs past the limit within one op: 12 cases fail,
      then the device is lost. A decode-sized one takes 0.35 ms.
  - **Patch `vk4`.** It does three things:
    - the q4_K mat-vec reads B as two `vec2` loads, for every vendor;
    - on Qualcomm's proprietary driver, at most 20 GFLOP and 16 nodes per submission;
    - on Qualcomm's proprietary driver, FLASH_ATTN_EXT is reported unsupported, so llama.cpp's automatic setting runs
      attention as MUL_MAT and SOFT_MAX.

    Every other op a Llama or Qwen3.5 graph uses passes all its cases. The SDK's tree plus `vk1`-`vk4` gives exactly the
    tested `ggml-vulkan` tree. The SDK's own recipe (`build-backends.sh`, `LB_BUILD_VULKAN=1`) builds
    `libllamabro-ggml-vulkan.so` from it.
  - **Correct output.** Greedy replies to the 668-token prompt:
    - Llama 3.2 3B Q4_K_M: on Vulkan, byte-identical to OpenCL in all 3 rounds; both keep the CPU's first 26 words and
      then choose other, sensible words.
    - Qwen3.5 4B Q4_K_M: coherent, the CPU's first 38 words (OpenCL writes `8888…`, F71).
    - Phi-4-mini Q8_0: coherent, the CPU's first 37 words (F64's `edlyedly…` is gone).
  - **Speed.** Llama 3.2 3B, 3 rounds, phone at thermal status 1-2:

    | Arm | Ready | Prefill t/s | Decode t/s |
    |---|---|---|---|
    | CPU | 5.9-6.2 s | 82-84 | 18.1-18.5 |
    | OpenCL | 12.9-14.8 s (68 s on the first load) | 269-271 | 19.7-20.0 |
    | Vulkan | 3.9-4.5 s | 19.5-19.6 | 16.6-16.8 |

    One round each of the other models, Vulkan against the CPU:
    - Qwen3.5 4B: prefill 14.5 against 62 t/s, decode 10.9 against 13.3 t/s.
    - Phi-4-mini Q8_0: prefill 12.5 against 82.5 t/s, decode 7.5 against 10.9 t/s.

    Vulkan's memory figure is still the overcount (19 203 MiB on a 15.1 GB phone, F64).
- **Evidence:** [`research/vulkan-adreno/`](../../research/vulkan-adreno/README.md), 2026-11-19: `build.sh`, `run.sh`,
  `speed.sh` and `q4k-variant.py`, with every test-backend-ops count and bisection step in the README;
  `research/vulkan-adreno/patches/` (`vk1`-`vk4`); the upstream PR heads `401563f` (merge), `3bae17b` and `8672ff2`; the server logs'
  `ggml_vulkan: device lost on Vulkan0`.
- **Means:**
  - Vulkan can now run correctly on this phone, but it beats nothing: prompts are 4 times slower than on the CPU and 14
    times slower than on OpenCL, and replies are 10% slower than on the CPU. Its only gain is the load: 4 s, against 6 s
    on the CPU and 13-15 s on OpenCL (68 s the first time).
  - D31 still holds, now for speed rather than crashes. Bringing it back would add a third GPU route that `Auto` should
    never pick on an Adreno.
  - Any pipeline failure still aborts the process, uncaught. A driver with another bug would still take an app down.
  - With flash attention off on Qualcomm, a quantized V cache can't be used there (upstream allows one only with flash
    attention), and a forced `-fa on` runs attention on the CPU.
  - The patches touch only `ggml-vulkan` and are kept in `research/vulkan-adreno/patches/`, not in the build. The
    recipe is kept for a retry once #30821 and #30826 merge and the q4_K load is fixed upstream (a draft could be filed,
    but nothing is posted).
  - A retry on newer Adreno parts (matrix cores: `QUALCOMM_ADRENO` with cooperative matrix) or a newer driver could
    change the speed.
- **Feeds:** D31, D23, D32, F64, F63, F71, D10 (upstream drafts).

## Voice mode (F163–F173)

### F173. Patch 0008's tail guard holds for a male voice, Q8_0, Spanish, German and Japanese: every runaway stopped, a clean reply loses at most a last word said after a 3 s pause; outside English the model runs away far more often and speaks most languages unintelligibly

- **Finding:** Replayed over 500 new captioned replies (100 seeds each), the guard's thresholds (3 s of near-silence, or 5 frames per text token plus 13; F167) stop every runaway and cut no clean reply early, beyond two German replies that lose a last word said after a 3 s pause. English with a male voice: no runaway, no stop, the most headroom (78% of the length limit, at most 29 quiet frames). Q8_0, female, English: no runaway, two replies stopped, one looping ("That night, that night...") at 21.8 s of 41 s and one at its garbled last sentence (2.6 s); every unstopped English reply says its whole caption. Spanish runs away in 11 of 100, seven times English's rate; the guard stops all 11, ten at 16-58 s, and one at 115 s whose caption itself loops (181 words), so the length limit is far, and whose garbled speech stays loud. Its 9 other stops fall on speech already gone wrong; the most they remove is 4 caption words after a loop. German replies are one sentence (10 s) and never run away; 4 are stopped on the quiet rule, two of them before a last "bitte" said after the 3 s pause. Japanese replies all read the misheard question back, 49 of 100 run away, and the guard ends 98 of 100 at 6.9-11.9 s (93 on the length rule; 11.9 s is 1.45 times the question's own spoken length); their speech is unintelligible stopped or not. Looser lengths only trade stops: 6 frames per token spares 3 Spanish stops but misses two of F167's failures. The thresholds stay. Apart from the guard: in this mode the model's German, French, Japanese and Chinese speech is barely intelligible, and with an English instruction German and Japanese read the question back; the answering instruction must be in the reply's language.
- **Evidence:** Mac CPU, upstream `llama-server` b12479 (upstream's CMake, no patches), `-c 2048`, one `/v1/realtime` turn per `--seed` 13-112 with `research/voice/realtime-sweep.sh` (new `RT_MODEL`, `RT_THREADS`, `RT_TIMEOUT`), the trained prompt plus an answering instruction in the reply's language, questions from macOS `say` at 16 kHz; sweeps male-en, Q8_0 female-en (`ggml-org/LFM3-Omni-2B-GGUF`), female-de, female-es, female-ja; pilots of 2 seeds in de, es, fr, ja, zh. `research/voice/tail-guard-sim.py --stats` replays the guard (text deltas match the tokenizer's count to 1-2 tokens); `research/voice/tail-guard-check.py` has whisper.cpp `ggml-small` transcribe each stopped reply whole and up to the stop, and with `--all` screens every English and Spanish reply. Results and the per-sweep table: `research/voice/tail-guard-sweep.md`. Not heard by the user; phone not run.
- **Means:** Patch 0008 and D96 stand unchanged; the thresholds are not English-only. Outside English the guard matters more, since runaways are far more common, and a reply-length cap (D95's maximum reply length) remains the bound for a reply whose caption itself loops. The voice mode is fit for English and Spanish only as measured; a language picker should not offer German, French, Japanese or Chinese speech for LFM3-Omni-2B without a listening test, and the session's answering instruction should be in the reply's language.
- **Feeds:** D95, D96, F166, F167; patch 0008.

### F172. Two fit measurements at once crashed the app: upstream's measurement swaps llama.cpp's process-wide log callback and restores it when it ends

- **Finding:** `common_fit_measure` holds a `common_fit_log_scope` while it reads a model's headers: the scope saves llama.cpp's log callback, installs its own (which forwards through a pointer to itself), and puts the saved one back when it ends. The callback is process-wide, so two measurements at once interleave: the second saves the first one's callback as "the original", the first ends and its scope's stack frame is gone, and the second's callback then forwards through that dead frame to a garbage function pointer. The demo crashed twice this way on the phone (SIGSEGV at pc 0 under `llama_log_internal`, from `lb_fit_estimate` → `common_fit_measure`), when the model sheet estimated several sizes at once; a load's automatic context runs the same measurement, so an estimate during a load could hit it too.
- **Evidence:** the phone's tombstones of 2026-11-19 01:12 and 01:13 (OnePlus 13, both the same frames). `common/fit.cpp` `common_fit_log_scope`; `src/llama-impl.cpp` `llama_log_set`. Host test `fit_estimates_at_once_all_answer` (8 threads, 6 estimates each, upstream's tiny test model): SIGSEGV in 3 runs of 3 without a lock, all pass in 5 runs of 5 with the bridge's lock around `lb_measure_ctx`.
- **Means:** The bridge runs every fit measurement one at a time (`config.cpp`, `g_fit_mutex`). A measurement reads only headers and takes well under a second, so the cost is small. Upstream's own server measures once at start, so it never meets this; an upstream issue could make the scope thread-safe.
- **Feeds:** D101, F58, F88.

### F171. On the phone's NPU patch 0008 ends LFM3-Omni's runaway speech too, and leaves clean replies whole

- **Finding:** Twenty captioned replies on the NPU, one per server seed (1-20), each from a fresh server: all twenty end by themselves. The guard of patch 0008 ended one, seed 4, by its length rule (274 frames for 52 text tokens, 12 quiet); its caption is whole, and the user heard it speak the caption, then lose its place until the guard ended it. The other nineteen speak 0.30-0.43 s of audio per caption word (seed 4: 0.55), the longest 20.9 s (seed 9), which the user heard clear and steady to its end. The NPU speaks them at 1.25-1.62 times real time, the shorter replies slower since the first 1.5 s go to the question.
- **Evidence:** OnePlus 13, `research/voice/phone-step4.sh seeds 1 20` (device tools of `build-device-tools.sh` with patches 0001-0008, `-dev HTP0 -ngl 99`, `-c 4096 -np 1`, `GGML_HEXAGON_OPPOLL=1`, a fresh `--seed` per reply, thermal status at most 1 at each start), the 5.3 s question of F170 sent at once, session set up as F166 (voice, language, answering instruction, text and audio); the guard's warning in the server log. Clips heard by the user, 2026-11-18; not committed.
- **Means:** D96's phone check is done: the thresholds measured on the Mac (F167) hold on the NPU. One runaway in 20 is above F167's 1.6% but within chance for so few seeds.
- **Feeds:** D96, F167.

### F170. On the phone's NPU a realtime voice turn answers 0.3 s after the user stops, speaks at 1.65 times real time and stops 80 ms after a cancel; captioning the user's words costs 0.6 s alone, 0.8 s more before the reply, or 0.3 s on a second slot

- **Finding:** With the audio sent at real time, as a microphone gives it, the session has encoded it by the commit (committed within 3 ms), and the reply's first text comes 0.13-0.15 s and its first audio 0.29-0.31 s after the commit; sent all at once, as F166 did, the first audio is 1.5-1.6 s after it, the question's encoding. The 20.3 s reply took 12.2-12.4 s from the commit: 1.64-1.66 times real time. `response.cancel` 2 s into the audio ends the reply 79-84 ms later (`response.done`, status `cancelled`); closing the socket frees the slot in 1.0 s, the handler's polling of its connection (F168), and a new session's first event follows in 10 ms. The server takes about 1.8 GB of the phone's available memory with the model loaded and a reply made (5.7 → 3.9 GB), nearly all in buffers shared with the NPU: its own RSS is 76-89 MB. A session holds its slot while it is open, so with one slot a transcription request waits until the session ends (it timed out after 20 s). Transcribing the 5.3 s question (`/v1/audio/transcriptions`, the same model) takes 0.59-0.61 s warm (1.7 s the first time) and gives its words exactly. With two slots (`-np 2`, twice the context, the same per slot), transcribing the turn after the commit and then asking for the reply moves the first audio to 1.04-1.26 s (from 0.32); transcribing while the reply is made gives the transcript 1.7 s after the commit, moves the first audio to 0.59-0.63 s and slows the reply to 1.49-1.51 times real time. Two slots cost about 0.1-0.4 GB more (the readings drift by 0.3 GB between runs).
- **Evidence:** OnePlus 13, thermal status at most 1 at each start, device tools of `build-device-tools.sh` with patches 0001-0008, LFM3-Omni-2B Q4_K_M with its Q8_0 projector, `-dev HTP0 -ngl 99`, `-c 4096 -np 1` (`-c 8192 -np 2` for two slots), seed 42, `GGML_HEXAGON_OPPOLL=1`. `research/voice/phone-step4.sh` modes `timings`, `memory` and `two-slots`, driving `research/voice/phone-turns.py` over `adb forward` with a 5.3 s question spoken by macOS `say` ("Can you tell me a short story about a lighthouse keeper who finds a message in a bottle?"), the session set up as F166 (female voice, English, an answering instruction, text and audio): three paced turns, one sent at once, two cancels, three transcriptions alone, one transcription with a session open; two plain, two transcribe-then-reply and two transcribe-while-replying turns on two slots. Memory: `/proc/<pid>/status`, `dumpsys meminfo`, `/proc/meminfo` `MemAvailable`. The in-memory session runs the same handler (F168), so its times and memory are these, plus the app's own.
- **Means:** The voice session answers as soon as a push-to-talk button is released, and its speech stays ahead of playback. `cancelReply` stops speech within one 80 ms frame; a new session after `close` can open at once, but its slot is free only after about 1 s. Captioning the user's words in the SDK would need a second slot (`parallelSlots = 2`, more memory), since a session holds its slot; done in parallel it costs 0.3 s before the first audio, and done before the reply 0.8 s. The app can already get the words itself with `transcribe` on a second slot.
- **Feeds:** D95, D98, F166, F168, F169.

### F169. Upstream's realtime conversation sends no transcript of the user's speech; only a transcription session transcribes, one commit at a time

- **Finding:** In a `/v1/realtime` session of type `realtime` (the conversation), a committed turn becomes a user item whose content is `input_audio` with no transcript, and no `conversation.item.input_audio_transcription.*` event follows: `on_commit` returns right after the commit unless the session's type is `transcription`. Only a transcription session transcribes, and it does so instead of answering: each commit is transcribed on its own with the model's speech-recognition prompt, then the slot is cleared. The session type is fixed once the first audio or message has gone in. So one session can't both answer the user and caption the user's words, and the reply's text (`response.output_text.delta`) is the only caption a conversation gives.
- **Evidence:** b12479 source: `tools/server/server-realtime.cpp` `on_commit` (`if (!commit() || type != "transcription") return;`), `on_result` (transcription deltas only while `transcribe_item_id` is set, which only `on_commit` of a transcription session sets), `on_session_update` (the type can't change after the first audio or message); the committed user item in `commit()` has `{"type": "input_audio"}` and no `transcript`. The SDK's voice session on the test model (`HostVoiceTest`) gets no transcription event.
- **Means:** The Kotlin voice session (D98) has no event for the user's words. Captioning them needs a second pass over the turn's audio (for example `Model.transcribe` on the same model, which shares the one slot and may delay the reply) or a patch to upstream's handler; the cost of the second pass is measured on the phone in the voice plan's step 4 before choosing.
- **Feeds:** D95, D98.

### F168. Upstream's realtime handler runs unchanged on an in-memory connection and sends the same events as over a WebSocket; a session has no seed of its own

- **Finding:** `server_realtime_conversation` reads and writes only through `server_http_context::ws_conn` (`read`, `send`, `is_open`, `close`), sends text messages only, and polls `is_open` while it waits for the slot, so a connection made of a queue and the caller's callback runs it with no HTTP layer, and a closed connection ends it within a polling tick. It sends from two threads (its reader of client events and its result loop). Fed the same scripted events with the same seed, the in-memory session and upstream `llama-server` over a real WebSocket send identical events, ids aside: a spoken turn answered in text and speech (47 events: 12 text and 20 audio deltas), two turns answered in text, a transcription session (8139 events) and two bad events answered with errors. The sampling of a realtime response takes the server's `--seed` and nothing per request, so the comparison needs a fixed server seed.
- **Evidence:** Mac CPU, b12479 with patches 0001-0008, upstream's test model tinyLFM3-Omni-for-testing Q2_K and the check's spoken clip; `sdk/native/tests/contract/audio.jsonl` r-01 to r-04 through `http-run.py` (WebSocket) and `lb-run` (`lb_request_send`), both with seed 42: 12/12 identical with the audio cases. Changing one audio delta's bytes on one side makes `compare.py` report a difference. Host tests `realtime_turn_in_memory`, `realtime_misuse_refused`, `realtime_model_free_closes_session`. `tools/server/server-realtime.cpp:249, 928-975` (`is_open` polls, the reader thread), `server-realtime.cpp:1095` (the seed from the server's sampling).
- **Means:** The voice session needs no network port: the C API carries it as a request (D97). The callback of a realtime request runs on two upstream threads, one call at a time, unlike a plain request's single thread. A seeded realtime test needs the model config's `seed`.
- **Feeds:** D95, D97, F56.

### F167. About 1.6% of LFM3-Omni's captioned replies lose their place after the text ends and never sample the end of audio; a guard on silence and length stops all of them and no clean reply

- **Finding:** In text-and-audio replies (`/v1/realtime`, `output_modalities: ["text", "audio"]`) the model writes its text ahead of its speech, and after `<|text_end|>` speaks the rest with audio alone until a frame samples the end-of-audio code. In 10 of 600 replies to the same 7 s question it loses its place near the end: the voice stops at the last words it had reached and the audio turns to near-silence with bursts of noise (the "ghastly sounds" of F166) or to garbled speech, and the end code's probability stays under 0.03 for over a thousand frames where a clean reply reaches 0.98, so the audio runs until the context is full (2-6 minutes). The rate does not depend on the session's prompt (3 of 200 seeds without one, 1 of 200 with the model card's trained "Respond with interleaved text and audio.", 2 of 200 fresh seeds across both), on CPU load or on our patches: plain upstream b12479 does it, and upstream's loop matches Liquid's reference (`Lfm3OmniForConditionalGeneration._generate`: 6 text and 12 audio slots, audio alone after `<|text_end|>`, any codebook's code 2016 ends it, no cap but the caller's `max_new_tokens`). The depthformer's confidence does not warn of it (top-code probability 0.22-0.29 in both kinds), but the audio does: clean replies use at most 0.37 s of audio per text token (median 0.28) and never fall quiet for 3 s after the text ends, while failures fall silent within seconds of losing their place or run long. Ending the speech at 3 s of near-silence (80 ms frames under RMS 0.004) or at 5 frames per text token plus 13 stops all 10 failures (seed 111: at 34.4 s of 145 s; the user heard its speech stop at about 32 s) and no clean reply, in the 400 replies the thresholds were chosen on and in 200 fresh ones. Patch 0008 does this in LFM3's speech helper, ending the response with an end-of-audio frame; left to continue after a forced end, the model reopened interleaving and looped silently until the context was full.
- **Evidence:** Mac CPU, upstream `llama-server` at b12479 built with upstream's CMake, LFM3-Omni-2B Q4_K_M, `-c 2048`, one turn per `--seed` with `research/voice/realtime-turn.py` (`realtime-sweep.sh`): seeds 13-212 without a prompt and with the trained prompt plus female voice and English, seeds 213-312 both ways for validation; `research/voice/tail-guard-sim.py` replays the guard over the recorded turns. A per-frame trace (research build, not committed) of seeds 111 and 13 gave the end-code probabilities and depthformer confidences. Liquid's reference read on Hugging Face (`LiquidAI/LFM3-Omni-2B`: `config.json`, `generation_config.json`, `modeling_lfm3_omni.py`, partly through a summary). The user listened to seeds 111 and 226 (2026-11-18). With the patch, a host build on seeds 13 and 50 gives byte-identical audio, and seeds 111, 132, 189 and 21 end at 34.5, 37.5, 51.5 and 63.5 s with their captions unchanged; seed 111 takes 13.6 s like a clean reply. Phone not yet run.
- **Means:** The voice mode's captioned replies need the guard: without it about one reply in sixty plays minutes of noise. The thresholds are this model's (LFM3-Omni-2B, English, one voice), set from one question; another model or language needs its own measurement. The trained prompt is still worth sending (tighter replies, median 25 s against 38 s), and a maximum reply length stays useful as a last bound. Upstream's loop is not wrong, so this is reported as the model's behaviour with the data, not as a bug.
- **Feeds:** D95, D96, F154, F166; patch 0008.

### F166. Upstream's realtime conversation with LFM3-Omni-2B needs a voice, a language and an instruction to answer; with all three one session speaks a whole reply in one steady voice, 1.6 times real time on the NPU with captions

- **Finding:** One turn of `/v1/realtime` (a spoken question of 7 s, then `response.create`) behaves very differently by session setup. With no voice, language or instructions, text-and-audio output (`output_modalities: ["text", "audio"]`, the captioned mode D95 chose) ends its text after 9 s but never ends its audio: the depthformer never samples the end code, so frames run until the 4,096-token context is full, about 300 s, and the user heard the speech turn into "ghastly sounds" after about 20 s. Audio-only output stops by itself but speaks a language that is not English. With a voice and language set (`audio.output.voice: "female"`, `language: "en"`), upstream's system prompt becomes "Speak in English using a female voice." and the model reads the question back instead of answering. With those plus an instruction to answer ("You are a helpful voice assistant. Answer the user's spoken question in your own words."), every run stops by itself with a fitting story (25-27 s of audio on the Mac), and the user heard one steady voice from start to end. The same setup without voice and language also works, but its pauses grow unnaturally towards the end. On the phone's NPU (setup with all three, polling on) two stories of 19.8 s and 21.5 s took 12.7 and 13.3 s: 1.56-1.61 times real time, the first audio 1.9 s after `response.create`, no unsupported op. The user found both clear and steady, with one short phrase in each hard to make out through a heavy accent. Plain upstream b12479, with none of our patches, runs on in the same way, so the patches play no part.
- **Evidence:** `research/voice/realtime-turn.py` (a standard-library WebSocket client: `session.update`, 100 ms `input_audio_buffer.append` chunks, `commit`, `response.create`, deltas collected), questions made with macOS `say` and `afconvert` at 16 kHz. Mac: upstream `llama-server` built with upstream's CMake (CPU, `-dev none --no-mmproj-offload`), with our patches and without. Phone (OnePlus 13, thermal status 0): the device tools of `build-device-tools.sh`, `-dev HTP0 -ngl 99`, `GGML_HEXAGON_OPPOLL=1`, `adb forward`. Upstream's speech prompt: `common/chat.cpp` `common_chat_get_speech_system_prompt`; the end of audio: `tools/mtmd/mtmd-helper-gen.cpp` `step_gen`. Clips heard by the user, 2026-11-18; not committed.
- **Means:** D85's open question is answered for the voice mode: inside one realtime session a reply is one generation and keeps its voice, so no speaker reference or temperature change is needed. A voice session must set a voice, a language and an answering instruction; without them the captioned mode can speak noise for minutes, so the SDK's session needs those in its configuration, and a cap on output tokens is worth having (`max_output_tokens`). Speech in the captioned mode runs at 1.6 times real time on the NPU, slower than `/tts`'s 2.3-2.4 (F165) since the text is generated too, and still ahead of playback. The first audio after a fully sent question is 1.9 s on the NPU; in a live session the audio is encoded while the user speaks, measured in step 4 of the plan.
- **Feeds:** D85, D95, F154, F165.

### F165. At b12479 the speech patches still apply and are still needed, and 0006 can ride on upstream's two output flags: an embeddings-only output skips the LM head, byte-identical audio, the same 4.5 ms per frame saved on the NPU as the old version

- **Finding:** On b12479 (`d92ea9bb1`) patches 0005 and 0007 apply unchanged and 0006 had one rejected hunk: upstream's `llama_batch_ext` migration (#32356) has LFM3's speech helper decode a sampled token with `decode(sampled)` instead of `llama_batch_get_one` + `decode(batch)`, so the head goes back on just before that call. All three are still needed: `lfm3a-gen.cpp` is unchanged, Hexagon still lacks `ARANGE`, nothing upstream skips the text head for audio frames, and `htp-drv.cpp` still reads without blocking only `#ifdef _WIN32`. Upstream's new batch API has `llama_batch_ext_set_output_embd` and `_set_output_logits`, documented as "for now equivalent", with a `TODO: have dedicated output flags` in `llama-batch.h`; the one other caller of `set_output_embd`, YuE2's NAR decode (`mtmd.cpp`), reads only embeddings. Making the flags differ, so that a decode whose outputs all ask for embeddings only leaves the head out of the graph, gives the same skip as the b12352 patch's context-wide `llama_set_logits`, with no new API and no per-batch toggling in the server. On the Mac CPU (LFM3-Omni-2B Q4_K_M, the 55-word `/tts` of `device-speech`, seed 42, 20.0 s of audio, three alternating rounds): upstream without 0006 6.89-6.96 s, the b12352-style 0006 5.99-6.26 s, the re-cut 6.18-6.27 s; every WAV byte-identical across the three builds. The skip is about 2.9 ms per 80 ms frame on the CPU. On the phone's NPU (`GGML_HEXAGON_OPPOLL=1`, as `device-speech`), three builds in alternating order, three runs each at thermal status 0: without 0006 37.5-39.7 ms per frame (2.01-2.14 times real time), the old 0006 33.6-34.2 (2.34-2.38), the re-cut 33.2-34.4 (2.33-2.41); means 38.3, 33.8, 33.8 ms, so both versions save 4.5 ms per frame (F156: 4.1 at b12352). All nine WAVs are identical (19.84 s), and no run reports an unsupported op.
- **Evidence:** `git apply` of each patch on a `git archive` of the submodule with 0001-0004 applied (2026-11-18). Upstream `llama-server` built three times with upstream's CMake, CPU only (`-DGGML_METAL=OFF`), `-dev none --no-mmproj-offload`, from that tree with no 0006, the old 0006 and the re-cut; `/tts` timed with curl, WAVs compared by SHA-256 (all `bb96a014…`). `sdk/scripts/check.sh` ALL PASS with each version (`omni_tts_same_audio_as_upstream`: 145,964 bytes, fnv1a `8208b204b9c01943`, as upstream); `demo/scripts/check.sh` ALL PASS with the rebase. Phone (OnePlus 13, 2026-11-18): device tools built in the Snapdragon image three times (`build-device-tools.sh`, `LB_DEVICE_TOOLS_NAME`), each served `/tts` once per round with the 55-word text, seed 42, `-dev HTP0 -ngl 99`; per frame = request time / (audio / 80 ms). `sdk/scripts/check.sh --device` ALL PASS with the re-cut: device contract 76/76 identical, `device-speech` 2.31 times real time with no unsupported op, acceptance only the tolerated F27. `demo/scripts/check.sh --device` passes with three journeys skipped on this phone, as in F163; its monkey step, which skips the phone (F117), passes on the emulator (fake engine).
- **Means:** the speech patches ship again on the voice branch, and 0006 is re-cut on the output flags (D94); once upstream makes the flags differ, only the helper's frame hunk and the server's sample skip remain. Speech on the NPU runs 2.3-2.4 times real time at b12479, as at b12352 (F162).
- **Feeds:** D86, D90, D94, F155, F156, F162.

### F164. Since b12479 a load's staging buffer returns its pages on macOS as soon as it is freed, so the Mac's peak memory for the DFlash fit test fell 543 MiB; the projection did not move

- **Finding:** At b12479 `dflash_fit_matches_load_rss` (Qwen3.5-4B with the DFlash draft, 4096 tokens, answered once) peaks at 5,149-5,153 MiB of footprint on a quiet Mac, nothing compressed, against 5,587-5,770 at b12352 (F160). Upstream's projection is unchanged, 5,793 MiB, so the ratio went from 1.00-1.04 to 1.125, past the test's bound of 1.12. The cause is one upstream commit, #31438 (`3e41ae69d`, `llama-model-loader.cpp`): the staging buffer that weights pass through on their way to a non-host buffer is now allocated from ggml's CPU buffer type, which on Apple uses `vm_allocate`, so freeing it gives its pages back at once. Before, it was a `std::vector`, whose freed pages stayed in the process's footprint, and the buffer holds the largest tensor (Qwen3.5-4B's token embedding is about 500 MiB). The projection never counted the buffer, so at b12352 it matched the peak only because that buffer's leftover pages filled the gap.
- **Evidence:** `lb-tests` at b12479, `ctest -R dflash_fit_matches_load_rss -V`, three runs each: as pinned, 5,149-5,153 MiB peak footprint, 0 MiB compressed, ratio 1.124-1.125; the same build with only #31438 reverted (a temporary local patch, removed afterwards), 5,692 MiB in all three, ratio 1.018. The first failing run in the full check read 5,159 MiB with 1,120 MiB compressed, so host pressure (F160) was not the cause. 2026-11-17.
- **Means:** an app on Apple hardware sees less peak memory during a load. On Android the CPU buffer type allocates with `posix_memalign`, and the change was not measured there. The test's upper bound goes from 1.12 to 1.20 (the user chose, 2026-11-17): F90's double-counted recurrent state (+754 MiB) would now read about 1.27, so the bound still catches it. The fit projection itself is upstream's and stays as it is.
- **Feeds:** F58, F90, F160, D93; `dflash_fit_matches_load_rss`.

### F163. The upgrade to b12479: the contract still holds on the Mac and the NPU, two patches needed porting to the server refactor, and the delta-net gate still pays (+67%)

- **Finding:** At b12479 (`d92ea9bb1`, upstream master on 2026-11-17, 127 commits after b12352; untagged on the day, since tagged `b12479` by upstream) the SDK matches upstream llama-server on every contract case on the Mac and on the phone's NPU, where all 76 cases are identical (no near-tie split this time, D80). The web client moved from `tools/ui` to `ui/apps/chat` and is now called Llama Chat. Its compaction code changed only its imports (`$lib/...` to `#lib/.../index.ts`), so the 400-case golden is identical apart from its source path. The hub's golden is unchanged. Patches 0001 and 0002 apply unchanged, and neither can be dropped: Hexagon's gate is still `K == 1` (`htp-kparams.cpp:567`), GDN replay (#31669) and the BF16 NEON path (#30432) are still open PRs, and `vec.cpp` still has no ARM BF16 dot product. Patches 0003 and 0004 no longer applied after upstream's server refactor (#32154, `server_slot_sched` with a prompt running hash). 0003 ports with no change in behaviour: the progress callback, the three load stages and the setter sit where they did. For 0004, the SWA threshold moved unchanged into `server_prompt::get_reuse` (`server-slot-sched.cpp:23`), still `n_swa > 0 ? pos_eval - n_swa + 2 : pos_eval`, so upstream still has the hybrid-SWA cache bug (F153). The port passes `get_reuse` the SWA window only when the memory can be partly removed, else 0, at its one caller; that is the same rule as before. With MTP on the NPU, the gate (0001) still makes prefill much faster: 381-394 t/s without it, 635-655 with it (means 388 and 648, +67%; F149 measured +61% at b12352). With MTP off it changes nothing (912 and 900 t/s).
- **What changed for the SDK:** the build is named b12479 (`LB_LLAMA_BUILD`, the version tests, the hub's user agent, the demo's About row); llama.cpp itself is now version 1.0.0 (#32190), which does not reach `build_info`, still `b12479-d92ea9b`. `server.cpp`'s two functions the bridge copies, the backend loader it ports (`ggml-backend-reg.cpp`) and the audio file check (`mtmd-helper.cpp`) are unchanged. The old batch API (`llama_decode`, `llama_batch_init`) is deprecated in favour of `llama_batch_ext` (#32306, #32356); the bridge calls neither. Two DFlash variants, DFLASH2 and LiliCorr (#32016; a DFLASH2 model in #31904), have their own `--spec-type`; the SDK's `dflash` still selects `draft-dflash` and does not offer them. New and unused by the SDK: `server_context::set_video_decoder` (#32188) and `--tts-speaker-text` (#32330). The Mac's peak memory during a load fell (F164). Behaviour that reaches apps through llama-server, and that the contract therefore matches: empty stop strings are ignored (#32093); a `reasoning_effort` level the template rejects is answered with 400 (#32332); `/v1/messages` keeps `output_config.effort` (#32321); LFM2's tool-call grammar accepts undeclared tools (#32164); Gemma4 and Ministral-3 may call tools while thinking (#32232, #32271); the slot cache clears when the global LoRA config changes (#30367). Speed work the SDK gets with no change: grammar matching by left factoring (#29557, typed output, D63), less Jinja rendering overhead (#32023), and no O(n) cell scan per sequence removal (#32380). On the NPU: Hexagon buffer mapping (#32144), the flash-attention no-mask tail (#32243), new ops SSM_SCAN and TIMESTEP_EMBEDDING, IQ2_XXS and NVFP4. On the GPU: OpenCL fixes (q4_K mat-vec out-of-bounds read, f16 swiglu_oai, three flash-attention prefill defects, #32251, #32110) and Adreno A7 Q4_K, Q6_K and Q8_0 kernels. New speech models (VibeVoice-ASR, Higgs v3 STT, Zonos3-mini TTS, Fish Audio S2, YuE2-3B, MiniMax-Music3) are reachable only through llama-server's routes; the voice phase looks at them (D85).
- **Evidence:** `sdk/scripts/check.sh` ALL PASS on the host (contract on Qwen3.5 4B with its projector, Llama 3.2 3B and upstream's audio test model) and with `--device` (OnePlus 13: device contract 76/76 identical on `HTP0`; acceptance full flavor 28 of 29, the 29th the tolerated F27; cpu, gpu and noskel flavors and the minified builds; hub on the phone); `demo/scripts/check.sh` ALL PASS on the host and with `--device` (journeys over the fake engine; three skipped for this phone's own reasons: a photo picker, and no microphone for tests, F150). The 0001 A/B: upstream llama-server built at `d92ea9bb1` with upstream's CMake (`research/b12352/build-at.sh`) with and without the patch, Qwen3.5 4B Q4_K_M on `HTP0`, the 2,071-token prompt of F121, MTP on (`--spec-draft-n-max 3`), three rounds in alternating order, thermal status 1 in every run (F149's were at 0). `git log 5676dfbe0..d92ea9bb1`, 2026-11-17.
- **Means:** the pin moves to b12479 (D93) with the same four patches. Open from the release: whether F75's `GGML_OPENCL_COOPK_DP4A=0` is still needed after the OpenCL fixes, and whether OpenCL drafting still aborts (F80, F125); DFLASH2 and LiliCorr drafts; the speech models above and the speech patches on `voice/speech-npu` (D90), the voice phase's next step; whether #31438 lowers peak memory during a load on Android too (F164).
- **Feeds:** D10, D52, D80, D91, D93, F121, F149, F153, F164.

## PH: the headline API (F139–F162)

### F162. With NPU polling on, Android's DSP library logs an error for every empty poll and drowns the app's log; upstream's Windows path, a no-block read, removes it at the same speed

- **Finding:** `GGML_HEXAGON_OPPOLL=1` (`LlamaBroConfig.npuPolling`, on by default since D86) makes ggml-hexagon read the DSP queue with a zero timeout in a loop. On Android, `dspqueue_read` with a zero timeout returns "expired" and has Qualcomm's `libcdsprpc` log an error line (`dspqueue_cpu.c:1864 … wait_signal_locked … DSPQUEUE_SIGNAL_RESP_PACKET`) for every empty poll. An app loading LFM3-1.7B on the NPU wrote about 82,000 such lines in a few seconds, and the phone's log flow control (`LOGS OVER PROC QUOTA(300) … DROPPED`) then dropped the app's own lines too, so the minified acceptance build's "loaded" line never reached logcat and `check.sh --device` failed. Nothing is logged while the NPU is idle. Upstream's `htp-drv.cpp` already sends a zero-timeout read to `dspqueue_read_noblock`, which answers "would block" without logging, but only under `#ifdef _WIN32`. Doing the same on every platform (patch 0007) leaves one info line per queue, and the speech speed is unchanged.
- **Evidence:** OnePlus 13, `ph-main` after f8f78d1. The acceptance app's full minified build, cold start on the NPU: 82,076 lines from the app in about 4 s, the "loaded" line dropped (2026-11-17, `check.sh --device`, step `device`). `device-speech` (LFM3-Omni-2B `/tts` on `HTP0`, polling on, 55 words, 19.84 s of audio), logcat cleared before each run: without the patch, 2.26 times real time with 2,900 error lines left in logcat after 10 quota drops; with patch 0007, 2.27 and 2.26 times real time with no error line and no drop (thermal status 1). F156's energy measurement included the logging, so polling's cost can only fall.
- **Means:** patch 0007 carries upstream's Windows path to Android. `npuPolling` stays on by default (D86). Without the patch, an app with polling on loses its own logcat lines whenever the NPU works. A fact sheet for upstream is for the user to post.
- **Speed on a cool phone (2026-11-17, later):** the 2.26-2.27 above came straight after about 25 minutes of device gates (thermal status 0-1, but a warm phone). On branch `voice/speech-npu` (patches 0001-0007, polling on), with the phone cooled (NPU sensors 34-36 °C, status 0) and a minute between runs, `device-speech` gave 2.39, 2.37 and 2.38 times real time, the same as D86's 2.40. So patch 0007 costs nothing, and a warm phone loses about 5%.
- **Feeds:** D86, F156; patch 0007.

### F161. A quick Pause→Resume can't run two writers within one hub; two hubs reaching one cache through a link failed instead of waiting, since the JVM refuses a second lock on a file it already locks

- **Finding:** `BlobDownloader` writes each blob under `Locks`: a coroutine mutex per lock-file path plus a kernel `FileChannel.lock`. A cancelled transfer keeps the lock until its write loop really exits, so a resumed download of the same blob waits for it, and within one hub there is never a second writer. The mutex was keyed by `absolutePath`, so two hub objects on one cache whose folders are spelled differently (a symlink, as `/data/data` is to `/data/user/0`) took different mutexes; the second `FileChannel.lock()` on the same file in the same JVM then threw `OverlappingFileLockException` rather than blocking, and the resumed download failed with `HubException.Network`. `LlamaBroHub.create` already gives one hub per canonical folder (D70), so an app reaches this only through two hubs built another way. The mutex is now keyed by the lock file's `fileKey()` (device and inode), exactly the key the JVM's lock table uses, with the canonical path where a file system has no key; the second hub then waits and resumes the same partial.
- **Evidence:** `OneWriterTest.quickResumeWaitsForTheStoppedWriter` on the host JVM, 2026-11-17: a paused download at 1 MiB of 4 MiB at 0.5 MiB/s, a resume through a second hub on a symlinked alias of the cache, seen waiting on the lock, then the first cancelled. With the `absolutePath` key: `download failed`, caused by `OverlappingFileLockException`. After: complete, sha256 correct, at most one open writer.
- **Means:** W5's note that a quick Resume can start a new worker while the old one still writes the same partial does not hold for the SDK's worker, since the hub serialises; the demo's own `awaitStopped` covers only its UI state. The identity key stays as cheap insurance (D89).
- **Feeds:** D89, D44, D70.

### F160. On macOS a process's peak RSS under-counts its memory when the host is short of it; the peak footprint holds steady, so the DFlash fit test measures that

- **Finding:** `dflash_fit_matches_load_rss` compared the projection for Qwen3.5-4B with the DFlash draft at 4096 tokens (5,793 MiB) with the process's peak RSS (`ru_maxrss`). macOS compresses resident pages when the host runs short of memory, and compressed pages leave the resident set, so the peak RSS followed the host's state. It read 5,081-5,781 MiB in seven runs of one build on a 32 GB Mac with 23 GB of swap in use, 4,558-5,151 MiB while another process held 14 GiB, and it gave the 2026-11-15 failure (ratio 1.27 against the bound 1.18). The task's peak footprint (`TASK_VM_INFO` `ledger_phys_footprint_peak`) counts compressed pages. In the same quiet runs it read 5,587-5,770 MiB (ratio 1.00-1.04), and it read 5,770 under the 14 GiB load with a peak of 2,022 MiB of the process compressed (`compressed_peak`, the most compressed at any moment of the run). Twice under that load it fell to 5,189-5,251 (1.10-1.12), so heavy pressure can still lower it, likely through swap (not confirmed). The weights are read into memory, not mapped (`--load-mode none`, F58), so the footprint holds them. The host build has no Metal, so no GPU memory goes uncounted.
- **Evidence:** `lb-tests` at `ph-main` 2359168 with this change, `ctest -R dflash_fit_matches_load_rss`, printing peak RSS, peak footprint and the amount compressed at the peak. Memory pressure came from a Python process holding 14 GiB of random bytes. The full native suite passed 78/78. 2026-11-17.
- **Means:** the test measures the peak footprint on macOS (peak RSS on Linux), and its bound tightens from 0.90-1.18 to 0.95-1.12. Against the footprint, F90's double-counted recurrent state (+754 MiB) would read 1.13-1.17, which the old bound let through. When the test fails with a compressed peak above a tenth of the process, it says the host was short of memory. F90's 5,208 MiB peak was itself an under-count; its conclusion stands, and the projection is closer than F90 measured (within 4% of the footprint).
- **Feeds:** F58, F90; `dflash_fit_matches_load_rss`.

### F159. Fixed ports let concurrent SDK checks collide, and a port held by another process can answer a check's health poll; ports the OS picks remove both

- **Finding:** `sdk/scripts/check.sh` used fixed ports on this machine: 18181 for the host contract (`LB_CONTRACT_PORT`), 18182 for its extra models, 18185 for its audio cases, 18183 for `e2e`, and 18190/18191 for the fake Hugging Face server. The device contract and device speech used the same port at both ends of their adb forward (18182, 18183). With two full checks running in two worktrees, the second `llama-server` cannot bind its port and exits. The step's health poll on the fixed port can then be answered by the other check's server, or by an adb forward another session left behind (F150), so requests reach a different build's server or the phone. Now every server on this machine listens on port 0 and the script reads the port back from that server's own output (`llama-server`'s "listening on", `fake_hub.py`'s first line). adb picks this machine's end of each forward (`adb forward tcp:0`). The ports on the phone stay fixed, which the one-session-per-phone pre-flight (`sdk/scripts/device-preflight.sh`) makes safe. Fixed ports remain available through `LB_CONTRACT_PORT`, `LB_E2E_PORT`, `LB_HUB_API_PORT`, `LB_HUB_CDN_PORT`, `LB_DEVICE_CONTRACT_PORT`/`LB_DEVICE_CONTRACT_HOST_PORT` and `LB_SPEECH_PORT`/`LB_SPEECH_HOST_PORT`.
- **Evidence:** with another listener on 127.0.0.1:18181, the audio contract (`tests/contract/run.sh` with `audio.jsonl` on upstream's tiny LFM3-Omni) fails with `LB_CONTRACT_PORT=18181` (llama-server: "couldn't bind HTTP server socket … port: 18181") and passes 8/8 identical on the default port 0. With 18190 and 18191 held, check.sh's `start_fake_hub` got its own ports and answered on them; against a stub adb its reverses were `tcp:18190` → `tcp:<api>` and `tcp:<cdn>` → `tcp:<cdn>`. Upstream's port 0 support: `tools/server/server-http.cpp` (`bind_to_any_port`). The collision was seen 2026-11-15; this entry 2026-11-17.
- **Means:** full SDK checks can run in several worktrees on one machine at once; the device steps still need the phone to themselves, and the pre-flight refuses when another session holds it (a live lock, a stray forward or reverse, a running server or test app). `demo/scripts/check.sh` still bound its fake hub to fixed ports and took no phone lock when this was written; its move to the same scheme is part of W9 step 4's demo work. **2026-11-17, later:** done (e54d05e): the demo's check uses ports the OS picks for its fake hub and the same pre-flight and phone lock.
- **Feeds:** F150; `docs/testing.md` (SDK checks: ports, device pre-flight).

### F158. Integer type bounds cost little in llama.cpp's grammar; an unsupported `pattern` is dropped silently

- **Finding:** On the host (llama.cpp at `ph-main`'s submodule, LFM3-1.7B's vocabulary loaded vocab-only, no inference), schemas were measured with integers unbounded and bounded to their Kotlin type's range, through upstream's `json_schema_to_grammar` and grammar sampler. One `Int` field grows the GBNF from 190 to 2,289 bytes; a forecast (a string and four `Int`s) from 564 to 9,215 bytes (`Short` bounds 2,607, `Byte` 1,087); twelve `Int`s plus a list of `{x, y}` from 1,175 to 31,612 bytes. Conversion plus sampler build stays under 1 ms per request (0.06 → 0.73 ms for the largest; 1.4 ms in a noisier run). Checking a sampled token against the grammar, upstream's usual path, goes from about 1.8 µs to 3.3-3.8 µs per token; the full-vocabulary pass, run only when a sampled token is rejected, from 5.8-6.0 to 9.1-10.5 ms. A `Long`'s bounds are int64's limits, which upstream treats as unbounded (its integer rule already caps at 16 digits). Separately, upstream at this version turns an unsupported `pattern` into "any string" with only a log warning (an unanchored pattern, `\d`, `\w`, `\p{…}`, lookarounds, POSIX classes); only a malformed one (unbalanced parentheses) throws, which llama-server answers with 400 `invalid_request_error`.
- **Evidence:** a harness linking the host `libllama-common` and `libllama` (`grammar_cost.cpp`, `run.py`, in the W9 session's scratchpad, not kept), bound and unbound documents tokenized and fed token by token; two runs, 2026-11-17, host only.
- **Means:** bounding `Byte`, `Short` and `Int` by default costs a larger grammar but well under a millisecond per request and microseconds per token on the common path (D88). The phone was not measured; expect a few times the host cost, still small. `@LbPattern`'s failure mode is mostly silent, not an error at the first request; the docs say so.
- **Feeds:** D88, D63, F143.

### F157. Chat media sent as file:// instead of base64 halves a voice-note turn on the phone, with no llama.cpp change; a raw-buffer patch adds little

- **Finding:** In a chat holding three voice notes (2.3 MB of WAV) on LFM3-Omni-2B on the NPU, a text turn takes
  192-200 ms with today's base64 data URLs and 93-94 ms when the bridge writes `file://<path>` and llama-server reads
  the file itself (`--media-path`); handing the bridge's buffer over directly (a research patch) gives 80-93 ms. The
  server's prompt work is 40 ms in every case; most of the rest (~45 ms) is llama-server decoding each clip again per
  turn to match its prompt cache, which neither variant changes.
- **Evidence:** `research/ph/media-path/` (`media-ab.sh`, `results-2026-11-17.md`, `media-research.patch`), three
  rounds, run attended 2026-11-17 through the C API (`lb-run`) at `ph-main` 39577de with the research switches; the
  carried bridge (D87, 0e087b7) gives 91-92 ms against 189-191 ms on the same build.
- **Means:** D84's base64 costs ~100 ms per turn with voice notes in the history; `file://` removes it without a patch,
  so upstream's way wins over a raw-buffer patch (no measurable gain). Carried (D87): path attachments linked into the
  SDK's media folder and sent as `file://`; bytes and descriptors keep base64; a caller-written `file://` reaches only
  that folder.
- **Feeds:** D84, D87; F152, F153.

### F156. Speech on the NPU alone reaches 2.4 times real time once the speech graph has no CPU op, the host thread polls and the unused text head is skipped; faster than the split

- **Finding:** A per-stage timer on the phone (LFM3-Omni-2B `/tts`, 55 words, seed 42) splits each 80 ms frame into
  the LM step, the depthformer (3 MaskGIT passes and a feedback pass), the codec and the server's text sample. Three
  things decide the speed. (1) The depthformer's positions come from `ARANGE`, which the NPU does not support, so the
  speech graph hops NPU → CPU → NPU in every pass; passing the positions in as an input, together with the graph fix
  that reads each codebook's row from the 3-D table (no flattened view, so the table can stay tiled; F155), removes
  every CPU op, and the depthformer drops from 30.3 to 15.8 ms per frame, faster than on the CPU (19.3). Keep-flat
  (patch 0005) cannot get there, since its matmul runs on the CPU. (2) With the work on the NPU, the host thread
  sleeps between submissions and its remaining work runs 3-4 times slower, likely from frequency scaling;
  `GGML_HEXAGON_OPPOLL=1` (polling) restores it: 1.55 → 2.04 times real time. (3) Every audio frame computed the LM's
  2048 × 128,000 text head and sampled a text token that the audio branch ignores; skipping both saves 4.1 ms per
  frame with bit-identical audio. Together, with flash attention: 2.36-2.38 times real time, against 1.68 for the split
  (1.83 with the head skipped). Hexagon's host-side preparation is small (0.56 ms per frame). Polling speeds text
  generation by 4-7% (Qwen3.5-4B 15.7 → 16.3 tok/s, LFM3-Omni-2B 38.5 → 41.2).
- **Evidence:** `research/ph/speech-output/stages-2026-11-17.md`, `tts-stages.sh` and the research build
  `stages-research.patch` (every change behind an `LB_*` switch), run attended 2026-11-17 on the phone, cooled to
  thermal 0 before each configuration; Mac CPU and Metal checks byte-identical.
- **Means:** the fast way to speak on this phone is everything on the NPU, given three changes: the speech graph
  without `ARANGE` and with the 3-D lookup (mtmd), the text head skipped in audio frames (llama and the server), and
  polling. The user listened (one text, seed 42): the NPU clip with flash attention sounds very slightly better than
  without, and both slightly better than the split; one take each, so read it as no loss, not as a gain. Over
  sustained speech on battery (about 210 s of audio each), polling draws 4.44 W against 3.50 W but finishes sooner,
  so the energy per second of speech is the same (1.74 vs 1.73 J above idle); the NPU runs hotter while speaking
  (90 vs 79 °C), and neither throttled. The split needs 2.28 J per second of speech, about 30% more. Polling helps by keeping
  the host core from idling, not by waking sooner: checking with 50-1,000 µs naps between checks gives only 1.80-1.87
  times (blocking 1.75, spinning 2.34), with ~6 waits per frame. Operating-system hints (ADPF) are the alternative and
  need the app; the shell-launched server is unrestricted, while an app runs in `top-app` or `background`. Carried
  (D86): patch 0005 (positions input, 3-D lookup), patch 0006 (no text head in speech-only frames) and
  `LlamaBroConfig.npuPolling`, on by default (since D90 off by default again, and patches 0005-0006 moved to branch `voice/speech-npu`). On the phone with the real device tools (patches 0001-0006, polling, no
  flash attention): 2.40 times real time, no unsupported op in the speech graph (`device-speech`; the tools before the
  patches fail it on `ARANGE` and the CPU matmul). Flash attention stays off on auto for this graph: upstream sets
  `flash_attn_auto = false` for LFM3's speech generator because CUDA lacks the head-dim-32 kernel
  (`mtmd-model.cpp`); `-fa on` adds ~3% on the NPU. These are upstream-worthy facts for the user to report.
- **Feeds:** D37, D53, D85; F154, F155.

### F155. LFM3-Omni's speech part gives empty audio on the NPU because Hexagon tiles a weight that a lookup also reads; keeping such weights flat fixes it, but the CPU stays faster for the speech part

- **Finding:** With everything on the NPU, `/tts` returns an empty WAV (F154) because Hexagon's runtime repacking
  (`ggml_hexagon_graph_repack`) tiles each matmul's weight in place, and the depthformer's codebook table
  (`a.gen.code.embd.weight`) is also read by a `GET_ROWS`, through a reshaped view, that embeds the sampled codes. After
  tiling, that lookup reads tiled bytes as rows, and the first frame samples the end-of-audio code. Every op passes
  `test-backend-ops` alone on the NPU with the depthformer's shapes; `GGML_HEXAGON_RT_REPACK=0`, or `MUL_MAT` kept off
  the NPU, makes it speak. The language model shares `token_embd` the same way, but upstream keeps it in a host buffer,
  which is never tiled. A per-graph check misses it: `ARANGE` runs on the CPU, so the lookup and the matmul are in
  different graph splits. A fix that marks such weights keep-flat on the tensor (unpacking one already tiled) and makes
  `can_tile` refuse them, makes the NPU speak: 1.33-1.39 times real time,
  first audio 0.13-0.19 s, against 1.66-1.73 times for the split (speech part on the CPU, F154). Skipping the repack
  alone crashed the DSP, because the kernel was still chosen for a tiled weight.
  Correction, 2026-11-17: Hexagon multiplies quantized weights only in tiled form, so a kept-flat weight's matmul is
  refused by the NPU and runs on the CPU: 992 of 994 `supports-op` answers say no for the codebook's logits matmul,
  and the depthformer's graph splits NPU → CPU → NPU on every pass. That hop is likely part of why the NPU path
  (1.33-1.39×) trails the split. The better fix teaches the NPU's `GET_ROWS` to read the tiled table, and the view to
  report its base's tiled mark, keeping the matmul on the NPU; not built.
- **Evidence:** `research/ph/speech-output/` (`results-2026-11-16.md`; the fix is patch 0005), run
  attended 2026-11-16 on the phone with device tools built at `ph-main` 5cbd4cd plus the fix; the bisection used
  `GGML_HEXAGON_OPFILTER` and Hexagon's switches; `test-backend-ops` from a separate build with the shapes added.
- **Means:** the empty audio is an upstream Hexagon bug, not an NPU limit; it is worth a fact sheet for the user to
  report. The fix makes NPU-only speech correct but not faster, so for speed the split setup (D53's `encoderDevice` on
  the CPU) stays the recommendation. Any model whose weight is shared by a lookup and a matmul in a device buffer
  would hit the same bug. The fix is carried as patch 0005 (the user's choice): on the phone only
  `a.gen.code.embd.weight` stays flat, the text model answers as before, and `check.sh --device`'s `device-speech`
  step (4.3 s of audio) fails without it. Later replaced (D86): patch 0005 now removes the view, so keep-flat was dropped
  and kept at `research/ph/speech-output/hexagon-keep-shared-weights-flat.patch`.
- **Feeds:** D37, D53, D85; F154.

### F154. LFM3-Omni-2B speaks on the phone at 1.7 times real time with the model on the NPU and its speech part on the CPU; long texts must be spoken a sentence at a time

- **Finding:** Through upstream `llama-server`'s `/tts` on the phone, LFM3-Omni-2B (Q4_K_M, Q8_0 mmproj, 24 kHz mono)
  produces no audio with everything on the NPU: each request returns an empty WAV at once with no error. With everything
  on the CPU it speaks at 0.88-0.99 times real time, slower for longer texts, the first streamed audio after 0.45-1.7 s.
  With the language model on the NPU and the mmproj's speech part (depthformer and codec) on the CPU it speaks at 1.7
  times real time, the first streamed audio after 0.11-0.19 s. A long text (151 words) spoken in one request is garbled
  or cut, and varies between runs (26 s, 44 s and 48 s of audio; 96 s when it never ended), while the same text spoken
  sentence by sentence (8 requests, 15-24 words each) is clear, 53 s at 1.69 times real time, though the voice changes
  slightly from sentence to sentence, with a new random seed per request or one fixed seed for all (both drift, user
  listened). Speech output streams a chunk per 80 ms frame; a spoken reply in a chat exists only
  through `/v1/realtime`. The Mac (Metal) speaks at 2.8 times real time. The user listened to every clip.
- **Evidence:** `research/ph/speech-output/` (`tts-measure.sh`, `results-2026-11-16.md`), run attended 2026-11-16 with
  the device tools built at `ph-main` b9ea4c7 (patches 0001-0004); the split setup is `-dev HTP0 -ngl 99
  --no-mmproj-offload`. The SDK's raw request returns text and corrupts audio (F74), so the runs used `llama-server`.
- **Means:** an interactive voice mode is feasible on this phone with the split setup, speaking a reply sentence by
  sentence, which also starts the first sound sooner. The SDK already moves the encoder to another device (D53). It would
  still need a byte-returning request (F74) and the realtime connection (F56); D37 keeps speech output out until the
  user decides. The NPU's silent empty output is an upstream gap worth reporting.
- **Feeds:** D37, D53, D65; F56, F74.

### F153. On the LFM3 family (hybrid with SWA layers), the prompt cache reuses neither the live state nor the latest checkpoint, because the SWA threshold rejects them

- **Finding:** The cache stop in F152 is not about media. LFM3 models (`sliding_window: 256`: LFM3-1.7B, LFM3-Omni-2B,
  LFM3-VL-2B, LFM3-Audio-2B) mix full-attention, sliding-window and recurrent layers (`llama_memory_hybrid_iswa`). To
  reuse a prefix, llama-server accepts the live slot state or a context checkpoint only if its `pos_min` lies n_swa - 2
  tokens before the end of the shared prefix (`server-context.cpp` ~4219, used at ~4271 and ~4284, at `5676dfb`). On
  this memory `pos_min` is the recurrent position, which equals the state's end (`llama-memory-hybrid-iswa.cpp:178`).
  So the live state is never reused, nor the checkpoint made 4 tokens before the previous prompt's end; the newest
  checkpoint at least 255 positions back is used instead. After a long message that is the one at or inside its
  start, so each turn re-processes the whole last user message: the clip in F152 (encoded again), or plain text the
  same way. In a chat of short messages it is the checkpoint at position 0, so each turn re-processes the whole chat
  (cache_n 1 on every one of 12 short turns on LFM3-Omni-2B). Voice turns encode the previous clip again too. The
  state that is rejected is a valid resume point: the recurrent state is exact at its end, the SWA cells within n_swa
  of that end are kept (a checkpoint saves the unmasked ones, `llama_kv_cache::state_write`), and full attention is
  cut after it. Patch 0004 applies the SWA threshold only to memory that can be partly removed
  (`use_swa = n_swa > 0 && seq_rm_type == PART`, the fix tested on upstream master); otherwise the state must end
  before the first token to evaluate, upstream's rule for memory without SWA. With it (or with `--swa-full`), each turn
  reuses the live state, or the previous prompt up to 4 tokens from its end: turn 4 of the voice-note chat caches
  1,206 of 1,237 tokens and processes 31; on LFM3-Omni-2B the turn-4 prompt time drops from 1,815 ms to 149 ms (Mac
  CPU). On Metal, replies equal an uncached run, word for word, over 10 greedy turns on LFM3-Omni-2B and LFM3-1.7B; on the
  tiny test model (CPU) over 8 turns, top-5 logprobs are within 0.024 nat. On CPU, reusing a state that holds decoded
  reply tokens can flip near-ties: unpatched master, Gemma 3 and `--swa-full` do the same, and the fix equals
  `--swa-full` turn for turn, while with fixed assistant turns its output equals no-cache (`theory-test.md` G1, G1b iii,
  G1c). Models without SWA layers are unaffected, the
  threshold being pos_eval for them already: LFM2 and LFM2.5 (conv and full attention only), Qwen3.5, and Llama 3.2
  and Gemma 3 (partly removable memory) give the same cache and replies with and without the fix.
  `--ctx-checkpoints` (2, 8, 32) and `--checkpoint-min-step` change nothing.
- **Evidence:** upstream `llama-server` at the pin (host build, `5676dfb`, CPU) at trace verbosity with
  `LLAMA_SERVER_SLOTS_DEBUG`, on tinyLFM3-Omni Q2_K and LFM3-Omni-2B Q4_K_M, with a 4-turn chat of three 30 s clips
  (report `cache-stop-report.md`, W9). Then a test on upstream master `ef3848f` (Metal), with the predictions written
  before each run: chats of long, short and mixed messages and of clips on tinyLFM3, LFM3-Omni-2B and LFM3-1.7B;
  controls on Llama 3.2 3B, Gemma 3 4B, Qwen3.5 0.8B and LFM2.5-230M; every reuse decision in 120 calls re-derived
  from the logs and matched; upstream's own unit test of the rule (report `theory-test.md`, W9), all 2026-11-16.
  Native tests on tinyLFM3-Omni, each failing at turn 2 without the patch (1 token cached): `hybrid_swa_cache_covers_last_message`,
  `_covers_last_voice_note` and `_covers_short_chat` (each turn caches the previous prompt but 4 tokens),
  `_reuses_live_state` (raw completions; the previous prompt and its reply are reused); and
  `hybrid_swa_cache_replies_equal_uncached` (which also asserts each cached turn covers the previous prompt but 8
  tokens, so it exercises the new resume points; `LB_TEST_LFM3_MODEL` points it at a real LFM3 GGUF for attended runs). The
  tiny model's output barely depends on the history (another first message moves its top logprobs by 0.034 nat), so
  that last test catches a broken state only; the 2B and 1.7B comparisons are the stronger evidence.
- **Means:** Without the patch, on LFM3 each turn pays for at least its predecessor's whole user message, and for the
  whole chat while the messages are short: about 550 ms per text turn after a voice note on the phone (F152), and a
  re-encode on each voice turn. `--swa-full` avoids it, but grows LFM3-Omni-2B's SWA cache from 7.5 MiB to 40 MiB at
  4,096 context, and more for other SWA models. Patch 0004 avoids it for a bounded memory cost and is carried until upstream
  changes the rule. Without it, a fallback erased the newer checkpoints; with it up to `--ctx-checkpoints` (8) survive
  per slot, so at most 8 times a checkpoint's size more (G4: 8 checkpoints 22.4 MiB against 5 at 10.9 MiB, about 2.8 MiB
  each on LFM3-Omni-2B, so about +11 MiB), also in each RAM prompt cache entry, and a saved slot file grew from 23.9 MB
  to 36.0 MB; `--swa-full` costs +32.5 MiB. DeepSeek V4's compressed cache also cannot be partly removed, so it falls
  under the same rule (it reports n_swa 0, so its threshold is unchanged); an upstream issue and PR with the same fix are drafted, not posted. A repeated identical request
  now reuses its cache too (the transcription tests compare responses without the cached count). The phone saving is
  still to measure.
- **On the phone (2026-11-16, LFM3-Omni-2B, NPU, `ph-main` 10b44ee):** the 4th text turn after 3 voice notes now
  reuses 1,508 of 1,532 prompt tokens (1,007 before), processes 24 (525 before), and no longer re-encodes the last clip;
  prompt time falls from 552 ms to 115 ms. First token: 522 ms against 824 ms (`AudioDeviceTest`). What remains is the
  media path before the model runs: base64 and JSON (the bridge's base64 and body, llama-server's JSON parse and base64
  decode) and audio decoding, 395 ms in this run against 234 ms for the same steps in F152's run; the phone was slower
  across every step, likely warm. With the cache fixed, base64 and JSON are the largest remaining cost (about 200-310
  ms), so D84's no-base64 patch is worth more than F152 measured.
- **Feeds:** D84, F152; D65, D17; patch 0004 (`sdk/native/patches/README.md`).

### F152. Speech input on the phone: LFM3-Omni-2B transcribes exactly; audio costs 12.6 tokens a second; earlier voice notes slow later turns, mostly because the cache stops before the last clip

- **Finding:** On the phone's NPU, LFM3-Omni-2B (Q4_K_M, Q8_0 encoder) transcribes a 30.5 s spoken clip word for word
  in 3.4 s, the same from a file and from a descriptor, and a chat about the clip summarises it correctly with the first
  token after 0.6 s. Audio costs 12.6 tokens per second (383 tokens for 30.47 s), so a 2-minute voice message is about
  1,500 tokens and a 4,096-token context holds roughly 4-5 minutes of audio. In a chat holding 3 voice notes of 30 s, a
  4th, text-only turn reaches its first token after 790-824 ms, against 113-176 ms with no media. Of that, about 200 ms
  is base64 and JSON (the bridge writing base64, 51 ms, and the body, 26 ms; llama-server parsing it, 39 ms, and
  decoding the base64, 81 ms), about 30 ms is decoding the WAV files and tokenizing, and about 550 ms is the prompt cache
  stopping just before the last voice note: that clip is encoded again (245 ms) and 525 tokens are processed again,
  with 1,007 cached. The host run with upstream's tiny test model shows the same cache stop.
- **Evidence:** `AudioDeviceTest` (sdk/acceptance/backends, `full`) at `ph-main` afb22e3, run attended 2026-11-16 with a
  clip made by macOS `say`; the breakdown from a temporary timing build (branch `tmp/w7-timing`, never merged: spans
  logged in the bridge and through a temporary patch in llama-server's `handle_media`, mtmd and the slot timings).
- **Means:** speech input works on the phone (D65, D84). The no-base64 patch D84 left open would save about a quarter
  of such a turn; the larger cost is the cache stopping before the last media, likely LFM3's hybrid memory restoring only
  at checkpoints (unverified). The user chose to investigate the cache first (W9) and to revisit the patch after; the
  bridge keeps writing chat media's base64 for now. Cause: F153.
- **Feeds:** D65, D84, D76.

### F151. Typed output on the phone: clean JSON with Think off and with a thinking budget; with no budget the model thinks as if chatting, for minutes or until it runs out

- **Finding:** On the phone's NPU, `generate<Person>("A person named Bob aged 30.")` returns `Value(Person("Bob", 30))`
  with Think off and with Think on under a 256-token thinking budget, for Qwen3.5 4B and 0.8B (Q4_K_M, greedy, 4096
  context). The grammar holds the JSON in every case: no reply was `Invalid`, and the JSON is sometimes spread over
  lines, which decodes the same. With Think on and no budget, the model never learns it should write JSON, since
  llama-server applies the grammar only after the thinking (F143): 4B reasoned about how to answer a chat message,
  re-drafted that reply in a loop, and wrote correct JSON after 114 s and about 7,000 characters of thinking; 0.8B
  thought past its 3,072-token limit and returned `Incomplete(Length)` with no JSON after 58 s.
- **Evidence:** `TypedDeviceTest` (sdk/acceptance/backends, `full` flavour), cases `personWithThinkOff`,
  `personWithThinkOn` and `personWithThinkOnAndNoBudget`, run attended on 2026-11-16 at `ph-main` e5b0023 plus the
  no-budget case; replies logged to the app's `typed.log`. The host run (`HostTypedTest`, Mac CPU, 4B) matched: Think
  on without a budget ended at its 1,024-token limit.
- **Means:** typed output works on the phone (D63). Apps should turn Think off for typed calls, or set a budget, and
  phrase the prompt as the task; the README says so. Telling the model the format before it thinks (for example the
  schema or an instruction in the prompt) would be a deviation from upstream and needs an A/B first; not done.
- **Feeds:** D63.

### F150. W5 on the phone: the hub's device steps and the demo's device tests pass; three failures are the phone, not the change

- **Finding:** With W5 built (`ph-main` at 5cce87d), `sdk/scripts/check.sh --device` passes `device` and `hub-device`, and
  `demo/scripts/check.sh --device` passes everything but one journey. Three failures come from the phone, not the change.
  First, `hub-resume`'s notification test can't see the download's notification, which is posted and updated about every
  second: it is low-importance, and with about thirty other notifications it sits below the visible part of the shade,
  which the test never scrolls. Second, the device contract matched 75 of 76 cases. `t2-14-image` splits at a near-tie
  ("the color palette" or "the overall color palette"), and which side takes "overall" swapped between two clean runs of
  the same build, whose 22 pushed files matched the local build byte for byte. Third, the journey
  `aReplyGoesOnInTheBackgroundUnderANotification` can't grant a runtime permission
  (`GRANT_RUNTIME_PERMISSIONS` refused to the shell user), a restriction of this phone's system. A first run, made while
  another session probed the same phone, also failed `e2e` and `hub-device`. A port forward its probe left on 18183 sent
  the host contract's requests to the phone, and the phone's free memory moved between two fit estimates. Both passed
  once the phone was used by one check alone.
- **Evidence:** the two SDK device runs and their reruns of single steps, with `.lb-stamp`, pushed-file hashes,
  `dumpsys notification` and `adb forward --list` (diagnosis notes kept locally, not committed); two
  `demo/scripts/check.sh --device` runs, before and after 5cce87d (test fixes: a `@After` returning a value, a drawer
  checked before it finished sliding in, the shade opened with `cmd statusbar expand-notifications` and scrolled).
  2026-11-16.
- **Means:** W5 needs no SDK change from its device run. The device contract needs a tolerance for near-tie splits on
  the NPU, `hub-resume`'s test must scroll the shade, and a journey that grants a permission must allow for phones that
  refuse it. Two sessions must never share the phone during a device check. All are carried to W9.
- **Feeds:** D64, D75.

### F149. A one-line Hexagon gate gives NPU prefill with MTP the same +61% as the head/tail split, and drifts less than upstream's own MTP on/off
- **Finding:** At b12352 the Hexagon backend runs gated delta-net on its fast chunked HMX kernel only when K == 1 (`htp-kparams.cpp:565`), so with MTP (K = 4) prompts fall to the slow recurrent kernel (F7). ggml's contract says that when `n_tokens > K` only slot 0 (the final state) is guaranteed (`ggml.h:2423`), and CUDA's chunked kernel already relies on it (`gated_delta_net.cu:276`). Letting the Hexagon gate also accept `n_tokens > K` is one line in the backend, and the HMX kernel already writes the final state where slot 0 is. On the phone it is as fast as the head/tail split and changes nothing with MTP off. Its replies drift no more than upstream's own switch between its two kernels.
- **Evidence:** [upstream-notes.md](../../research/ph/delta-net/upstream-notes.md) §8; OnePlus 13, Qwen3.5 4B Q4_K_M on `HTP0`, upstream llama-server at b12352 built three ways (no patch, the head/tail split, the gate), thermal status 0. Prefill of 2,071 tokens with MTP on, three rotating rounds: 406 t/s with no patch, 651 (+60%) with the split, 654 (+61%) with the gate; MTP off: 907 with no patch, 899 with the gate. Correctness, the 19 greedy requests of P0's test A (up to 8k tokens, as F8): no patch MTP off against on, 16/19 identical, chosen-token |Δlogprob| p95 0.041, max 0.32 (the reference); no patch against the gate, both MTP on, 15/19, p95 0.031, max 0.32; no patch MTP off against the gate MTP on, 17/19, max 0.10; the split repeats F8 (16/19, p95 0.037, max 0.39). Every divergence is at a near-tie (top-2 gap ≤ 0.20); MTP acceptance 0.62-0.66 in every run.
- **Means:** the SDK's patch 0001 becomes the one-line gate (D80): backend-only, within ggml's contract, and the change to propose upstream; the split reintroduced the ubatch-dependent graph upstream removed in #32078. Not covered: whether MTP rollback snapshots stay valid when a draft's tail shares a batch longer than K (`llama-memory-recurrent.cpp`, `TAG_RECURRENT_ROLLBACK_SPLITS`), the question for upstream, and a `test-backend-ops` case with `n_tokens > K`. MTP still costs prefill (654 against 907 t/s), outside both patches; not investigated.
- **Device contract (2026-11-16):** without MTP the gate is a no-op (K = 1), and the SDK's device contract then differed in 3 runs of 3 on `t2-14-image`: identical for about 80 tokens, then "the overall color palette" against "the color palette", once with the sides swapped. On an idle phone, upstream llama-server gave the same reply fresh and after the contract's earlier requests, with the prompt cache on or off, with delta-net on the CPU and with Hexagon's recurrent kernel only (`GGML_HEXAGON_GDN_SELECT=1`). A first probe that ran alongside another process on the NPU did flip with the earlier requests. So the NPU breaks this near-tie either way between runs, and the old split, which sends the last token of every batch to the recurrent kernel even without MTP, had moved it away from the edge (the contract passed in 2 runs of 2 with it). The device contract now passes a reply that splits at one near-tie (D80); with that, 76/76.
- **Upstream replay (2026-11-16):** open PR #31669 replaces the K snapshots with replay, so every delta-net call is K = 1. On the phone, with it and `LLAMA_RS_REPLAY=1`, NPU prefill with MTP is 847 t/s against 396 with replay off (+114%) and 672 with the gate (+70%); MTP off is 911 (PR head `a18ecc4`, replay confirmed in the server log; an earlier head gave 832, 386, 653, 904). Its replies are as close to MTP off as snapshots' (17/19 identical, p95 0.031, max 0.10; acceptance 0.62 against 0.63) ([upstream-notes.md](../../research/ph/delta-net/upstream-notes.md)). So replay supersedes the gate once it is merged and on by default.
- **Feeds:** D10, D80, F7, F8, F121.

### F148. On the phone, a cancelled 4B load returns in 0.4-1.8 s on the CPU and about 1 s on the NPU, against 6-11 s for a full load

- **Finding:** With D67 built, cancelling `loadModel` (Qwen3.5 4B Q4_K_M) about a second after it starts made it return in 418 ms and 1,816 ms on the CPU and 970 ms and 971 ms on the NPU; each was followed by a full load of the same model on the same device, which succeeded (11.3 s and 9.8 s on the CPU, 5.9 s and 6.8 s on the NPU), so a cancelled load leaves nothing behind. A cancel saves 5-10 s against waiting the load out, but D67's "well under a second while weights are read" holds only for the first CPU run. Not measured, inferred: in the second CPU run the file was already in the page cache, so a second in the weights were read and the load was in context creation, which can't be stopped (F145); the steady ~1 s on the NPU is likely its session teardown.
- **Evidence:** a throwaway instrumented test in the acceptance app's `full` flavor (`DevicePolicy.Only(Cpu)` and `Only(Npu)`, two rounds each, `job.cancel()` after `delay(1_000)`, time from cancel to `join`), run once on the phone with the APKs left installed (F57) and deleted afterwards, not committed; the device gates passed just before (`sdk/scripts/check.sh --device` ALL PASS). 2026-11-15.
- **Means:** cancelling a load is worth it and safe; the sub-second figure in D67 is optimistic. Where the time goes after a stop (freeing, NPU teardown, the unstoppable stages) is not yet measured.
- **Feeds:** D67.

### F147. On the phone, with no logger, init's native lines reach logcat under `llamabro`

- **Finding:** After D66, a runtime created with no logger at `LogLevel.Debug` and asked only for its devices (no model) wrote ten lines to logcat under `llamabro` while it started: the CPU, Hexagon and OpenCL backends loading, the Hexagon driver and architecture (v79), and the Adreno 830 OpenCL device. All are `Info`, so at the default `Warn` none appears, which a first run at the default level showed; nothing during init on this phone is a warning. llama-server's own lines are still not routed (F22).
- **Evidence:** a throwaway instrumented test in the acceptance app's `noskel` flavor (`LlamaBro.getInstance(context).devices()` and `backends()`, with a temporary `Application` setting only `logLevel = Debug`), run once on the phone with the APKs left installed (F57), then `adb logcat -s llamabro`; the test files were deleted, not committed. 2026-11-15.
- **Means:** D66's phone check passes: the logcat default and the early attach work in production.
- **Feeds:** D66.

### F146. Recommended sampling is machine-readable on the Hub only sometimes, and a fine-tune carries its original's base tag

- **Finding:** Of six open base models, four carry sampling values in `generation_config.json` (Qwen3-4B 0.6/20/0.95, Qwen2.5-3B-Instruct 0.7/20/0.8 with repetition 1.05, SmolLM3-3B, DeepSeek-R1-Distill-Qwen-1.5B), Phi-4-mini and Granite 4.1 carry none, and Qwen3.5-4B has no such file (its values are in the card's text, per mode). Gated models (Gemma 3, Llama 3.2) answer 401 without a token. The file holds one set of values, so a model's thinking and non-thinking values can't both be expressed. Of three popular GGUF uploads, only ggml-org's LFM3-Omni carries `general.sampling.*` (greedy), which llama.cpp applies at load (F17); unsloth's Qwen3.5 and bartowski's Gemma 3 carry none. The Hub's GGUF summary (`expand[]=gguf`) gives the architecture, context length and chat template, no sampling. A GGUF's `general.base_model` can be wrong (bartowski's Gemma 3 instruct names the pretrained model); the Hub's tags tell a re-upload (`base_model:quantized:X`) from a fine-tune (`base_model:finetune:X`), and the demo's search groups on either, so a fine-tune folds into its original.
- **Evidence:** `GET {repo}/resolve/main/generation_config.json` for the eight repos named, `GET api/models/{repo}?expand[]=gguf` for three uploads, the first 25 MB of each upload's Q4_K_M read over HTTP and parsed (a scratch script), `GET api/models?search=qwen3.5-4b&filter=gguf` (the tags of `ccore/Qwen3.5-4B-3D-Coder`); `demo/app/.../models/HfSearch.kt` (`parseItem`, any `base_model:` tag). Read 2026-11-15.
- **Means:** search reports recommended values only where they exist, with their source, and groups only quantized re-uploads (D68).
- **Feeds:** D68.

### F145. A cancelled load runs to its end and is then freed; llama.cpp could abort its weight reading, but llama-server's progress callback never asks it to

- **Finding:** The native load can't be interrupted, so the runtime runs it under `NonCancellable`, frees the model when it finishes and then rethrows the cancellation: nothing leaks, but the caller waits for the whole load and the free (seconds for a 4B on the CPU or NPU, about a minute for a first GPU load, F63). Cancellation is checked only between device attempts. llama.cpp aborts reading weights when the progress callback returns `false` (the load returns -2, cancelled), and the image encoder loads through the same callback, but llama-server installs its own progress callback, which always returns `true`. Creating the context, the warm-up decode, compiling GPU kernels and setting up the NPU session have no callback and can't be aborted.
- **Evidence:** `sdk/llamabro/src/main/kotlin/com/suhel/llamabro/internal/LlamaBroRuntime.kt:426-475` (`NonCancellable`, close on cancellation) and `:279` (the check between attempts); `src/llama-model-loader.cpp:1787-1788` and `src/llama.cpp:315` (abort on `false`); `tools/mtmd/mtmd.cpp:719` (the encoder's loader uses the callback); `tools/server/server-context.cpp:1179-1202` (`load_progress_callback` returns `true`, throttled to 200 ms). Read 2026-11-15 at b12352.
- **Means:** a real abort needs a small patch to llama-server's callback (D67).
- **Feeds:** D67.

### F144. With no app logger, no native line reaches logcat, and an app's logger misses everything said during init

- **Finding:** `LlamaBroRuntime.init()` runs the native init (`common_init`, the CPU backend, then Hexagon and OpenCL) and attaches the app's `Logger` only after it succeeds, so an app's logger never sees init's lines, and a failing init throws before the logger is attached. `LlamaBroConfig.Default` and `HubConfig.Default` have no logger; then the bridge hands each line to llama.cpp's default, which writes to stdout and stderr, and an Android app's stdout and stderr are discarded, so by default not even an error reaches logcat. Backend discovery failures found in Kotlin (a missing manifest entry, a skeleton install that throws) are only kept for `backends()`.
- **Evidence:** `sdk/llamabro/src/main/kotlin/com/suhel/llamabro/internal/LlamaBroRuntime.kt:90-123` (init, then `engine.setLog` at :117); `sdk/native/bridge/src/runtime.cpp:69-92` (`log_trampoline` falls back to `common_log_default_callback`) and `:413-416` (trampoline installed after `common_init`), `:439-454` (`lb_runtime_set_log` works before init); `common/log.cpp:94-103` (stdout, stderr). Read 2026-11-15; the debugging of F129 found nothing in logcat.
- **Means:** attach the logger before init, and give Android a default destination (D66).
- **Feeds:** D66.

### F143. With a schema, llama-server lets the model think first, writes required fields before optional ones, and calls no tools

- **Finding:** A request with `response_format` gets a grammar that is not lazy: the model's thinking block, then the JSON, optionally inside a ```json fence that is not part of the content. The parser checks for a schema before tools, so in that request the tools stay in the prompt but cannot be called. The grammar writes an object's required fields first, in the schema's order, then its optional fields in order; keys keep their insertion order. It enforces integer minimum and maximum, string `pattern`, length and `format` (date, time, date-time, uuid), list item counts, `enum`, `const`, `anyOf`/`oneOf`, `$ref` and closed objects; a `number` has no bounds. A type list such as `["string","null"]` becomes `anyOf`.
- **Evidence:** `common/chat-auto-parser-generator.cpp:72-77` (grammar not lazy with a schema) and `:116-123` (reasoning, optional fence, schema; checked before tools); `common/json-schema-to-grammar.cpp:726-800` (`_build_object_rule`: required, then optional); `common/json-schema.cpp:140-345` (keywords read; `build_number` keeps no bounds; type arrays at `:262-274`); `common/json.h:267` (insertion order). Read 2026-11-15 at b12352; not yet run on the phone.
- **Means:** typed output works with Think on. A typed turn in a conversation cannot call its tools; the tools stay in the prompt so its cache holds. Declaration order is the order the model writes, so a reasoning field declared before the answer comes first; a field with a default moves after the required ones. Only the limits the grammar enforces get annotations; decimal bounds are left out.
- **Feeds:** D63.

### F142. Keep rules make reflection survive R8, but not its other costs: only a processor gives build-time errors and Multiplatform

- **Finding:** Keeping the annotated classes and members, `kotlin.Metadata` and the runtime-visible annotations lets reflection find tools by name in a minified app. Reading Kotlin parameter names, nullability and defaults still needs `kotlin-reflect`, mistakes still surface at runtime, the kept classes can't be shrunk, and `kotlin-reflect` is JVM-only, so it rules out the planned Kotlin Multiplatform bindings. A KSP processor has none of these costs.
- **Evidence:** Android Developers, "Shrinking Kotlin libraries and applications using Kotlin reflection with R8" (Kotlin metadata must be kept and rewritten for `kotlin-reflect`); Guardsquare's ProGuard manual on Kotlin metadata (`-keep class kotlin.Metadata`); R8's compatibility FAQ (full mode keeps annotations only on matched members). Read 2026-11-15.
- **Means:** typed tools are generated by KSP only; no reflection path.
- **Feeds:** D61.

### F141. Small models choose tools worse as the tool count and the prompt grow; a tool's description is what the model chooses by

- **Finding:** Giving a model on edge hardware fewer, relevant tools improves its choice and cuts run time by up to 70% and power by up to 40% (Paramanayakam et al., DATE 2025). Several benchmarks report accuracy falling as the tool count grows, worst for small models, and one 2026 preprint attributes it to the prompt's length rather than the count. Descriptions help the choice (OpenAI's guide asks for the function, each parameter and the output to be described; an enterprise ablation found description length and quality matter), but long descriptions across many tools hurt (OSWorld-MCP, 158 tools).
- **Evidence:** arXiv 2411.15399 ("Less is More"); arXiv 2603.11495 (Tool-DC: all models degrade from under 10 to 20 tools, smaller ones most); arXiv 2605.24660 (prompt length, not count); arXiv 2510.24563 (OSWorld-MCP); arXiv 2412.15660 (description ablation); OpenAI's function-calling guide. Read 2026-11-15; not measured on the phone.
- **Means:** an app registers a few tools per conversation. A tool's description is required; a parameter's is optional, since a clear name already says it and every description costs prompt on a phone.
- **Feeds:** D61.

### F140. A call to a tool that doesn't exist goes back to the model as an error in tools/ui; Llama Bro stops the turn instead

- **Finding:** tools/ui answers a call to an unregistered name with `Error: Unknown tool: <name>` as that call's result and continues the loop. LangGraph's `ToolNode` does the same and lists the valid names; the Vercel AI SDK throws `NoSuchToolError` unless the app adds a repair function; the OpenAI Agents SDK raises `ModelBehaviorError` and ends the run (a pull request to send it back to the model was declined). Llama Bro's loop finds no handler for the name and treats it as a manual tool, so the turn stops with `ToolCallsPending` for a call the app never registered.
- **Evidence:** `tools/ui/src/lib/stores/agentic/index.svelte.ts:853-869` and `stores/mcp/index.svelte.ts:356` (b12352); `ToolRegistry.handler` returns null for an unknown name and `Conversation.runTurns` counts it as pending; LangGraph's `INVALID_TOOL_NAME_ERROR_TEMPLATE`; Vercel's `AI_NoSuchToolError` reference; openai-agents-python PR #2957. Read 2026-11-15.
- **Means:** the loop follows tools/ui: an unknown name is an error result and the loop goes on. Listing the valid names, as LangGraph does, is a deviation that would need a measurement first.
- **Feeds:** D62.

### F139. llama-server passes only a tool's name, description and parameters to the template; the description is optional and tools open the prompt

- **Finding:** Parsing a request's tools keeps `name`, `description` (default `""`), `parameters` and `defer_loading`, and the template is given that list again, so anything else on a function, such as the `response` declaration Gemma 4's template can render, never reaches the model. Most templates write each tool as JSON, so a missing description is simply absent; Gemma 4 writes an empty one. Qwen3.5's template writes the tools at the very start of the prompt, before the system message. `defer_loading` (a tool withheld until a `tool_reference` names it) is rendered only by the LFM3 template at b12352.
- **Evidence:** `common/chat.cpp:620-640` (`common_chat_tools_to_json_oaicompat`) and `:683` (`function.value("description", "")`), `common/chat.h:226-232`; `models/templates/google-gemma-4-31B-it.jinja:106-115`; `models/templates/Qwen3.5-4B.jinja:45-52`; `defer_loading` found only in `models/templates/LFM3.jinja`. Read 2026-11-15 at b12352.
- **Means:** a tool's result has no schema the model can see: what its fields mean belongs in the tool's description, and annotations on a result class would do nothing. Changing the tools mid-chat changes the prompt's first tokens, so the whole chat is processed again (3.5 s for 3k tokens, F135); tools are registered per conversation.
- **Feeds:** D61.

## PD demo app, execution (PD T9–T12, design audit)

## After PD: pictures on the NPU, llama.cpp b12352, memory, sampling (F120–F138)

### F138. The held-message note was missed a second time, in another test, and a turn test timed out once under the full suite

- **Finding:** On 2026-11-14 the emulator gate failed `FirstRunTest.getOpensTheChatAndHoldsAMessage` once: "Qwen3.5 0.8B will answer the moment it's here" did not appear within 15 s. It passed 10 of 10 in the class's rerun and in the next device step. This is the same note `downloadSurvivesProcessDeath` missed once (F132), so two tests, one symptom. Separately, `ChatEngineTurnTest.stopWhileTheModelWakesEndsTheTurnAndKeepsTheMessageHeld` timed out waiting for "waking" once in a full unit run and passed 4 of 4 alone and in the next full run.
- **Evidence:** the device step's result file (the failure above, then `tests="10" failures="0"` for `FirstRunTest` alone, then the device step PASS); the unit log of the failing run (`ChatEngineTurnTest.kt:88`, the 10 s wait).
- **Trace, 2026-11-14, stopped at the user's request:** the test passed 15 of 15 alone on the emulator; a loop with the emulator's CPU loaded by six busy processes was stopped before it reported. Ruled out by reading the path from Get to the note: the chat reading its model before it is saved (Get creates the chat with the model stored and opens it by id), the library listing a download in progress (it lists finished files only), and `send` refusing the message (none of its refusals applies). The failing run's logcat was overwritten by the passing reruns. Both misses were on the emulator, and the run with this one later ended in "System has crashed".
- **Means:** not root-caused; to revisit. Next steps: make the test dump the screen's semantics when the wait times out, so a third miss shows whether the message was stored and what the chat thought of its model; keep each failing run's logcat. The held note is shown when a message is held for a model that is not on the phone yet, so a real miss would sit between the download state and the chat's rows.
- **Feeds:** testing, F132.

### F137. A cut reply continues seamlessly on the phone, from its answer or from inside its thinking, and the reasoning budget ends the thinking before an answer

- **Finding:** With Qwen3.5 0.8B on the NPU, greedy: a reply cut at 24 tokens in its answer ("1, 2, … 8, ") continued with "9, 10, … 30" when sent back as the last message, no restart and no repeat; a reply cut at 24 tokens inside its thinking (after `"strawberry`) continued the thought and then answered, and the continued thinking and answer match the uncut reply word for word. A 128-token reasoning budget ended the thinking and the model answered correctly ("3") in 254 tokens, finishing by itself well inside a 1,024-token limit.
- **Evidence:** 2026-11-14, the phone, `ReplyLimitDeviceTest` (acceptance app, run only with `-Pandroid.testInstrumentationRunnerArguments.replyLimit=Qwen3.5-0.8B-Q4_K_M.gguf`), context 4096, temperature 0: three tests, all passing; the replies are in the test's log. Upstream continues a trailing assistant message by default (`prefill_assistant`, `common_chat_continuation` AUTO: the thinking when the content is empty, else the content).
- **Means:** Continue needs nothing from the SDK beyond sending the reply back with its thinking; the demo's Continue is that (D60). The budget's forced end works on a small model, closing the open test from PD.
- **Feeds:** D60, demo design §4.7.

### F136. The leading local apps keep sampling per model, one lets a chat override it, and almost none guard against a loop by default

- **Finding:** Of ten apps read on 2026-11-14, sampling is per model in Jan, GPT4All, Ollama (its Modelfile) and LM Studio (per-model defaults and `model.yaml`; its presets are named bundles for any model); Open WebUI layers a chat over the account over the model over global defaults, showing "N modified" or "Default". Recommended values come only from a curated list (LM Studio's `model.yaml`, Google AI Edge Gallery's allowlist); none reads them from the GGUF, which llama-server itself does (F17). Replies are unlimited by default in Jan, Ollama, llama-server and PocketPal; GPT4All caps at 4,096 tokens, the Gallery at 1,024 or 4,096 per model. Only GPT4All has a repetition guard on by default (repeat penalty 1.18); KoboldCpp alone documents continuing a cut reply, and LM Studio has continue in its developer mode.
- **Evidence:** the apps' docs and source: lmstudio.ai/docs (per-model, presets, model.yaml, TTL), docs.openwebui.com (chat parameters), docs.jan.ai (model parameters), docs.ollama.com (Modelfile, FAQ), docs.gpt4all.io (settings), the KoboldCpp wiki, llama-server's README, PocketPal's `completionSettingsVersions.ts`, ChatterUI's `SamplerState.ts`, the Gallery's `model_allowlist.json`. Not verified: LM Studio's in-app panel and its reset (pages missing), the llama-server web UI's fields, PocketPal's per-chat path, ChatterUI's defaults.
- **Means:** the demo's layers (card, model, chat) follow the established pattern, with Open WebUI's labels; a length limit and Continue go further than most.
- **Feeds:** D60, sdk-review item 8.


### F135. Restoring a chat's slot from disk saves 90% of the prompt time after a reload, at 97 KB a token

- **Finding:** After the model is freed and loaded again, restoring the chat's slot from a file (llama-server's `/slots/0?action=save|restore`, reachable through `rawRequest(Endpoint.SlotsAction)`) brings the next message's prompt from 3.5 s to 0.3 s for a 3,074-token chat on Qwen3.5 4B (NPU), with the same greedy answer; save and restore take 0.2 to 0.4 s each. The file is large: 297 MB for those 3,076 tokens, about 97 KB a token, so a full 8k context would be about 800 MB. The load itself (4 to 5 s) is unchanged, so the whole wait goes from about 8 s to about 5 s.
- **Evidence:** 2026-11-14, the phone, `SlotCacheDeviceTest` (acceptance app, run only with `-Pandroid.testInstrumentationRunnerArguments.slotCache=Qwen3.5-4B-Q4_K_M.gguf`), context 8192, temperature 0, two runs. Run 2: load 5.4 s; first turn 3,074 tokens at 909 t/s (3.6 s), answer "40"; save 366 ms, 311,647,056 bytes; reload and read again 3.5 s (906 t/s), "40"; reload, restore 210 ms, then 4 new tokens with 3,070 cached in 325 ms, "40". In all: 8.4 s reading again against 5.3 s restoring. Run 1 gave 7.6 s against 4.7 s. Qwen3.5's hybrid layers restore correctly.
- **Means:** Worth building for long chats with limits: one file, the chat that was open, written when the model is freed and deleted when it is used, the chat changes or is deleted; skipped for short chats, where the saving is under a second. The disk it needs (hundreds of MB) is the cost to weigh.
- **Feeds:** D59.

### F134. Android sends TRIM_MEMORY_BACKGROUND only under pressure, not on Home

- **Finding:** On leaving the app (Home), the application gets `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` (20) at once, and no `TRIM_MEMORY_BACKGROUND` (40) while memory is plentiful: the process stays the "previous app" (oom adj 700, process state 15) for minutes. Level 40 is the signal that Android is moving the app towards the cached list, so freeing the model on it does not undo a grace period on every Home.
- **Evidence:** 2026-11-14, the demo (debug) on the Android 15 emulator with a log in `onTrimMemory`: Home at uptime 1505.20 s, level 20 at 1505.92 s, nothing else in 5 min 40 s; `dumpsys activity processes` then showed `oom adj … cur=700`, `curProcState=15`. On the phone (release build, the same day): 3 min after Home the process was the previous app (`oom adj … 450`, `curProcState=15`) with `trimMemoryLevel=0`.
- **Means:** `BackgroundRelease` frees the model on level 40 and above, idle only, under every Settings choice; level 20 is left to the timer.
- **Feeds:** D59, demo design §4.6.

### F133. A chat failed its next message after its model was freed

- **Finding:** A chat reused its SDK `Conversation` whenever the model's key was unchanged, but a conversation belongs to one loaded `Model`. After the model was freed (the minute in the background, now also Unload) and loaded again, the next message in that chat went to the closed model and failed with "That didn't work.".
- **Evidence:** 2026-11-14, `ChatEngineTest.aChatAnswersAgainAfterItsModelWasReleased` (send, `releaseModelIfIdle`, send): the second reply was stored `Failed` with no text before the fix and is answered after it; the journey `unloadFromTheModelSheetAndTheNextMessageLoadsItAgain` does the same through the model sheet on the emulator.
- **Means:** The handle keeps the `Model` its conversation was made on and rebuilds the conversation from the stored messages when the loaded model is another instance.
- **Feeds:** D59, demo design §4.6.

### F132. The demo gate passed two runs that tested nothing

- **Finding:** `demo/scripts/check.sh` reported a step as passed when it ran no test, in two ways: `--only` with a space-separated list ("lint quality") was accepted, matched no step and printed ALL PASS; and a device step whose install failed (an emulator with 142 MB free of 10 GB) ended in a successful Gradle build with zero tests, which counted as PASS.
- **Evidence:** 2026-11-14. `--only "lint quality"` finished in seconds, leaving the step logs from an earlier run; the next full run then failed `quality` on a line that the "passed" run should have caught. The monkey step "passed" in 6 s; its log shows `Failed to install APK … not enough space` and its result file `tests="0"`. After the emulator's data was wiped, the full gate ran 153 device tests, the journeys, the monkey and coverage (94.3% of lines). On that fresh emulator `RealDownloadJourneys.downloadSurvivesProcessDeath` missed the held note once and passed on the rerun.
- **Means:** `--only` takes commas or spaces; a run in which no step ran fails ("NO STEP RAN"); each device step fails when its results report no test, and clears the results of an earlier run first, so a stale file can't count. A test step's PASS now means tests ran. The held-note miss is watched as a possible flake.
- **Feeds:** [testing](../testing.md) (the gates), D58 (verified with the fixed gate).

### F131. The drawer's scrim was the ink colour, a light veil over the page in dark
- **Finding:** The Sidebar layout's drawer dimmed the page with `colors.ink` at 32%. Ink is the text colour, near-white in the dark theme, so opening the drawer laid a bright haze over a dark page. The sheets already used the theme's scrim (the canvas at 50% in dark).
- **Evidence:** the owner's report on the phone, 2026-11-14; `DemoDrawer` in `components/Layout.kt` against `DemoSheet` in `Surfaces.kt`.
- **Means:** the drawer uses the theme's scrim like every other overlay; the demo design states the rule.
- **Feeds:** the demo design's colour rules.

### F130. A touch target that reaches past its row is cut by the row's parent, and accessibility sees only the rest
- **Finding:** Two 48 dp targets drawn as overlays past their rows, to keep the visible spacing tight, failed the accessibility touch-target check: the thinking fold measured 41 dp, and the section label's "What's a model?" 35 dp. Compose reports a node's bounds clipped to every ancestor's layout bounds, so the part of a target outside its parent is gone for accessibility services, whatever the pointer input does.
- **Evidence:** the emulator device gate, 2026-11-14: `ChatScreenTest.foldedThoughtExpands` and six `FirstRunTest` cases, `AccessibilityViewCheckException` with bounds `[42,439][355,547]` (41 dp) and `[730,937][1038,1028]` (35 dp).
- **Means:** every target is a real 48 dp of layout (as Material's `minimumInteractiveComponentSize` does). The spacing works around it: the fold's row is the gap to the reply, and a label with an action is a 48 dp subheader. The design doc's spacing rule says so.
- **Feeds:** the demo design's spacing rule (§8.3), the design system's fold and section label.

### F129. A splash screen kept until the first composition hangs every Compose UI test at launch
- **Finding:** With the AndroidX splash kept on screen until `AppShell` composed and called `onReady`, every instrumented test that launches `MainActivity` hung in `Instrumentation.startActivitySync`. Under a Compose test rule the composition waits for the test's clock, which the test can't advance before the activity has launched; the launch waits for the first frame, which the splash withholds. A normal launch is unaffected (real frame clock): it drew in about 1 s on the emulator and the phone.
- **Evidence:** the emulator device gate stalled for about 3 hours on the first app test (`SmokeTest.theRuntimeReportsAtLeastOneDevice`), 2026-11-13. A jdb thread dump showed the runner in `startActivitySync` under `ActivityScenarioRule.before`, with every dispatcher thread idle. `dumpsys activity` showed the splash window up and `firstWindowDrawn=false`. With readiness taken from the data, both smoke tests pass in 3 s.
- **Means:** the splash waits for the shell's data (the settings and the first library scan), read in the activity's scope with a 2 s cap, never for a composition. The cap also means a failed read can't hold the splash. The gate needs a time limit per test run, so a hang fails in minutes, not hours.
- **Feeds:** the demo's splash (architecture fix 10), testing.md's device gate.

### F128. The memory budget refuses models that run: it ignores the cached apps Android closes first
- **Finding:** On the OnePlus 13 with the owner's everyday apps open, the demo refused Qwen3.5 4B for memory. Measured the same way at another moment: `MemAvailable` 5.7 GB, while Android's own "Free RAM" was 7.8 GB, 3.2 GB of it held by cached background apps. Upstream llama-server b12352 then loaded Qwen3.5 4B with its F16 projector on the NPU at a 4096-token context and answered; `MemAvailable` fell to 2.2 GB (the model took about 3.45 GB), and Android closed about 25 cached apps (Chrome, Files, Wellbeing and others) to make room, as it does for the app in front, while the model's process stayed up. The SDK's budget at that moment is `MemAvailable` less Android's low-memory threshold, the projector's file (0.67 GB), the whole prompt cache (1 GB, which fills only as a chat grows) and the 512 MiB reserve: about 2 GB, so it would have refused. The SDK's waiting for memory after a close (F124) is a separate, smaller cause.
- **Evidence:** `/proc/meminfo` and `dumpsys meminfo` before and after the load, and the processes that disappeared, 2026-11-13.
- **Means:** the refusal is a false negative on a phone with background apps, which is every phone. Decided in D57: the SDK itself now warns by default (`MemoryCheck.Warn`, `LoadReport.memoryWarning`) and the demo shows a note. Still open: whether the budget should count the memory that cached apps free (an app can't read their size directly) and charge the prompt cache as it grows, not up front.
- **Feeds:** D33, D34, D45, D57, the demo's memory note.

### F127. Upstream PR #30432's NEON path makes the phone's CPU encode a BF16 projector 5.3x faster, with nothing else changed
- **Finding:** ggml-cpu's `ggml_vec_dot_bf16` (`ggml/src/ggml-cpu/vec.cpp:139`) has SIMD paths for x86, RISC-V, POWER and s390 but none for ARM, so on a phone a BF16 matmul runs a scalar loop summed in double; llamafile's `sgemm` has no ARM BF16 case either, and none of the Android CPU variants enables BF16 (`ggml/src/CMakeLists.txt:536-542`). Upstream knows (issue #29917) and has an open fix, PR #30432 (19 lines: 8 BF16 values per step, widened exactly to F32, two F32 FMA accumulators, the scalar loop for the tail). Carried as patch 0002 at b12352, on the phone (thermal 0, an external cooler on the phone from this run on): Qwen3.5 0.8B's BF16 projector on the CPU 16.5 s without it and 3.1 s with it (16.55/16.54/16.59 against 3.16/3.10/3.08, alternating); the F16 projector 2.16 s in both; greedy CPU text replies byte-identical (Q4_K uses no BF16 matmul); answers right. The host check passes with it (contract identical).
- **Evidence:** `research/b12352/bf16-neon-ab.sh` and `build-at.sh` (`PATCHES=`), 2026-11-13; the PR's diff at head `cde3115c`, reviewed line by line; upstream's open PR and issue, read 2026-11-13.
- **Means:** the slow CPU encoder (F123) is fixed for BF16 without changing floating-point rules (F126). The patch is carried until #30432 or another ARM BF16 path is in the pin (D55); `sdk/native/patches/README.md` records when to drop each patch.
- **Feeds:** D55, F123, F126, D10.

### F126. -ffp-model=fast does not speed up CPU text generation, and it changes greedy replies
- **Finding:** Qwen3.5 4B Q4_K_M on the phone's CPU, b12352 with its CPU variants built with default floating-point rules against `-ffp-model=fast -fno-finite-math-only` (F123's flag), three alternating rounds at thermal status 0 to 1: prefill of 2,071 tokens 63.2 against 61.2 t/s, decode of 128 tokens 14.7 against 14.3 t/s (round 3: 61.2 and 61.4, 14.5 and 14.7), so no gain beyond thermal drift; Q4_K's matmuls run on integer dot products the flag doesn't touch. Three of five greedy replies differ (wording from the 15th word, a code block instead of a sentence, a colon inside or outside bold), all still correct.
- **Evidence:** `research/b12352/cpu-flags-ab.sh`, 2026-11-13.
- **Means:** the flag's one measured gain is the CPU image encoder with a BF16 projector (18 s to 5 s, F123), a path that runs only without an NPU or with `encoderDevice = Only(Cpu)`; its cost is CPU output that differs from upstream's standard Android build everywhere. Not taken. The F16 projector (1.8 to 2.5 s with unchanged numerics) is the better lever, but the hub mirrors `-hf`'s choice, so preferring it would be a deviation or a catalog choice (open).
- **Feeds:** F123, D52, the hub or catalog choice of projector (open).

### F125. At b12352 every drafting mode still aborts the GPU, and Vulkan's crash has no fix yet
- **Finding:** Upstream llama-server b12352 on the Adreno 830, Qwen3.5 4B, `-dev GPUOpenCL -ngl 99` with `GGML_OPENCL_COOPK_DP4A=0` (F75): MTP (`--spec-type draft-mtp --spec-draft-n-max 4`), DFlash (`--spec-type draft-dflash` with its Q8_0 draft) and n-gram (`--spec-default`, once a long repeat makes it draft) each abort the process on the first drafted batch, with F80's error: `clEnqueueNDRangeKernel` -54 (`CL_INVALID_WORK_GROUP_SIZE`), now at `ggml-opencl.cpp:1366`. Without drafting the same server answers (600 tokens, 15.9 t/s). OpenCL's speculative-verification work in this release (`c19043f`, `4b77dee`) does not reach this kernel. A short n-gram request seemed to pass, but only because it never drafted (`ngram-mod` needs a 24-token match). Vulkan was not run: F64 named two upstream fixes a retry needs, the Q4_K pipeline crash (#28635) and the wrong output (#30821 or a successor), and neither is in b12352; its one Qualcomm change (`78ffbaa`) is a flash-attention speed fix.
- **Evidence:** the OpenCL build of b12352's device tools (`build-device-tools.sh` with `LB_EXTRA_TARGETS=ggml-opencl`), 2026-11-13; `git log b12052..b12352 -- ggml/src/ggml-vulkan`.
- **Means:** unchanged: the SDK never drafts on the GPU, and the reason in `loadReport` now names b12352. Vulkan stays out (D31). Retry both with the next upgrade.
- **Feeds:** F80, F67, D31, D52.

### F124. Since b12352 an NPU model's memory is not in the app's PSS, and comes back about a second after close
- **Finding:** Qwen3.5 4B on the NPU, upstream llama-server: process PSS 607 MiB at b12052 and 76 MiB at b12352, while the phone's MemAvailable falls by about the same amount in both (3,189 and 3,023 MiB). The weights now live in DSP buffers that PSS does not attribute to the process. After `closeAndJoin()` returns, most of an NPU model's memory is still in use for about a second: Qwen3.5 4B, used memory 8,149 MiB right after close, 6,038 MiB one second later and flat for the next 29 s (below the 6,145 MiB before the load). In a test that runs straight after another, the earlier model's 2.2 GB is still out at the start.
- **Evidence:** `/proc/<pid>/smaps_rollup` and `/proc/meminfo` around a load of each build, 2026-11-13; `LifecycleDeviceTest` on the phone at b12352: `closeThenLoadNeverHoldsTwoModels` peak 3,163 MiB while switching (Qwen 3,162, Llama 2,394), `closeDuringGenerationFreesMemory` sampled every second for 30 s after close; the full-flavor suite, 20 of 20 after the tests measured used memory and waited up to 5 s (memory back 201 ms into the wait).
- **Means:** PSS can no longer show what a model on the NPU costs. The lifecycle tests measure the phone's used memory (`ActivityManager` total minus available) instead; `MemoryBudget` already reads available memory. Not a leak: the memory returns by itself. But an app that loads the next model at once would see about 2 GB less available memory for that second, so `ContextSize.Auto` and the fit check could choose a smaller context. Open: whether `closeAndJoin()` should wait for the memory to settle on the NPU.
- **Feeds:** P2 T6 lifecycle tests, D33 (memory budget), F121.

### F123. The phone's CPU encodes a BF16 projector about 3.4x slower in the SDK's build than in upstream's Snapdragon preset; b12352 changed nothing (corrected)
- **Finding:** Qwen3.5 0.8B, a 640x480 picture, model on the NPU and the encoder on the CPU (`--no-mmproj-offload`), phone cool: built the same way (upstream llama.cpp, `GGML_CPU_ALL_VARIANTS`, default floating-point rules, as the SDK builds), b12052 and b12352 take the same time, 18.3 and 18.1 s (two runs each). The first comparison (5.4 s at b12052 against 18.1 s at b12352) set the P0 spike's build against the SDK's: the spike used upstream's `arm64-android-snapdragon` preset (one CPU library, `-march=armv8.7a+fp16+dotprod+i8mm -ffp-model=fast -fvectorize -flto`). So there was no upstream regression to bisect. The same weights as F16 take 1.8 to 2.5 s.
- **Evidence:** upstream llama-server built at both commits by one script, and b12352's CPU variants rebuilt with only the fast floating-point flags, timed back to back on the phone, 2026-11-13; the preset in `sdk/native/third_party/llama.cpp/docs/backend/snapdragon/CMakeUserPresets.json`.
- **Means:** nothing to report upstream. With the encoder on the NPU (the default, D53) this does not matter: 0.35 s. On phones without an NPU and for `encoderDevice = Only(Cpu)` it does. The flag that matters is `-ffp-model=fast` (with `-fno-finite-math-only`, as the preset): the same b12352 build with only those flags on its CPU variants encodes in 5.2 s against 18.1 s (two runs each, thermal 0, answers unchanged). It lets the compiler reorder float sums, so it changes every CPU computation slightly; an A/B of CPU text generation found no gain there and changed replies, so it is not taken (F126). The other lever is an F16 projector where a repo has one.
- **Fixed for BF16 (2026-11-13):** upstream PR #30432, carried as patch 0002, adds the missing ARM path: 16.5 s to 3.1 s (F127, D55).
- **Feeds:** D53, the CPU build's flags (open), a hub or catalog choice of projector (open).

### F122. The patched llama.cpp copy kept upstream's file times, so an upgrade linked stale objects
- **Finding:** `prepare-llama.sh` unpacked the submodule with `git archive | tar -x`, which gives every file its commit time. b12352's sources are older than objects an Android build of b12052 had made hours earlier, so ninja kept a stale `speculative.cpp.o` and the link failed (`llama_model_target_layer_ids` declared C++ in b12052's `llama-ext.h`, C in b12352's `llama.h`). A cached CMake variable also kept the old build name, b12052, in `lb_version()`.
- **Evidence:** the AGP link error and the object and source times, 2026-11-13; `version_names_build` and `props_build_info_names_llama_build` failing in a build directory made before the upgrade.
- **Means:** the copy is unpacked with `tar -m` (files get the current time), and `LB_LLAMA_BUILD` is a plain variable. An upgrade could have shipped a library that mixed two builds and still linked.
- **Feeds:** D52.

### F121. The upgrade to b12352: the contract still holds, the NPU reads pictures again, and the delta-net patch still pays
- **Finding:** At b12352 (2026-11-12, 300 builds after b12052) the SDK matches upstream llama-server on every contract case on the Mac and on the phone's NPU, and the `tools/ui` goldens are unchanged. The NPU now encodes Qwen3.5 0.8B's pictures correctly ("Red and blue.", "Green and yellow.") at 0.35 s, so F120's fault is fixed upstream (likely the precision work, `d6be065` or `494d0f4`; not bisected). The re-ported delta-net head/tail patch still makes NPU prefill with MTP about 66% faster: 644 against 387 t/s (Qwen3.5 4B, 2,071 tokens, three alternating runs each, thermal status 0), as F8 found at b12052; upstream's fast NPU kernel still needs K == 1 (`htp-kparams.cpp:565`).
- **What changed for the SDK:** `common_fit_measure` takes a list of model paths (external PLE files, `5f490d7`); a new route, `/v1/audio/speech` (OpenAI's speech shape over `/tts`), added as `Endpoint.Speech` with a contract case; `-hf` tags match only at a token boundary (`4b29d02`, `4c4ca5b`), ported to the hub, whose golden table gained four plan cases and a repository; ggml-hexagon opens its skeleton by absolute path from `GGML_HEXAGON_LIBHTP_DIR` (`76a6773`), which the SDK now uses (D54); NPU memory left PSS (F124); the phone's CPU encodes BF16 projectors slowly in the SDK's build, which turned out not to be a b12352 change (F123, fixed by F127). The HF token rule upstream added (`bbc2e4b`) the hub already had. `server.cpp`'s two functions the bridge copies, and the backend loader it ports, are unchanged. Nothing the bridge uses was removed (`8ca5a80`).
- **Evidence:** `sdk/scripts/check.sh` (host steps all pass; contract 2026-11-13) and `check.sh --device` (device contract identical on the NPU; `VisionTest` both cases); the A/B with upstream llama-server built for the phone with and without the patch; `git log b12052..b12352`.
- **Means:** the pin moves to b12352 (D52), the image encoder goes back to following the model (D53). Open from the release: FP8 KV cache (`202c1a2`), external PLE or shard files (`--ple`/`--shard`), OpenCL's speculative-verification work (`c19043f`, `4b77dee`), which may make GPU drafting worth retesting (F80), and Vulkan to retry (D31).
- **Feeds:** D10, D52, D53, D54, F8, F120.

### F120. The NPU computed Qwen3.5 0.8B's image encoder wrongly; the encoder had been on the NPU since P2 T6, and no test asked a model what it saw
- **Finding:** On the OnePlus 13, with the model on the NPU (`HTP0`), Qwen3.5 0.8B answers a 640x480 picture that is red on the left and blue on the right with "The left half is blue and the right half is black." The same files are right ("Red and blue") when the image encoder (the mmproj) runs on the CPU or on OpenCL, with the language model still on the NPU. It is the same in upstream llama-server (b12052) as in the SDK: upstream also puts the encoder on the model's GPU-type device, so the SDK matched upstream and both were wrong. The projector's format is not the cause: the BF16 projector converted to F16 gives the same wrong answer on the NPU. Qwen3.5 4B's F16 projector is right on the NPU, so only some encoders break; Gemma 3 4B's wrong answers in F119 fit the same cause but were not rerun. Switching off ggml-hexagon's matmul (`GGML_HEXAGON_OPFILTER=MUL_MAT`), op fusion or the HMX paths did not help. Filtering other op groups crashed the server, so the faulty op is not isolated.
- **What changed:** in P1 every model ran on the CPU, and the encoder with it. P2 T6 (`bfe82cf0`, 2026-11-06, D32) made `DevicePolicy.Auto` pick the NPU and put the encoder on the model's device ("not the first GPU"). The demo app has shipped the Hexagon backend since its first commit and has always used `Auto`, so on this phone its pictures were never encoded on the CPU. The code-quality pass (F105) did not change the image path: the demo's `Content.Image` and the SDK's `ChatJson.part` are unchanged by it, and the fault reproduces in upstream llama-server with no project code.
- **Why no test caught it:** (1) every image test checked that a reply arrived or that the SDK matched upstream, and none read what the reply said: the host end-to-end test, the contract case `t2-14-image` and the device contract; (2) the device contract on the NPU uses Qwen3.5 4B, whose encoder happens to be right on the NPU, so even a test that read the reply would have passed there; (3) the acceptance app had no image test, and the demo's end-to-end step 3 asserted only that a reply arrived (F119). The only small vision model, the demo's Qwen3.5 0.8B, reached the phone only in PD T13.
- **Evidence:** upstream `llama-server` on the phone (`--device none` / `--device HTP0 -ngl 99`, with and without `--no-mmproj-offload`, `--device GPUOpenCL` with `GGML_OPENCL_COOPK_DP4A=0`), temperature 0, thinking off, the demo's own downloaded files (`unsloth/Qwen3.5-0.8B-GGUF` at `6ab4614`: `Qwen3.5-0.8B-Q4_K_M.gguf`, `mmproj-BF16.gguf`); the 4B with `mmproj-F16.gguf`. Prompt time for one picture: 0.38 s with the 0.8B encoder on the NPU and 5.0 s on the CPU; 1.0 s and 5.6 s for the 4B. `VisionTest.seesAPicture` (acceptance, full flavor, NPU) fails without the fix ("the left half is blue and the right half is black.") and passes with it ("red and blue."), 2026-11-13.
- **Means:** with the model on the NPU, the SDK keeps the encoder on the CPU (D51). That costs about 4.6 s per picture and makes the answers right. Each image test now asserts that the reply names the picture's colours: the host end-to-end test, the acceptance app's `VisionTest` on the NPU with the 0.8B, and the demo's end-to-end step 3. Upstream: report to ggml-hexagon with these steps (D10).
- **Superseded in part by F121 (2026-11-13):** b12352 fixes the NPU encoder; D51 gave way to D53.
- **Feeds:** D51, F119.

### F119. The models do not appear to see an attached picture in the demo app on the phone (resolved by F120)
- **Finding:** Found by the end-to-end run's step 3 (an image sent to Qwen3.5 0.8B), which only asserts that a reply arrives and so passed. The replies show that the picture is not used. Qwen3.5 0.8B answered "I am a text-based AI model and I don't see images in this conversation. Can you share the picture with me?" to a solid blue picture. Gemma 3 4B (a vision model; 3 further tries too) answered "Multicolored" to the same blue picture and, for a picture that is red on the left and blue on the right, "a classic joke about the distribution of jelly beans … White on the left, Brown on the right": invented. So the picture does not reach the model in a usable form, on both models. The app side is right: the stored files (`files/images/*.jpg`) are the right 640x480 JPEGs (pixels (220,30,30) and (20,60,220); (30,90,200)), the chat shows the right thumbnail, and `ChatEngine` passes the bytes to `Conversation.send(text, *images)` as `Content.Image(bytes, "image/jpeg")`. The SDK side passed its own image contract case (`t2-14-image`, SDK against upstream llama-server) on the host and on the phone's NPU. So the break is between them or on the phone. Candidates not yet tested: the mime type hard-coded as `image/jpeg`, the vision encoder on the phone's NPU backend (`DevicePolicy.Auto`), the projector not being loaded for these two models (both have their `mmproj-BF16.gguf` on the phone), and the way the message's image part is placed in the chat template. Not tested: the same pictures on the CPU backend, through the acceptance app, or on the Mac with the same files.
- **Evidence:** the two test pictures and the answers' screenshots from the end-to-end run (raw output not kept); the hub folder on the phone (`mmproj-BF16.gguf` beside both models); `ChatEngine.kt` (the `Content.Image` line); `sdk/native/tests/contract/make-requests.py` (`t2-14-image` needs the projector).
- **Is it a regression? Not shown, and probably not.** The phase did change SDK code (a conversation from stored messages and the memory budget; then a code-quality pass that reformatted and refactored the whole codebase). But the image part is built the same way before and after (`ChatJson.part`: an `image_url` with a base64 data URL, compared at the base commit and now). And **no test anywhere asserts that a model understands a picture**: the host end-to-end test sends "Describe the colours." with an image and only `collect()`s the reply (`HostNativeTest.kt:258-273`); the contract case `t2-14-image` compares the SDK's output with upstream llama-server's, so both could be wrong together; the phone's device contract does the same. So vision's correctness was never checked in this repository, before or after the refactor; the demo app is the first thing that looked at an answer. The first step of a fix is a short host check (the Mac CPU, the SDK's own host test with Qwen3.5 4B and its projector): does it name the colours of a red and blue picture? If yes, the fault is the phone (NPU) or the app; if not, it is the SDK or the model files, and has probably always been.
- **Host checks (2026-11-13):** upstream llama-server on the Mac CPU reads the red and blue picture correctly with Qwen3.5 4B and **either** projector (`mmproj-F16` or `mmproj-BF16`: "Red on the left side, Blue on the right side"), and the SDK's own Kotlin and JNI path gives the same answer with the F16 projector. So the model files, the BF16 format and the SDK's image handling are not the cause; what is left is the phone (the NPU backend) or the app's use on it. Not run: the SDK path with BF16, or anything on the phone.
- **Means:** an open, serious defect: the app advertises "Sees images" on three of its five suggested models. Decided (2026-11-13): fix it as its own task after PD, not inside it. Likely first steps: send the same file through the acceptance app and the host test with the phone's models; try the CPU backend; read the native log (it does not reach logcat); then the mime type. Also open after PD: the loop guard (F113: a cap on the reply per level and Qwen's `presence_penalty`), a Settings page for sampling values with a "reset to the model's default", and a test of the reasoning budget's forced end on a small model.
- **Resolved (2026-11-13):** ggml-hexagon computed some image encoders wrongly at b12052 (F120); first worked around with the encoder on the CPU (D51), then fixed upstream in b12352, so the encoder follows the model again (F121, D53).
- **Feeds:** a task after PD, F120, D51.

### F118. A model sheet could park itself low with its size cards and Get button cut off below the screen
- **Finding:** On the OnePlus 13 the model sheet (opened from Get models) was, on some first opens, left parked at 73 % of the screen's height showing only its header, the by-line, the description and the Basic / Advanced switch; the size cards and the **Get** button were below the screen's edge, so a person would have to drag the sheet up to find them. The sheet opens on "Looking at the sizes…" and grows into the cards when the list arrives; Material's modal sheet kept the offset it had animated to for the first, short height. It was intermittent: 1 of 3 and 1 of 8 cold opens in two trials, and the end-to-end run's step 4 failed on it three times ("did not see Get … within 30 s"; the sheet was there, its Get button was not on screen). A second open of the same sheet (sizes cached) was always right. Not seen on the emulator.
- **Evidence:** a screenshot of the failing step (the sheet parked low); `uiautomator dump`: the Drag handle at y = 2,341 of 3,168 instead of 1,118; 12 cold opens after the fix and 15 more, 0 parked (27 of 27), against 2 of 11 before. The fix: `DemoSheet` expands again, one frame after it, whenever its content's height changes. `ComponentBehaviourTest.aSheetThatGrowsAfterOpeningKeepsItsContentOnScreen` guards it (the original is intermittent, so that test alone would not have caught it).
- **Means:** fixed in the design system for every sheet whose content grows after it opens. The count is small (27 clean opens); a rate below about 1 in 10 could still hide in it, and the end-to-end run opens the sheet again on every run.
- **Feeds:** T13.

### F117. The phone was found in airplane mode after a test run; nothing the project does sets it, and the cause is not established
- **Finding:** After the phone runs of 2026-11-12 the OnePlus 13 had `airplane_mode_on = 1`. Nothing in the project can set it (no `airplane`, `cmd connectivity` or radio command anywhere in `demo/` or `sdk/scripts/`; T13's rule is that airplane mode is never toggled). The runs that touched the phone were the instrumented tests, the journeys (one opens the notification shade), the monkey (random taps and swipes, restricted to the app, system keys and app switching off) and the end-to-end script (taps by coordinates). The log buffer is 256 KiB (F114), so the moment of the change was gone by the time it was looked for; the same runs also lost the adb connection once and the screen lock engaged between commands. The likely paths are a random swipe or tap of the monkey reaching the quick settings tile, or a stray tap while the screen was changing; neither is proven.
- **Evidence:** `adb shell settings get global airplane_mode_on` = 1; `grep -ri airplane demo sdk/scripts` empty; `MonkeyTest.kt` (the flags); the logcat line `isAirplaneModeOn = true`.
- **Means:** the monkey no longer runs on the phone (the emulator runs it, ALL PASS), and `check.sh` stops with a plain message when the phone is locked or in airplane mode before the journeys and the real-model run, so a run cannot fail later in a way that looks like the app's fault. The phone's setting was changed by something not identified, and was left for the phone's owner to switch back.
- **Feeds:** T13.

### F116. Where Material is silent the app had been built from its own numbers: a pattern audit against the leading chat apps
- **Finding:** The spec's "chosen" rows (the composer, the user's bubble, reply actions, section gaps, empty-chat chips) had never been compared with how ChatGPT, Claude, Gemini, Google Messages or the others do them. An audit (teardown sites, Gemini's and Google Messages' redesign write-ups, a streaming-UI article; no leader publishes pixel values, so no number was verified) found: **matches:** the single-row pill with **+** at the start, attach behind it, send at the end and stop while streaming (Gemini, ChatGPT, Claude, Grok, Perplexity); open, unbubbled assistant text with an action row on the latest reply; "Thought for N s"; the model name with a chevron in the top bar; the empty-chat greeting and starter chips; a navigation drawer. **Differences, changed:** the user's bubble (the app's was near-black in light and near-white in dark; the leaders use a soft neutral), send always live when empty (the leaders show it only when there is something to send), the Think chip outside the bar (the leaders keep a tool chip in it), no way back to the latest message after scrolling up. **Differences, kept:** the "Switched to X" divider (no leader has one; useful because the models differ), the size cards and fit words (no precedent; leaders do not download models), letter avatars for models. **Not verified:** exact radii, padding and colours of any leader; haptics; dark palettes; WhatsApp, Telegram and iMessage (their pages were not reachable).
- **Evidence:** the audit's sources: ai-design-patterns.pages.dev and aiuxplayground.com teardowns of ChatGPT, Claude, Grok, Perplexity and DeepSeek; 9to5google.com on Gemini's and Google Messages' redesigns; smashingmagazine.com on streaming UI. The audit also wrongly said replies carry actions under every reply, which was not so (`ChatContent.kt`: `showActions = !busy && item.key == lastReplyKey`).
- **Means:** D49. The limit: "what the leaders do" here rests on secondary sources, not on measured apps; the bubble colours are the app's own. The rule going forward: a value Material does not give is checked against the leaders first and recorded as chosen with that check, or not used.
- **Feeds:** D49, T13.

### F115. Four small edge cases in the chat that the end-to-end run met
- **Finding:** (1) **A stale model divider after an edit.** Choosing Swift in an empty chat appended "Switched to Swift" as the chat's first line; the run then switched to Writer ("Switched to Writer"), and editing the first message cut the chat back to that message, which removed the later divider but kept the model: the new reply came from Writer (13 words/s, the header said Writer) under a banner that still said "Switched to Swift". (2) **Choosing a model in an empty chat adds a divider** ("Switched to Swift" above the first message) though nothing was switched from. (3) **A stopped reply that only thought** shows a blank preview line in the chat list ("Swift:" and nothing) where "Stopped" would say what happened. (4) The first switch to Writer, minutes after its download, answered "Writer needs more memory than this phone has free right now" with 7.3 GB available; the same model loaded in a new chat nine minutes later and the same mid-chat switch passed on the rerun (`MemAvailable` 3.7 GB while loaded). Not reproduced; treated as the moment's memory (the 3.3 GB just written was in the page cache), but the app's limit line does not offer a plain retry for it ("Try a smaller size" only opens the size sheet).
- **Evidence:** `ChatEngine.switchModel` and `edit`/`truncateFrom` (`chat/ChatEngine.kt`); screenshots from the run (the chat after the edit, the Chat list); `Limits.insufficientMemory`; `adb shell grep Mem /proc/meminfo` before and after.
- **Means:** none breaks a journey; all four are rough edges a person would notice. **Fixed 2026-11-12 (all four):** no divider for the first model of an empty chat; an edit cut that removes a divider re-appends the divider for the model that answers; the list preview says "Stopped" for a reply with no answer text; the memory line offers "Try again" first.
- **Feeds:** T13.

### F114. Facts about driving the real phone from outside (T13's script)
- **Finding:** (1) A **debug build has two launcher entries** (`MainActivity` and LeakCanary's `LeakLauncherActivity`); `monkey -p <pkg> -c LAUNCHER 1` may start LeakCanary's, so the first run looked for 60 s at a screen that was never the app. The script starts `<pkg>/.MainActivity` by name. (2) **logcat's ring buffer is 256 KiB** on this phone: a 20-minute download pushed the hub's resume lines out, so the log is read early after the relaunch and again as the run goes. (3) A **Clear all in Recents kills the app**: touching the phone mid-run broke the first attempt. (4) `uiautomator dump` text for the composer's Think chip also matches the **"Thinking" fold** in the transcript: the script takes the lowest node whose text is "Think" or starts "Think ·". (5) `ChatScreenTest` clipboard tests failed once on the emulator (three reads returned null, a focus loss) and passed on the rerun of the whole device check.
- **Evidence:** `demo/scripts/e2e/phone.py` and `real_model_e2e.py`; the first run's "did not see first run within 60 s"; `adb logcat -g` (256 KiB); the device check logs (FAIL 618 s, then PASS 670 s with no change in the tests).
- **Means:** the script is a fair gate again; a phone run must be left untouched; a clipboard failure on the emulator alone is not a regression until it repeats.
- **Feeds:** T13.

### F113. A small model can loop without end in its answer, and nothing in the app ends it: Think Quick looped on a fresh install, Think Deep never finished
- **Finding:** Swift (Qwen3.5 0.8B) was asked "How many R letters are in strawberry? Think it through." on the OnePlus 13. **Quick** finished once in 16.5 s ("Thought for 9 s"); on three other tries it looped. The clearest picture (final run) shows the thinking **finished normally** ("Thought for 8 s", inside Quick's 512-token budget) and the **answer** then listing the letters over and over ("1. s - s, 2. t - t, 3. r - 1st R, …", then the same again) for the whole 150 s the script waited. Earlier on the same chat the answer looped as "Wait, is the 'r' in 'be'?…" for over ten minutes. **Deep** (no budget) was still going after 150 s. **Stop** ended every one at once and kept the reply as "Stopped". Nothing in the app (no maximum reply length, no repeat guard) ends such a loop by itself. So the reasoning budget is not the issue: the loop is in the answer.
- **Evidence:** the end-to-end run's results (`think_quick_still_going_after_s` / `think_deep_still_going_after_s` and `*_stop_worked`; raw output not kept); `Thinking.kt` (the budgets cover the reasoning only; no level sets a maximum for the reply). Background research for the decision: hosted APIs always cap output; llama-server, Ollama and PocketPal ship no cap and no penalty by default; Qwen3.5's model card recommends `presence_penalty` and warns that the 0.8B loops more than its siblings; the repeat penalty is not recommended.
- **Means:** a loop is a normal event on a phone-sized model and Stop is the only exit. Open decision (deferred on 2026-11-12 to after PD): a per-level cap on the whole reply (for instance 1,536 Off, 2,048 Quick, 4,096 Balanced, 8,192 Deep, ended as "Stopped at the length limit" with Retry) and Qwen's recommended `presence_penalty`; and a Settings page where the sampling values can be changed with a "reset to the model's default". Not decided: D50 when it is. **Decided 2026-11-14 (D60):** a per-level limit on the whole reply with Continue, the card's presence penalty, and per-model and per-chat sampling.
- **Feeds:** T13, a possible D50 after PD.

### F112. Writer was fetched as a file the library could not name: the catalog's key was not the size picker's Recommended file
- **Finding:** The catalog said Writer is `gemma-3-4b-it-qat-GGUF:UD-Q4_K_XL`, but the size picker's Recommended tier is `Q4_K_M` whenever a repo has it (the plan's own rule, `Sizes.kt`). "Get Writer" on the phone therefore downloaded `Q4_K_M` (2,489,894,016 bytes) plus the vision file, and the library, which gives a friendly name only to the catalog's own key, listed it as "Gemma 3 4B": two names for one model (D48's "one name per model" broken), and the sizes on the Writer card (2.54 GB) did not match the download. Found by the end-to-end run, which waited 20 minutes for a "Writer" row that could not appear.
- **Evidence:** the phone's hub folder (`gemma-3-4b-it-qat-Q4_K_M.gguf` and `mmproj-BF16.gguf` in the snapshot, plans for three sizes); `huggingface.co/api/models/unsloth/gemma-3-4b-it-qat-GGUF/tree/main` (Q4_K_M 2,489,894,016; UD-Q4_K_XL 2,537,530,496); the Models screen ("Gemma 3 4B 2.5 GB").
- **Means:** fixed: the catalog's Writer key and size are `Q4_K_M` (catalog, previews, the catalog pin notes, the plan's table), and `CatalogTest.everyCatalogFileIsWhatTheSizePickerRecommends` fails if a catalog key is ever not the Recommended quant. Not changed: a person who picks another size in the sheet still gets the real name in the library (the stated rule).
- **Feeds:** T13, D48.

### F111. The photo picker is two different things, and the Google Photos one needs Done
- **Finding:** `sendAnImage` found no picture on the OnePlus 13 ("the photo picker showed no picture") because it looked for AOSP's `icon_thumbnail` ids. Android 16 phones with Google Photos open `com.google.android.photopicker`: no resource ids, each picture described "Photo taken on …", and a multi-select grid where a tap only ticks a picture and **Done** hands it back; the journey also has to wait for the app to be in front again before it looks for the app's own UI. The driver now takes either picker, presses Done where it is asked, and waits for the app. The picker shows the real gallery, and each run adds a blue test picture to it (named `journey-<time>.jpg`): six were left in the phone's gallery and removed by name (`content delete … _display_name LIKE 'journey-%'`).
- **Evidence:** `ChatJourneys.sendAnImage` on the phone: 1 test, 0 failed, 0 skipped; the failing runs' messages; `Robot.pickFirstPhoto`.
- **Means:** the phone gate for the chat journeys is real (the emulator's missing picker is no longer the only reason the test is skipped). Anyone running it on a phone with a gallery should expect it to add a picture and clean up with the command above.
- **Feeds:** PD T11, design system v2 step 8, T13.

### F110. The monkey's random "flip" event aborts the run on the emulator
- **Finding:** `MonkeyTest` ran Android's `monkey` with its default event mix, which includes a keyboard-lid flip. The emulator refuses to inject it (`FileNotFoundException: /dev/input/event0: open failed: EACCES`), monkey reports "Monkey aborted due to error" and the test fails with "monkey stopped early", on whichever seed happens to draw a flip first. It failed in three of five full runs on 2026-11-12 and passed on rerun; the assertion's tail showed only the permission line. `--pct-flip 0` removes it (a lid flip means nothing on a phone); all three seeds then pass (3 of 3, twice, and through `check.sh`).
- **Evidence:** the monkey log of the failing runs ("** Monkey aborted due to error." after "Got IOException performing flip"; raw output not kept); `MonkeyTest.kt` (the command); `check.sh --device --only quality,monkey` PASS.
- **Means:** the monkey step is a fair gate again: a failure now means a crash or an ANR.
- **Feeds:** PD T11, design system v2 step 8.

### F109. The screens do not share a layout system: gutters, gaps, type roles and states differ by screen, because nothing owns them
- **Finding:** An audit of every route, sheet and state (58 pictures looked at, both themes) found: (1) **Gutters differ by place.** Screens use 16 dp; the composer and the limit and token banners 12 dp (they stick out past the text and bubbles); the model sheet 20 dp; the model switcher, Think menu and message menu 36 dp for their section label and 41 dp for their rows; "Get more models" sits at about 28 dp, which matches neither the card edge nor the card text. (2) **Proximity is broken.** A section label is as far from its own content as from the content above it (24 dp each side on Appearance, Models, Instructions), so labels float; Save sits 8 dp under its field while labels get 24; in the chat, `31 words/s` and timestamps touch the text above and the action row is far below the reply (open style) or nearly touches the composer (bubbles); option rows in the Phi sheet are 44 and 28 px apart. (3) **The type has too little contrast:** row title 15 sp, subtitle 13 sp, card description 15 sp again, caption 11 sp; the user's text, the reply, the list and the code are all about 15 sp. Row titles are bold only when the row is tinted (Models, Get models bold; Settings, drawer, sheets regular); sheet titles are serif while screen titles are sans. (4) **Components differ.** Two primaries (near-black on Get, Save, New chat; navy on the FAB, send, selection, links); in Get models and the quant list a card's tint means fit, a navy border means selected and a badge means recommended, with the badge straddling a card's top edge; "Get more models" is bare text on two screens; Settings, About and Storage are bare text rows with no group, divider or chevron; the drawer lists destinations and chats in one style; the About values sit in a column at 68 % of the width; trailing text such as "not used yet" stops 47 dp short of the card's padding edge while "Runs great" sits on it; the empty chat's chips are left-aligned under a centered hero. (5) **Defects:** the token field has a search icon; the disabled Save is white on grey (contrast fails); the send button shows a grey halo in some states; in dark theme the status bar band looks lighter than the screen while a sheet is open (not confirmed); model tiles mix lowercase and sentence-case lines ("can think first, or not" beside "Uses about 1.0 GB"); a model has a nickname on first run and its real name on Get models.
- **Evidence:** the audit's pictures (raw output not kept); `DemoRow` in `designsystem/.../components/Lists.kt` (one component with 10 parameters: an `inset` default of 12 dp that callers override with 0 or pad over, a title weight that depends on `tint`, a `weight(2f)` text column that leaves trailing text mid-row); `ModelSwitcherSheet.kt` (16 dp sheet padding plus the row's inset), `SettingsScreens.kt` (`inset = 0.dp`), `LibraryScreen.kt` (literal `16.dp`, `24.dp`, `8.dp`); `DemoType` (7 sizes, 28 22 20 17 15 13 11, the iOS text-style sizes, no ratio, 2 sp apart for the three styles used 100 times); `DemoSpacing` (5 steps; no layout primitive uses them for a screen's gutter).
- **Means:** the cause is that nothing owns the gutter or the gap rules: every screen and sheet picks its own, and one row component stretches to every use. The fix is structural, not a nudge of values: layout primitives that own the gutter and gaps, a type scale from an established system, one component per job, one signal per state, and a check that bans literals. Planned as design system v2 before T13; decisions in D48. Research (secondary sources, to be verified against the Compose Material 3 token sources in that plan's step 1): Material 3's 15 type roles and 4/8 dp grid, Carbon's productive type set and 8 px mini-unit, Atlassian's and Polaris's token-scoped layout primitives, Android's grouped settings, Gestalt proximity. Not audited: large font scales, landscape and tablets, right-to-left.
- **Resolution (2026-11-12):** all five groups are fixed in design system v2 and checked against the pictures and the code (two review rounds): the gutter is 16 dp on every screen and sheet (a test asserts it), labels sit 4 dp over their content, the type is Material's roles, one primary, one signal per state, sentence case, one name per model, the composer padded on one 16 dp line. The dark-theme band was wider than the audit saw: the activity's window background follows the phone's day or night setting, so a dark app on a light phone showed a light strip behind the status bar on every screen (sheets only made it visible); the window now follows the app's own theme and the walk asserts it. Left as they are, with reasons: the model card's trailing layout, dp literals inside `:designsystem`, the gap under "Picked for your phone".
- **Feeds:** D48, PD design system v2, T13.

### F108. Every performance budget is met on the phone; the heap budget measures what the heap holds, not what ART sets aside
- **Finding:** On the OnePlus 13, release build with the Baseline Profile and 10 iterations each, cold start is 116 ms to initial display and 198 ms to full display (budgets 450 and 700; without the profile 123 and 236 ms); flinging the Chat list of 300 chats and a chat of 300 messages finishes every frame early (P90 −9.0 and −7.3 ms); streaming 60 tokens a second for 20 s has frameOverrun P90 −1.4 ms, `ChatEngine.onDelta` 5 µs and `Markdown.parse` 0.18 ms a call; the model sheet and the layout switch have P90 −6.0 and −4.5 ms. Macrobenchmark's heap metric is the size ART sets aside: it saw-tooths from 19 MB to 50 to 75 MB inside each stream and reads 56 MB to 89 MB median (up to 139 MB at the end of the full suite) while the heap holds 10 to 23 MB and the baseline stays flat (the same process for ten runs): no leak, only the generational collector's headroom. The gate samples Heap Alloc from `dumpsys meminfo` (median of the run peaks 21.2 MB, budget 64) and reports the footprint.
- **Evidence:** `demo/scripts/perf.py` and the macrobenchmark result files it reads (the results table was not kept); the polling of `dumpsys meminfo` through the ten streams, 2026-11-11.
- **Means:** how T12 differs from its plan, so no one reads more into it than it proves. (1) The benchmark builds swap the container through a class found by name (`BenchmarkContainerSource`, in `src/benchmark/kotlin`, kept by `proguard-rules.pro`), because a manifest `android:name` override did not apply to the build types the Baseline Profile plugin creates; the production code only calls `ContainerFactory`. (2) The generated profile is in `demo/app/src/release/generated/baselineProfiles/` (the plugin's place), not `src/main/baseline-prof.txt`. (3) The budgets are checked by `perf.py` from the result files, because `MacrobenchmarkRule.measureRepeated` returns nothing a test could assert on; `check.sh --device --perf` runs both. (4) `UNLOCKED` is suppressed for the clock-lock check an unrooted phone cannot pass. (5) The heap budget's definition was changed to Heap Alloc (decided 2026-11-11). (6) The fake engine and hub mean the numbers are the app's own; the model's cost is T13's. Not covered: both message styles in the chat fling, a trace section around `ChatRepository.update`.
- **Feeds:** PD T12, Reference I, T13.

### F107. The journeys found four defects in the screens; each was fixed
- **Finding:** (1) A download that stopped (a model behind a token, the network, the disk) reached the chat as "<Name> isn't on this phone any more", and a held message kept saying it would be answered "the moment it's here": `ChatModelState` had no word for a failed download. It has now (`DownloadFailed`), the chat shows the plain line of Reference G with its button (Add token, Retry), Retry starts the download again, and saving a token restarts the downloads refused for want of one (`ModelRepository.tokenSaved`). (2) Switching from Sidebar to Chat list inside a chat left nothing beneath the chat, so its Back did nothing; Back from such a chat now goes to the list. (3) A phone with no system photo picker (this emulator image has none) crashed on "Add a photo" with `ActivityNotFoundException`; it says "This phone has no photo picker." now. (4) Reference G says "Try a smaller size" opens the model's sheet on Smaller; the app opened the model switcher, which lists the models on the phone, not the sizes of this one. Decided (2026-11-11): the spec stands. It now opens this model's own sheet on Smaller (`ModelSheetRoute(startTier = Smaller)` from the chat), and Get starts that file and moves the chat to it, the question held.
- **Evidence:** `GatedJourney.gatedModelNeedsAToken`, `ChatEngineTest.aRefusedDownloadBecomesAPlainLineAndRetryStartsItAgain`, `ModelRepositoryTest.savingATokenRestartsOnlyTheDownloadsRefusedForWantOfOne`; `ShellJourneys.bothLayoutsJourney` (Back did nothing before the fix); `ChatJourneys.aPhoneWithNoPhotoPickerSaysSoInsteadOfCrashing` (the first run of `sendAnImage` died with the exception); `ChatJourneys.memoryRefusalJourney` (the sheet opens on Smaller and Get enqueues the Q3_K_M file), `ModelSheetViewModelTest.aSheetOpenedOnSmallerStartsOnTheSmallerSize`.
- **Means:** how the journeys differ from the plan, so no one reads more into them than they prove. They run in the light theme; `WalkJourneys.everyScreenInBothThemes` walks every screen and sheet in both, with the accessibility check on each once it settled. Process death cannot be made by the test (the instrumentation shares the app's process): `downloadSurvivesProcessDeath` stops the worker as the system does and restarts the activity, and the SDK's `hub-resume` check is the real kill. The monkey runs inside the instrumentation (fake hub and engine, three seeds of 10 000 events) rather than on a `benchmark` build, which T12 had not yet made. `sendAnImage` needs a phone with a photo picker (it is skipped on this emulator, so the gate needs the phone). The system asks about notifications once per install, so `NotificationDeniedJourney` runs first on a fresh install in its own run.
- **Feeds:** PD T11, F106, Reference G, Reference L.

### F106. A test can fail a load the way the native side does, and the accessibility check flags a surface that is still moving
- **Finding:** (1) `FakeLlamaBro.failLoad(reason, message, times)` makes `load` throw the `NativeError` the native bridge would (`file_not_found`, `insufficient_memory`, `other`), so the app's memory-limit and retry lines are tested against the SDK's own error path rather than a hand-made exception; `NativeError` became public under `@InternalLlamaBroApi` for it (`llamabro.api` +6 lines). (2) The Compose accessibility check runs right after an action, while a drawer or a sheet is still sliding in, and reports the 48 dp menu button or sheet row as 45 dp because the moving surface clips it; the shell tests turn the check off across the move and on again once the screen is idle.
- **Evidence:** `ChatEngineTest.insufficientMemoryBecomesAPlainLimit`, `aLoadThatFailsForAnotherReasonSaysSoAndOffersRetry`; `sdk/scripts/check.sh` ALL PASS (host, API dump updated); `ShellTest` 15 of 15 on the API 35 emulator (the first run showed the 45 dp reports; the same views pass once settled).
- **Means:** the menu button and the sheet rows are 48 dp (`IconAction` sets `size(48.dp)`); the check stays on for every settled screen.
- **Feeds:** PD T11, F104.

### F105. ktlint and detekt are wired into the root build; the ktlint Gradle plugin finds no sources under AGP 9, so the CLI runs instead
- **Finding:** (1) `org.jlleitschuh.gradle.ktlint` 14.0.1 creates only a script check and a format task for Android modules on AGP 9.1 (built-in Kotlin has no `org.jetbrains.kotlin.android` plugin to hook its source-set tasks on) and defaults to ktlint 1.5.0; the root build therefore runs the pinned ktlint 1.8.0 command-line jar (the `shadowed` variant) in a `JavaExec`. (2) detekt 2.0.0-alpha.3 (built against Kotlin 2.3.x, Gradle 9.3.1, AGP 9.1.x) works with AGP 9 and creates per-variant tasks; 2.0 renamed rule options (`threshold` became `allowed*`; `MatchingDeclarationName` moved to `naming`). (3) The first run reported 13.5 thousand ktlint findings in 265 files and 611 detekt findings; `ktlintFormat` fixed all but 203. (4) ktlint's `binary-expression-wrapping` wrote conditions like `if (x !=` / `null` / `) {` in 29 places and `when-entry-bracing` braced 94 one-line entries; both are disabled in `.editorconfig` and the results tidied by script. (5) Real defects found: two dangling KDocs (`Catalog.byRepo`, `NativeBridge`), 148 inline fully-qualified names, constant names in PascalCase, three files not named for their declaration, `ChatEngine.stream` of 103 lines with eight responsibilities (now `ReplyDraft` plus four small functions), and `ChatEngineTest.until` ignoring its `what` argument.
- **Evidence:** commits `ec621a6b`, `e7f5713b` and the clean-up commit `06a97bdd`; `demo/scripts/check.sh` and `sdk/scripts/check.sh` both ALL PASS with the new `quality` step (the SDK's contract, JVM and end-to-end steps unchanged by the formatting); 66 detekt findings baselined in the SDK modules (`sdk/*/detekt-baseline.xml`: `LlamaBroHub`, `BlobDownloader`, `HttpTransport`, `HeaderFetch`, `Transfers`, `ModelConfig.toJson`, `Conversation.streamReply`, and magic numbers in the acceptance app), 2026-11-11.
- **Means:** new code must pass both tools; the baselined SDK functions need real refactors (each with its golden and device tests), left to be scheduled.
- **Feeds:** the code-quality pass; F104.

T9 follow-up (same day): `ChatScreen` is a thin route (view model, platform: clipboard and photo picker, passing sheets) over `ChatContent`, a stateless function of `ChatScreenState` (one derived value from `ChatViewModel.uiState`), `ChatLive` (the token, the keystroke and the thumbnail, read as lambdas where each is drawn, so a token still redraws one row and typing only the bar) and `ChatScreenActions`; the parts (`ChatTopBar`, `MessageList`, `StreamingHost`, `ComposerArea`, `PhotoRow`, `LimitRow`) are small. In the view model the streaming reply is a `scan` instead of a mutable `previousParse`, the send guard a `Mutex`, and a photo's acceptance one atomic `getAndUpdate`. Sheets split into window and content (`ThinkMenuContent`, `ModelSwitcherContent`, `MessageMenuContent`). 10 whole-screen states and 9 parts have previews, light and dark, also run as screenshot tests; the 36 chat screen tests and the whole demo check are unchanged and green. Photo thumbnails show only their ✕ in a preview (no image source there).

Shell and settings follow (same day): `DrawerContent`, `ChatListContent`, `ChatActionsSheets` (one sheet whose content swaps between `ActionsContent` and `RenameContent`), and every settings page (`AppearanceContent`, `InstructionsContent`, `DefaultModelContent`, `TokenContent`, `AboutContent`) are stateless with previews of each state; `SettingsViewModel.hasToken` is derived from a write counter. Reading the previews found misaligned left edges (the Chats title at 27 dp, settings rows at 26 dp and the drawer's selected row wider than its button, against a 16 dp gutter), fixed at the cause: `DemoRow(inset)`, the `TopBar`'s leading space, drawer rows at 4 dp, Default model as cards. Spacing now comes from `DemoSpacing` (4, 8, 12, 16, 24 dp) and type from `DemoType`; `demo/scripts/check.sh` fails on a size in sp or a dp literal in a padding, gap or spacer of `:app`. The demo's phone tests also run on an arm64 emulator (`DEMO_DEVICE=emulator-5554`, API 35: API 37's image breaks Compose's test input injection).

### F104. T10's screens are built on derived state, and the SDK's plan and estimate types can't be built in a test, so the app reads them as plain values
- **Finding:** (1) Under the architecture rule of declarative, flow-derived state, every T10 view model exposes `StateFlow`s made with `combine`, `flatMapLatest` and `stateIn(WhileSubscribed(5 s))`; only genuine input is a mutable flow (the query, the mode, the pick, "Retry"). Per-row work (a fit, a download's progress, a real estimate) is a flow asked for when the row first appears, so a late result recomposes that row alone. A sheet's view model lives in a store that is cleared when the sheet leaves the screen (`rememberSheetViewModelOwner`), which cancels its estimates; the activity's own store would have kept them until the whole screen closed. (2) `HubPlan` and `FitEstimate` have internal constructors, so a JVM test can't build them: `HubGateway.estimate(model)` returns `HubEstimate` (plain sizes and the projection), and `RealHubGateway` converts. (3) The hub can list complete models but not partial downloads, so the library's "Downloading" needs the app to keep the keys: `AppSettings.pendingDownloads`, added on Get, removed on cancel, remove, or when the model appears in `cached()`; a paused download stays listed with Resume. (4) Without `AppContainer.discovery` as an injectable, the phone tests would have needed a fake HTTP server; `HfSearchTest` already covers the HTTP layer, so the phone tests stand a `FakeDiscovery` in. (5) Every screen is a thin route (view model, `collectAsStateWithLifecycle`) over a stateless screen over small parts, each with `@Preview`s for its states in light and dark; `:demo:app` runs them as screenshot tests (`Screenshots.kt`), and the design system gained `DemoDialog`, `DialogCard`, `SwipeToRemove` (with a screen-reader action), `FactCard`, and a public `SheetFrame`.
- **Evidence:** `demo/app/src/test/.../ui/{firstrun,models}/*ViewModelTest.kt` (51 tests; a deliberate break of the debounce, the Basic fallback and the parallelism limit each failed its test, 2026-11-10); 68 screenshot-test pictures reviewed against the mockups (the pictures and the screenshot tests are not in the public repository); `ModelRepositoryTest` (four new tests for the downloading list).
- **Means:** T9's chat screen got the same treatment afterwards (see F105). The mockup says "120 GB free", the plan "6.1 GB of 126 GB free space used": the library follows the mockup. For a model the app doesn't ship (a search result), the sheet shows the repo alone, not "by <owner>", because the repo's owner is the uploader and not the maker.
- **Feeds:** PD T10, PD T9, D46.

### F103. A review of T9 found a chat handle that outlived its screen, a message lost on refusal, and a crash on a bad photo
- **Finding:** A review of commit cba016d by a reader with no part in writing T9 found, confirmed in the code: (1) every `ChatEngine.open` started about six coroutines in the app-wide scope that never ended, so each chat opened leaked a database observer and a pump, and two handles on one chat could answer one held message twice; (2) a photo that couldn't be decoded threw in `viewModelScope` and killed the process; (3) `send` cleared the draft before the engine said yes, so a message with pictures for a model that can't see them was lost and its files leaked; (4) a layout switch kept the rows built for the old style; (5) edit mode dropped new photos; (6) tapping the model already in use added a "Switched to" divider and stopped the reply; (7) Retry stacked a second reply on a failed one and the limit line stayed; (8) the share card named the current model, not the model that wrote the reply; (9) a share-card write failure crashed and an old card was deleted while another app might still read it; and tests that could not fail (the waking hairline is always in the tree; the photo test never checked the thumbnail). All are fixed in the follow-up commit, each with a test that fails without the fix. Not changed: "Get more models" still opens the library, because the `get_models` route is T10's.
- **Evidence:** the review report (2026-11-10); `ChatEngineTest` (`closingAHandleStopsItsReplyAndItsObservers`, `sendSaysWhetherItWasTaken`, `picturesToAModelThatCantSeeAreRefusedWithTheLine`), `ChatViewModelTest` (nine new tests), `ChatScreenTest` (five new tests).
- **Means:** every handle now has its own scope and `close()`, called from `ChatViewModel.onCleared`; leaving a chat stops its reply (saved as stopped). `send` is `suspend` and returns whether the message was taken. A rule for T10 and T11: anything a screen opens in the engine is closed with the screen.
- **Feeds:** PD T7, PD T9, PD T10, PD T11.

### F102. A Light or Dark choice in the app left the status bar's icons for the system's setting, so the clock vanished on a mismatch
- **Finding:** `enableEdgeToEdge()` reads the phone's dark mode once. With the phone dark and the app set to Light, the T9 screenshots show white status icons on the light canvas (the time is unreadable). `AppShell` now sets the icon appearance (`WindowInsetsController`) whenever the theme choice changes (`SystemBars`). The first attempt, calling `enableEdgeToEdge()` again from the composition, made LeakCanary report each activity's compose view as leaked in every shell test, so only the appearance is set.
- **Evidence:** a T9 screenshot before the fix (the clock is a ghost; raw output not kept); `ChatShellTest.systemBarIconsFollowTheAppTheme` (after), 2026-11-10.
- **Means:** the T8 theme setting was incomplete. Fixed in T9.
- **Feeds:** PD T8, PD T9.

### F101. T9's accessibility checks found what T3's component checks missed: long-press targets 37–40 dp tall and an unlabelled sheet handle; the screen also drew a ghost reply row
- **Finding:** `enableAccessibilityChecks()` on the whole screen failed on (1) message bubbles and replies, whose long-press area was only as tall as the text (37–40 dp; `UserBubble`, `ModelBubble`, `OpenReply`), (2) the bottom sheet's drag handle (no label, 36 × 24 dp). Both are in `:designsystem` and passed T3 because no component test put them in a real list or opened a sheet. Both now get a 48 dp touch area and the handle a label. The bubbles' area grows without moving anything. The handle is Material's own plain 48 dp box, which makes every sheet 24 dp taller than the mockups draw (15 screenshot references re-recorded): two versions that kept the mockups' pixels (a bigger box laid out around the bar; the content pulled up over the box) either failed the check or failed it whenever a sheet's first row was tappable and covered the handle's lower half. The tests also found a real bug: the stored placeholder row of a reply being written was drawn next to the streaming row, an empty clickable ghost; `ChatItems` now leaves it to the streaming item. On the OnePlus 13 the system zooms an activity open for about a second, and an accessibility check made then reads every button at about three quarters of its size; the tests wait for the window to settle before the first action (`ChatScreenTest.settleWindow`).
- **Evidence:** the `ChatScreenTest` runs of 2026-11-10 (first run 12 failures of 31, last run 31 of 31 pass); `ChatItemsTest.theReplyBeingWrittenIsNotAStoredRow`.
- **Means:** component tests with accessibility checks are not enough: each screen that uses a component in a new role needs its own check. Reference K's recomposition test needs the list to stop scrolling before it counts (new rows scrolling in are compositions, not recompositions).
- **Feeds:** PD T3, PD T9.

### F100. The message bar floated far above the keyboard: the window resized for the keyboard and the screen padded for it too
- **Finding:** Found while the tests ran, then reproduced in the real app: with the keyboard up, the bar sat about 260 dp above it with a hole between, and the empty chat's title was pushed off the top. `MainActivity` drew edge to edge and the screen used `imePadding()`, but the manifest left `windowSoftInputMode` unset, so the system also resized the window. `adjustNothing` fixed it: the bar now sits on the keyboard.
- **Evidence:** screenshots of 2026-11-10 (the fixed state is the T9 streaming screenshots; raw output not kept), the manifest line in `demo/app/src/main/AndroidManifest.xml`.
- **Means:** every screen with a text field needs the same pairing: edge to edge, `imePadding()` and `adjustNothing`. T10's search field must follow it.
- **Feeds:** PD T9, PD T10.

## PD demo app (PD T1, read from the SDK and upstream b12052)

### F97. An app can't show a fit label for a list of models without the memory budget, which is internal
- **Finding:** The budget `ContextSize.Auto` fits a model to is computed only inside the runtime, from Android's memory at load time, the prompt cache and the reserve. An app listing search results has no public way to get it. `hub.estimateFit` gives a full answer, but it needs a plan and GGUF header reads per file (F88), about five requests per search row.
  - **Thinking isn't in `/props` either.** At b12052 `/props`' `chat_template_caps` has no "can think" flag: its keys are `supports_*` for tools (`supports_tools`, `supports_tool_calls`, `supports_parallel_tool_calls`, `supports_object_arguments`), the system role, typed and string content, `supports_reasoning_effort` and `supports_preserve_reasoning`. llama-server decides thinking with `common_chat_templates_support_enable_thinking`, which `/props` doesn't export. The app infers it instead (PD plan, Changes to the design, 2).
- **Evidence:** `LlamaBroRuntime.budgetFor` and `MemoryBudget.budgetMib` (internal); `sdk/native/third_party/llama.cpp/tools/server/server-context.cpp:1706` (`template_supports_thinking`) and `:5689` (`chat_template_caps`), `common/chat.cpp`'s caps keys, at b12052, 2026-11-09.
- **Means:** The SDK exposes the budget as `LlamaBro.memoryBudgetBytes(configure)`: the snapshot and `MemoryBudget.budgetMib` with no side files, in bytes; null where memory can't be read (the fake engine). Its KDoc says it moves with free memory and is a hint, not a promise. Tested by `MemoryBudgetApiTest`.
- **Feeds:** PD T1, PD T5, D42, D45, F88.

### F96. An app that stores its own chats can't start a `Conversation` from them; the SDK only restores its own JSON
- **Finding:** `Model.conversation` always starts empty, and `restoreConversation` needs `Conversation.toJson` output. An app whose chats live in its own database (PD's Room) can't rebuild a `Conversation` after an edit, a regenerate, a model switch or a reopened chat without writing the SDK's JSON format itself.
- **Evidence:** `sdk/llamabro/src/main/kotlin/com/suhel/llamabro/Model.kt:269` passed `emptyList()` as the history to the internal constructor, which already takes one; `restoreConversation` decodes `ConversationJson` only, 2026-11-09.
- **Means:** `Model.conversation(system, history, configure)` takes the stored messages (without the system prompt, which has its own parameter; a system message in `history` throws `IllegalArgumentException`). Tested by `ConversationHistoryTest`.
- **Feeds:** PD T1, D46.

## Hub (P3 planning, read from upstream b12052)

### F99. The SDK's DFlash test passed in 4 of 4 runs, each right after a fresh install of the test app; F95 did not recur
- **Finding:** The `f95-dflash-repeat` script ran `DflashDeviceTest` four times on the OnePlus 13. Before each run it uninstalled the hexagon test app, pushed Qwen3.5 and the draft, and let Gradle install the APK, so each run was the first after an install, as F95's failing run was. All four passed: the reply equals upstream's byte for byte (the test's assertion) and `draft_n` is 435. Decode 32.7-33.5 t/s. `MemAvailable` before the load 6.9-8.1 GB, after the reply 1.3-1.5 GB.
  - **Discarded:** a first attempt never started Gradle (wrong wrapper path, exit 127) and its printed results were stale files from an earlier run; deleted. The script now removes old result files before each trial.
- **Evidence:** [`research/p3/f95-dflash-repeat/sdk_probe.sh`](../../research/p3/f95-dflash-repeat/sdk_probe.sh) and the per-trial JUnit XML and logcat in [`logs/`](../../research/p3/f95-dflash-repeat/logs/) (`probe-t*.xml`, `probe-t*.logcat`), 2026-11-10; the script's console output was not kept.
- **Means:** with F98 (upstream, 24 of 24) and F90's earlier runs, F95's divergence has now not recurred in 28 further runs, including the fresh-install case. The one-token divergence stays unexplained and unreproduced; it does not block P3. Treat it as a rare event: if `DflashDeviceTest` fails again, keep the XML and the logcat of that run and compare with F95.
- **Feeds:** F95, F98, F90, D41.

### F98. Upstream llama-server with DFlash on the NPU matched F73's greedy reply in 24 of 24 asks over 8 fresh loads; F95's divergence stays unexplained
- **Finding:** [`f95.sh`](../../research/p3/f95-dflash-repeat/f95.sh) ran upstream llama-server (b12052 build on the phone, `-dev HTP0 -ngl 99`, `-md Qwen3.5-4B-DFlash-Q8_0.gguf --spec-type draft-dflash --spec-draft-n-max 15 -fa on`, `-c 8192`, `--load-mode none`) through 8 fresh server starts of 3 json-greedy asks each (seed 42, temperature 0, `cache_prompt` false, thinking off). Every one of the 24 replies equals the reference reply recorded in F73's DFlash run byte for byte. Each ask: `draft_n` 435, 225 accepted, decode 31.8-32.8 t/s, prefill 565-604 t/s. Thermal status 2-3.
  - **Not covered:** the first run after a fresh app install, where F95 happened. The test uses the shell binary, not the SDK's app, so no install precedes the first run.
  - **Discarded:** a first launch overlapped a second copy of the script on the same phone and port; its output was garbled and not used.
- **Evidence:** the script's run output and the 24 replies (raw output not kept), 2026-11-10.
- **Means:** upstream does not diverge in this setting, so F95 is not shown to be NPU numerics. It stays open: the candidates are the first NPU graph build after an install, or an earlier draft load in the same process (F90's `Auto` load before the fixed one). Next probe: `DflashDeviceTest` repeated right after reinstalling the APK, with and without the earlier `Auto` loads.
- **Feeds:** F95, F73, F90, D41.

### F95. DFlash on the NPU once diverged from upstream's greedy reply at one token, in 1 of 6 runs; not reproduced, not root-caused
- **Finding:** `DflashDeviceTest` was run six times in a row on the OnePlus 13 (NPU, `Fixed(8192)`, `Speculative.DFlash()`, seed 42, temperature 0), each time after an `Auto` load of the same model with the draft (16,384 tokens) and one without.
  - **The first run**, right after the test APK was reinstalled, diverged at one token: `"cmake_flag": "-DGGML_BLAS=ON -DGGML_BLAS_VENDOR=BLIS"` where upstream wrote `"[See BLIS.md"`. Everything after that token differed.
  - **The next five runs** matched upstream byte for byte, with `draft_n` 435 and 32.6–33.2 t/s.
  - **Earlier runs:** all matched, including four full `check.sh --device` runs. In those, `Auto` with the draft refused, so the fixed DFlash load was the first draft load in the process.
- **Evidence:** the failing run's JUnit XML diff and the passing runs' `DflashDevice` log lines, 2026-11-09.
- **Means:**
  - Greedy DFlash on the NPU is usually but not always deterministic here.
  - Candidates, none tested:
    - The first NPU graph build after an install.
    - A draft load earlier in the same process.
    - Numeric ties in the draft's acceptance.
  - It doesn't block P3: the gate passes, and drafting decides only which tokens are tried, never what greedy decoding accepts, so a divergence points at the NPU's numerics, not at the SDK.
  - Open: upstream llama-server did not diverge in 24 of 24 asks over 8 fresh loads (F98), and the SDK's own test passed 4 of 4 right after fresh installs (F99). Not reproduced, not root-caused.
- **Feeds:** D41, F73, F90, P3 T6.

### F94. The end-of-phase review found no Critical or High issue; its five Medium and seven of its nine Low findings are fixed with tests
- **Finding:** A review by a reader with no part in writing the code covered P3 in full (every commit of `phase/p3-hub` plus the T9 sample logger). It found the six review-focus items met and both API dumps equal to Reference B.
  - **Medium, all fixed:**
    - A `CancellationException` from inside a transfer (a timeout in the token provider) left the transfer with no end, so every collector waited for good. It now fails them.
    - The sample's job service cancelled another model's job; it now keeps one coroutine and one notification per job.
    - `delete("org/repo#file")` removed the whole repo; it now removes that file and its parts.
    - The hub's scope had no exception handler, so a failed disk read would crash the app; it now logs.
    - The DFlash refusal check only tested self-consistency. A new host test, `dflash_fit_matches_load_rss`, now bounds the projection against a real load's peak RSS: 5,793 projected vs 5,207 MiB, ratio 1.11, allowed 0.90–1.18. A double-counted recurrent state would give about 1.26.
  - **Low, fixed:**
    - A complete partial is now hashed before any request, so D44's shortcut needs none even without `Accept-Ranges`.
    - `delete()` cancels a pending first disk read, so it can't overwrite the state with Complete.
    - A verified blob not yet linked no longer counts toward `bytesToDownload`.
    - The sample worker rethrows cancellation, which is an `IllegalStateException`.
    - Offline, a tag missing from the cache is a retryable `Network` error, not `NotFound`.
    - A bad or truncated GGUF header is a `HubException.NotFound` saying which.
    - Deleting a selection also removes its unfinished downloads that no other selection plans.
  - **Low, left:**
    - `getInstance` and the sample touch `noBackupFilesDir` and SharedPreferences on the caller's thread, once.
    - `killDuringEachPhaseResumes` doesn't kill during the hash or the refs write. The hash only reads, and refs go through `writeAtomic`.
    - On this phone the DFlash `Auto` success path didn't run with the default prompt cache (F90). By D45 the test now loads with `ramCacheMib = 0`, as F88's does (512 still left it 170 MiB short when the phone had 7.8 GB free); the host bound above stays.
- **Evidence:** the review report, 2026-11-09. The tests: `TransfersTest.cancellationFromInsideFailsCollectorsAndFrees`, `CacheTest.deleteExactFileKeepsTheRepoOtherQuants`, `CacheTest.deleteTagRemovesItsUnfinishedDownloadsOnly`, `DownloadTest.completeVerifiedPartialNeedsNoRequestEvenWithoutRanges`, `HubStateTest.offlineMissingTagIsRetryable`, `HubStateTest.verifiedUnlinkedBlobIsNotToDownload`, `GgufHeaderTest.badOrCutHeadersAreHubErrors`, and the native `dflash_fit_matches_load_rss`. All 90 hub JVM tests pass.
- **Means:** P3's review-focus items hold, with tests behind each.
  - **Open:** verifying a large partial before resuming. Only per-chunk checkpoints written by the SDK, or Hugging Face's Xet chunk hashes (F93's CDN is Xet), can do that; a whole-file SHA-256 can't. **Settled 2026-11-10 (D47):** leave as is; the partial is re-hashed on resume.
- **Feeds:** P3 T9 Step 4, F90, F91, D44.

### F93. One real download from huggingface.co: killed at 51%, resumed with Range (206 from Xet's CDN), both files verified, chat answers
- **Finding:** `unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M` at 6ab4614: the model (532,517,120 bytes) and the BF16 mmproj (207,346,528 bytes) through `samples/background-download` on the OnePlus 13 over Wi-Fi, with no token.
  - **The run:**
    - kill -9 at 37 s, holding 377,519,564 bytes. WorkManager re-ran the work 21 s after the relaunch.
    - The resumed `GET` asked `Range: bytes=378682828-` and the CDN answered 206.
    - Both blobs were named by their SHA-256, done 97.6 s from the start, about 10 MB/s.
    - Chat answered "Hi, how can I help you today?" 6 s after the tap.
  - **The resolve URL** redirects (302) to `us.aws.cdn.hf.co/xet-bridge-us/…`, Hugging Face's Xet storage. The CDN answers HEAD with 200 and `accept-ranges: bytes`, and Range with 206.
  - **The tree response has no `Link` header**, so there is no pagination, as upstream assumes.
  - **The sample's first chat showed an empty reply:** it read only `content`, and Qwen3.5 thinks by default. The sample now turns thinking off for its greeting and falls back to the reasoning.
- **Evidence:** [`research/p3/real-download/`](../../research/p3/real-download/results.md) (`results.md`, `run.sh`, `timeline.txt`, `headers.txt`; the hub log was not kept), 2026-11-09.
- **Means:**
  - The plan's real-CDN risks don't occur: HEAD and Range both work, with no pagination.
  - Upstream's `-hf` would see the same.
  - P3's done-when holds on the real Hub as well as the fake one.
- **Feeds:** P3 T9 Step 2, D39, D40, D43, F92.

### F92. Kill and resume passes on the phone; the force-stop case stalled only because the sample's notification prompt was left open, which kept the relaunch in the background where the OEM freezer stops it
- **Finding:**
  - **The done-when passes.** With `step_hub_resume` on the OnePlus 13, against the fake Hub at 30 MiB/s, on a 1.5 GiB file:
    - kill -9: the first GET after the kill asked `bytes=508559360-`.
    - force-stop: it asked `bytes=504365056-`, both at 31%.
    - Both blobs were named by the corpus's SHA-256 and no partial was left.
    - `BackgroundDownloadTest` read the notification's MB count rising.
    - Three rounds passed in a row, including one inside a full `check.sh --device`.
  - **Why earlier force-stop rounds stalled** at 72%, and once failed to resume at all:
    - The first launch asks for `POST_NOTIFICATIONS`, and nothing answered the prompt.
    - After `am force-stop`, `am start` only brought the task forward (result code 3), so `GrantPermissionsActivity` stayed on top and `MainActivity` was never resumed.
    - The app therefore counted as background. WorkManager re-ran the work 21 s later and `setForeground` threw `ForegroundServiceStartNotAllowedException`, which the sample now catches to continue as plain background work.
    - OnePlus's freezer (`OplusHansManager`) then froze the background process, and the transfer stopped.
  - **What this phone denies the shell:** `pm grant` (no `GRANT_RUNTIME_PERMISSIONS`) and `pm clear`. `adb install -g` works.
- **Evidence:** `sdk/native/build/check/hub-resume.log` (the three passing rounds), and logcat of the failing round (`ActivityTaskManager` START … result code=3 behind `GrantPermissionsActivity`; `WM-ForceStopRunnable` "Application was force-stopped, rescheduling"; `ModelDownloadWorker` `ForegroundServiceStartNotAllowedException`), 2026-11-09; raw output not kept.
- **Means:**
  - `run_device_resume.py` reinstalls the sample with `adb install -g`.
  - `step_hub_resume` installs it with `-g` before the UI test.
  - An app on such a phone has to get its notification permission, or be in the foreground, before a background resume can run as a foreground service. Otherwise the OEM may freeze the download: the hub can't prevent that (D9), and the sample shows the request.
- **Feeds:** P3 T8 Step 4, D9, F91.

### F91. A review of P3 T1–T8 found the hub's state read on the caller's thread, five differences from upstream and a late job notification; all fixed except two listed deviations
- **Finding:** Three read-only reviews covered the T1–T8 work (committed through 91cab2a, plus the uncommitted T8 sample) against the plan and upstream b12052; each claim used below was checked against the code.
  - **The SDK's main-thread rule:** `progress()` and `download()` read the plan record and the cache tree on the caller's thread the first time a selection was seen, and the background sample calls `progress()` on the main thread.
  - **Differences from upstream `-hf`:**
    - A well-formed listing of the wrong shape escaped as `IllegalArgumentException`.
    - An empty online listing didn't fall back to the cache (download.cpp:795).
    - An `lfs` object without an oid fell back to the git oid; upstream uses `else if` (hf-cache.cpp:327). D40's hash would then fail on every attempt.
    - A missing `#file` still picked a DFlash draft (download.cpp:827).
    - `Accept-Ranges` was ignored on resume (download.cpp:370).
    - The offline ref read rejected a trailing newline and could pick a leftover `refs/*.tmp-*` file.
  - **Download:**
    - A discarded partial pushed `bytesDone` past `bytesTotal`.
    - A complete, verified partial (killed before the rename) was downloaded again in full.
    - A verified blob that was never linked didn't count after a restart.
    - An offline split model missing a part counted as complete.
    - A dangling snapshot link switched the process to degraded mode.
    - The degraded copy wasn't atomic.
    - HEAD had no retry.
    - An `Error` left every collector waiting.
    - `download()` turned the collector's own `HubException` into `Failed`.
  - **Fit:** each doubling of the header range downloaded again from byte 0 into a heap array of up to 256 MiB.
  - **Sample:**
    - The user-initiated job posted its first notification only after the first progress update; Android 14 requires one within a few seconds of the start.
    - Any non-Hub exception crashed the job.
    - `setForeground` from the background (a retry on Android 12+) failed the work.
    - One job id served every model and could collide with WorkManager's ids.
  - **Fixed, each with a test:**
    - The state now starts `Absent` and is read on `Dispatchers.IO`.
    - The resolver and downloader match upstream on every item above.
    - The header fetch asks only for the missing bytes and parses a mapped stub file.
    - The sample posts a "Preparing" notification at once and catches every error; WorkManager gets an id range (0–99,999), and user jobs get ids above it.
  - **Not changed, listed as deviations:**
    - A complete verified partial is renamed with no request, where upstream sends a Range GET that 416s and downloads again.
    - `delete(tag)` removes the tag's files from every snapshot, where upstream only touches the current ref's.
    - A file without a digest is skipped (D40).
  - **The parallelism default:** F89's one-beats-four result was measured through the Python fake server and one `adb reverse` tunnel, so it doesn't show what a CDN does. The default stays 1 until decided otherwise.
- **Evidence:**
  - Review reports, 2026-11-09.
  - The new tests:
    - `ResolverTest`: `wrongShapeJsonIsRetryableOrUsesCache`, `emptyOnlineListingFallsBackToCache`, `lfsWithoutOidDoesNotUseGitOid`, `missingNamedFileSelectsNoDraft`, `offlineRefReadsFirstLineAndSkipsTempFiles`.
    - `DownloadTest`: `discardedPartialNeverPushesProgressPastTotal`, `completeVerifiedPartialRenamedWithoutRequest`, `noAcceptRangesRestartsFromZero`, `danglingSnapshotLinkIsReplaced`.
    - `HubStateTest`: `verifiedUnlinkedBlobCountsAfterRestart`, `collectorExceptionLeavesStateAlone`, and `splitModelMissingAPartIsNotComplete`. The other state tests now wait on `awaitDiskState` before reading.
    - `GgufHeaderTest.headerGrowsUntilComplete` (incremental ranges).
  - The fake Hub's new `body` and `no_accept_ranges` faults, with `test_fault_body_and_missing_accept_ranges`.
- **Means:** The port is closer to `-hf` than T3 and T4 left it. The "Deviations from upstream" table in the P3 plan now lists the three deviations kept and the open parallelism default.
- **Feeds:** P3 T3–T8, D40, D42, F89, §11.2–§11.3.

### F90. With DFlash, ContextSize.Auto's projection is right: upstream keeps 15 recurrent-state copies for the target (+754 MiB), and a real host load lands within 4%; on the phone the reply matches upstream byte for byte
- **Finding:**
  - **Phone run, `DflashDeviceTest` on the OnePlus 13, NPU, `Fixed(8192)`, `Speculative.DFlash()`:**
    - The reply equals the reference reply of F73's DFlash run (upstream llama-server) byte for byte.
    - `draft_n` = 435 and decode = 34.6 t/s.
    - `MemAvailable` went from 6,333 to 1,473 MiB.
  - **`Auto` on the same phone, right after:** without the draft it chose 16,384. With the draft it refused, needing 6,060 MiB at 4,096 tokens with 3,984 MiB available, so the test failed at that step.
  - **The projection itself, on the host at 4,096 tokens:**
    - Without the draft: 3,805 MiB.
    - With the draft: 5,793 MiB. The target's context grows from 178 to 932 MiB, and the draft adds 1,234 MiB (643 weights, 96 KV, 495 compute).
  - **Where the target's growth comes from:**
    - For DFlash upstream sets the target's `n_rs_seq` to the draft's `n_max` (15; common/common.h:403, common.cpp:1755).
    - The recurrent buffer holds `mem_size × (1 + n_rs_seq)` rows (src/llama-memory-recurrent.cpp:102), so 50.25 MiB × 16.
    - With `n_rs_seq = 0` the projection drops back to 3,805 MiB, so forcing flash attention on costs nothing.
  - **The draft context matches upstream's:** the target's `n_ctx`, `n_rs_seq` 0, `ctx_other` the target (common/speculative.cpp:2720).
  - **Real loads on the host (`lb-run`, 4,096 tokens, max RSS):** 3,926 MiB without the draft, 5,576 MiB with it; the projection is 3.8% over.
  - **Upstream itself can't project this:** `llama-fit-params` rejects `-md`, and the server's `--fit` drops the draft when its measurement throws "requires ctx_other" (common/fit.cpp:307). Upstream therefore under-counts by the whole draft.
  - **The phone's drop of about 4.9 GB is not the load's footprint.** It was read before any decode, and it ended at 1.5 GB free, where the kernel reclaims and compresses other memory. F73 measured 6.3–6.4 GB with upstream after replies.
- **Evidence:**
  - `DflashDeviceTest`'s log (tag `DflashDevice`), 2026-11-09.
  - The host probe and its logs (`nodraft.log` and `draft.log`, a probe mirroring `lb_measure_ctx`), plus `lb-run` under `/usr/bin/time -l`, 2026-11-09; raw output not kept.
  - Upstream lines as cited.
- **Means:**
  - No SDK change: `Auto` was right to refuse on a phone with 3,984 MiB free.
  - T6 Step 4's reply check passes. Its `Auto` half needs about 6.1 GB of budget, which this phone with its usual apps doesn't have: 5,951–6,051 MiB in four runs. The test therefore accepts a refusal that agrees with `estimateFit`; one run's estimate said 6,111 MiB of budget and the load 160 MiB less, as F88 saw.
  - A load right after closing a 6 GB model sees memory still coming back: 1.4 GB just after, 8.1 GB within about 10 s. The device tests now wait for `MemAvailable` to settle (`settleMemory`) before each load.
  - The one lever on the cost is a smaller `draftMax`: each step of `n_max` costs 50 MiB on Qwen3.5 4B.
  - Review focus 6 holds: the projection is not below the measurement.
- **Feeds:** D41, P3 T6 Steps 4–5, F73, F66.

### F89. One download at a time beat four on the phone
- **Finding:** Four 400 MiB split files over the unthrottled local fake Hub took 6,881 and 6,618 ms with one transfer, versus 16,365 and 12,599 ms with four transfers, in alternating 1/4/4/1 order. The two ranges do not overlap; one transfer was about 2.1 times faster on average. Hashing a 2,735,111,724-byte partial during resume took 2,042 ms before new bytes arrived.
- **Evidence:** `HubMeasureTest.parallelismAndResumeVerification` on the OnePlus 13, Android log tag `LlamaBroHubMeasure`, 2026-11-09; 1,677,721,600 bytes per four-file trial.
- **Caveat (F91):** the server in this measurement is the single-process Python fake Hub reached over one `adb reverse` tunnel, so the result shows the test rig as much as the phone; it says nothing about a CDN.
- **Means:** The Hub defaults to one download at a time. The measurement test retains a four-transfer override so a future phone or CDN can be checked before changing that choice.
- **Feeds:** P3 T8, §11.2.

### F88. Header-only Hub fit matches the real GGUF and load on the phone
- **Finding:** The Hub fetched 24 MiB of Qwen3.5 header data in two bounded range requests (8 MiB, then 16 MiB). Its sparse-file estimate and an estimate from the complete model and projector both chose 16,384 tokens; an actual CPU load chose 16,384. The projected memory at 4,096 tokens was 3,989,831,680 bytes. The header estimate took 775 ms. The projector needed only a sparse file of its declared length. With the default RAM cache, a 159 MiB drop in the phone's memory budget between estimates crossed the 4,096-token threshold, so the device comparison used `ramCacheMib = 0` for all three calls.
- **Evidence:** `HubDeviceTest.estimateBeforeDownloadMatchesLoad`, the fake Hub's range log and Android log on the OnePlus 13, 2026-11-09; `fit_estimate_on_stub_equals_real` also passed for Qwen3.5, Gemma 3 and Llama 3.2 on the host.
- **Means:** D42 can expose llama.cpp's projection before weights are downloaded. Its context choice is sensitive to available memory at call time, so a later load can choose a different context when the budget changes.
- **Feeds:** D42, P3 T7, F85.

### F87. The fake Hub must consume POST bodies before reusing HTTP/1.1 connections
- **Finding:** The JVM transport tests received HTTP 501 (`Unsupported method ('{}GET')`) after `POST /_reset` because the fake server had not consumed its two-byte `{}` request body. Java reused the connection, so those bytes prefixed the next request line. Reading `Content-Length` bytes in `/_reset` made all eight transport tests pass.
- **Evidence:** `sdk/scripts/hub-fake/fake_hub.py`, `sdk/llamabro-hub/src/test/kotlin/com/suhel/llamabro/hub/HttpTransportTest.kt`, `:llamabro-hub:testDebugUnitTest`, 2026-11-08.
- **Means:** Keep the fake server compatible with persistent JVM clients; its tests alone use fresh requests and did not expose this failure.
- **Feeds:** P3 T2–T5.

### F86. Relative symlinks work in the sample's noBackupFilesDir and survive a cache-tree move
- **Finding:** On the OnePlus 13, an app-owned relative snapshot symlink read the blob before and after renaming the enclosing directory; both reads returned `hi`. The phone remained unlocked and no setting changed.
- **Evidence:** [`research/p3/header-fit/results.md`](../../research/p3/header-fit/results.md), 2026-11-08.
- **Means:** P3 can use upstream's relative-symlink cache layout in `noBackupFilesDir`; T4 still tests the degraded copy/move fallback.
- **Feeds:** D39, §11.2, P3 T1/T4.

### F85. Sparse GGUF headers match full main-model memory projections on host and phone
- **Finding:** For Qwen3.5 4B, Gemma 3 4B and Llama 3.2 3B, every real/stub device-memory line matched at contexts 4096 and 16384: six host pairs and twelve phone CPU/NPU pairs. Header lengths were 10,969,408, 6,541,184 and 7,837,984 bytes. After Android `truncate` restored sparse tails expanded by `adb push`, allocated bytes were 10,981,376, 6,545,408 and 7,847,936. The standalone projector and DFlash commands fail for both full files and stubs because those are not main models; paired checks (the sidecar verified with its main model) were adopted for T6–T7.
- **Evidence:** [`research/p3/header-fit/results.md`](../../research/p3/header-fit/results.md), 2026-11-08; the host and phone console outputs were not kept.
- **Means:** T7 uses llama.cpp's header-only `Projection` for main models. Sidecars are verified with their main model in T6–T7, not inferred from standalone fit results.
- **Feeds:** D42, §11.2, P3 T1/T6/T7, F84.

### F84. The model loader checks tensor offsets against the file's size only, and the fit's dry run allocates nothing: a header-only file padded to full size may be enough for a memory projection (To verify, P3 T1)
- **Finding:** `llama_model_loader` rejects a tensor only if `offs + ggml_nbytes(tensor)` passes `file->size()` (`src/llama-model-loader.h:45`), and upstream's memory-fit pass loads with `no_alloc` and no mmap (`src/llama-model-loader.cpp:1356`, `common_fit_measure`, which the bridge already calls: `bridge/src/config.cpp:285`). So a file holding only the GGUF header (metadata and tensor infos) at the start, extended to the real size as a sparse file, might give the same projection as the real file without reading any weights. Not run yet: whether anything in the dry run touches tensor data, and how big the header is (the tokenizer's vocabulary sits in it, so several MB for large vocabularies), are open.
- **Evidence:** source at b12052 as cited.
- **Means:** A pre-download "does it fit" check could fetch the header with HTTP `Range` and run llama.cpp's own projection (D33) instead of a guessed formula. P3 T1 tries it on the host and the phone before anything is built on it; the fallback is the file size plus the context cache computed from the header's metadata, reported as an estimate.
- **Feeds:** D42, P3 T1/T7, D33, PD (recommendations).

### F83. Upstream never checks a download's content: blobs are named by their SHA-256 but not hashed, and resume trusts the partial file; upstream ships its own resolution test with synthetic and real repo listings
- **Finding:**
  - **No hash.** `common/download.cpp` writes `blobs/<oid>.downloadInProgress`, renames it to `blobs/<oid>` when the GET ends and never computes a digest; with `skip_etag` (every `-hf` file) an existing blob is a cache hit (`common_download_file_single_online`, `cache_hit`). A partial file is resumed by size alone (`Range: bytes=<size>-`). The Hub's LFS `oid` is the file's SHA-256 (64 hex digits); non-LFS files carry the git blob SHA-1 (40 digits, `is_valid_oid` accepts both). GGUFs are LFS files.
  - **Tests to port.** `tests/test-model-resolution.cpp` (2892 lines) serves synthetic repos and recorded listings of real Hugging Face repos from a loopback server and checks `common_download_get_hf_plan`'s choice of primary, split parts and each sidecar, the partial paths, sidecar removal and shared-file removal. `app/download.cpp` is upstream's `llama download` command over the same code.
- **Evidence:** source at b12052 as cited.
- **Means:** P3's done-when ("resumes and verifies") goes beyond upstream: the hub hashes every LFS file before it enters the cache (D40). The port's file choice is checked against upstream's on upstream's own cases, run through upstream's code (a golden, like tools/ui's, F31).
- **Feeds:** D40, P3 T3/T4, §11.2, §12.

### F82. How `-hf` resolves, caches and resumes, step by step
- **Finding:**
  - **Reference.** `user/model[:tag]`; the tag is everything after the last `:` (`common_download_split_repo_tag`). Repo ids are checked (`is_valid_repo_id`: one `/`, `[A-Za-z0-9_]` with `.`, `-`, `/` only between them, at most 256 chars). There is no revision option: the commit is `main`'s from `GET {endpoint}api/models/{repo}/refs`, else the first valid branch, written to `refs/<branch>` (`get_repo_commit`). The endpoint is `MODEL_ENDPOINT`, else `HF_ENDPOINT`, else `https://huggingface.co/` (`common/common.cpp:1580`). A token is sent only if it looks like one (`hf_` + at least 34 alphanumerics, `is_valid_hf_token`).
  - **Files.** `GET api/models/{repo}/tree/{commit}?recursive=true`; each file's `oid` is `lfs.oid` if present, else the git `oid`; paths that escape the snapshot are skipped (`is_valid_subpath`). The URL is `{endpoint}{repo}/resolve/{commit}/{path}`. Upstream keeps no file size (`hf_file` has none).
  - **Choice** (`common_download_get_hf_plan`). A repo with `preset.ini` at its root downloads only that file (llama-server's router presets). Otherwise the primary is the first model GGUF matching `<tag>[.-]` (case-insensitive), else with no tag `Q4_K_M`, then `Q8_0`, then the first model GGUF; a model GGUF is one without `imatrix` or a sidecar keyword in its name, and for split files only part 1 counts. All parts of a split primary come with it (`-NNNNN-of-NNNNN`). Sidecar kinds and keywords: `mmproj`, `mtp-`, `dflash-`, `eagle3-`, `dspark-`; each is the best sibling (`find_best_sibling`: deepest shared directory, then an exact `-TAG.` match, then the closest quant bits). Drafts resolve at the tag even with no full model of that tag; the mmproj pairs with the model.
  - **What `llama-server -hf` fetches** (`common_models_handler_init`, `common/arg.cpp:337`): the mmproj unless `--no-mmproj` or an explicit `--mmproj`; a draft head only when `--spec-type` names it; a separate draft repo (`-hfd`) resolves every draft kind and the type is inferred from what it ships (MTP, then DSpark, DFlash, EAGLE-3).
  - **Cache.** `models--{org}--{repo}/blobs/{oid}`, `snapshots/{commit}/{path}` as a relative symlink to the blob, `refs/{branch}`; if a symlink fails once, it moves (or copies) the blob into the snapshot for the rest of the process (`finalize_file`). Locks are `.locks/models--{org}--{repo}/{oid}.lock`, shared with the `hf` CLI. Offline (`--offline`, or the API failing) resolves from `refs` and the snapshot tree.
  - **Transfer** (`common_download_file_single_online`). Under the lock: HEAD (redirects followed by cpp-httplib) for `Content-Length` and `Accept-Ranges`; GET with `Range: bytes=<partial size>-` when resuming; a resume answered 416 or 200 deletes the partial; up to 3 attempts, 2 s then 4 s apart; cancelled downloads keep the partial. Every file of a plan downloads at once, one thread each (`common_download_run_tasks`). Progress is reported every 0.1% of the file.
  - **Removal** (`common_download_remove`): a tag removes its snapshot entries except files another cached tag still loads, then blobs nothing references.
- **Evidence:** `common/hf-cache.cpp`, `common/download.cpp`, `common/arg.cpp:320-560`, `common/common.cpp:1580` at b12052.
- **Means:** The port's behaviour list for P3 T3–T5, with these deliberate differences: no `preset.ini` (the SDK has no router; such a repo resolves its GGUFs as if the file weren't there, and the plan says so), sizes kept from the tree (`size`, `lfs.size`) for progress and the free-space check, the token sent only to the endpoint's host and never across a redirect (§11.4), a SHA-256 check (D40), and the sidecar kinds the SDK can use (mmproj, DFlash; MTP heads live inside the GGUFs measured so far, F67; EAGLE-3 and DSpark out by D36).
- **Feeds:** D39–D41, P3 T3–T5, §11.2.

### F81. Upstream's downloader can't run inside the SDK: llama.cpp is built without OpenSSL, so its HTTP client has no HTTPS
- **Finding:** `sdk/native/CMakeLists.txt:33` forces `LLAMA_OPENSSL OFF`, and `common/http.h`'s client is cpp-httplib, which needs OpenSSL for `https://`. Hugging Face is HTTPS only. So calling `common_download_get_hf_plan` or `common_models_handler_apply` through the C API would fail on the phone; making it work means building and shipping OpenSSL in the core AAR (and keeping a TLS stack patched), for code that is a few hundred lines of HTTP and file handling.
- **Evidence:** the CMake line; `common/http.h` (`common_http_client`), at b12052.
- **Means:** The hub is a Kotlin port of upstream's logic over Android's own TLS (`HttpURLConnection`, D15), checked against upstream by golden tests (D39).
- **Feeds:** D15, D39, P3 T2–T4, §11.4.

## Feature spike (PF)

### F74. Text to speech (Qwen3-TTS 1.7B) works on the CPU and on the NPU with its codec on the CPU, intelligible but slower than real time; the NPU codec aborts, the GPU runs on; the SDK can't return audio bytes
- **Finding:**
  - **Speed** (upstream llama-server, `ggml-org/Qwen3-TTS-12Hz-1.7B-Base-GGUF` Q4_K_M + mmproj Q8_0, a reference voice, one round). CPU: real-time factor 0.20–0.52 (8 s of speech in 18–40 s, 22 s in 44–48 s), first streamed audio after 2.4 s. NPU with the codec on the CPU (`--no-mmproj-offload`): RTF 0.37–0.59, first streamed audio after **0.3–0.5 s**. No chip reaches real time: streamed speech would stall about every second.
  - **Failures.** With the codec on the NPU the server aborts on the first request (`ggml-hexagon.cpp:1734: dspqueue_read failed`, in `clip_encode` from `mtmd_gen_audio_process`). On the GPU two sentences took 19 minutes and produced 41 s of audio instead of 8 (generation ran past the text; stopped by hand).
  - **Quality.** A listening check of the CPU's output found it "perfectly clear, although not very human like" (the reference was a synthetic macOS voice, which Qwen3-TTS clones).
  - **Memory.** 2.3–2.7 GB on the CPU and NPU, load 3 s.
  - **API.** llama-server takes every TTS setting per request (`input`, `lang`, `voice`, `speaker_ref_b64`, `response_format` wav/pcm, `stream`, sampling, `n_predict`); the `--tts-*` flags belong to the `llama-tts` tool only. No load keys are needed. However, the SDK's `Model.rawRequest` hands replies over as UTF-8 strings (`Model.kt:216`), which destroys WAV bytes (an equivalent round trip turns 376,364 bytes into 677,981 with 154,247 replacement characters; checked on the Mac, not through the SDK on the phone). The SDK also puts the mmproj on the model's device (F65), which on the NPU is the device that aborts.
- **Evidence:** [`research/pf/tts/results.md`](../../research/pf/tts/results.md) (script `tts.sh`, `utf8-check.py`), 2026-11-06; `tools/tts/README.md`, `tools/server/server-schema.cpp` `make_tts_schema`, `tools/server/server-context.cpp` `post_tts`, `common/arg.cpp` (`--tts-*` are `LLAMA_EXAMPLE_TTS`), at b12052.
- **Means:** Not usable for live speech on this phone (below real time everywhere), and not reachable through the SDK at all until requests can return bytes. Usable for generating audio ahead of time on the CPU, or on the NPU with the codec on the CPU. Supporting it needs a byte-returning request (e.g. `rawRequestBytes` returning `Flow<ByteArray>`, also useful for any binary endpoint) and a way to keep the mmproj on the CPU (a `ModelConfig` option mapping to `--no-mmproj-offload`). Both are small, in P4. **Decided (2026-11-06): D37**, out of 1.0.
- **Feeds:** PF T4, P4 Breadth (binary replies, typed TTS), F65 (mmproj device), F69.

### F73. Draft models for Qwen3.5 4B: DFlash decodes JSON 2.3× faster than off and 1.5× faster than MTP on the NPU, but loses on prose and the CPU and needs about 2.9 GB more memory; EAGLE-3 and DSpark lose everywhere
- **Finding:**
  - **Sidecars exist for Qwen3.5 4B**, so no Qwen3 stand-in was needed: DFlash from its authors (`z-lab/Qwen3.5-4B-DFlash`), EAGLE-3 and DSpark only from the community (`yuyijiong/…-Eagle3`, `shanjiaz/…-DSpark-b16-…`). Upstream's converter handles all three with only the target's config and tokenizer (no target weights); Q8_0 GGUFs are 685 / 259 / 696 MB. All three load on the NPU and CPU and draft on Qwen3.5's hybrid graph.
  - **Speed** (upstream llama-server, the SDK's settings, upstream's example flags, 256-token replies; NPU 2 rounds, the second reversed, CPU 1). On the NPU, JSON decodes at **32.6–36.0 t/s with DFlash** against 21.9–22.3 with MTP(4) and 14.6–14.9 off; prose loses (10.0–11.6 vs 14.9–15.2 off, MTP 11.7–13.5). EAGLE-3 is slower than off for every output (9.6–11.8 t/s) and cuts NPU prefill from 885 to 222 t/s; DSpark is slower everywhere (6.2–9.4). On the CPU every drafter is slower than off (DFlash JSON 8.2 vs 10.0; prose about 4× slower).
  - **Cost.** DFlash takes about 2.9 GB more memory than off on the NPU (MemAvailable drop 6.3–6.4 GB vs 3.3–3.5) and 1.5 GB more on the CPU, more than its 685 MB file: room that `ContextSize.Auto` (F66) would otherwise give to the context. Load is 1–2 s longer.
  - **Output.** Every drafter, MTP included, drifts from greedy off at the same two places on the NPU, and DFlash's replies are byte-identical to MTP's (and to its own second round): the drift comes from batched verification, not from the draft. No arm crashed. The GPU wasn't measured (F71).
- **Evidence:** [`research/pf/draft-models/results.md`](../../research/pf/draft-models/results.md) (scripts `convert.sh`, `dm.sh`), 2026-11-06; `docs/speculative.md` §EAGLE-3/DFlash/DSpark, `conversion/qwen.py` (DFlash/DSpark take the target's config and tokenizer; target weights only for a `mask_embedding.pt`), at b12052.
- **Means:** DFlash passes the plan's bar for structured output on the NPU (beats off and MTP beyond noise, two rounds) and fails it for prose and on the CPU, like MTP (F68) but more strongly. It fits only as an opt-in for JSON or tool-call apps on the NPU that can spare about 3 GB, never in `Speculative.Auto`. Shipping it needs the sidecar as a second download (P3 Hub: a `ModelSource` that names a draft file next to the model, with its checksum), a bridge `speculative` type `dflash` with `-md`, `--spec-draft-n-max 15` and `-fa on` (`config.cpp:175` takes `none`/`mtp` today), and the memory in `ContextSize.Auto`'s budget. EAGLE-3 and DSpark are left out with these checkpoints; a better-trained one could be measured with the same script. **Decided (2026-11-06): D36**, an opt-in once P3 can download a sidecar; EAGLE-3 and DSpark out.
- **Feeds:** PF T3, P3 Hub (sidecar download), P2 (`Speculative`, `config.cpp`, `ContextSize.Auto`), F66, F68, F72.

### F72. n-gram speculation (`ngram-mod`, upstream's `--spec-default`): code edits decode 7× faster on the NPU and 2.3× on the CPU with identical replies, prose untouched; partly matching output wins on the NPU but loses on the CPU
- **Finding:**
  - **How it drafts.** The n-gram types look up the recent tokens in earlier text and propose what followed, with no extra model. Several types can be listed; `common/speculative.cpp` tries them in a fixed priority (n-gram types first, then draft models, MTP last) and uses the first that has a draft, so `ngram-mod,draft-mtp` drafts from n-grams when the context repeats and from MTP otherwise. ngram-mod's pool is shared across requests and slots, so a reply can draft from an earlier one.
  - **Speed** (Qwen3.5 4B Q4_K_M, upstream llama-server, the SDK's settings, 256-token replies, one round per chip, each arm at thermal ≤ 2). Code edit (a 146-line file returned, nearly all copying): NPU 14.6 → **105 t/s** with ngram-mod and **138** with ngram-mod + MTP; CPU 10.5 → **23.8**; every draft token accepted. Quoting three sentences: NPU 14.4 → 20.3 (mod), 29.9 (mod + MTP), CPU 10.2 → 9.2. JSON at the agent preset: NPU 14.5 → 21.1, CPU 9.7 → 8.2 (both helped by the pool holding the JSON greedy reply from the preceding request). Prose: no draft is ever made, speed within noise (NPU 14.4–14.6 vs 15.0, CPU 10.0–11.9 vs 10.5).
  - **On the CPU, partial matches cost.** Drafts of up to 64 tokens that are only partly accepted make JSON and quoting 10–15% slower, as verifying a long draft on the CPU costs more than it saves. On the NPU the same drafts pay off.
  - **ngram-map-k4v** (upstream's example settings) drafted rarely and gained little (NPU JSON 15.0–15.7 vs 14.5–15.0) and nothing on code: weaker than ngram-mod everywhere.
  - **Output.** Every greedy reply under ngram-mod and k4v is byte-identical to off, on both chips. MTP's batched verification makes greedy prose and JSON drift slightly (as with MTP alone); n-grams do not. No arm crashed. The GPU wasn't measured (F71).
- **Evidence:** [`research/pf/ngram/results.md`](../../research/pf/ngram/results.md) (script `ng.sh`, prompt `code.cpp`), 2026-11-06; `docs/speculative.md` §n-gram and `--spec-type`; `common/speculative.cpp` (the priority list and the draft loop); `common/arg.cpp` (`--spec-default`), at b12052.
- **Means:** ngram-mod is the first speculation that helps without hurting prose, but it loses on the CPU for output that only partly repeats, so it can't join `Speculative.Auto` as "never loses". The fit is an opt-in for apps whose replies copy their input (code edits, rewrites, quoting, repeated structured output): a `Speculative.Ngram` with upstream's `--spec-default` values, which on the NPU combines with `Speculative.Mtp(n)` (mod + MTP was the fastest arm for code, quoting and JSON). It is a load setting (F68): the bridge's `speculative` key takes `none`/`mtp` today (`config.cpp:175`), so it needs a new type there and in Kotlin. **Decided (2026-11-06): D35**, an opt-in `Speculative.Ngram`, built in P2.
- **Shipped (2026-11-07):** `Speculative.Ngram(then: Mtp? = null)`; bridge `speculative` type `ngram` with an optional `mtp_n_max` (`--spec-type ngram-mod[,draft-mtp]`, n-match 24, n-min 48, n-max 64). Host tests `ngram_config_maps_to_ngram_mod`, `ngram_drafts_and_keeps_the_reply` (drafts, greedy reply identical); JVM `ModelConfigTest.ngramSentAndReported`; device `NgramDeviceTest` on the NPU through the SDK, a 12-line copy prompt: off 15.4 t/s, `Ngram()` 43.8 t/s with an identical reply, `Ngram(then = Mtp(4))` 46.1 t/s. `check.sh --device` all pass.
- **Feeds:** PF T2, P2 (`Speculative`, `config.cpp`), F67, F68, D10.

### F70. Backend sampling never beats off: 0.5–3% slower decode on the NPU, noise on the CPU and GPU; the same replies
- **Finding:**
  - **Where it runs.** On the NPU every sampler of the default chain is offloaded (`-lv 4` marks each with `+`), with no fallback. On OpenCL `top-p` falls back to the CPU on every request ("device 'GPUOpenCL' does not have support for op ARGSORT"); the rest runs on the GPU. On the CPU the chain runs in the graph on the CPU, so nothing moves.
  - **Speed** (Qwen3.5 4B Q4_K_M, upstream llama-server, the SDK's settings, ~2K-token prompt, 256-token replies, `backend_sampling` per request, arms alternating; NPU 3 rounds, GPU and CPU 1). On the NPU decode is lower with it on for all four output kinds, by 0.5% (prose greedy, 14.86 vs 14.93 t/s) to 3% (JSON agent preset, 13.87 vs 14.29), and never higher. On the CPU and GPU the differences go both ways, within run-to-run noise (CPU 8.3–10.3 vs 8.8–10.8 t/s). Prefill follows the request's place in the round, not the setting.
  - **Output.** On the NPU 12 of 12 replies are byte-identical with it on and off, sampled ones too (same seed), and on the CPU 4 of 4. On the GPU they differ, but both arms are garbage (F71).
- **Evidence:** [`research/pf/backend-sampling/results.md`](../../research/pf/backend-sampling/results.md) (script `bs.sh`), 2026-11-06; `src/llama-sampler.cpp` `llama_sampler_backend_support` (the per-op check and its warning), `src/llama-context.cpp` `set_sampler`, `tools/server/server-context.cpp:2067-2076` (skipped with pre-sampling `n_probs` and for TTS), all at b12052.
- **Means:** No gain on any chip, so upstream's default stands: `Sampling.backendSampling` stays a per-request field that defaults to off, and the SDK sets no other default. Nothing to ship. If a later llama.cpp adds sampler kernels for Hexagon or OpenCL that change this, the same script measures it again.
- **Feeds:** PF T1, P2 (no default change), F69.

## Backends (P2 execution)

### F80. Any drafting aborts the GPU backend, F75's switch on or not; the P2 review's findings and what changed
- **Finding:**
  - **Drafting on OpenCL.** With `GGML_OPENCL_COOPK_DP4A=0` (F75) MTP still aborts ggml-opencl on the first drafted batch (`ggml-opencl.cpp:1319`, error -54, F67's failure), and so does n-gram drafting (`--spec-default`), which F72 never ran on the GPU. A process abort can't be caught, and `DevicePolicy.Auto` and `Prefer` could place an app's `Speculative.Mtp` or `Ngram` load on the GPU (after an NPU failure, e.g. `noskel`, F78). **Fixed:** with drafting set, the GPU is skipped with that reason in `loadReport`, and `Only(Gpu)` throws `DeviceUnavailable` (`draftingNeverLoadsOnTheGpu`).
  - **The review** (a read-only pass over P2's `sdk/` diff, `33412d4..9084d53`) found the core sound (skeleton install, discovery, device resolution, the fit, config parsing; rules 2, 3, 5 hold) and asked for changes. Fixed with it:
    - `ContextSize.Fixed(0)` reached `--ctx-size 0`, upstream's "the training context" (262 144 tokens for Qwen3.5, reserved at load): `Fixed` and `Speculative.Mtp.draftMax` now require ≥ 1.
    - `check.sh`'s device step passed when a flavor never ran (a stale result file stood in); it now clears the results, requires each flavor's, and fails a gradle failure that no tolerated test failure explains.
    - A C++ exception from a backend's init would have escaped `lb_runtime_init` (`std::terminate`): caught per backend. An installer returning a blank file or a bad env name would have failed the whole runtime, CPU included: discovery now fails that backend only.
    - With `parallelSlots > 1` the fit treated the context cap as per slot and the no-budget path as total: per slot in both, as in upstream's fit; the reason text compares the right numbers.
    - LoRA adapter files are subtracted from `ContextSize.Auto`'s budget, as the mmproj's is.
    - `Speculative.Auto`'s dead path (the native `mtp_if_available` type and its GGUF probe, `MtpPolicy.faster`, the `toJson` kind parameter) is gone: Auto sends no speculative setting.
    - `LoraAdapter`, `Speculative.Mtp` and `Ngram` are plain classes instead of data classes, so settings can be added later (D26; decided 2026-11-07).
    - Scripts: the device contract's cleanup trap is set before the server starts, its first health checks don't mistake a server still starting for a dead one, `find | head -1` under `pipefail` became `find -print -quit`, and `check-aar.sh` requires the five skeletons. Stale KDoc and comments fixed.
  - **Left as is:** `InsufficientMemory` stops the device fallback (every device shares one RAM; the projections differ by up to about 260 MiB, F66), recorded in D33. The skeleton install's cross-process `FileLock` has no test (the in-process lock has). A cross-check of `loadReport.deviceName` against native placement would compare the configured list with itself; the device contract's `HTP0 model buffer size` check is the real one (F76).
- **Evidence:** [`research/p2/gpu-drafting/`](../../research/p2/gpu-drafting/) (both aborts on upstream llama-server, 2026-11-07); the JVM tests `draftingNeverLoadsOnTheGpu`, `invalidSizesAreRejected`, `badEntriesAreReportedWithReasons`, `speculativeAutoIsOffOnEveryChip` (now checks no key is sent); host test `auto_ctx_cap_is_per_slot`; `check.sh --device`.
- **Means:** Speculative decoding is an NPU and CPU feature until ggml-opencl's kernel is fixed; retry with each llama.cpp upgrade (the same check as F75's switch).
- **Feeds:** F67, F72, F75, F78, D26, D33, D35, P2 T11.

### F79. Sizes at the end of P2: the core AAR has 20.5 MB of native code, the NPU AAR adds 5.1 MB and the GPU AAR 3.9 MB; a sample APK with all three is 30.2 MB
- **Finding** (release builds, stripped, arm64-v8a, 2026-11-07, after D38 dropped `libomp.so`):

  | Artifact | File | Native code inside | Biggest parts |
  |---|---|---|---|
  | `llamabro` | 8.44 MB | 20.52 MB | `llama-common` 4.58, `llama` 3.85, 7 CPU variants 0.94–0.99 each (6.83), `mtmd` 1.62, `libllamabro` 1.44, `c++_shared` 1.37, `ggml-base` 0.77 |
  | `llamabro-hexagon` | 2.97 MB | 5.06 MB | 5 HTP skeletons (v73–v85) 0.90–0.94 each as assets, `ggml-hexagon` 0.45 |
  | `llamabro-opencl` | 0.94 MB | 3.93 MB | `ggml-opencl` 3.93 (Adreno kernels embedded) |
  | `llamabro-hub` (added in P3 T9, 2026-11-09) | 0.22 MB | none | `classes.jar` (pure Kotlin, no new runtime dependency); no `jni/` |

  Sample app, minified release APK (`samples/minimal`, F78): `cpu` 21.1 MB, `gpu` 25.1 MB, `full` 30.2 MB. The APK stores the `.so` files uncompressed, as AGP does by default, so the APK is about the native code's size, while the AARs are compressed.
  Against F25 (P1, 21.6 MB native in the core): 1.1 MB less, mostly `libomp.so` (0.97 MB, D38).
- **Evidence:** `unzip -l` of `sdk/llamabro*/build/outputs/aar/*-release.aar` and `sdk/samples/minimal/build/outputs/apk/*/release/*.apk` from a passing `check.sh --device` run, 2026-11-07.
- **Means:** P5's size budget starts here. The biggest levers, none taken yet: one CPU variant instead of seven (about 5.9 MB, at a speed cost on newer cores), only the skeleton for the phone's chip (Play feature delivery or per-device splits, about 3.7 MB), and upstream's own library sizes.
- **Feeds:** F25, D38, P5 (size budget).

### F78. P2 acceptance: each sample flavor gets the device its artifacts allow, a broken NPU skeleton falls back to the GPU with the reason, and every flavor's minified build loads
- **Finding:**
  - **Flavors** (`samples/minimal`, one per set of backend artifacts, same `applicationId`): `cpu` (core only), `gpu` (+ `llamabro-opencl`), `full` (+ `llamabro-hexagon`), and `noskel`, `full` with an empty `libggml-htp-v79.so` in the app's own assets (which win over the library's). `DevicePolicy.Auto` picks `Cpu`, `Gpu`, `Npu` with no attempts, and on `noskel` `Gpu` after one attempt: `Npu`, `hexagon didn't load: installer … failed: …MismatchException: libggml-htp-v79.so in the APK has SHA-256 e3b0c442…, expected b…`. Chat streams and stop is fast on each.
  - **Per device** (Qwen3.5 4B unless named; the replies are 6–8 tokens from 18–42-token prompts, so prefill and decode here are a smoke check, not a measurement: F63, F65, F67 have those):

    | Flavor, device | Load | Prefill t/s | Decode t/s | Cancel → flow end | Next reply after cancel |
    |---|---|---|---|---|---|
    | full, NPU | 6.3 s | 80.6 | 14.3 | 2 ms | 3.4 s |
    | full, NPU, Gemma 3 4B | 5.4 s | 62.2 | 15.8 | 2 ms | 22.3 s |
    | full, NPU, Llama 3.2 3B | 4.7 s | 650.3 | 23.7 | 3 ms | 2.5 s |
    | cpu, CPU | 14.3 s | 21.2 | 8.5 | 4 ms | 102.1 s |
    | gpu, GPU | 24.2 s | 23.4 | 9.5 | 2 ms | 34.4 s |
    | noskel, GPU | 23.1 s | 23.8 | 10.0 | 2 ms | 34.3 s |

  - **The reply after a stop re-reads the stopped turn.** `stopIsFast` cancels a ~3,000-token prompt 200 ms in, then sends "Say OK." The conversation keeps the stopped user message, as `tools/ui` does, so that reply first reads the whole prompt: 102 s on the CPU, 34 s on the GPU. Expected, not a bug; an app that wants it gone uses `regenerate()` or edits the history.
  - **The forced failure first passed by accident, then failed for a reason in the test.** All flavors share the app's files, and a valid skeleton that `full` installed earlier still matched the checksums in `noskel`'s APK (only the `.so` is overridden), so the installer, rightly, kept it and the NPU loaded. The test now deletes `noBackupFilesDir/llamabro/htp` before the runtime starts on `noskel`, as a fresh install would be.
  - **Minified release builds** (R8, F28) of `cpu`, `gpu` and `full` each load a model on the phone.
- **Evidence:** the acceptance log of the passing `check.sh --device` run (2026-11-07, OnePlus 13), `samples/minimal/build.gradle.kts`, `AcceptanceTest` (`autoPicksTheFlavorsDevice`, `freshSkeletonInstall`), `check.sh` `step_device`.
- **Means:** P2's done-when (design §13 as amended by D23, D29, D31) is met on this phone: one AAR per backend, each making its device available alone, `Auto` choosing the best shipped one and reporting what it skipped and why. `check.sh --device` runs every flavor.
- **Feeds:** P2 T10, T11, D29, D31, D32, F28, F61.

### F77. The SDK's CPU path uses OpenMP and the phone's upstream baseline doesn't; same median speed, but OpenMP had the only two slow runs
- **Finding:**
  - **The difference.** The SDK's `CMakeLists.txt` leaves `GGML_OPENMP` at llama.cpp's default (on), so `libggml-base.so` needs `libomp.so` (shipped in the AAR, F25). Upstream's Snapdragon preset (`docs/backend/snapdragon/CMakeUserPresets.json`) sets it off, and that preset built every `llama-server` the phone spikes compared against (`/data/local/tmp/lb-spike/P`, no `libomp.so`). The difference surfaced when the device contract's first build lacked `libomp.so` (F76). OpenMP only decides how ggml spreads CPU work over threads, not what it computes.
  - **Measured.** Two builds from the same tree differing only in `-DGGML_OPENMP` (`build-device-tools.sh`), Qwen3.5 4B on the CPU, a ~2.2K-token prompt and 128 greedy tokens, a fresh load per arm, arms alternating, thermal status ≤ 2 before each, 6 rounds: prefill median 41.7 t/s on (range 26.9–49.0) vs 40.7 off (32.5–43.6); decode 10.6 (6.1–11.4) vs 10.4 (9.2–11.3). The medians are within noise. The only two slow runs (decode 6.1 and 6.9 t/s, one with prefill 26.9) were both with OpenMP on; off never went below 9.2.
- **Evidence:** [`research/p2/openmp/bench.sh`](../../research/p2/openmp/bench.sh), 2026-11-07, OnePlus 13; `llvm-readelf -d libggml-base.so` (needs `libomp.so` only in the default build).
- **Means:** CPU speed comparisons between the SDK and the shell (F67, F72) hold at the median. Turning OpenMP off matches upstream's Android preset and the measured baseline and drops `libomp.so` (0.97 MB, F25) from the AAR; two outliers in six runs are not enough on their own to prove that OpenMP causes slow runs. **Decided (2026-11-07): off (D38).**
- **Feeds:** F25, F67, F72, P2 T10/T11 (AAR size), P5 (size budget).

### F76. The contract on the NPU: 75/75 identical after one fix; on Android the SDK answered 500 for a named `tool_choice` and for malformed JSON, because `libllamabro.so` hid the type info that upstream's catches need
- **Finding:**
  - **The contract runs on the phone** (P2 T9). Both `llama-server` and `lb-run` are built in the Snapdragon image from the same tree and `CMakeLists.txt` (`build-device-tools.sh`), run from `/data/local/tmp`, and get the host contract's 77 requests with Qwen3.5 4B, its mmproj and `-dev HTP0 -ngl 99`. Both logs show `offloaded 34/34 layers` and `HTP0 model buffer size = 2856.64 MiB`, so neither fell back to the CPU, and the replies read correctly ("Tokyo"; the four inner planets; `{"name": "Bob", "age": 30}`), so the match isn't two equal garbage answers (F71). With the fix below: **75/75 identical** (2 skipped by design, as on the host).
  - **Before the fix, 72/75.** Three requests differed, all in error handling, none in the NPU's output:
    - `x-12-tool-choice-named` and `x-36-error-bad-tool-choice` (`tool_choice` given as an object): upstream answers; the SDK returned 500 `type must be string, but is object`. **Any app that names the tool the model must call got an error on Android.**
    - `x-31-error-bad-json`: upstream returns 400 `invalid_request_error`, the SDK 500 `server_error`.
  - **Cause.** On Android `libllamabro.so` exported only `lb_*` and `JNI_OnLoad` (`exports.map`, F25), so its copy of `common_json_error`'s type info was local. The error is thrown in `libllama-common.so`, which exports its own copy (`nm`: `V _ZTI17common_json_error` there, `d` in `libllamabro.so`). Android's libc++abi matches a `catch` by type info address. So upstream's own catches compiled into `libllamabro.so` (server-context is linked in statically) missed it: `json_value`'s `catch (const common_json_error &)` (`server-common.h`), whose fallback reads an object `tool_choice`, and the ex_wrapper's 400 branch (`bridge/src/upstream.cpp`, copied from `server.cpp`). Both fell through to `catch (const std::exception &)`, i.e. 500. macOS compares type info by name, so the host contract never saw it; `llama-server` links the same libraries with everything exported, so it doesn't either.
  - **Fix.** `exports.map` also exports type info (`_ZTI*`, `_ZTS*`), no code. The dynamic linker then gives both libraries one copy, as for `llama-server`. `check-aar.sh` allows those symbols and fails if `_ZTI17common_json_error` isn't exported.
- **Evidence:** the device-contract runs on the OnePlus 13, 2026-11-07 (72/75 with the three diffs before the fix; 75/75 with the device lines and replies after); `sdk/native/tests/device-contract/run.sh`, `sdk/native/scripts/build-device-tools.sh`, `sdk/native/bridge/exports.map`; `llvm-nm -D` on both libraries.
- **Means:** The device contract is `check.sh --device`'s `device-contract` step. It checks the NPU only, as the plan says. Any exception type caught across a library boundary is covered by the exported type info; a test that only runs on the host can't catch this class of bug, so the device contract is what guards it.
- **Feeds:** P2 T9, F25, D21, design §12, P5 (CI on a phone).

### F75. F71's cause: ggml-opencl's cooperative-K DP4A Q4_K kernel is wrong on the Adreno 830 for matmuls of 2–4 rows, which llama-server sends for every model with checkpoints (Qwen3.5, Gemma 3) and for speculative verification; `GGML_OPENCL_COOPK_DP4A=0` fixes it
- **Finding:**
  - **The kernel.** `kernel_gemm_coopk_q4_k_q8_1_dp4a` (ggml-org/llama.cpp#30741), which ggml-opencl runs only when a matmul has 2 to 4 activation rows (`ggml_cl_coopk_dp4a_narrow_on`, on Adreno A8X/X2E unless the compiler is E17), gives garbage on this phone (Adreno 830, driver `0800.77`, compiler `E031.47.18.56`). `test-backend-ops` with Qwen3.5's and Llama's matmul shapes: 210/240 pass; the 30 failures are exactly the Q4_K cases with n = 2, 3, 4, Llama's shapes too (NMSE 0.63–0.98). n = 1, 8, 19, 64, 512 pass, and every Q5_K and Q6_K case passes. With `GGML_OPENCL_COOPK_DP4A=0`: 240/240. Turning off only the next kernels in line (`GGML_OPENCL_COOPK_Q4_K_BIN=0`, `GGML_OPENCL_Q4_K_BIN_DP4A=0`) doesn't help.
  - **Why only some models.** llama-server sends the last 4 prompt tokens as their own batch whenever it makes context checkpoints, i.e. for models with recurrent state or SWA (`server-context.cpp`, offsets `{4 + n_ubatch, 4}`). So for Qwen3.5 and Gemma 3 every Q4_K matmul of that batch is garbage, and the first token already is. Llama 3.2 (attention only) never sends such a batch for a plain prompt. Speculative decoding verifies 2–4 tokens per step, so it would hit the kernel on any Q4_K model.
  - **With the switch, on the full model.** Qwen3.5 on OpenCL answers "The sea is a vast, ever-shifting expanse of blue…", and its 2K-token reply is word for word the CPU's. Gemma 3 4B goes from an empty reply to a correct one. Llama is unchanged.
  - **Ruled out.** The delta-net patch (unpatched b12052 gives the same `8888…`); the gated delta net, SSM conv and every other Qwen3.5-only op (all moved to the CPU: still `8888…`); fusion; the tiled GEMVs; the prefill GEMM; fp16 overflow of the Q8_1 block sums (largest block sum 149 for Qwen3.5, 630 for Llama).
  - **The SDK's own OpenCL AAR** does the same: `Only(Gpu)` answers `"8888888888888888"`, and `DeviceChoiceDeviceTest#onlyGpuLoadsAndIsDeterministic` passed because it compares two replies, as F71 noted. (Now `onlyGpuLoadsAnswersRightAndIsDeterministic`, which also needs "Paris" from "The capital of France is": `"88888888"` without the fix.)
  - **Upstream:** no later commit touches the coopk code (to 52c9bad94, 2026-11-06), and no issue was found.
- **Evidence:** [`research/pf/backend-sampling/opencl-check/bisect/results.md`](../../research/pf/backend-sampling/opencl-check/bisect/results.md): six bisection rounds on upstream llama-server (`ob.sh`, `round1.sh`–`round6.sh`), the `test-backend-ops` cases (`tbo-cases.cpp.txt`), the activation statistics (`actstat/`), the switch on Qwen3.5, Gemma 3 and Llama, 2026-11-06; `ggml-opencl.cpp` `ggml_cl_coopk_dp4a_enabled`, `ggml_cl_coopk_dp4a_narrow_on`, at b12052.
- **Means:** OpenCL is unusable for Qwen3.5 and Gemma 3 (and for speculative decoding on any Q4_K model) until the kernel is avoided. **Decided (2026-11-06): option 1.** `OpenClInstaller` sets the switch; the device test asks for "Paris" on the GPU and the CPU. The options were:
  1. The SDK sets `GGML_OPENCL_COOPK_DP4A=0` before it loads the OpenCL backend. This uses upstream's own switch with no llama.cpp patch, and the kernels that take over pass every case. It only costs speed on 2–4-row batches (the checkpoint tail, speculative verification); not measured. Plus an upstream issue with the `test-backend-ops` repro, and the switch dropped once a fixed pin passes it.
  2. A patch in `sdk/native/patches/` that turns the coopk path off on Adreno compiler E031 (like upstream's E17 rule). This is narrower, but a patch to carry, and still needs the upstream issue.
  3. Keep OpenCL away from these models in `DevicePolicy.Auto` (D32). Too coarse: the bug is in a Q4_K kernel, not in the model, so it would also miss Llama with speculative decoding.

  Either way, the GPU device test should compare the reply's text with the CPU's (for a model where they agree) instead of with itself.
- **Feeds:** F71, D32, P2 (T6 `Auto`, T9 contract, device tests), F63, F65, F67 (MTP on OpenCL), PF T2–T3 (GPU arms skipped because of F71).

### F71. OpenCL gives garbage for Qwen3.5 4B (`8888…` for every prompt) while Llama 3.2 3B is correct: F63 and F65 checked only that the GPU's replies repeat
- **Finding:** With upstream llama-server (`/data/local/tmp/lb-spike/P`, b12052 + the delta-net patch), `-dev GPUOpenCL -ngl 99` and the SDK's load settings, Qwen3.5 4B Q4_K_M answers greedy prompts with `8888888…`, for a 19-token prompt and a 2K-token one alike; with backend sampling on, `otareotare…`. The same server on the CPU answers correctly, and Llama 3.2 3B Q4_K_M answers correctly on OpenCL and on the CPU. F63's "every OpenCL greedy reply was identical to the other OpenCL runs; for Qwen it differs from the CPU's" and F65's "`Only(Gpu)` … two greedy replies identical" compared checksums only: identical garbage passes both.
- **Evidence:** [`research/pf/backend-sampling/opencl-check/`](../../research/pf/backend-sampling/opencl-check/) (`oc.sh`), and the GPU replies of PF T1, 2026-11-06. Not known at the time: which op is wrong (Qwen3.5's gated delta net and SSM conv layers are what Llama doesn't have; ggml-opencl has kernels for both, and the delta-net patch changes how the graph splits those), and whether the SDK's own OpenCL build does the same.
- **Means:** Until fixed, OpenCL can't serve Qwen3.5: `DevicePolicy.Auto` puts the GPU second (D32), so on a phone without the NPU it would pick a backend that answers garbage. P2 needs a reply check that reads the text (or compares with the CPU's for a model where the two agree), the op found, and then a fix (an upstream issue or a patch in `sdk/native/patches/`) or OpenCL kept away from such models. It also bounds every GPU result in PF: speed on the GPU means nothing for Qwen3.5 until this is fixed.
- **Feeds:** P2 (T6 `Auto` order, T9 contract, device tests), D32, F63, F65, PF T2–T4 (GPU arms). **Cause found: F75** (the Q4_K cooperative-K DP4A kernel on 2–4-row matmuls; the SDK's AAR does the same).

### F69. Load-time llama-server features the SDK can't reach: embeddings and rerank (a bug: their endpoints always fail), KV-cache type, LoRA; four more need an A/B test
- **Finding:**
  - Requests reach almost all of llama-server (design §3.3), but `ModelConfig` maps to 17 load keys (`config.cpp`), and several features are switched on only at load.
  - **A bug:** `Endpoint.Embeddings`, `EmbeddingsNative` and `Rerank` always fail, since nothing sets `--embeddings`/`--pooling`/`--reranking`: "This server does not support embeddings. Start it with `--embeddings`" (`server-context.cpp:6856`, rerank at `:6975`).
  - **Cheap and useful:** KV-cache type (`-ctk`/`-ctv`, e.g. q8_0 about halves KV memory, so `ContextSize.Auto` fits more; upstream's default f16 stays unless q8_0 wins an A/B; an earlier prototype measured it on the NPU at similar speed); LoRA adapters (`--lora`, `--lora-scaled`; the per-request `lora` field then applies them).
  - **Needs an A/B on the phone:** backend sampling (`-bs`, experimental; the pin's latest commit adds an OpenCL `topk_mask` kernel for it), n-gram speculation (`ngram-mod`, `--spec-default`; no extra model, helps when replies copy the context), EAGLE3 and DFlash drafters (need a sidecar model: after P3 Hub), TTS through mmproj (`mtmd_cap.audio_gen`, Qwen3-TTS; untested).
  - **Not for 1.0:** control vectors, `--moe-cache-mib`, `--cache-reuse` (hybrid models can't shift), video (needs ffmpeg), `--sleep-idle-seconds` (`close()` covers it), router mode, server tools and MCP (§3.4), diffusion (CLI only).
- **Evidence:** `common/arg.cpp` flag list and help strings, `tools/server/server-context.cpp` (endpoint gates), `common/common.h` (`COMMON_SPECULATIVE_TYPE_*`), `tools/tts/README.md`, all at b12052; `sdk/native/bridge/src/config.cpp` (2026-11-06).
- **Means:** The embeddings/rerank load mode must be added before P4 types those calls (P4's "typed embeddings, rerank" assumed it worked). KV type and LoRA are small `ModelConfig` additions. The A/B items follow the reference rule: upstream's default until a phone test wins. Backend sampling turned out to be per request (`backend_sampling`, `server-schema.cpp:189`), already `Sampling.backendSampling`: only its measurement is missing.
- **Shipped (2026-11-06):** `ModelConfig.task` (`ModelTask.Generate`, `Embedding(pooling)`, `Rerank`), `kvCacheType` (llama.cpp's nine types, default F16) and `loraAdapters`; bridge keys `embeddings`, `pooling`, `reranking`, `kv_cache_type`, `lora`. Host tests are upstream's own server tests on upstream's test models (`sdk/scripts/fetch-test-models.sh`): bge-small mean pooling gives a normalized vector, jina-reranker-tiny ranks document 2 first and 3 last, the stories15M LoRA at scales 0/0.3/0.7/1 gives each expected text, q8_0 KV loads Qwen3.5 4B and chats, and a model whose head size (48) q8_0's 32-blocks don't divide fails with upstream's reason. Device `TaskDeviceTest`, every chip: bge-small lands on the NPU under `Auto` (512 tokens, 74 MiB) and its vectors agree with the CPU's (cosine 0.9999996 on the NPU and on the GPU); jina-reranker ranks the relevant document first everywhere, but **the NPU moves the near-zero scores**: CPU 0.2009 / 0.0366 / 0.0083 / −0.0058 (the Mac's CPU gives the same; the GPU is within 0.002), NPU 0.2062 / 0.0036 / 0.0151 / 0.0115 for documents 2 / 1 / 0 / 3, so document 1, not 3, comes last. Upstream llama-server on HTP0 returns the same NPU scores, so it's the backend's precision, not the SDK. An app that needs a reranker's exact order beyond the top results can load it with `DevicePolicy.Only(DeviceKind.Cpu)`: these models are tiny. The four A/B items were run in the PF feature spike (F70, F72, F73, F74).
- **Feeds:** design §3.3, §8, P4 Breadth, P3 Hub (sidecars).

### F68. MTP's gain depends on the output: on the NPU structured output (JSON) decodes 34–51% faster, prose 7–21% slower; why an earlier prototype saw it win
- **Finding:**
  - **The earlier prototype measured another workload, not another engine.** Same GGUF (`Qwen3.5-4B-Q4_K_M.gguf`, 2 834 975 040 bytes, Unsloth's file with the MTP layer), same delta-net fix. Its wins were tool-call JSON, mostly greedy: 16 → 25–28 t/s with 82–96% of drafts accepted. Its own sampled prose lost the same way F67 did: a temp-0.7 poem went 15.7 → 10–12 t/s at 22–32% accepted. F67 measured only sampled prose (a summary at the model's default sampling).
  - **Re-measured with llama-server** (`mtp2.sh`): F67's settings, 2K-token prompt, 256-token reply via `/v1/chat/completions` with thinking off, 3 rounds, arms off / draft 3 / draft 4 rotated per round. Decode medians (t/s), off → draft 3 → draft 4, with the acceptance rate:

    | Chip | Prose, default sampling | Prose, greedy | JSON, greedy | JSON, 0.2/0.95/20 |
    |---|---|---|---|---|
    | NPU | 14.7 → 12.5 → 11.6 (44%, 32%) | 14.8 → 13.8 → 13.3 (50%, 41%) | 14.4 → 19.3 → **21.7** (88%, 84%) | 14.3 → 19.1 → **21.9** (86%) |
    | CPU | 9.6 → 5.8 → 5.5 (39%, 38%) | 9.6 → 8.1 → 5.6 (47%, 37%) | 9.5 → 9.6 → 10.2 (85%, 88%) | 9.5 → 11.6 → 9.6 (83%) |

    NPU spread within an arm was under 1 t/s. The NPU's prompt reading drops 5–13% with MTP loaded (842 → 736 t/s on the first request): the draft context processes the prompt too. On the CPU even 85% acceptance gains about nothing: a 4-token verify batch costs nearly 4 single steps there.
  - **llama-server can't switch MTP per request:** the `speculative.*` request fields are compiled out (`#if 0` in `tools/server/server-schema.cpp`, "disable speculative parameter adjustments for now"). It's a load setting, so the SDK can't pick it per reply without going beyond llama-server.
- **Evidence:** [`research/p2/mtp/mtp2.sh`](../../research/p2/mtp/mtp2.sh) (2026-11-06); file sizes on the phone.
- **Means:** F67's measurement stands, but its "MTP adds nothing on this phone" holds for prose only. `Speculative.Auto` stays off: a chat app's output is mostly prose, the rule (plan T8) was "faster on prefill and decode", and MTP always costs prefill. An app whose replies are mostly structured (agents, tool calls, JSON) on the NPU should set `Speculative.Mtp(4)`: about 1.5× decode. The KDoc, `loadReport.speculativeReason` and `sdk/README.md` say so. Draft 4 beats 3 for JSON on the NPU; for prose both lose. Not for the CPU (no gain) or the GPU (F67's crash).
- **Feeds:** P2 T8, D10 (upstream evidence), design §8; PD (an agent demo would turn MTP on).

### F67. MTP is slower on the CPU and the NPU and crashes the OpenCL backend, so `Speculative.Auto` leaves it off on every chip
- **Finding:**
  - **Measured** with upstream llama-server (b12052 + the delta-net patch), Qwen3.5 4B Q4_K_M, the SDK's settings (8192 context, `--load-mode none --fit off`), MTP off vs `--spec-type draft-mtp --spec-draft-n-max 3`, prompts of 512 and 2048 tokens (llama.cpp's `docs/build.md` plus a summarise instruction), 256-token replies, seed 42, 5 rounds, a fresh load per arm, arms alternating (F6). Medians, 2K prompt:

    | Chip | Prefill off → on (t/s) | Decode off → on (t/s) | Drafts accepted |
    |---|---|---|---|
    | CPU | 43.3 → 40.7 | 10.4 → 6.7 | 46% (147/320) |
    | NPU (HTP0) | 818 → 691 | 14.9 → 12.9 | 42% (142/336) |
    | GPU (OpenCL) | 149 → crash | 13.5 → crash | – |

    At 512 tokens the same: CPU decode 13.1 → 8.2, NPU 15.5 → 14.2 and prefill 680 → 570. The NPU's spread was under 0.5 t/s in every arm, so its loss is well beyond noise; the CPU's off arm was noisy (3.9–11.7 t/s, thermal status 1–2), its on arm never beat the off arm's median.
  - **Why it loses here:** with about 45% of 3-token drafts accepted, each verify batch yields about 2.3 tokens but costs a 4-token batch plus the draft head's own pass; decode on these chips isn't cheap enough per extra token for that to pay. The draft context also processes the prompt, which costs prefill (NPU −16% at 2K).
  - **OpenCL aborts the process** on the first drafted batch, after the prompt: `clEnqueueNDRangeKernel … error -54` (`CL_INVALID_WORK_GROUP_SIZE`) then `GGML_ASSERT(0)` at `ggml-opencl.cpp:1319`, in `llama_context::decode` from the server loop. Every one of the 5 rounds; MTP off ran clean on the same load settings. An upstream bug in ggml-opencl.
  - **Shipped:** `Speculative.Auto` (the default) applied the per-chip table `MtpPolicy`, which was off on every chip at b12052, with the reason in `loadReport.speculativeReason` (e.g. "off: MTP measured slower on the NPU …"). When a chip won it in a later llama.cpp, the table was to send `mtp_if_available`, which the bridge turns on only if the GGUF has an MTP head (`<arch>.nextn_predict_layers` > 0, F16). (This path was later removed: Auto sends no speculative setting, F80.) `Speculative.Mtp` still turns it on explicitly; its KDoc warns against the GPU.
- **Evidence:** [`research/p2/mtp/mtp.sh`](../../research/p2/mtp/mtp.sh) (2026-11-06); the OpenCL abort reproduced with `ROUNDS=1 CHIPS=gpu KEEP_LOG=1 mtp.sh`; host test `mtp_if_available_follows_the_gguf`; JVM `ModelConfigTest` (`speculativeAutoIsOffOnEveryChip`, `speculativeSetByTheAppIsReported`); device `DeviceChoiceDeviceTest` asserts `Off` with an MTP reason on NPU, GPU and CPU; `check.sh --device` green.
- **Means:** (Prose only: refined by F68, structured output on the NPU gains 1.5×.) MTP adds nothing for prose on this phone today, and on the GPU it kills the app. Re-measure with `mtp.sh` when the llama.cpp pin moves (P5) and flip `MtpPolicy` per chip if one wins. The OpenCL crash is worth an upstream issue in P5 (D10's rules).
- **Feeds:** P2 T8, D10 (upstream evidence), design §8, readiness gap 4.

### F65. `DevicePolicy` resolves in Kotlin, one load per device; on the phone `Auto` picks the NPU and reproduces the shell's reply byte for byte, and the GPU's first-load minute is paid once per install
- **Finding:**
  - **Native.** The model config takes `devices` (names resolved with `ggml_backend_dev_by_name`, the CPU and unknown names rejected as `parse_device_list` does, never through `--device`, F35) and `n_gpu_layers`. A missing or empty `devices` is CPU only (`{nullptr}`, as `--device none`), so llama.cpp's "every GPU-type device" default (F32) is never reached. The image encoder follows the model's device (`mmproj_use_gpu`, `mmproj_device`), because upstream otherwise puts it on "the first GPU", which with both artifacts could be another chip. `lb_model_info` reports `devices`.
  - **Kotlin.** `DeviceResolver` turns the policy and `backends()` into candidates: `Auto` NPU → GPU → CPU, `Prefer(k)` puts k first, `Only(k)` is k alone (D32). A backend that's present but failed is a skipped `Attempt` with its reason; one the app doesn't ship isn't mentioned, except under `Only`. The runtime loads on each candidate in turn, with `devices: [name]` and `n_gpu_layers: 999`; a failure is recorded and the next is tried. `FileNotFound` stops at once. Under `Only` the result is `DeviceUnavailable(kind)` with every reason and the last load error as cause; under `Auto`/`Prefer` the CPU's own error. `Model.loadReport` gives the kind, device, backend and attempts. `DeviceInfo.kind` comes from the backend that added the device (ggml's type says `gpu` for HTP0).
  - **On the phone** (both artifacts in one test APK, Qwen3.5 4B Q4_K_M): `Auto` → `Npu`/`HTP0`/`hexagon`, no attempts, loaded in 5.8 s, and the 16-token greedy reply is byte-identical to the shell run's and the T1 probe's (F36). `Only(Gpu)` → `GPUOpenCL`, two greedy replies identical (but see F71: identical garbage); the first load in a fresh install took 65.6 s, the next two (each a new process) 16.7 and 15.2 s. `Only(Cpu)` loaded in 8.1 s.
  - **The GPU's first-load minute** is ggml-opencl compiling its kernels and caching the binaries itself (`ggml-opencl/cl-program-cache.cpp`) under the temp directory, which in an Android app is the app's cache directory: `cache/llama.cpp/cl-cache/*.clbin`, 7.7 MB. It's paid once per install, and again if Android clears the app's cache. `GGML_OPENCL_KERNEL_CACHE_DIR` can move it. The default is kept: the cache directory is for data that can be rebuilt.
- **Evidence:** host tests (33/33): `config_defaults_applied` (CPU-only default, encoder off the GPU), `model_devices_default_is_cpu`, `model_devices_empty_is_cpu_and_no_scan` (with `GGML_BACKEND_PATH` set, `ggml_backend_reg_count()` stays 1), `model_devices_invalid_names_fail` (`NOPE0`, `CPU`). JVM `DevicePolicyTest` (10; 140 in all), `FakeLlamaBroTest.modelLoadsOnCpuWithNoAttempts`. Device `DeviceChoiceDeviceTest` (4) and `HexagonDeviceTest` on the CPH2649, 2026-11-05, with `DeviceChoice` log lines for the load times; `run-as … find cache` for the kernel cache. `check.sh --device` green.
- **Means:** An app adds artifacts and gets the fastest chip that loads, with reasons when it doesn't. The host test for a registered fake GPU device (plan T6 Step 2) wasn't written: the test backend has no devices, and the phone covers real ones. D24's memory pre-check ("skip a device that can't hold the model") isn't implemented: no backend's memory figure is usable (F64); T7 decides what budget it could use. The SDK README has a "Faster chips" section.
- **Feeds:** D24, D32, design §9, P2 T6, T7, T10, F32, F35, F36, F63.

### F64. ggml-vulkan fails on the Adreno 830 (known driver bugs, upstream unfixed): Q4_K loads abort the process, Q8_0 runs with wrong output; and Vulkan's reported memory is an overcount
- **Finding:**
  - **Build.** `libllamabro-ggml-vulkan.so` (31.4 MB, nearly all precompiled shaders; AAR 6.1 MB) builds on the Mac with NDK 29.0.14206865 and the same Android settings. The Docker image has no host C++ compiler for ggml-vulkan's shader generator and no `vulkan.hpp`. It uses Khronos' Vulkan-Headers and SPIRV-Headers at **1.3.275**, the NDK sysroot's own `vulkan_core.h` version and the one upstream's Vulkan CI builds with (Ubuntu 24.04's `libvulkan-dev`); `scripts/fetch-deps.sh` downloads them with pinned SHA-256s. ggml-vulkan finds SPIRV-Headers but never links its target (upstream relies on the system include directory), so the SDK's CMake adds the include path to `ggml-vulkan`; upstream's files are untouched. It NEEDs `libvulkan.so` (an NDK system library, no `<uses-native-library>`). Upstream's CI never builds Vulkan for Android. The AAR alone makes `Vulkan0` available (`VulkanDeviceTest`).
  - **On the phone it doesn't work.** With upstream llama-server built from the same source and `-dev Vulkan0 -ngl 99`, every Q4_K_M load (Qwen3.5 4B, Llama 3.2 3B; 6 of 6) aborts the process: the driver fails `createComputePipeline` for `mul_mat_vec_q4_k_f32_f32` with `ErrorUnknown`, and ggml-vulkan re-throws (`ggml-vulkan.cpp:771-775`) into an uncaught `vk::UnknownError`. None of ggml-vulkan's switches (`GGML_VK_DISABLE_INTEGER_DOT_PRODUCT`, `_F16`, `_COOPMAT`, `_MMVQ`, `_DOT2`, `_BFLOAT16`, alone or together) avoids it. A Q8_0 model (Qwen3 0.6B) loads but answers "Hello" with `edlyedlyedly…` where the CPU writes a sentence.
  - **Cause (research, 2026-11-05): a known, unfixed Adreno 830 driver bug, plus a second one for the wrong output.** [llama.cpp #28635](https://github.com/ggml-org/llama.cpp/issues/28635) reports the same failure on two other Adreno 830 phones (RedMagic 10 Pro, Honor, driver 0.800.74). The reporter's first theory (the NDK's shaderc producing different SPIR-V) was retracted: NDK 26 and NDK r29 produce byte-identical SPIR-V for the failing variant, `mul_mat_vec_q4_k_f32_f32` in its `_subgroup_no_shmem` form, which passes `spirv-val`. A second reporter isolated it to the driver. Only q4_k fails (q5_k, q6_k, q8_0, iq*, f16 pass); it fails when the `BLOCK_SIZE` specialization constant is 32 or more and succeeds below 32; Mesa/Turnip and lavapipe compile it fine. The issue is open, marked stale, with no maintainer reply and no fix. The wrong Q8_0 output matches [PR #30821](https://github.com/ggml-org/llama.cpp/pull/30821) (a Qualcomm engineer, draft, not merged): the Adreno driver miscompiles 8-bit vector `pack32`/`unpack8`, so "quantized models produce garbage" on Adreno 840/850 and X1/X2 Elite. The PR adds a Qualcomm-only specialization constant (or `GGML_VK_AVOID_8BIT_VECTORS=1`) that uses shifts instead, but it doesn't name the Adreno 830 or address the pipeline crash. An earlier suspicion of the SDK's `glslc` was therefore wrong: the build isn't the cause. Upstream's [#12421](https://github.com/ggml-org/llama.cpp/issues/12421) is an older report of the same q4_k pipeline failure on Adreno.
  - **What a retry would need:** both fixes. For the crash, nothing upstream yet; the evidence suggests a patch that gives Qualcomm a q4_k mat-vec `BLOCK_SIZE` below 32 (speed unknown). For the wrong output, PR #30821 or its successor. Plus catching a failed pipeline instead of aborting. Worth revisiting when #30821 merges and #28635 gets a fix.
  - **Memory.** Vulkan0 reports 19 203 MiB total and free on a phone with 15.1 GB of RAM (and 11 GB of swap): for an integrated GPU ggml sums every heap the driver lists (`ggml-vulkan.cpp:15415`), and on a phone those heaps are views of the same RAM. Free equals total, so there's no live budget either. No backend's memory figure is usable on this phone: CPU all RAM (F33), OpenCL 7.5 GB, Hexagon 0, Vulkan 19 GB.
- **Evidence:** [`research/p2/opencl-speed/results.md`](../../research/p2/opencl-speed/results.md) "Vulkan", 2026-11-05; the server log line `ggml_vulkan: compute pipeline creation failed for mul_mat_vec_q4_k_f32_f32`; `/proc/meminfo` (`MemTotal 15469920 kB`, `SwapTotal 11534332 kB`); the build (`scripts/build-backends.sh`, `scripts/fetch-deps.sh`); `check-aar.sh` on the AAR; device `VulkanDeviceTest.vulkanBackendLoads` (backend load only, no model).
- **Means:** As built, `llamabro-vulkan` would let `Prefer(Vulkan)` or `Only(Vulkan)` take an app down on a Snapdragon 8 Elite phone, a crash `loadReport` can't catch, and give wrong answers on Q8_0. D23 planned to verify Vulkan on this Adreno, and it fails that verification. **Decided: Vulkan is dropped from 1.0 (D31)**; the recipe stays for a retry. T6 and T7 can't take any backend's memory figure as given: the budget comes from Android (D22), and D24's "Auto skips a device that can't hold the model" needs it too.
- **Feeds:** D23, D24, D22, design §9, P2 T5, T6, T7, T10, D10 (a possible upstream issue report), P4/P5 (Vulkan revisited with a newer llama.cpp or driver); retried at b12479 in F174.

### F63. OpenCL on the Adreno 830: about 3× faster prefill than the CPU, the same decode, and 2.5× slower loads (about a minute the first time)
- **Finding:**
  - **Build.** `libllamabro-ggml-opencl.so` (11.1 MB; AAR 0.94 MB) comes from the same Docker run as Hexagon (`LB_BACKENDS=hexagon;opencl`, F62), with upstream's defaults: kernels embedded, Adreno kernels on, OpenCL 3.0. The image's NDK sysroot carries the OpenCL headers and a link-time `libOpenCL.so` (soname `libOpenCL.so`), which is where the P0 spike's build found them too. Stock NDKs don't ship these; the NDK on the development Mac has them as well, but the build doesn't rely on that. At run time the phone's `/vendor/lib64/libOpenCL.so` is used, reached through `<uses-native-library libOpenCL.so required=false>` (it's in the vendor's `public.libraries`). No loader is shipped. Each backend has its own output directory, and the Docker task is the root project's `buildBackends`.
  - **Device.** The AAR alone gives `backends()` an `opencl` entry with device `GPUOpenCL` ("QUALCOMM Adreno(TM) 830", 7553 MiB, 6529 free from the shell).
  - **Speed** (upstream llama-server from the same source, the SDK's load settings, `-dev GPUOpenCL -ngl 99` against `-dev none`, arms alternating, 668-token prompt, 128-token greedy reply, 3 rounds per model, the phone warming from thermal status 1 to 3):

    | | Load | Prefill | Decode |
    |---|---|---|---|
    | Qwen3.5 4B CPU | 7.7–8.0 s (one 22 s while throttled) | 39–61 t/s | 13.0–13.7 t/s (one 2.0 while throttled) |
    | Qwen3.5 4B OpenCL | 17.9–18.9 s; **58.9 s the first time** | 131–174 t/s | 13.8–15.2 t/s |
    | Llama 3.2 3B CPU | 6.6–6.8 s | 56–71 t/s | 12.2–16.4 t/s |
    | Llama 3.2 3B OpenCL | 16.4–16.6 s | 174–175 t/s | 16.1–16.2 t/s |

  - **Determinism.** Every OpenCL greedy reply was identical to the other OpenCL runs. For Llama it also matched the CPU's; for Qwen it differs from the CPU's, as the plan expected (but see F71: the Qwen replies were garbage).
- **Evidence:** [`research/p2/opencl-speed/results.md`](../../research/p2/opencl-speed/results.md) (script `speed.sh`), 2026-11-05; `llama-server --list-devices` on the phone; `llvm-readelf -d` on the library; build log and CMake cache (`OpenCL_INCLUDE_DIR`, `OpenCL_LIBRARY`, `GGML_OPENCL_*`); device `OpenClDeviceTest.openclBackendLoads`; `check-aar.sh` (`libOpenCL.so` accepted as the vendor public library the manifest asks for); `check.sh --device` green, run after the phone cooled to thermal status 1.
- **Means:** For a chat app, OpenCL helps long prompts (about 3× prefill) but not reply speed, and costs about 10 s more per load. The first load cost about a minute, most likely the Adreno driver compiling ggml's OpenCL kernels before caching them; where that cache lives, and whether an app pays it once or on every process start, was left to verify in T6, the first time an app loads a model on the GPU (answered in F65). T6's `Auto` order (NPU → OpenCL → CPU) stands, but `loadReport` and the docs should name the load cost. T4 Step 1's `openclChatMatchesItself` moves to T6 (`Only(OpenCl)`), like T3's reply check, because a model load needs T6's device choice. Because an emulator appeared in adb on the development Mac, `check.sh` runs with `ANDROID_SERIAL` set to the phone; Gradle's device tests honour it.
- **Feeds:** design §9, P2 T4, T6 (`Auto` order, load cost, the first-load kernel cache), T8 (MTP per backend), T10, F62, P5 (sizes).

### F62. `llamabro-hexagon` builds from the SDK's own CMake inside Qualcomm's image with the SDK's exact NDK, and the AAR alone makes HTP0 available
- **Finding:**
  - **Build.** `sdk/native/scripts/build-backends.sh` runs `sdk/native/CMakeLists.txt` with `-DLB_BACKENDS=hexagon` inside `ghcr.io/snapdragon-toolchain/arm64-android:v0.8`, with AGP's Android settings (API 29, `c++_shared`, Release). It builds only `ggml-hexagon` (renamed `libllamabro-ggml-hexagon.so` by the same prefix rule as the core, F25) and `htp-v73/v75/v79/v81/v85`. The image's NDK is **29.0.14206865**, the SDK's own, so no NDK download is needed; the script checks the revision. F34's "r28b" came from an older line in upstream's README. The Hexagon SDK's CMake also needs `PREBUILT_LIB_DIR=android_aarch64`, as upstream's Snapdragon preset sets it; without it, configuration fails in `hexagon_fun.cmake:99`.
  - **Output.** `libllamabro-ggml-hexagon.so` is 4.56 MB, 16 KB aligned. It NEEDs only `libllamabro-ggml-base.so` and `libc++_shared.so` (both from the core AAR) and system libraries: `libcdsprpc.so` is opened at run time, not linked. The skeletons are 0.90–0.94 MB each, 4.6 MB for all five. The AAR is 2.97 MB.
  - **Install.** `HexagonInstaller` (the module's `BackendInstaller`, named in its manifest with `<uses-native-library libcdsprpc.so required=false>`) copies the skeletons through `SkeletonInstall` to `noBackupFilesDir/llamabro/htp/<set>/`. `<set>` is the first 16 hex digits of a SHA-256 over every skeleton's name and hash. The installer copies into a staging directory, checks each file against the `.sha256` the build wrote, and renames the directory into place. Threads are serialised by a lock object, processes by a `FileChannel` lock. Other sets are deleted. Every start re-hashes the installed files (about 4.6 MB; the time wasn't measured), so a truncated or modified file is replaced. The env (`ADSP_LIBRARY_PATH` and the `CDSP*` variants) points at that directory (F19, F36).
- **Evidence:** build log 2026-11-05; `llvm-readelf -d/-lW` on the library; JVM `SkeletonInstallTest` (6: install, reuse without copying, a corrupt or missing file replaced, a wrong hash in the APK fails with `MismatchException` and leaves nothing, an update gets a new set and deletes the old, 4 threads × 20 rounds give one complete set); `check-aar.sh` on the backend AAR (5 skeletons match, no core library duplicated, every NEEDED resolved; a tampered skeleton fails it); device `HexagonDeviceTest.hexagonBackendLoads` (`backends()` has `hexagon` loaded with `HTP0`, `devices()` lists HTP0 as `gpu`, one set with the five skeletons), and `check.sh --device` green on 2026-11-05 with the sample's suite unchanged.
- **Means:** An app gets the NPU by adding the dependency. T3 Step 4's greedy reply on HTP0 moves to T6: loading a model on a chosen device needs `devices`/`n_gpu_layers` in the model config, which T6 adds. Without an explicit choice llama.cpp would pick every GPU-type device (F32), and fit skips HTP0 because it reports no memory (F36's log). The image has no OpenCL SDK under `/opt` (no `OPENCL_SDK_ROOT`), so T4 must find or fetch one. `check.sh` needs Docker: `:llamabro-hexagon`'s `buildHexagonBackend` task reruns the image build whenever the patched copy, `CMakeLists.txt` or the script changes (an unchanged tree is up to date and skips it).
- **Feeds:** design §9, D20, D24, P2 T3, T4, T6, F18, F34, F36, F61, P5 (sizes, CI image).

### F61. Optional backends load by name from the runtime config; a failure is reported with ggml's own reason and never stops the runtime
- **Finding:**
  - **Native.** `lb_runtime_init` takes `backends: [{name, files, env}]`. After the CPU backend, under the init lock, it sets each backend's env with `setenv` and loads each file by name with `ggml_backend_load`, before `llama_backend_init`, so the F35 scan still never runs. A failure is recorded and init still returns OK. ggml says why only in its error log (`load_backend: failed to load <file>: <dlerror>`, or `failed to initialize backend from <file>: ggml_backend_init returned NULL`), so the bridge keeps that line with its error capture, as for model loads (F51). `lb_runtime_backends()` returns `[{name, loaded, error, devices}]`, `cpu` first, and `[]` before init. Malformed entries are `LB_ERR_INVALID_ARG`: not an array, missing name or files, non-string env values, unknown keys, the reserved name `cpu`, or a name listed twice.
  - **Kotlin.** A backend artifact announces `<meta-data android:name="com.suhel.llamabro.backend.<name>" android:value="<installer class>">`. `getInstance` reads the merged manifest with one `PackageManager.getApplicationInfo(GET_META_DATA)` call when the runtime first starts (on `Dispatchers.IO`). It creates each installer by name; its `BackendInstaller.install(context)` returns the files and env. A bad entry or an installer that throws becomes a failed `Backend` with the reason and isn't sent to native. `suspend fun LlamaBro.backends(): List<Backend>` lists the native results, then those failures. The seam (`BackendInstaller`, `BackendLibraries`) is public under `@InternalLlamaBroApi`, like `Engine` (F54), and the core's consumer R8 rules keep every installer's no-argument constructor. `Engine.backends()` has a CPU-only default, so `FakeLlamaBro` and the SDK's `FakeEngine` needed no change (D29).
  - **Edge case:** if a backend lists several files and a later one fails, the earlier ones stay registered with ggml while the backend reports `loaded: false`. Every planned backend has a single file, and T6 always passes an explicit device list (F32), so no model can land on such a device unasked.
- **Evidence:** host tests (30/30) `backends_empty_before_init`, `optional_backend_failure_is_reported` (a missing file: the reason has the file name and `dlopen`, CPU still loads a model), `backend_init_failure_is_reported` and `backend_env_set_before_load` (`lb_test_backend.cpp`, a device-less test backend that records the env it saw in `ggml_backend_init`, or returns NULL), `runtime_init_twice_same_config_with_backends_ok`, `backends_config_invalid` (9 malformed configs). JVM `BackendsTest` (6; 130 in all). API dump +27 lines. `check.sh --device` green on 2026-11-05 (contract, e2e and device unchanged; device 17 with the known F27 failure and Gemma's skip).
- **Means:** T3–T5 only add artifacts: each implements `BackendInstaller` (Hexagon copies its skeleton there, T3) and declares the `<meta-data>`. Nothing else in the core changes for a new backend. A backend whose library crashes inside `ggml_backend_init` (a driver fault) still takes the process down; only failures that return can be reported.
- **Feeds:** D24, P2 T2–T6, review focus 1 and 3, F35, F51, F54.

### F36. An ordinary app, debuggable or not, loads the unsigned HTP skeleton from `noBackupFilesDir` and runs a model on the NPU
- **Finding:** On the OnePlus 13 (SM8750, HTP v79, Android 16), an app copies `libggml-htp-v79.so` from its assets to `noBackupFilesDir/llamabro/htp/` and sets `ADSP_LIBRARY_PATH` (and `CDSP*_LIBRARY_PATH`, F19) to that directory before any ggml call. With that, FastRPC opens the skeleton from there and starts an unsigned protection domain (`/vendor/dsp/cdsp/fastrpc_shell_unsigned_3`) for the app's own user (`u0_a630`). Qwen3.5 4B Q4_K_M then loads with all 34/34 layers on HTP0 (2856.64 MiB model buffer, KV and recurrent state on HTP0 too), exactly as from the shell (F19). This holds for a **non-debuggable release build** (no `DEBUGGABLE` flag) as well as a debug build. The 16-token greedy reply is byte-identical to the shell run of the same build and request (F4). Speeds: decode 15.2 / 16.5 t/s (debug / release) against 16.1 from the shell; prefill 42.5 / 43.4 against 48.1, on single runs. None of T1's fallbacks were needed. Two log lines are harmless: FastRPC first looks for the skeleton in `<dir>/cdsp/` (not found, error 0x2) before `<dir>/`, and a `dspqueue` invoke error 0x80000414 appears in the shell run too. Packaging needs: `<uses-native-library>` for `libcdsprpc.so` (a vendor public library on this phone) and for `libOpenCL.so` when the process links ggml-opencl; a model file the app can read (a file pushed by adb into the app's external dir needed `chmod 644`).
- **Evidence:** probe app [`research/p2/htp-probe/`](../../research/p2/htp-probe/README.md) (upstream `llama_server()` run in the app's process from the spike's `P` libraries), 2026-11-05. The logcat of the release run showed `Successfully opened file …/no_backup/llamabro/htp/./libggml-htp-v79.so`, `fastrpc_shell_unsigned_3`, `remote_handle64_open … on domain 3`, `load_tensors: offloaded 34/34 layers`, `HTP0 model buffer size = 2856.64 MiB` and `debuggable=false`. Reply in all three runs: `"\n\n<think>\nHere's a thinking process that leads to the story above:\n\n"`.
- **Means:** The P2 gate passes. `llamabro-hexagon` goes ahead as designed (design §9): skeletons ship as assets, are copied to `noBackupFilesDir`, and `ADSP_LIBRARY_PATH` is set inside `lb_runtime_init` before the Hexagon backend loads (T2, T3). Only one phone is tested: another OEM or chip may refuse unsigned protection domains to apps, and that case ends as a reason in `loadReport` with CPU still working (review focus 1). The skeleton versions other than v79 are untested.
- **Feeds:** design §9, P2 T1 (gate), T2, T3, readiness gap 1, F19.

## Memory (P2)

### F66. Upstream's fit misjudges a phone, its projection over every device is a safe budget measure, and one OEM's `availMem` hides reclaimable driver pools
- **Finding:**
  - **Upstream's fit can't size a context here.** It compares projected use with device memory only and assumes the host is unlimited (`fit.h`). On the CPU alone it compares the Host row with total RAM (F33), and that row leaves out `CPU_REPACK` (2.6 GB of Qwen3.5 4B's weights): "projected to use 909 MiB of host memory" for a 3.5 GB load. HTP0 reports 0/0, so fit can't use it ("did not report memory; --fit will not use it"), and with `n_gpu_layers` set it aborts. On any failure llama-server logs a warning and loads the **training context**: with `-c 0` and a tight target the CPU load went on at 262 144 tokens (`fit.cpp:540-550`).
  - **Its measurement is sound.** `common_fit_measure` (public, a dry run with `no_alloc`) gives disjoint rows (devices, host, other buffer types). Their sum at 8192 tokens was 110–425 MiB above what the system really lost after loading and filling the context (MemAvailable drop): Qwen3.5 4B CPU 3506 vs 3394, NPU 3769 vs 3461; Llama 3.2 3B CPU 3207 vs 2996, NPU 3419 vs 2994. Two dry runs cost about 0.2 s on the Mac and add under a second to a phone load. Process PSS is no measure for the NPU: 735 MiB for a 3.5 GB load, since its buffers are shared DMA memory.
  - **The budget source matters.** AOSP's `ActivityManager.MemoryInfo.availMem` is `MemAvailable` alone (`android_util_Process.cpp`, `getFreeMemory`). On this OnePlus build it isn't: 3377 MiB against `MemAvailable` 8239, consistent with leaving out `KReclaimable` (4383 MiB, mostly GPU and NPU driver page pools, filled by the SDK's own device tests) plus about 0.5 GB. It swung from 7959 to 3675 MiB within one test run while `MemAvailable` stayed near 8.2 GB. Those pools are reclaimable: a 64K-context NPU load took `KReclaimable` from 4281 to 461 MiB, and `lowmemorykiller` killed nothing ("device has enough memory … disable killing"). So the budget is the kernel's `MemAvailable`, read from `/proc/meminfo`.
  - **Uncapped, "as much as fits" is a lot**: 53 760 tokens on the CPU and 67 072 on the GPU for Qwen3.5 4B on this phone, and more with the kernel's figure. The GPU and NPU reserve that at load. Filling 32K tokens takes about 40 s on the NPU and about 10 minutes on this phone's CPU (prefill 37–48 t/s).
  - **Shipped (D33, D34):** `ContextSize.Auto` = the largest context up to 16 384 that fits `MemAvailable` − threshold − prompt cache − image encoder − 512 MiB; at least 4096 or `ModelLoadFailed(InsufficientMemory)`, which also stops device fallback (every chip shares the RAM). `ContextSize.UpTo(n)` sets the ceiling. `loadReport.contextSize`/`contextReason` say what and why. On the phone: CPU, GPU and NPU all loaded 16 384 tokens ("the ceiling of 16384 tokens fits: 4527 MiB of 5291 MiB available" on the NPU), back to back, after the device tests that had refused the NPU load with `availMem`. Context checkpoints (8 × about 50 MiB on Qwen) aren't projected: the reserve covers them.
- **Evidence:** [`research/p2/fit-memory/results.md`](../../research/p2/fit-memory/results.md) (`measure.sh`), 2026-11-05/06; AOSP `core/jni/android_util_Process.cpp` (`android_os_Process_getFreeMemory` reads `kMemAvailable`) and `ProcessList.getMemoryInfo`; developer.android.com `ActivityManager.MemoryInfo`; host tests `auto_ctx_fits_budget` (huge budget = training context, a 16384 ceiling holds, half way interpolates aligned to 256, below the minimum fails with `insufficient`), `auto_ctx_budget_load_and_insufficient_error`, `config_defaults_applied` (`fit_params` off); JVM `ContextFitTest` (budget formula, `/proc/meminfo` parsing, the ceiling and budget sent only for Auto/UpTo, InsufficientMemory stops fallback, the reasons); device `DeviceChoiceDeviceTest` with `MemoryInfo` logged; `check.sh --device` green. Sources for the 4096 defaults: Ollama, LM Studio, Apple TN3193 (D34).
- **Means:** The context is never llama.cpp's 262K fallback and never more than the phone can take at load. "Three free-memory levels" (plan T7 Step 2) weren't run separately: the budget is read at each load, and the device tests ran at different levels as the pools filled. The ceiling's reasoning is in the `ContextSize` KDoc and `sdk/README.md`. D24's memory pre-check is no longer needed: a load that can't fit fails in its dry run, before allocating.
- **Feeds:** D22, D33, D34, D24, design §8, P2 T7, T10, PD (recommendations: the same dry run can tell before download whether a model fits).

### F60. The unmap patch keeps mmap's load speed with one copy of the weights, but the first draft crashed on tied embeddings
- **Finding:**
  - **The first draft (F59, 5 lines) crashed.** It unmapped each tensor right after copying it. Every load then segfaulted at the first decode, for both Qwen3.5 4B and Llama 3.2 3B. Both tie `output` to `token_embd`: one file range is loaded twice (`TENSOR_DUPLICATED`, `llama-model-loader.cpp:1384-1397`). `output` is copied into `CPU_REPACK`, while `token_embd` is used in place from the mapping (in another context, loaded in another `load_all_data` call), so unmapping the copy removed the embedding's pages.
  - **v2 (14 lines, `sdk/native/patches/0002-unmap-copied-weights.patch`).** It records per file the ranges copied out of the mapping and the ranges still read from it (mmap-backed and lazy tensors). After the last `load_all_data` call, next to upstream's own head/tail unmap, it unmaps copied minus read with the loader's own `merge_ranges`/`sub_ranges`. `unmap_fragment` rounds inward to whole pages.
  - **Measured on the phone** (spike build, arms alternating): Qwen loads in 5.84 s mean (mmap 5.63, `none` 7.00) at 3383–3395 MiB PSS (`none` 3396, mmap 5496). Llama loads in 4.74 s (4.66, 5.45) at 2747 MiB (2746, 4355). Replies are byte-identical to unpatched (MD5 of the 64-token greedy reply), and decode speed is equal. About 0.5 GB (Qwen) and 0.3 GB (Llama) stay mapped as file pages: the tensors used in place, clean and droppable by the kernel, where `none` holds them as anonymous memory. **Peak RSS while loading** is 5.4 GB (Qwen) and 3.9 GB (Llama), like mmap, against 3.4 and 2.75 GB with `none`: the file is mapped with `MAP_POPULATE` and released only after the last tensor.
  - **In the SDK** (with `--load-mode` back at upstream's `auto`): `check.sh --device` all green, so the contract and e2e checks pass with the patch on both sides. `LifecycleDeviceTest`: Qwen loaded at 3485 MiB (`none` 3489, mmap 5558), Qwen then Llama at 3446 and 2728 MiB, and a model-switch peak of 3875 MiB (`none` 3443, mmap 5545). The switch peak is the load spike.
- **Evidence:** [`research/p2/f50-load-mode/results.md`](../../research/p2/f50-load-mode/results.md) (the crash, the v2 runs for Qwen and Llama), OnePlus CPH2649, 2026-11-05; `check.sh --device` run 2026-11-05 with the patch.
- **Means:** **Decided: `none` (D30)**; the patch is not shipped and stays in the research folder ([`unmap-copied-weights.patch`](../../research/p2/f50-load-mode/unmap-copied-weights.patch)) for an upstream PR. The two options were, both with one copy of the weights after load: `none` (no patch, about 1.2 s / 0.8 s slower, never above the final memory) or mmap + this patch (mmap's speed, carried as patch 0002 until upstream takes it, a brief peak as high as today's while loading). The same tie trap applies to any range-sharing tensors, so an upstream PR (D10) should come with a test of a tied-embedding model. No search for an existing issue or PR yet.
- **Feeds:** F50, F58, F59, D30, design §8, D10 (upstream PR), P5.

### F59. Direct I/O is no faster than `none`, and a five-line upstream patch could keep mmap's load speed without the second copy
- **Finding:**
  - **`--load-mode dio` loses.** Same memory as `none` (3395–3403 MiB for Qwen3.5 4B) but slower: mean 7.43 s, against 6.93 s for `none` and 5.77 s for `auto` (mmap), warm cache. Direct I/O skips the page cache but still goes through the staging buffer, and on a warm cache the cache was the fast part.
  - **Why `none` is slower than mmap.** In mmap mode the file is mapped with `MAP_POPULATE` (`llama-mmap.cpp:492-494`), and each repacked tensor is copied straight from its mapped pages into `CPU_REPACK`. The tensors that aren't repacked are used in place, with no copy at all. Without mmap, the repack buffer isn't a host buffer (`repack.cpp:5253`, `is_host` null), so every repacked tensor is `read()` into a staging buffer and then repacked (`llama-model-loader.cpp:1816-1830`). The ~0.5 GB that isn't repacked is read into fresh memory. That's one more pass over all 2.8 GB, in many reads.
  - **The patch.** In mmap mode, after `ggml_backend_tensor_set` has copied a tensor out of the mapping (`llama-model-loader.cpp:1748`), call the loader's existing `unmap_weight` for it. That gives one copy, like `none`, while loading like mmap. `unmap_fragment` rounds the range inward to whole pages (`llama-mmap.cpp:531-541`), so a page shared with a tensor still used from the mapping stays mapped. It must skip lazy tensors (read later from the mapping) and `--check-tensors` (whose async validation reads the mapped data after the copy). It also covers weights copied to a GPU in mmap mode. The draft is 5 lines in one function and touches nothing the delta-net patch does. **This draft crashes on tied embeddings; v2 and its measurements are F60.** No search for an existing upstream issue or PR yet (D10).
- **Evidence:** A/B with `ARMS="auto none dio"`, arm order rotated per round, Qwen 4 rounds (thermal 0, one server per arm), Llama 3 rounds (indicative: a leftover server stuck after SIGTERM stayed resident), OnePlus CPH2649, 2026-11-05: [`research/p2/f50-load-mode/results.md`](../../research/p2/f50-load-mode/results.md), the patch beside it (`unmap-copied-weights.patch`, v2). Source lines above (b12052).
- **Means:** `dio` is out. The choice is `none` (shipped now, no patch, about 1.2 s slower than mmap) or mmap plus this patch (about mmap's speed, one more patch to carry until upstream takes it). Either keeps a single copy of the weights. The patch would have to win its own A/B first (design reference rule).
- **Feeds:** F58, design §8 (`load_mode`), D10 (upstream PRs), P2 T3–T5, P5.

### F58. Reading the weights instead of mapping them (upstream `--load-mode none`) removes F50's second copy: 38% less memory for about 1 s more load
- **Finding:** With upstream's default `--load-mode auto` (mmap), the CPU backend repacks most weights into its own buffers, but llama.cpp unmaps only the file's head and tail outside the tensors it serves straight from the mapping (`mmap_used`, `llama-model-loader.cpp:1745, 1872-1879`). So the repacked tensors' pages stay mapped and resident next to their copy (F50). Upstream's own loader warns about the combination ("consider using --load-mode none", `:1259`). With `--load-mode none`, every tensor is read into its buffer and nothing stays mapped. On the phone (CPU, `-c 4096`) PSS drops from 5426–5494 to 3391–3395 MiB for Qwen3.5 4B and from 4363 to 2754 MiB for Llama 3.2 3B. Anonymous memory grows by about 0.5 GB (Qwen) and 0.3 GB (Llama), for the tensors that aren't repacked and used to be served from the mapping. Warm-cache load time grows from 5.9–6.6 to 7.3–8.2 s (Qwen, one 18.5 s outlier) and from 4.6–4.7 to 5.5–5.6 s (Llama). Decode and prefill speed are the same within noise.
- **Evidence:** A/B with llama-server from the P0 spike's `P` build (b12052 + delta-net patch), arms alternating within each round, 5 rounds for Qwen and 4 for Llama, OnePlus CPH2649 (SM8750), thermal status 0–1, 2026-11-05: [`research/p2/f50-load-mode/results.md`](../../research/p2/f50-load-mode/results.md), script `ab.sh` beside it. The SDK with the new default, through `check.sh --device` (all steps green, contract and e2e with `--load-mode none` on both sides): `LifecycleDeviceTest` gives Qwen loaded at 3489 MiB (native heap 3449; PA T4 measured 5558, F49), Qwen then Llama at 3442 and 2725 MiB (was 5545 and 4335, F48), and a model-switch peak of 3443 MiB (was 5545). Every page cache was warm; cold loads weren't measured (dropping the cache needs root).
- **Means:** The SDK's phone default is `--load-mode none` (design §8; `config.cpp`, host test `config_defaults_applied`). It's an upstream option, so no patch. It isn't exposed in `ModelConfig`: no app need is known. The contract and e2e llama-servers pass the same flag. The trade is about 1 s (≈20%) more load for 1.6–2.1 GB less resident memory, which is what Android's low-memory killer weighs. P2 T7 measures its margin with this default. P2 T3–T5 check the default per backend: `auto` turns mmap off itself for a device without mmap support, and offloaded weights aren't repacked, so `none` may not be best for the NPU or GPU.
- **Feeds:** design §8, D29 (6), P2 T3–T5, T7, P5 (memory levers).
- **Update (2026-11-05):** F60 measured the alternative (mmap + patch 0002); `none` was chosen (D30).

## Phone test setup (PA T8)

### F57. Running the sample's device tests outside `check.sh` uninstalls the app and deletes the models pushed into it
- **Finding:** `connectedDebugAndroidTest` uninstalls the app under test when it finishes unless `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` is set, and Android deletes an app's data with it. The acceptance models live in the sample's `files/` (pushed with `run-as … cp`), so one plain run of a single test class deleted all three. `check.sh` always sets the flag, which is why this never happened before. Qwen3.5 4B and Llama 3.2 3B were restored from local copies; Gemma 3 4B (`gemma-3-4b-it-qat-UD-Q4_K_XL.gguf`) had no local copy and was downloaded again (2026-11-05: `unsloth/gemma-3-4b-it-qat-GGUF` at commit `165f274`, 2 537 530 496 bytes, SHA-256 `ccd7e4b7…382c72` matched). A local copy is kept now.
- **Evidence:** 2026-11-05, during PA T8: `:samples:minimal:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=…SampleUiTest` (2/2 passed), then `check.sh --device`: 14 of 17 sample tests failed with "missing model …/files/Qwen3.5-4B-Q4_K_M.gguf" (and the same for the others); `run-as … ls files/` showed only the profile installer's files, dated at the reinstall.
- **Means:** Run the sample's device tests through `check.sh`, or pass the flag. Keep a copy of every device-test model off the phone; the working rules say so.
- **Feeds:** working rules, P5 (CI device setup).

## Audio routes (read for P4, 2026-11-05)

### F55. File transcription needs only the request's `files` filled, so the audio can come from a path or a descriptor with no upload
- **Finding:** llama-server's `POST /v1/audio/transcriptions` handler reads the audio from `server_http_req::files["file"]`, an in-memory `uploaded_file` (`raw_buffer data`, a byte vector) that llama-server's HTTP layer fills from the multipart upload; nothing else in the handler depends on HTTP. The bridge builds `server_http_req` itself (as for every route, F1), so it can fill `files` too. Then an app passes a file path or an open file descriptor (an Android `content://` URI's `ParcelFileDescriptor`): the bridge reads the file once, natively, and calls upstream's handler unchanged. No base64, no bytes through Kotlin or JNI, no copy on disk. In memory there is the bridge's buffer, one copy upstream makes (`out_files.push_back(it->second.data)`), and the decoded audio, as in llama-server itself; the copy could become a move with a one-line patch, only if an A/B test shows it matters (audio files are small: about 2 MB per minute of 16 kHz WAV). `Content.Audio` in chat messages is different: llama-server's `input_audio` is base64 in JSON, so large files belong on this route.
- **Evidence:** b12052 `tools/server/server-http.h:43-47` (`uploaded_file`), `:55` (`files`); `server-chat.cpp:683-695` (`convert_transcriptions_to_chatcmpl`: "No input file found for transcription" without `files["file"]`, then the copy at `:693`); `server-context.cpp:6080` (`post_transcriptions_oai`). Read, not built: there is no audio model in the test set.
- **Means:** P4 adds a C API request variant with file attachments (form name plus path or fd) and a typed `transcribe(AudioSource.file(…) | .fd(…))`, with `Endpoint.Transcriptions` for raw use. The contract case runs the same audio through llama-server's multipart upload and through the path.
- **Feeds:** P4 (audio), §14 Q1, F53 (why transcription was left out).

### F56. Live transcription needs no WebSocket: upstream's realtime handler is written against an abstract connection the bridge can implement in memory
- **Finding:** `/v1/realtime` is registered as a WebSocket, but its handler (`ws_realtime`) takes a `server_http_context::ws_conn &`, an interface with a blocking `read()`, a thread-safe `send()`, `is_open()` and `close()`. llama-server's HTTP layer supplies a WebSocket implementation; the bridge can supply one backed by two in-memory queues, as it supplies `server_http_req` for the other routes, so the handler runs unchanged and nothing opens a port. Audio arrives as OpenAI-realtime `input_audio_buffer.append` events, PCM16 mono at the model's rate in base64 (about 43 KB of text per second at 16 kHz, small next to the model's audio encoding), `input_audio_buffer.commit` ends an utterance, and the handler streams transcript deltas and a `…transcription.completed` event per item. The handler runs on one thread for the connection's life. It needs a model that supports it: Voxtral Realtime, or an ASR model opened with `intent=transcription` (a query param, so the existing params map carries it); others get upstream's "does not support realtime transcription" error. A loopback WebSocket would instead bring back the 6 MB HTTP layer (F2), a port other apps can reach, and its threads.
- **Evidence:** b12052 `tools/server/server-http.h:78-89` (`ws_conn`, `ws_handler_t`); `server-context.cpp:6113` (the audio format comment), `:6134` (`live_asr`, `intent=transcription`), `:6141` (the error), `:6229-6232` (`input_audio_buffer.append`, base64 decode). Read, not built.
- **Means:** P4 adds a C API session (open with params, push a message, event callback, close) and a Kotlin `liveTranscription { }` session (`append(pcm)`, `commit()`, `events`, `close()`). Its contract check feeds the same scripted audio to llama-server over a real WebSocket and to the in-memory connection, and requires identical events.
- **Feeds:** P4 (audio), §14 Q1, F53.

## Testing seam (PA T7)

### F54. The engine seam is public but opt-in, and the API dump can't show the opt-in
- **Finding:** `Engine`, `EngineModel`, `EngineRequest` and `RequestSink` (package `com.suhel.llamabro.internal`) and `LlamaBro.withEngine(engine, filesDir)` are public under `@InternalLlamaBroApi`, a `@RequiresOptIn` marker at level ERROR, so app code that touches them doesn't compile without `@OptIn`. The SDK opts in module-wide (`compilerOptions.optIn`); `llamabro-testing` opts in on `FakeLlamaBro` alone, and its own tests use none of it. BCV's dump lists the seam like any public API and doesn't show the marker, so a reader of `llamabro.api` must know the `internal` package is the seam. The SDK's JVM tests keep their own `FakeEngine` (finer control: blocking closes, cancel races, load progress); `llamabro-testing` has a separate fake rather than a shared source directory, since both already run the real `Model` and `Conversation`. `RequestSink` moved files but kept its name, so the R8 keep rule (F28) still matches.
- **Evidence:** `api/llamabro.api` diff in PA T7 (the four interfaces and `withEngine` with no annotation); `FakeLlamaBroTest`, 8 tests, 2026-11-05: a screen model with a text reply, the tool loop running the app's handler, `ContextExceeded` then `compact` and `resume` (6 chat requests, the summary among them), event order, `ask` throwing `Incomplete` on length, an unscripted request failing as `ServerError` with "no reply scripted", the context size, and `JsonArgs.parse` with nested objects and lists.
- **Means:** Apps test against `FakeLlamaBro` (D27), not against the interfaces. The testing module has no API dump of its own yet; its public surface is `FakeLlamaBro` and `FakeLlamaBro.Reply` (classes with an internal constructor or a single script parameter, D26). P5 adds its dump when it publishes the module.
- **Feeds:** PA T7, D27, P5 (publishing).

## Routes and names (PA T6)

### F53. Every single-model llama-server route works in-process except transcriptions; the contract needed an id-blind comparison for the new shapes
- **Finding:** Of the routes `server_register_routes` (b12052 `tools/server/server.cpp:170-218`) maps for one model, the C API mapped 14 handlers and now maps all 27: `infill`, `responses`, `responses_input_tokens`, `messages`, `messages_count_tokens`, `embeddings_native` (`POST /embeddings`), `tts`, `systemone`, `props_set` (`POST /props`), `models`, `health`, `lora_adapters` and `lora_adapters_set` were added. Each answers exactly as llama-server. Left out: `/v1/audio/transcriptions`, whose handler reads the audio from `req.files` (a multipart upload the HTTP layer parses; the C API takes a JSON body), and the routes that belong to the HTTP layer or the router (`/v1/realtime` WebSocket, `/cors-proxy`, `/tools`, `/models/load`, `/v1/stream`, `/v1/streams/lookup`, GCP compat). The Responses and Anthropic shapes carry random ids (`resp_…`, `msg_…`, `chatcmpl-…`) and wall-clock times (`created_at`, `completed_at`) nested inside events, where `compare.py` dropped them only at the top level, so those cases use a new `compare: "events"` mode that compares every data payload without them. Separately, a test that checks an exception's `cause` must walk the chain: kotlinx.coroutines' stack-trace recovery (on in JVM tests, where assertions are enabled) rethrows a copy of a `LlamaBroException` whose `cause` is the original.
- **Evidence:** contract on Qwen3.5 4B with the projector, 2026-11-05: 71/75 identical with the old comparison, the four differences only ids and timestamps (checked with a nested id- and timestamp-blind diff), then 75/75. Real answers: `infill` (Qwen3.5 has FIM tokens: "return a + b"), both token counts (267, 16), `health`, `models`, `lora_adapters` (`[]`), `lora_adapters_set` (`{"success":true}`); the same 501 `not_supported_error` from both for `embeddings_native` (no `--embeddings`), `tts`, `systemone` (not a decision model) and `props_set` (no `--props`). `HostNativeTest.everyEndpointReachesAHandler` sends all 27 `Endpoint` constants to the real core and checks that none gets the bridge's unknown-endpoint error; its control, a made-up name, does. Its first version checked `e.cause is NativeError` and passed for every route while its control failed: the cause was the recovered copy's original.
- **Means:** `Model.rawRequest` reaches everything llama-server serves for one model; transcriptions waits for a way to pass files (P4, with `Content.Audio`). `Endpoint` is a class with one constant per route, so P4 or an upgrade adds routes without a break (D26).
- **Feeds:** PA T6, design §6, P4 (audio).

## Errors (PA T5)

### F51. llama.cpp says why a model failed to load only in its log; the bridge now puts that line in the error
- **Finding:** `server_context::load_model` returns `false` and `llama_model_load` logs the reason (`LLAMA_LOG_ERROR "error loading model: …"`), so the C API's error was only "failed to load model: <path>". Upstream llama-server is no better: it prints the line and exits. The bridge now catches ERROR lines logged on the loading thread (a thread-local capture in its log trampoline, which `lb_runtime_init` now always installs and which forwards to llama.cpp's default logger when the app has none) and appends the first one. A model or mmproj file that can't be opened is checked before the load and gets `"code": "file_not_found"`. Other reasons (damaged file, unknown architecture, out of memory) stay `Other`, with upstream's text in the message: classifying them would key a table to upstream's log wording (decided 2026-11-05: keep T5 slim).
- **Evidence:** upstream llama-server b12052 on the host, one bad file each, stderr:

  | File | Upstream's first error line |
  |---|---|
  | missing | `gguf_init_from_file: failed to open GGUF file '/nonexistent.gguf' (No such file or directory)` |
  | text, not GGUF | `gguf_init_from_reader: invalid magic characters: 'hell', expected 'GGUF'` |
  | 3 bytes | `gguf_init_from_reader: failed to read magic` |
  | first 20 MB of Qwen3.5 4B | `llama_model_load: error loading model: tensor 'token_embd.weight' data is not within the file bounds, model is corrupted or incomplete` |
  | GGUF with `general.architecture = notanarch` | `llama_model_load: error loading model: unknown model architecture: 'notanarch'` |

  Out of memory couldn't be provoked on macOS, which overcommits (262144 context × 64 slots, f32 KV, `-fit off`, loaded); from source it reads `unable to allocate … buffer` (`src/llama-model.cpp:1972`) or `failed to allocate buffer for …` (`src/llama-memory.cpp:84`). Sources: `src/llama.cpp:374`, `tools/server/server-context.cpp:1311`. Tests: `lb_tests` `model_load_missing_file_fails`, `model_load_bad_file_has_reason`; `HostNativeTest.loadReasonFileNotFound`, `loadFailureCarriesUpstreamReason`.
- **Means:** `ModelLoadFailed.reason` is `FileNotFound` or `Other`; the message is what an app shows. More reasons can be added before 1.0 if the demo app shows that an app needs them.
- **Feeds:** PA T5, design §6.1.

### F52. A pre-encode still samples one token, unseeded, so the end-to-end test compared a random token
- **Finding:** tools/ui's pre-encode (`n_predict: 0`, no sampling fields, ported in T3) gets a response with one predicted token (`predicted_n: 1`), sampled with llama-server's random default seed. `HostNativeTest` compared that token between the SDK's core and upstream, so it failed whenever the two draws differed (here an end token vs " I"). It had passed since T3 by chance. The test now compares a pre-encode's cache counters and usage, not its `choices`. The SDK's behaviour is unchanged: the token is discarded, as in tools/ui.
- **Evidence:** `check.sh --device` on 2026-11-05: `e2e` failed at record #15 (`finish_reason` `stop` vs `length`, identical `cache_n` 267, `prompt_n` 112, `predicted_n` 1); then `--only e2e` failed once and passed once. After the change it passed three runs in a row. `HostNativeTest.normalize`.
- **Means:** A differential test compares only what's deterministic: requests without a seed are compared by counters.
- **Feeds:** PA T5 (test fix).

## Lifecycle and memory (PA T4)

### F50. A loaded model's weights are resident twice on the phone: the mapped file and a repacked copy
- **Finding:** With Qwen3.5 4B Q4_K_M (2.83 GB file) loaded at `n_ctx` 4096, the process's PSS is about 5.5 GB: 2.6 GB of the mapped GGUF and 2.9 GB of `[anon:libc_malloc]`. The malloc part is about the size of the weights, which fits the CPU backend's repacked weight buffers (`CPU_REPACK`, ARM): the repack reads every mapped page, and those file pages stay resident though the CPU no longer reads them. Not investigated further in PA (no change to loading in that phase).
- **Evidence:** `LifecycleDeviceTest` with a per-mapping PSS dump from `/proc/self/smaps` (removed after the diagnosis), three load/close cycles on the OnePlus CPH2649 (SM8750), 2026-11-04: loaded 5514–5561 MiB, `libc_malloc` 2909–2919 MiB, the GGUF mapping 2602–2615 MiB.
- **Means:** The mapped half is clean file cache the kernel can drop, but it counts in PSS, which is what Android's low-memory killer and memory dashboards weigh. P2's context fitting (design §8) and its memory budget should measure both halves; dropping the mapped pages of repacked tensors after the repack (`madvise(MADV_DONTNEED)`, or loading without mmap when everything is repacked) is a candidate for P2 or P5, A/B tested against load time.
- **Feeds:** P2 (context fit, memory budget), P5 (size and memory levers).
- **Resolved by F58** (2026-11-05): the SDK now loads with upstream's `--load-mode none`, so the mapped half is gone.

### F49. Closing a model returned none of its memory to the system until the bridge purged the allocator
- **Finding:** After `lb_model_free` the process kept about 2.9 GB of `[anon:libc_malloc]` (the freed repacked weights, F50, and KV cache): bionic's allocator keeps freed pages for reuse. It is not a leak (three load/close cycles stay flat at about 2.9 GB; the next load reuses them), but an app that closes a model to free memory got none back. `mallopt(M_PURGE, 0)` (API 28+) right after the free returns it: PSS after closing went from 2962 to 82 MiB (baseline before the first load 43 MiB).
- **Evidence:** `LifecycleDeviceTest.closeDuringGenerationFreesMemory` failed (baseline 43 MiB, loaded 4850 MiB, after close 2976 MiB, of which native heap 2937 MiB) and passes with the purge (baseline 54, loaded 5558, after 43 MiB); the cycle diagnosis is in F50's evidence. `bridge/src/model.cpp` `lb_model_free`. P1 behaved the same: its blocking `close()` ran the same free.
- **Means:** `lb_model_free` purges on Android, so the C API gives the memory back for every binding (Kotlin now; Flutter and KMP later). The purge runs on the closing thread, which since T4 is never the caller's (`Model.close()` hands it to the background). llama-server never needs this: it frees by exiting.
- **Feeds:** PA T4, design §3.2 (model lifecycle), §8.

### F48. Native init costs about 13 ms on the phone, so it moved off `getInstance`
- **Finding:** In a cold process, loading `libllamabro.so` takes 7.5–8.3 ms and `lb_runtime_init` (backend loading and the CPU variant scoring of F29) 4.5–5.4 ms; `lb_runtime_devices` 0.1 ms. That is over the plan's 2 ms bar for staying eager on the main thread, so `LlamaBro.getInstance` runs no native code: the first `loadModel` or `devices()` (now `suspend`) initialises the runtime on `Dispatchers.IO`, once, and a config conflict is reported from that call as `LlamaBroException.InvalidRequest` (was `IllegalStateException` from `getInstance`).
- **Evidence:** a one-off device test (`InitTimingDeviceTest`, not kept) run alone three times on the CPH2649 (SM8750), 2026-11-04: `loadLibrary` 8.26 / 7.46 / 7.84 ms, init 4.51 / 5.38 / 5.16 ms. JVM: `RuntimeTest.initRunsOnFirstUseNotOnCreation`, `configMismatchThrowsInvalidRequestOnFirstUse`.
- **Means:** Nothing on `LlamaBro` blocks the caller's thread. `Model.close()` doesn't either (1.8–2.0 ms on the main thread with StrictMode `detectAll` and no violations while a reply streams, `closeOnMainThreadDoesNotBlock`); the native shutdown runs in the background, `closeAndJoin()` waits for it (225–700 ms during generation, mostly the cancel noticing, F23), and `loadModel` waits for pending closes, so switching models peaks at one model's memory (`closeThenLoadNeverHoldsTwoModels`: peak 5545 MiB with A 5545 and B 4335).
- **Feeds:** PA T4, design §5, §6.

## API guardrails (PA execution)

### F47. Conversation options: which requests get them, and tools/ui's pre-encode sends no template switches
- **Finding:**
  - **tools/ui's pre-encode sends messages and tools only**, with `n_predict: 0` and `stream: false`: no `chat_template_kwargs`, no sampling (`tools/ui/src/lib/services/chat.service.ts` `preEncode`). The SDK keeps that whatever the conversation's defaults (test `preEncodeBodyFollowsToolsUi`). Adding prompt-shaping options there (`enable_thinking`) was considered and not taken: it would differ from upstream, so it needs an A/B test showing tokens saved on the next turn first (reference rule).
  - **Compaction gets the options that shape the prompt or the sampler, not the ones that constrain output.** The summary request carries the chat's tools, sampling, reasoning and `extra`, so it shares the chat's cached prefix as before (P1), but not `responseFormat`, `stop`, `toolChoice` or `parallelToolCalls`, which would force a free-text summary into JSON, cut it short or make it call a tool (`compactionIgnoresFormatAndStop`).
  - **Per-send options are the defaults with the send's block applied** (`defaults.copy(block)`): `sampling { }` and `reasoning { }` add to the defaults, an assigned property replaces. They apply to every request of that send's tool loop and to nothing after it; the history is unaffected, so the next request still extends the last one's messages (F5).
  - **`regenerate()`** drops everything after the last user message and sends again; its messages equal the original request's exactly, so the server's cache covers the whole prompt. It refuses when a compaction node follows that user message (the summary would no longer match).
  - **Saved format version 2** stores the defaults as body fields (the same writer as the request body), `extra` as written, `maxTurns` and `preEncodeAfterReply`; a restored conversation's next request is byte-identical without repeating any setting, only the tool handlers (`savedSettingsRestoreWithoutRepeatingThem`, every option type incl. `logitBias` bans and a 13-digit seed). Version 1 still loads; a version-2 file without settings is rejected.
  - **`reply`** is set on every content or reasoning delta and cleared only after the reply is in `messages` (success, cancel or error), so a UI showing both never drops or doubles the text.
- **Evidence:** `ConversationOptionsTest` (10 tests); `ApiEdgesTest.corruptSavedConversationThrowsIllegalArgument`; `chat.service.ts` `preEncode` (b12052); `Conversation.kt` `compactionOptions`, `regenerate`; `ConversationJson.kt`.
- **Means:** A new public `RequestOptions` holds every option except messages and tools; `ChatRequest.Builder` extends its builder, so `ChatRequest { }`, `defaults { }` and `send(...) { }` share one DSL. `Conversation.Builder.sampling`/`reasoning` are gone (`defaults { }` replaces them).
- **Feeds:** PA T3, design §6.1/§7, D8.

### F46. Request types as builders: P1's bodies unchanged, every upstream field classified, one deliberate change
- **Finding:**
  - **Bodies unchanged.** A golden of nine full request bodies, recorded from P1's code before the change and covering every P1 field, is reproduced byte for byte by the new builders (`bodiesMatchP1`). The one difference is deliberate: `extra` with a nested `chat_template_kwargs` now keeps `enable_thinking` (F43).
  - **Upstream's completion schema has 69 live fields** (70 `add` calls, one commented out: `t_max_prompt_ms`). Typed now: 31 in `Sampling` (P1's 11 plus 20: `top_n_sigma`, `xtc_*`, `typical_p`, `dynatemp_*`, `dry_*` incl. `dry_sequence_breakers`, `mirostat*`, `adaptive_*`, `min_keep`, `logit_bias`, `ignore_eos`, `backend_sampling`), plus `stop` and three reasoning fields (`reasoning_budget_message` is new). Owned by the SDK: `stream`, `stream_options`, `return_progress`. The other 31 are reachable only through `extra`, each with its reason in `everySchemaSamplingFieldMapped` (logprobs and LoRA are P4, speculative settings are per model, template-set grammar fields, and so on). A new upstream field fails that test on an upgrade.
  - **`logit_bias` follows upstream's object form:** keys are token ids or text (the server tokenizes text), `-infinity` is sent as `false`, which llama-server reads as a ban (`server-schema.cpp` `parse_bias`); NaN and `+infinity` are rejected.
  - **Private constructors leak into the API dump.** A `private constructor` called from a nested `Builder` makes the compiler emit a public synthetic accessor constructor, which BCV lists; `internal constructor` doesn't. The remaining six synthetic constructors are default arguments on kept value types (`Message.Assistant`, `Message.Compaction`, `Content.Image`, `ResponseFormat.JsonSchema`), on `LlamaBroException` (T5) and `Speculative.Mtp` (P2 T8).
  - **The bridge passes the new fields unchanged:** five contract cases (`x-38` to `x-42`: DRY + XTC, Mirostat 2, typical-p + dynamic temperature + top-n-sigma + min-keep, adaptive-p, logit bias + ignore EOS), each seeded so the sampler matters, give identical output on the C API and upstream `llama-server`: contract Qwen3.5 60/60, Llama 3.2 3B 59/59 (was 55/54). `requests.jsonl` is generated by `make-requests.py` and committed; it is regenerated after editing the cases.
- **Evidence:** `sdk/llamabro/src/test/resources/p1-bodies.json` (recorded by a one-off test at P1's code, then deleted); `RequestTypesTest`; `sdk/llamabro/api/llamabro.api` diff; `server-schema.cpp:13-485, 401-440`; `sdk/native/tests/contract/make-requests.py` `x-38`..`x-42`.
- **Means:** `ChatRequest`, `Sampling`, `Reasoning`, `LlamaBroConfig` are built with `X { }` and changed with `copy { }`; `Timings`, `Usage`, `ChatEvent.Done`, `ModelInfo`, `DeviceInfo` are classes with internal constructors; `ModelConfig` is the `loadModel { }` scope itself. Public classes use `internal constructor`, not `private`, so the dump shows only real API (D26).
- **Feeds:** D26, PA T2, PA T5, P2 T8.

### F45. Neither ABI tool sets itself up for an AGP 9 library; BCV's tasks, wired by hand, do the job
- **Finding:**
  - **KGP's own ABI validation** (2.3.10) supports `kotlin-android` projects, but with AGP 9's built-in Kotlin the `kotlin` extension never gets `abiValidation`, before or after evaluation (`Unresolved reference 'abiValidation'`; runtime probe: extensions `[ext, sourceSets]`). AGP 9 applies `com.android.library` only, not `org.jetbrains.kotlin.android`.
  - **The Binary Compatibility Validator plugin** (0.18.1) also configures itself only on `org.jetbrains.kotlin.android`, `.jvm` or `.multiplatform`, so applying it adds no tasks.
  - **Its task classes work:** `KotlinApiBuildTask` fed the release variant's class directories through AGP's variant API (`ScopedArtifacts.Scope.PROJECT`, `ScopedArtifact.CLASSES`), with the worker classpath BCV itself would resolve (BCV, asm 9.6, asm-tree 9.6, `kotlin-metadata-jvm`), plus `KotlinApiCompareTask`. The dump has 72 public classes, no `internal` declarations, no R classes; it shows the default-argument constructors F37 describes (`synthetic <init>(…DefaultConstructorMarker)` on `ChatRequest`, `Sampling` and 8 more).
  - **The check can fail:** adding `public fun mutationProbe()` fails `apiCheck` with `+ public static final fun mutationProbe ()I`.
  - **Lint:** the library's release lint had 0 errors and 1 warning, `ChromeOsAbiSupport` (arm64 only is by design, §9), now disabled with that reason; every other warning is an error.
- **Evidence:** `sdk/llamabro/build.gradle.kts` (apiBuild, apiCheck, apiDump, `lint { }`); `sdk/llamabro/api/llamabro.api`; KGP 2.3.10 `AbiValidationActionKt` (looks up `KotlinAndroidProjectExtension` from the `kotlin-android` plugin); BCV 0.18.1 `BinaryCompatibilityValidatorPluginKt` (plugin ids `org.jetbrains.kotlin.android/jvm/multiplatform`).
- **Means:** `check.sh` gains `api` and `lint` steps. A reviewed API change is `./gradlew -p sdk :llamabro:apiDump`, committed with the code. When KGP's validation supports AGP's built-in Kotlin, switch to it and drop the hand wiring. Each later module (`llamabro-testing`, the backend AARs, the hub) gets the same tasks.
- **Feeds:** D26, PA T1, P5 (CI runs `check.sh`).

## Kotlin API review (2026-11-04, read from P1's code)

A review of the public Kotlin API before P2 added to it, each claim checked against the code (paths below are under `sdk/llamabro/src/main/kotlin/com/suhel/llamabro/`). Verdict: the engine core is sound; the public API needed a revision pass before anything was built on it (D25, the PA plan).

### F44. Quality tooling a public library is expected to have is missing
- **Finding:** There is no CI, no API compatibility check, no lint or static analysis (Android lint, detekt/ktlint, clang-tidy), no sanitizer run, and no generated API docs. `explicitApi()` is on and R8 rules ship (F28), but nothing stops an accidental public-API break.
- **Evidence:** no `.github/`; `sdk/llamabro/build.gradle.kts` has `explicitApi()` (line 53) and no ABI or lint configuration; `sdk/scripts/check.sh` has no api, lint or sanitizer step; design §12 lists ABI validation, HWASan and Dokka, none of them run yet.
- **Means:** The API check and lint come in PA (they guard the revision itself); CI, HWASan and Dokka stay in P5 (design §12).
- **Feeds:** D26, PA T1, P5.

### F43. `extraJson` is merged one level deep, so it can silently undo typed fields
- **Finding:** `extraJson` replaces whole top-level keys. `{"chat_template_kwargs": {...}}` drops `Reasoning.enableThinking`, which the SDK sends inside that object; `{"stream": false}` turns the always-streaming `Model.chat` into a single non-streamed body that the stream decoder doesn't expect.
- **Evidence:** `internal/ChatJson.kt:52` (`enable_thinking` inside `chat_template_kwargs`), `:54-56` (`stream`, `stream_options`), `:58-60` (`JsonObject(base + extra)`).
- **Means:** Merge extra JSON as a JSON Merge Patch (RFC 7396: objects merge recursively, `null` removes a key), and reject `stream` and `stream_options` in it, since the typed calls own them.
- **Feeds:** PA T2.

### F42. Several public types can't grow without breaking callers, and some names clash
- **Finding:**
  - `Endpoint` is an enum of the 14 routes the C API maps. Upstream b12052 serves more: `/infill`, `/lora-adapters`, `/v1/responses` (+ `input_tokens`), `/v1/messages` (+ `count_tokens`), `/tts`, `/v1/audio/transcriptions`, `/models`, `/health`, `POST /props`, `/v1/systemone`, the non-OpenAI `POST /embeddings` (the bridge maps only the OpenAI handler), and the WebSocket `/v1/realtime`. So "any llama-server endpoint" (design §6.1) isn't true yet, at the C API level either, and adding a route later changes a public enum.
  - `ChatEvent` (sealed) and `FinishReason` (enum) are matched with exhaustive `when` by design (§6.1 example), so each new event or reason is a source break and a runtime `NoWhenBranchMatchedException` for compiled callers.
  - `ChatEvent.Content`, `ChatEvent.Reasoning` and `ChatEvent.ToolCall` share names with `chat.Content`, `chat.Reasoning` and `chat.ToolCall`, so a file can't import both; `Message.System` shadows `java.lang.System` when imported.
- **Evidence:** `Endpoint.kt:4-19`; bridge endpoint names in `sdk/native/bridge/src/*.cpp`; upstream routes `tools/server/server.cpp:170-214`; bridge table `sdk/native/bridge/src/model.cpp:24-37`; `chat/ChatEvent.kt:4-19`; `chat/Content.kt`, `chat/Reasoning.kt`, `chat/Message.kt:5`.
- **Means:** Complete the route table in the C API and make `Endpoint` a class with constants; settle the event and finish-reason sets before 1.0 and treat later additions as a major change; rename the clashing events.
- **Feeds:** PA T6, design §14 Q1.

### F41. Errors lose their cause and some outcomes come back as generic or silent results
- **Finding:**
  - `LlamaBroException` takes only a message, so the native or I/O cause is lost; `ModelLoadFailed` can't tell a missing file from an unsupported model or out-of-memory.
  - `Conversation.ask()` throws a plain `IllegalStateException` on `TurnLimit`/`ToolCallsPending`, and on `Length` returns the cut-off text as if it were complete.
  - A second `send`/`resume` while one runs throws `IllegalStateException`, eagerly at the call, not when the flow is collected (`checkIdle()` before `exclusive { }`).
  - `ContextExceeded` reports `-1` when the server omits a count; `canCompact` (tools/ui's check) is internal, so an app can't grey out a Compact button.
- **Evidence:** `LlamaBroException.kt:4-18`; `conversation/Conversation.kt:64-87, 214-219`; `internal/ErrorMapping.kt:26-27`; `conversation/Compaction.kt:89-90`; `internal/LlamaBroRuntime.kt:83-85`.
- **Means:** Every SDK exception carries a `cause`; `ModelLoadFailed` gets a reason; `ask()` throws typed exceptions for every non-answer, `Length` included; busy is a typed error raised on collection, with an `isBusy` state; unknown counts are null; `canCompact` is public.
- **Feeds:** PA T5.

### F40. Lifecycle: `close()` blocks, load progress is dropped, the sample ties the model to an Activity
- **Finding:**
  - `Model.close()` cancels requests and joins the worker thread; it is `@WorkerThread`, and a streaming request can take up to about 1 s to notice the cancel (F23). `AutoCloseable.use { }` and `onDestroy` callers will block the main thread.
  - The native load reports progress, but `LlamaBroRuntime.load` passes an empty callback, so a multi-second load shows no progress.
  - `LlamaBro.getInstance` runs native init (the CPU variant scoring of F29) on the calling thread, usually the main thread.
  - The sample loads the model in `MainActivity` and closes it on a new `Thread`, so a rotation reloads it. A sample is what developers copy.
- **Evidence:** `Model.kt:72-88`; `internal/LlamaBroRuntime.kt:34-45, 82`; `LlamaBro.kt:36-43`; `sdk/samples/minimal/src/main/kotlin/com/suhel/llamabro/sample/MainActivity.kt:30, 65, 106`.
- **Means:** `close()` returns at once and finishes in the background, with `suspend fun closeAndJoin()` for callers that must wait; `loadModel` takes a progress callback; native init moves off the caller's thread; the sample holds the model in a `ViewModel`.
- **Feeds:** PA T4, PD.

### F39. An app can't fake the SDK in its own tests
- **Finding:** `Model`, `Conversation` and `LlamaBro` have internal constructors and are final; `chat`, `conversation` and `restoreConversation` are extension functions; `JsonArgs` has an internal constructor. An app's unit test can't create any of them, so testing a ViewModel that uses the SDK needs a phone and a model. The SDK's own tests already have the right seam: the internal `Engine` interface with a `FakeEngine` that speaks llama-server's SSE.
- **Evidence:** `Model.kt:30, 212, 232, 245`; `LlamaBro.kt:10`; `conversation/Conversation.kt:43`; `conversation/JsonArgs.kt:16`; `internal/Engine.kt`; the JVM tests' `FakeEngine`.
- **Means:** Ship a `llamabro-testing` artifact that drives the real `Model` and `Conversation` code from a scripted fake engine (D27), rather than turning the public classes into interfaces apps could implement.
- **Feeds:** D27, PA T7.

### F38. `Conversation`, the beginner path, can't reach half of what a chat request can do
- **Finding:** `Conversation` builds every request from `sampling`, `reasoning` and its tools only. `responseFormat`, `stop`, `toolChoice`, `parallelToolCalls` and `extraJson` can't be set, and nothing can change for a single `send` (say, a JSON answer for one turn). An app that outgrows these must drop to `Model.chat` and rebuild the history, tool loop and compaction itself.
- **Evidence:** `conversation/Conversation.kt:104-126` (Builder), `:240-245` (`request()` sets four fields); `chat/ChatRequest.kt:9-19`.
- **Means:** `Conversation` takes the same request options as `Model.chat`, as defaults in its builder plus per-send overrides.
- **Feeds:** PA T3.

### F37. The request types are public data classes, so every new field breaks binary compatibility
- **Finding:** `ChatRequest`, `Sampling`, `Reasoning`, `Timings`, `Usage`, `ContextUsage`, `DeviceInfo` and the event classes are `data class`es (the request types with constructor defaults). Adding a property changes the constructor, `copy()` and `componentN()` signatures, so code compiled against the old version fails at run time. They will grow: `Sampling` has 11 fields, while llama-server's completion schema has 70 request fields, about 30 of them sampling (`typical_p`, `dynatemp_*`, `xtc_*`, `dry_*`, `mirostat*`, `top_n_sigma`, `adaptive_*`, `n_probs`, `min_keep`, `logit_bias`, `ignore_eos`, …). No check would catch the break (F44).
- **Evidence:** `chat/ChatRequest.kt:9`, `chat/Sampling.kt:11-23`; upstream `tools/server/server-schema.cpp:13-485` (`make_llama_cmpl_schema`, 70 `add(new field_*)` calls).
- **Means:** Types that will grow become classes built through a builder or DSL, without `copy`/`componentN`; small value types that won't grow may stay data classes. The API dump is checked from PA on.
- **Feeds:** D26, PA T1/T2.

## Verification

### F31. Every layer is now checked against upstream in one command, and the first full run is green
- **Finding:** `sdk/scripts/check.sh` runs eight checks; each compares with upstream where upstream exists. First full run, 2026-11-04:

  | Check | Against | Result |
  |---|---|---|
  | build | the patched copy, submodule untouched | pass |
  | native | host C API tests | 21/21 |
  | contract | upstream `llama-server`, same build and flags; 57 requests (T2 set plus sampling, reasoning, tools, formats, non-streamed, completions, endpoints, `/props`, `/metrics` names, three cache turns with `cache_n`/`prompt_n` compared, a cancel and the request after it, errors) | Qwen3.5 55/55, Llama 3.2 3B 54/54 (second chat template; image requests left out) |
  | tools-ui | llama.cpp's own `compaction.ts`, run in node with no build, 400 seeded random message lists | golden unchanged; Kotlin replay 400/400 |
  | jvm | Kotlin unit tests | 83/83 |
  | e2e | Kotlin + JNI + native on the Mac, each request replayed to upstream `llama-server` in the same order (typed chat, image, raw endpoints, an error, an agent conversation with tools, compaction, a send after it) | 17/17 identical, cache counters included |
  | aar | names, exports, 16 KB alignment | pass |
  | device (`--device`) | phone: native 3/3, UI test, acceptance on three models, the minified build loading a model | pass (Llama 3.2 3B's tool loop is the known F27 failure) |

  - **`/props` is identical** apart from `media_marker`, a random per-process value, so the config the bridge builds matches llama-server's flags field for field.
  - **Fixed along the way (test side only):** tool-call ids are random in non-streamed bodies too, so they're ignored there as in streams; a request with an unknown role isn't an error (llama-server accepts it and samples with its defaults), so such cases now fix the seed.
  - **The checks can fail:** the golden test fails at case 0 when the port treats an empty compaction node as a boundary (mutation run).
- **Evidence:** the `sdk/native/build/check/*.log` files from the run; `sdk/native/tests/contract/make-requests.py`; `sdk/scripts/tools-ui/`; `HostNativeTest`, `CompactionGoldenTest`.
- **Means:** Every SDK change is verified this way (the project's working rules). On a llama.cpp upgrade, a contract difference or a changed tools/ui golden is the list of upstream behaviour to review and port. Not covered yet: embeddings and rerank (they need `--embeddings`/`--reranking` and config keys the C API doesn't have; P4), `tools/ui`'s message shaping (its code lives in Svelte stores that don't run outside the app; covered by the port, the contract and the end-to-end test instead), and CI (P5).
- **Feeds:** design §12, P2–P5 working method, the project's working rules.

## Backends (P2 planning, read from upstream b12052)

### F35. ggml's own backend scan runs unless a backend is already loaded, and it obeys `GGML_BACKEND_PATH`
- **Finding:**
  - `llama_backend_init()` calls `ggml_backend_load_all()` when no backend is registered. That scans the executable's directory and the current directory for `libggml-<name>*.so` and loads the library named by the environment variable `GGML_BACKEND_PATH`. P1's bridge called `llama_backend_init()` before loading its own CPU backend, so the scan always ran: setting `GGML_BACKEND_PATH` added a second CPU device to the SDK (host test `backend_path_env_ignored`: RED with 2 CPU devices).
  - Four option handlers in `common/arg.cpp` run the scan **every time**, whatever is loaded: `--device` (`parse_device_list`), `--override-tensor`, `--rpc` and `--list-devices`. P1's config uses none of them.
  - Repeating init after a failure is safe: upstream registers a backend once (`register_backend` skips a known `reg`), `start_gc` returns when the GC thread runs, and `common_init` only sets log options (host test `runtime_init_retry_after_failure`, green before and after the fix). The retry bug suspected in the P1 follow-up does not exist.
- **Evidence:** `src/llama.cpp:122-135`, `:102-115`; `ggml/src/ggml-backend-reg.cpp:185-205, 486-510, 567-598`; `common/arg.cpp:229, 1115, 1132, 1158`; `tools/server/server-stream.cpp:317-322`; `common/common.cpp:406-416`.
- **Means:** `lb_runtime_init` loads the CPU backend before `llama_backend_init()`, so the scan never runs and backends come only from the SDK's list (`backend_path_env_ignored` GREEN). P2 must not use the `--device` handler: it sets `common_params.devices` itself from `ggml_backend_dev_by_name` (P2 T6), and no other config key may map to the four handlers above.
- **Feeds:** design §9, D24, P2 T2/T6.

### F34. What each backend needs to build: Hexagon only in Qualcomm's Linux image, Vulkan needs headers the NDK lacks
- **Finding:**
  - **Hexagon.** The ARM-side `libggml-hexagon.so` needs the Hexagon SDK too, not just the skeletons: `build_idl(htp/htp_iface.idl)` runs the SDK's `qaic` to generate `htp_iface_stub.c`, and the stub includes `${HEXAGON_SDK_ROOT}/incs`. The skeletons `libggml-htp-v73/v75/v79/v81/v85.so` are `ExternalProject`s built with the Hexagon toolchain. Both exist only in the Linux image `snapdragon-toolchain/arm64-android:v0.8`, whose NDK was believed to be r28b; the SDK's core is built with NDK 29, and all ARM libraries in one process share one `libc++_shared.so`, so the image's build would need NDK 29 added. **Corrected by F62:** the v0.8 image's NDK is 29.0.14206865, the SDK's own; r28b was an older line in upstream's README.
  - **Skeletons are unsigned.** `GGML_HEXAGON_HTP_CERT` is empty by default, and the backend always asks FastRPC for an unsigned protection domain (`DSPRPC_CONTROL_UNSIGNED_MODULE`), then opens `file:///libggml-htp-v<arch>.so` found along `ADSP_LIBRARY_PATH`. If the phone refuses unsigned modules for an app, there is no workaround short of signing.
  - **OpenCL** needs `find_package(OpenCL)` (headers plus a loader to link); the Snapdragon image has an OpenCL SDK.
  - **Vulkan** needs `vulkan/vulkan.hpp` (`ggml-vulkan-types.h:26`) and `SPIRV-Headers` (`find_package`, `ggml-vulkan/CMakeLists.txt:14`). NDK 29 ships `glslc` and `vulkan_core.h` but neither of those.
- **Evidence:** `ggml/src/ggml-hexagon/CMakeLists.txt:1-100`, `ggml-hexagon.cpp:2104-2120`; `ggml/src/ggml-opencl/CMakeLists.txt:1`; `ggml/src/ggml-vulkan/CMakeLists.txt:9,14`; `docs/backend/snapdragon/README.md:52` (NDK r28b in the image); the NDK's `sysroot/usr/include/vulkan` and `shader-tools/` directories (NDK 29.0.14206865).
- **Means:** Hexagon and OpenCL are built in the Docker image with NDK 29 as the toolchain; Vulkan is built with the normal NDK plus pinned Vulkan-Headers and SPIRV-Headers. Whether an app may use an unsigned skeleton is P2's first test.
- **Feeds:** design §9, D20, P2 T1, T3-T5, P5 CI.

### F33. Upstream `--fit` isn't a RAM guard on a phone: ggml-cpu reports all RAM as free
- **Finding:**
  - `--fit` is on by default (`common_params::fit_params = true`). With `n_ctx = 0` it starts from the training context and lowers it, not below `fit_params_min_ctx` (4096), until projected use leaves `fit_params_target` (1 GiB) free per device (`common/fit.cpp`, step 2).
  - **CPU only:** it compares projected use with **total** RAM: ggml-cpu reports `free = total` from `sysconf` ("free system memory is ill-defined", `ggml-cpu.cpp:377`). On the 15.5 GB OnePlus 13 that allows about 14.5 GB.
  - **With a GPU-type device,** it counts only that device's memory and "assumes system memory is unlimited" (`fit.h`). Hexagon reports `opt_mbuf_total` (0 unless `GGML_HEXAGON_MBUF` is set; a device reporting 0/0 of GPU type is skipped by fit with a warning). OpenCL reports `global_mem_size` minus a 1 GiB margin.
  - The RAM cache (`cache_ram_mib`, D17) isn't part of fit's projection.
  - `common_measure_memory` / `common_fit_measure` give the memory at the smallest and largest context without allocating, which PD's recommendations could use.
- **Evidence:** `common/common.h:485-490`, `common/common.cpp:1270-1305` (`common_fit_apply`), `common/fit.cpp:111-140, 395-500`, `common/fit.h`; `ggml/src/ggml-cpu/ggml-cpu.cpp:365-379`; `ggml-hexagon.cpp:2951-2956`; `ggml-opencl.cpp:17164-17175`.
- **Means:** D22: keep upstream's fit, and give it the phone's real budget through `--fit-target`, computed from `ActivityManager.MemoryInfo`. P1's `min(8192, n_ctx_train)` goes away. The reserve is measured in P2 T7.
- **Feeds:** D7, D22, design §8, P2 T7, PD (recommendations).

### F32. llama.cpp uses every GPU-type device by default, and both Hexagon and OpenCL are GPU-type
- **Finding:** Without `--device`, `llama_model_load` adds every device of type `GPU` (deduplicated only by `device_id`), and integrated GPUs only from one backend. The Hexagon backend (`HTP0`...) and the OpenCL backend both report `GGML_BACKEND_DEVICE_TYPE_GPU`. So with both AARs present, llama-server's default would split one model's layers across the NPU and the Adreno GPU.
- **Evidence:** `src/llama.cpp:221-268`; `ggml-hexagon.cpp:2958-2959`; `ggml-opencl.cpp:17176-17177`.
- **Means:** The SDK always passes `--device` (and `--device none` for CPU) once any non-CPU backend is registered (D24, Proposed). `DevicePolicy` chooses; `loadReport` names the device.
- **Feeds:** design §9 `DevicePolicy`, D24, P2 T6.

## SDK build and C API (P1)

### F30. On Android 16, ART's GetStringUTFChars already returns real UTF-8
- **Finding:** A model path holding CJK and an emoji (`模型😀.gguf`) loaded through the old JNI code, which read strings with `GetStringUTFChars`. The JNI specification only promises modified UTF-8, which encodes characters outside the BMP differently, so the P1 review expected this to fail; on this phone it does not.
- **Evidence:** `NativeBridgeDeviceTest.loadsFromNonBmpPath` passed before the fix (OnePlus 13, Android 16, F20).
- **Means:** The JNI layer now converts with `String.getBytes("UTF-8")`, which the specification covers on every Android version down to minSdk 29; the test stays as a guard. Nothing was observed failing, so this is a robustness change, not a bug fix.
- **Feeds:** P1 cleanup, `sdk/native/jni/lb_jni.cpp`.

### F29. The CPU backend loads by name from the APK: no extraction, our own library names, private symbols
- **Finding:**
  - ggml's directory scan is only `ggml_backend_load_best`; `ggml_backend_load(path)` is a plain `dlopen`, and Android can `dlopen` a library by name straight from an uncompressed APK. The bridge now opens each CPU variant by file name, asks its `ggml_backend_score`, and loads the best (a port of `ggml_backend_load_best`, `ggml/src/ggml-backend-reg.cpp`). CMake passes the file names in. No patch.
  - The test APK and the sample run with no extracted `lib/` folder; the crash dump shows `libllamabro.so` mapped from `base.apk!`. `useLegacyPackaging` is gone from the library and the sample, and apps need no setting.
  - Android builds give the SDK's libraries a `llamabro-` prefix (`OUTPUT_NAME`): `libllamabro-llama.so`, `libllamabro-ggml-base.so`, `libllamabro-ggml-cpu-android_*.so` and so on, so an app that also ships another llama.cpp build gets neither a duplicate-file error nor the wrong library. Host builds keep upstream's names, so the contract test's `llama-server` finds its backends as upstream does (it scans for `libggml-cpu-*`). The NDK's runtimes keep their standard names (`libc++_shared.so`, `libomp.so`): `libggml-base` and every CPU variant use OpenMP, so linking it statically would put several OpenMP runtimes in one process.
  - A linker version script makes `libllamabro.so` export only `lb_*` and `JNI_OnLoad` (it exported 1,140 more symbols, from the statically linked server-context). It shrank from 1,596,824 to 1,375,960 bytes. `check-aar.sh` now checks the names and the exports.
- **Evidence:** `sdk/native/CMakeLists.txt`, `bridge/src/runtime.cpp` (`load_cpu_backend`), `bridge/exports.map`; `check-aar.sh` RED (unprefixed libraries, 1,140 exports) then OK; `NativeBridgeDeviceTest` RED without `backend_dir` (no CPU backend) then 3/3; contract 20/20 identical after the change.
- **Means:** Supersedes F25's extraction requirement. P2's GPU and NPU backends load the same way (by name). The Hexagon NPU skeleton is different: the DSP loader reads it from a file through `ADSP_LIBRARY_PATH`, which is why an earlier prototype extracted all its libraries; design §9 ships the skeleton as an asset and copies it to `noBackupFilesDir`.
- **Feeds:** D4, design §9, P2, P5 (size budget, packaging docs).

### F28. An app's R8 renamed the classes JNI_OnLoad looks up: the SDK ships consumer keep rules
- **Finding:** A minified release build of the sample aborted in `JNI_OnLoad`: R8 renamed `NativeBridge`, `RequestSink`, `NativeError` and the listener interfaces, which the JNI layer finds by name. The AAR now ships `consumer-rules.pro` keeping them, and `JNI_OnLoad` checks every lookup and returns `JNI_ERR` (so `System.loadLibrary` throws `UnsatisfiedLinkError` with a log line) instead of aborting on a null class. With the rules, the minified sample loads Llama 3.2 3B and streams a reply (62.9 t/s prefill, 17.9 t/s decode on 6 tokens).
- **Evidence:** logcat of the minified sample before (abort at `JNI_OnLoad+460`) and after; `sdk/llamabro/consumer-rules.pro`; `sdk/samples/minimal` release build type (minified, debug-signed).
- **Means:** The sample's release build is the regression check for keep rules. Any class the native code looks up by name must be listed in `consumer-rules.pro`.
- **Feeds:** P1 cleanup, P5 (publishing).

### F27. P1 device acceptance: Qwen3.5 passes everything; Gemma 3 has no tools; Llama 3.2 3B loops on tool calls
- **Finding:** `AcceptanceTest` (P1 T12) on the OnePlus 13 (F20), CPU, through the public API only:

  | Model | streamsReply | runsToolLoop | stopIsFast | exceedMapsTyped | Load | Decode t/s (short replies) |
  |---|---|---|---|---|---|---|
  | Qwen3.5-4B Q4_K_M | pass | pass (`get_weather {"city":"Paris"}`, then a correct answer) | pass | pass (1,023 > 512) | 6.9 s | 11.7-13.1 |
  | gemma-3-4b-it-qat UD-Q4_K_XL | pass | **skipped**: template `supports_tool_calls=false` | pass | pass (1,016 > 512) | 5.8 s | 13.4-16.3 |
  | Llama-3.2-3B-Instruct Q4_K_M | pass | **fail**: calls `get_weather` again after every result, until `Done(TurnLimit)` at 100 turns | pass | pass (1,041 > 512) | 6.0 s | 7.3-18.1 |

  - Prefill t/s from these replies (8-60) isn't meaningful: the prompts are 21-42 tokens. Throughput belongs to the P2 benchmarks.
  - **Gemma 3:** llama-server leaves the tools out of the prompt for this template (prompt 32 tokens; the model invents a weather report). Its template also raises "Conversation roles must alternate" for two user messages in a row.
  - **Llama 3.2 3B:** replaying the second request (with the tool result) against host `llama-server` built from the same tree gives the same repeated call, whether the assistant message has no `content`, `""` or `null`. So it is the model or its template at temperature 0, not the SDK.
  - **Stop:** the flow ends 0-1 ms after cancel. The next reply then takes 52-70 s, because the cancelled 3,000-token prompt stays in the history (as in tools/ui) and is prefilled with the new message.
  - **Bug found and fixed on the way:** a stop before any token kept no assistant message, so Gemma's template rejected the next request. tools/ui keeps the empty assistant message it created before streaming; `Conversation` now does too (`ConversationTest.stopBeforeAnyTokenKeepsEmptyAssistant`).
- **Evidence:** `sdk/samples/minimal/src/androidTest/.../AcceptanceTest.kt`; its log (`files/acceptance.log` in the sample app), 2026-11-04; the host replay with `llama-server` on the same GGUF.
- **Means:** P1's done-when holds for Qwen3.5 on all four checks and for Gemma and Llama on chat, stop and the context error. Tool use needs a template with tool support (Gemma 3 has none) and a model that stops calling (Llama 3.2 3B does not at temperature 0). Open question: whether the acceptance test should use the model card's sampling for Llama, or count this as a model limit. The 52-70 s follow-up after a stopped long prompt is worth showing in PD's UI design.
- **Feeds:** design §13 P1 done-when, P1 T12, PD (stop UX, capability-aware tools).

### F26. tools/ui keeps the compaction node at the end of the history, with a kept-step count
- **Finding:** `tools/ui` appends the compaction node after the steps it preserves and stores `kept: preserve` on it (`stores/chat/index.svelte.ts:1709`). `sliceAtLastCompaction` then builds what the model reads: the system prefix, the node framed as a user message, the last `kept` steps before the node, and everything after it (`utils/compaction.ts`). The framed copy keeps the compaction type, so it never counts as a step. Five of the upstream unit cases (`compactionTokensBefore`) read per-message timings, which only matter for tools/ui's context gauge.
- **Evidence:** `tools/ui/src/lib/utils/compaction.ts`, `tests/unit/compaction.test.ts`, `stores/chat/index.svelte.ts:1600-1800` (summary request, rollback on finish reason `length`, pre-encode after compaction when all slots are idle).
- **Means:** Decided (2026-11-04): `Message.Compaction(summary, keptSteps)` is appended at the end exactly like tools/ui (an earlier plan placed it "at the cut", which gives the same requests but could not run the upstream cases unchanged). 17 upstream cases are ported one for one in `CompactionTest`. The 5 `compactionTokensBefore` cases are not ported: SDK messages carry no timings, and `Conversation.contextUsage` covers the current size.
- **Feeds:** design §7.2, P1 T11, PD (a context gauge would need per-message timings).

### F25. Android packaging: backends must be named, extracted, and the CPU AAR is 21.6 MB of native code
> **Partly superseded by F29** (2026-11-04): the backends no longer need extracting, and the library names and sizes changed. The point about naming the backend targets for AGP still holds.

- **Finding:**
  - **AGP packages only the CMake targets it is told to build and what they link.** The ggml CPU backends are loaded at runtime (`GGML_BACKEND_DL`), not linked, so they were missing from the AAR until the seven `ggml-cpu-android_*` targets were listed in `externalNativeBuild.cmake.targets`.
  - **The backends must be extracted to disk.** ggml finds them by listing `backend_dir` (`nativeLibraryDir`), which is empty when AGP stores `.so` files uncompressed inside the APK (the default). The library sets `packaging.jniLibs.useLegacyPackaging = true`, which covers its own test APK. **An app using the SDK needs the same setting**, or the CPU backend will not load.
  - **Sizes (release AAR, stripped):** `libllamabro.so` 1,596,824 bytes; all native libraries 21,641,712 bytes: 7 CPU variants at 0.93-0.99 MB each, `libllama-common` 4.58 MB, `libllama` 3.84 MB, `libmtmd` 1.62 MB, `libc++_shared` 1.37 MB, `libomp` 0.97 MB, `libggml-base` 0.77 MB. Every `.so` has 16 KB LOAD alignment (`check-aar.sh` OK; the check was shown to fail on a 4 KB-aligned and on a missing library).
  - **On the phone** (OnePlus 13, F20): load and an 8-token chat pass; cancel 200 ms into a 3,000-token prefill reaches the terminal event in 851 ms, which is F23's 1-second poll.
- **Evidence:** `sdk/llamabro/build.gradle.kts`; `sdk/scripts/check-aar.sh`; `NativeBridgeDeviceTest` (P1 T6); `ggml/src/ggml-backend-reg.cpp:510` (directory scan in `ggml_backend_load_all_from_path`).
- **Means:** P1 T12's sample app sets `useLegacyPackaging = true`. Before P5, decide whether the SDK can avoid the extraction requirement (for example by loading each backend by name, which Android can open straight from the APK) or must document it. The size budget for P5 starts from these numbers.
- **Feeds:** D4, design §9, P1 T6, T12, P5 (size budget, docs).

### F24. The C API matches upstream llama-server byte for byte on the host; build info needed a fix
- **Finding:**
  - The contract test (`sdk/native/tests/contract/run.sh`: the spike's 20-request T2 set, through host `llama-server` and through the C API, same tree, same flags) gives **20/20 identical**, all status 200, 1,174 chunks on each side. This repeats F1 for the SDK's own bridge.
  - llama.cpp's `cmake/build-info.cmake` asks `git` in the source directory. The patched copy isn't a repo, so git answered for the enclosing repository, and `/props` `build_info` and every `system_fingerprint` said `b34-e677346` instead of `b12052-84d9ce0`. The contract test cannot catch this: both sides come from the same build. `sdk/native/CMakeLists.txt` now points `GIT_EXECUTABLE` at a generated stand-in that answers with the submodule commit (from `.lb-stamp`) and the build number.
- **Evidence:** the contract test's `run.sh` output (20/20 identical); host test `props_build_info_names_llama_build` (RED `b34-e677346`, GREEN `b12052-84d9ce0`); `cmake/build-info.cmake:19-40`.
- **Means:** The per-upgrade check (design §12) is in place and passing. Any build of a llama.cpp copy outside its own repo must provide build info this way.
- **Feeds:** D12, design §12, P1 T5, P5 CI.

### F23. A streaming request notices cancel at its next result or at the 1-second poll
- **Finding:** While a stream waits for the server, `server_response_reader::next` blocks for up to `HTTP_POLLING_SECONDS` (1 s) and checks `should_stop` only between waits. During prefill, a result arrives only at the end of each `n_batch` pass (and only with `return_progress`). So the bridge sees the cancel after `min(1 s poll, rest of the current pass)`; the server then stops at the end of that pass (F9).
  - Host CPU, Qwen3.5-4B Q4_K_M, no Metal or BLAS: prefill 109 t/s (1,513 tokens in 13.8 s), so one 512-token pass takes about 4.7 s. Cancel 200 ms into a 3,000-token prompt reaches the terminal event in 800-810 ms with or without `return_progress`, and with `n_batch` 128 too: the 1-second poll always fires first.
  - Cancel during decode reaches the terminal event in 39 ms (a token result arrives every few tens of ms).
- **Evidence:** `tools/server/server-queue.cpp:450-475` (`recv_with_timeout`), `:551-558` (`next` checks `should_stop` on timeout); `server-context.cpp:46` (`HTTP_POLLING_SECONDS = 1`), `:4331`, `:4821` (progress sent at slot start and after each sub-batch); host tests `cancel_during_prefill`, `cancel_during_prefill_with_progress` (P1 T4), and a prefill timing run through the C API.
- **Means:** After a cancel, the bridge's terminal event arrives within about 1 s whatever the model's speed; the slot itself frees at the end of the current pass, as F9 measured. The 1-second bound comes from upstream's polling constant, so "stop is fast" tests should bound the terminal event at about 1 s, not at one pass. Also: `recv_with_timeout` would call `std::terminate()` if the result queue stopped while a request waits (`:464-467`). In b12052 nothing stops the result queue (`server_context::terminate()` stops only the task queue, `server-context.cpp:5208-5210`), so an undrained request would instead wait forever. `lb_model_free` drains every request before terminating the loop. (Corrected after the P1 final review, 2026-11-04.)
- **Feeds:** P1 T4, T6 (`cancelDuringPrefillFast`), T7, T12 (`stopIsFast`), design §6.1 (cancel).

### F22. llama-server's own log lines can't be redirected without a patch
- **Finding:** Two log channels exist. llama and ggml log through `llama_log_set`, which takes a callback. llama-server's own lines (`SRV_*`, `SLT_*`, `COM_*`) go straight to `common_log`, whose worker thread writes to stdout/stderr or a file and has no callback hook.
- **Evidence:** `common/log.h` (no callback API; `common_log_set_file`, `common_log_set_verbosity_thold` only); `common/log.cpp:94-155` (`fprintf` to `stdout`/`stderr`); `common/common.cpp:415` (`common_init` installs `common_log_default_callback` for llama/ggml).
- **Means:** `lb_runtime_set_log` forwards llama/ggml logs only. `log_level` sets `common_log`'s verbosity threshold, so server lines are still filtered, but on Android they reach stderr, which logcat does not show. Getting them into the Kotlin `Logger` needs a patch to `common/log.cpp`, which goes beyond P1's one shipped patch and is left open.
- **Feeds:** P1 T2, T7 (`Logger`), design §12 observability.

## Tooling and device facts (spike sessions 1-2)

### F21. Quality tests on the Mac run at about 95 tokens/s in total
- **Finding:** Qwen3.5-4B Q4_K_M under Metal on the development Mac (`Mac17,2`, 32 GB) generates about 95 tokens/s across 16 parallel slots. GSM8K answers average about 400 tokens, so one run takes about 4 s.
- **Evidence:** a 48-problem timing run (203 s) before the test B pilot; [`research/p0/scripts/b-run.py`](../../research/p0/scripts/b-run.py)
- **Means:** Before starting a generation-based quality test, estimate its runtime: 2,400 runs take about 3 hours. Prefer offline logprob analysis where it answers the question (F12).
- **Feeds:** the method for any future quality test.

### F20. The test phone and how to drive it
- **Finding:**
  - **Device:** OnePlus 13 `CPH2649`, SM8750 (Hexagon v79), Android 16 (API 36), 15.5 GB RAM.
  - **`adb shell` hangs while a server it started is still running**, even with `nohup` and `</dev/null`. Launching in the background on the host (`adb shell "... exec llama-server ..." &`) works.
  - **The connection can drop** once during heavy throttling, and comes back on its own.
  - **Unlocked check** (read-only): `dumpsys window` (`isKeyguardShowing`).
  - **Thermal status:** `dumpsys thermalservice`.
- **Evidence:** [`research/p0/scripts/phone.sh`](../../research/p0/scripts/phone.sh), [`session.sh`](../../research/p0/scripts/session.sh)
- **Means:** Device test scripts in P1 T6/T12 and P2 reuse these patterns. Test conventions: never change device settings; check that the phone is unlocked first.
- **Feeds:** P1 T6, P1 T12, P2.

### F19. On-device Hexagon runtime: environment that works from a shell
- **Finding:** Upstream's `scripts/snapdragon/run.py` sets `LD_LIBRARY_PATH`, `ADSP_LIBRARY_PATH`, `CDSP_LIBRARY_PATH` and `CDSP1/2/3_LIBRARY_PATH` to the library directory. With that, `-dev HTP0 -ngl 99` put 34/34 layers (2.86 GB) on the NPU. This was verified as the shell user in `/data/local/tmp`, **not** from inside an app.
- **Evidence:** [`research/p0/scripts/phone.sh`](../../research/p0/scripts/phone.sh) `run`; [session1.md](../../research/p0/results/session1.md) setup line
- **Means:** Loading the HTP skeleton from an app-private directory (design §9: extract to `noBackupFilesDir`, set `ADSP_LIBRARY_PATH`) was still **To verify**, and it was P2's first risk to test. **Verified by F36** (2026-11-05): it works from an app, debug and release.
- **Feeds:** design §9, P2.

### F18. Hexagon build recipe
- **Finding:** The build that works:
  - **Image:** Docker image `ghcr.io/snapdragon-toolchain/arm64-android:v0.8`, run with `--platform linux/amd64`.
  - **Preset:** b12052's `docs/backend/snapdragon/CMakeUserPresets.json`, preset `arm64-android-snapdragon-release`, which builds CPU, Hexagon and OpenCL.
  - **Flags:** `-DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_UI=OFF -DLLAMA_SUBPROCESS=OFF`.
  - **The HTP skeleton needs its own target.** `htp-v79` (and its siblings for other chips) isn't built by default, so without it the NPU silently isn't available.
- **Evidence:** [`research/p0/scripts/build-android.sh`](../../research/p0/scripts/build-android.sh); [t1-sizes.md](../../research/p0/results/t1-sizes.md)
- **Means:** P2's `llamabro-hexagon` build builds every `htp-v*` target it ships, inside this image. P1's CPU build uses the plain NDK instead (P1 T6).
- **Feeds:** design §9, P2, P5 CI.

## Engine: in-process server-context (spike session 1)

### F1. Embedding `server-context` gives llama-server's exact behaviour and speed
- **Finding:** Calling llama-server's route handlers in-process (no HTTP) produced byte-identical output, the same speed and the same memory as the `llama-server` binary.
- **Evidence:** [session1.md](../../research/p0/results/session1.md):
  - T2: 20/20 identical on CPU and NPU
  - T3: within -0.4% to +1.8%
  - T4: 742 vs 744 MB peak
  - T7: 13/13 cache turns identical
- **Means:** The engine design in design §3 holds. Fallback §3.7 isn't needed.
- **Feeds:** D12 (passed), design §3, P1 Tasks 3-5.

### F2. The HTTP layer is the size cost the SDK avoids
- **Finding:** The in-process harness adds 1.27 MB stripped. llama-server's HTTP layer is 6.25 MB.
- **Evidence:** [t1-sizes.md](../../research/p0/results/t1-sizes.md)
- **Means:** No size reason to keep HTTP. Track `libllamabro.so` size per release.
- **Feeds:** design §3.5, P1 Task 6 Step 4, P5 size budget.

### F3. Startup must mirror `server.cpp` main() step by step
- **Finding:** Parity needs, in this order:
  1. `server_prepare_params`
  2. the stream session manager start
  3. `load_model`, then `start_loop` on the same thread
  4. `update_meta`

  Leaving out any step breaks parity silently.
- **Evidence:** the P0 spike plan (§3.1); working code in [`research/p0/harness/lb-spike.cpp`](../../research/p0/harness/lb-spike.cpp)
- **Means:** The bridge copies this sequence and the `static` helpers word for word.
- **Feeds:** P1 Task 3.

### F4. NPU output is deterministic
- **Finding:** Two runs on HTP0 give identical output (U vs U: 20/20).
- **Evidence:** [session1.md](../../research/p0/results/session1.md) T2.
- **Means:** Byte-exact parity tests work on the NPU too, not only the CPU.
- **Feeds:** P2 contract tests.

### F5. Slot selection can lose a conversation's cache when switching chats
- **Finding:** Chat B shares chat A's system prompt and tools, so prompt-similarity slot selection puts B into A's slot (similarity 0.957). On the way back, A matches that slot at 79%, so the RAM cache isn't consulted and A is partly re-processed (409 of 743 tokens).
- **Evidence:**
  - [session1.md](../../research/p0/results/session1.md) finding 3
  - [session2.md](../../research/p0/results/session2.md), "Also found"
  - `server-context.cpp:1850-1900`
- **Means:** With 1 slot (D17), switching chats always goes through the RAM cache path, so `Conversation` must be tested switching between chats. The levers are `id_slot`, `--slot-prompt-similarity` and `--cache-idle-slots`.
- **Feeds:** design §7.1, P1 Task 9, P4 (slots).

## Phone performance (spike sessions 1-2)

### F6. The phone throttles under sustained NPU load
- **Finding:** Thermal status reaches 3 after about 4 minutes of continuous NPU work, even on AC power. A one-off comparison made on a warmer phone looked 25% slower.
- **Evidence:** [session1.md](../../research/p0/results/session1.md) finding 4; [session2.md](../../research/p0/results/session2.md) header.
- **Means:** Benchmarks alternate the builds within a round and compare only within a round. Device benchmarks in later phases follow the same rule.
- **Feeds:** method for P2 and the `bench/` tooling.

### F7. MTP halves NPU prefill upstream
- **Finding:** With MTP on, upstream NPU prefill falls from 724 to 377 t/s (564 tokens). The fast HMX chunked delta-net kernel needs K == 1, MTP uses K = 4, so the whole prompt falls back to the recurrent kernel.
- **Evidence:** [session1.md](../../research/p0/results/session1.md) T3; `ggml-hexagon/htp-kparams.cpp:551-556`
- **Means:** Without a fix, MTP is a bad default on the NPU.
- **Feeds:** F8, D10, P2 `Speculative.Auto`.

### F8. The delta-net head/tail patch fixes F7
- **Finding:** Running the prompt head with K = 1:
  - NPU prefill with MTP on: +56%, +69% and +63% at 564, 2,368 and 9,883 tokens
  - CPU prefill: +18% and +38%
  - MTP off: unchanged
  - Logprob drift is no larger than upstream's own kernel switch (p95 0.038 vs 0.041).
- **Evidence:** [session2.md](../../research/p0/results/session2.md) test A; patch [`research/ph/delta-net/delta-net-head-tail-split.patch`](../../research/ph/delta-net/delta-net-head-tail-split.patch) (the SDK's 0001 until F149)
- **Means:** The patch ships in every build.
- **Feeds:** D10 (ships), P1 Task 1, P2.

### F9. Stop during a long prompt waits for a whole `n_batch` pass
- **Finding:** The server checks for cancel between passes of up to `n_batch` tokens. At the upstream 2048, freeing the slot took 1.7-2.6 s on the NPU; at 512 it took 0.37-0.44 s, with the same prefill speed (compared within a round, F6) and the same memory.
- **Evidence:** [session1.md](../../research/p0/results/session1.md) T5; [session2.md](../../research/p0/results/session2.md) test C
- **Means:** Default `n_batch` = 512.
- **Feeds:** D17, design §8, P1 Task 3, the Task 4/6 cancel tests.

### F10. A bigger `n_ubatch` doesn't help
- **Finding:** Values of 1024 and 2048 weren't faster than 512 beyond noise, and 2048 costs 136 MB more.
- **Evidence:** [session2.md](../../research/p0/results/session2.md) test C
- **Means:** Keep upstream's 512.
- **Feeds:** D17, design §8.

### F11. Checkpoints are large; upstream's cache budget doesn't fit a phone
- **Finding:**
  - One checkpoint is 50.25 MiB for Qwen3.5-4B, and a chat creates 2 per turn.
  - A saved 10-turn chat took 1,080 MiB of RAM cache.
  - Upstream's defaults allow about 14 GB in the worst case, before the model.
- **Evidence:** [session2.md](../../research/p0/results/session2.md), "Checkpoints and RAM cache" (trace-level run)
- **Means:** Decided: budget B, with 1 slot, 8 checkpoints and a 1 GiB RAM cache, about 1.4 GB in the worst case. 8 checkpoints cover about the last 4 turns. A saved chat is about 430 MiB with that cap, so the cache holds about two chats.
- **Feeds:** D17, design §8.

## Sampling (test B, stopped)

### F12. At Qwen3.5's main settings, vLLM's sampler order equals llama's
- **Finding:** With temperature 1.0 and min_p 0, the temperature and min-p steps do nothing, so both chains reduce to penalties -> top-k -> top-p. Order matters only when temperature is not 1.0 (Qwen's 0.7 general and 0.6 coding rows) or min_p > 0.
- **Evidence:** reasoning from the chain definitions (`common/common.h` sampler sequence), plus the model card rows (Qwen3.5-4B README)
- **Means:** A generation test at those settings only measures noise. If order is ever tested, compare the distributions offline from logprobs at temperature other than 1.
- **Feeds:** D18.

### F13. GSM8K baseline and seed noise for Qwen3.5-4B
- **Finding:** Upstream scores 91.8% (487 runs, 200 problems; non-thinking mode, card reasoning row 1.0 / 0.95 / 20 / min_p 0 / presence 1.5).
  - 3.9% of runs hit the 2,048-token limit; median 409 tokens.
  - The same problem flips between seeds 5.2% of the time.
- **Evidence:** the pilot results file (raw results are not kept in the repository); analysis script [`research/p0/scripts/b-analyze.py`](../../research/p0/scripts/b-analyze.py)
- **Means:** Detecting a 2-point quality change needs on the order of a thousand problems x several seeds (hours). Sampler changes aren't worth that unless something motivates them.
- **Feeds:** D18; the method for any future quality test.

### F14. Per-request `repeat_last_n: -1` is rejected
- **Finding:** The request schema requires `repeat_last_n` >= 0 (400 error). Only the command-line flag accepts -1.
- **Evidence:** a live request against b12052 `llama-server`; `tools/server/server-schema.cpp`
- **Means:** To cover the whole context, send the slot's context size. `Sampling.repeatLastN` documents this.
- **Feeds:** P1 Task 8.

### F15. Sampler order is already a per-request option
- **Finding:** The `samplers` request field changes the chain; `generation_settings.samplers` echoes it.
- **Evidence:** a live request against b12052 `llama-server`
- **Means:** Expose it as `Sampling.samplers`; no patch is needed for apps that want another order.
- **Feeds:** D18, P1 Task 8.

## Models and metadata

### F16. Most of what an app needs about a model is in the GGUF and readable at runtime
- **Finding:**
  - **Every GGUF key is readable:** `llama_model_meta_*` (`include/llama.h:631-643`).
  - **Typed helpers:** `llama_model_desc`, `llama_model_size`, `llama_model_n_params`, `llama_model_n_ctx_train`, `llama_model_chat_template`, `llama_model_is_recurrent` / `_is_hybrid`.
  - **What llama-server reports:** `GET /props` gives the template, `chat_template_caps`, modalities and generation defaults; `GET /v1/models` `meta` gives the vocab, trained context, parameters and size.
  - **The Unsloth Qwen3.5-4B file** carries 48 keys: arch `qwen35`, trained context 262,144, `nextn_predict_layers = 1` (MTP), the hybrid SSM layout, tokenizer, template, license and base repo.
- **Evidence:** a gguf-py dump of `Qwen3.5-4B-Q4_K_M.gguf`; live `/props` and `/v1/models`
- **Means:** `Model.info` returns the typed fields plus a map of every key. Capabilities come from the model, not a hand-kept list.
- **Feeds:** design §6.1 `model.info`, P1 Tasks 3 and 7, PD (capability-aware UI).

### F17. Recommended sampling can live in the GGUF, and llama.cpp applies it automatically
- **Finding:** llama.cpp defines `general.sampling.*` keys (`src/llama-arch.cpp:186-197`), and `common/common.cpp:1114-1167` uses them as defaults unless the caller set that field. The Unsloth Qwen3.5-4B file has none, so model-card values must come from the app or a catalog.
- **Evidence:** source; the same GGUF dump as F16
- **Means:** The SDK default is the file's values, else llama-server's. No per-model values are hard-coded in the SDK.
- **Feeds:** D18, design §3.3, PD (a model catalog with card values).

---

## Index by ID

| ID | Short name | Feeds |
|---|---|---|
| F174 | ggml-vulkan on the Adreno 830 at b12479: Qualcomm's upstream fixes and our patch (research `vk1`-`vk4`) make it run correctly (q4_K vec4 load, submission caps, no flash attention); 4x slower prefill than the CPU, 14x slower than OpenCL | D31, D23, D32, F64 |
| F173 | Patch 0008's guard holds for a male voice, Q8_0, es, de, ja (500 replies): all runaways stopped, clean replies lose at most a last word after a 3 s pause (2 in de); es runs away 11%, ja 49%; de/fr/ja/zh speech barely intelligible; thresholds unchanged | D95, D96, F166, F167 |
| F172 | Two fit measurements at once crashed the app: upstream's `common_fit_log_scope` swaps the process-wide log callback; the bridge now measures one at a time (8-thread host test: 3/3 crash before, 5/5 pass after) | D101, F58, F88 |
| F171 | Patch 0008 on the NPU: 20 seeded captioned replies all end; the guard ended 1 (seed 4), a runaway by ear, and cut none of the others (0.30-0.43 s of audio per word) | D96, F167 |
| F170 | NPU realtime turn: first audio 0.3 s after a live commit, 1.65x real time, cancel 80 ms, slot free 1 s after close, ~1.8 GB; a session holds its slot; captioning the user 0.6 s alone, +0.8 s before the reply or +0.3 s on a second slot | D95, D98, F166, F168, F169 |
| F169 | Upstream's realtime conversation sends no transcript of the user's speech; only a `transcription` session transcribes, each commit alone, and the type is fixed after the first input | D95, D98 |
| F168 | Upstream's realtime handler runs unchanged on an in-memory connection; same events as a real WebSocket on 4 seeded cases (r-01..r-04); sends from two threads; no per-session seed | D95, D97, F56 |
| F167 | LFM3-Omni's captioned replies lose their place after the text in ~1.6% (10/600) and never end; a guard at 3 s of silence or 5 frames per text token + 13 stops all, cuts no clean reply (patch 0008) | D95, D96, F154, F166 |
| F166 | Realtime LFM3-Omni needs a voice, a language and an answering instruction (else minutes of noise, another language, or the question read back); with them one steady voice, NPU 1.6x real time with captions, first audio 1.9 s | D85, D95, F154, F165 |
| F165 | Speech patches at b12479: 0005, 0007 unchanged, 0006 one hunk; re-cut on upstream's output flags (embeddings-only output skips the head), byte-identical audio, NPU 38.3 → 33.8 ms per frame as the old version | D86, D90, D94, F155, F156, F162 |
| F164 | Since b12479 a load's staging buffer gives its pages back on macOS (#31438): the DFlash fit test's Mac peak fell 543 MiB, projection unchanged; bound 1.12 → 1.20 | F58, F90, F160, D93 |
| F163 | The upgrade to b12479: contract identical on the Mac and the NPU (76/76), 0003 and 0004 ported to the server refactor, the delta-net gate +67% | D10, D52, D80, D91, D93, F121, F149, F153, F164 |
| F162 | NPU polling on Android logs an error per empty poll (82k lines, app's logcat dropped); a no-block read as upstream's Windows path removes it, same speed | D86, F156 |
| F161 | One writer per blob already held within a hub; two hubs on one cache via a link failed (OverlappingFileLockException) until the lock mutex was keyed by the file's identity (fileKey) | D89, D44, D70 |
| F160 | macOS peak RSS under-counts under memory pressure (1.27 failure); the peak footprint is steady, and the DFlash fit test now measures it with a 0.95-1.12 bound | F58, F90 |
| F159 | Fixed check ports collide across worktrees and let another listener answer a health poll; every host server now takes a port the OS picks | F150 |
| F158 | Integer type bounds: grammar up to ~27× (31 KB), <1 ms per request, ~2 µs/token more; an unsupported `pattern` silently becomes any string | D88, D63, F143 |
| F157 | Chat media as file:// halves a voice-note turn (192-200 → 93-94 ms), no patch; raw buffers add little | D84, D87, F152, F153 |
| F156 | Speech on the NPU alone reaches 2.4× real time: no CPU op in the speech graph (positions as input, 3-D lookup), polling, the unused text head skipped | D37, D53, D85, F155 |
| F155 | LFM3-Omni's speech part gives empty audio on the NPU because Hexagon tiles a weight a lookup also reads; patch 0005 keeps it flat; the CPU stays faster | D37, D53, D85, patch 0005 |
| F154 | LFM3-Omni-2B speaks on the phone at 1.7× real time with the model on the NPU and its speech part on the CPU; long texts must be spoken a sentence at a time | D37, D53, D65, D85 |
| F153 | On the LFM3 family (hybrid with SWA layers) the prompt cache reuses neither the live state nor the latest checkpoint; patch 0004 fixes it | D84, F152, patch 0004 |
| F152 | Speech input on the phone: LFM3-Omni-2B transcribes exactly; audio costs 12.6 tokens a second; earlier voice notes slow later turns, mostly the cache stopping before the last clip | D65, D84, D76 |
| F151 | Typed output on the phone: clean JSON with Think off and with a thinking budget; with no budget the model thinks for minutes | see F151 |
| F150 | W5 on the phone: hub device steps and demo device tests pass; three failures are the phone, not the change | see F150 |
| F149 | A one-line Hexagon gate (`K == 1 || n_tokens > K`): NPU prefill with MTP +61%, as the split; drifts less than upstream MTP on/off | D80 |
| F148 | A cancelled 4B load returns in 0.4-1.8 s (CPU) and ~1 s (NPU) on the phone, vs 6-11 s for a full load; nothing left behind | D67 |
| F147 | With no logger, init's native lines (backends loading) reach logcat as `llamabro` on the phone; all Info | D66 |
| F146 | Recommended sampling is on the Hub only sometimes (4 of 6 open models, none for Qwen3.5, gated needs a token); fine-tunes share the base tag | D68 |
| F145 | A cancelled load runs to its end, then is freed; llama-server's progress callback never lets llama.cpp abort | D67 |
| F144 | No logger means no native line in logcat (stderr is discarded); the app's logger is attached only after init | D66 |
| F143 | With a schema: thinking first, required fields before optional, no tool calls; which limits the grammar enforces | D63 |
| F142 | Keep rules let reflection survive R8, but it still needs `kotlin-reflect`, fails at runtime and rules out Multiplatform; KSP only | D61 |
| F141 | Small models choose tools worse as the count and prompt grow; the tool's description is what they choose by | D61 |
| F140 | tools/ui answers an unknown tool with `Error: Unknown tool`; Llama Bro stopped the turn as a manual tool | D62 |
| F139 | llama-server passes a tool's name, description (optional) and parameters only; no result schema; tools open the prompt | D61 |
| F128 | The memory budget refuses models that run: with 5.7 GB available the 4B loaded and Android closed cached apps; the SDK would have refused | D33, D45, D57 |
| F138 | The held-message note missed again (another test), and one turn-test timeout under load; not root-caused | testing, F132 |
| F137 | A cut reply continues seamlessly, from its answer or inside its thinking; the reasoning budget ends the thinking and the model answers | D60, demo design §4.7 |
| F136 | Local apps keep sampling per model (Open WebUI: chat over model); curated recommended values only; almost no loop guard by default | D60 |
| F135 | Restoring a chat's slot from disk: prompt 3.5 s → 0.3 s after a reload, same answer; 97 KB a token on disk | D59 |
| F134 | `TRIM_MEMORY_BACKGROUND` comes under pressure, not on Home; the model is freed on it | D59, demo design §4.6 |
| F133 | A chat failed its next message after its model was freed; the conversation is tied to the loaded model now | D59, demo design §4.6 |
| F132 | The demo gate passed runs that tested nothing (`--only` with spaces, a failed install); both fail now | testing, D58 |
| F131 | The drawer's scrim was the ink colour, a light veil in dark; it is the theme's scrim now | demo design colours |
| F130 | A touch target past its row is cut by the parent's bounds; accessibility sees only the rest | demo design §8.3 |
| F129 | A splash kept until the first composition hangs every Compose UI test at launch; readiness now comes from the data, capped at 2 s | demo splash, testing |
| F127 | Upstream PR #30432 (ARM NEON BF16 dot) as patch 0002: BF16 CPU encoder 16.5 s → 3.1 s, F16 and text unchanged | D55, F123 |
| F126 | -ffp-model=fast gives CPU text no speed (63 vs 61 t/s prefill) and changes 3 of 5 greedy replies: not taken | F123, open |
| F125 | At b12352 MTP, n-gram and DFlash still abort the GPU (error -54); Vulkan not retried, its fixes are not upstream | F80, D31, D52 |
| F124 | Since b12352 an NPU model's memory is not in the app's PSS (76 vs 607 MiB); it comes back about a second after close returns | P2 T6 tests, D33, open |
| F123 | The SDK's CPU build encodes a BF16 projector ~3.4x slower than upstream's Snapdragon preset (18 vs 5.4 s); b12352 changed nothing (corrected) | D53, open |
| F122 | prepare-llama.sh kept upstream's file times, so an upgrade linked a stale object; the build name was cached | D52 |
| F121 | b12352: contract identical on Mac and NPU, NPU pictures right again, the delta-net patch still +66% NPU prefill | D52, D53, D54 |
| F120 | The NPU computes Qwen3.5 0.8B's image encoder wrongly (upstream too); the encoder was on the NPU since P2 T6 and no test read a reply; first kept the encoder on the CPU (D51), then fixed upstream in b12352 (F121, D53) | D51, F119 |
| F119 | The models do not appear to see an attached picture in the app on the phone (invented answers on two models); the app stores and passes the right bytes; resolved by F120 | PD T13, F120, D51 |
| F118 | The model sheet could park low with its Get button below the screen when it grew after opening; fixed in DemoSheet | PD T13 |
| F117 | The phone was found in airplane mode after a run; nothing the project does sets it; guards added, the monkey stays off the phone | PD T13 |
| F116 | The app's Material-silent values (composer, bubble, send, chip, scroll) were our own; audited against the leading chat apps, four changed (D49) | PD T13, D49 |
| F115 | Edge cases in the chat: a stale model divider after an edit, a divider for an empty chat's first model, a blank preview for a stopped thinking reply, a transient memory refusal | PD T13 |
| F114 | Driving the real phone: the debug build's second launcher, a 256 KiB logcat, Clear all kills the app, the Think chip's twin, one emulator clipboard flake | PD T13 |
| F113 | A small model can loop without end and the app has no guard (Deep never finished on the 0.8B; Stop works) | PD T13, a possible D49 |
| F112 | Writer's catalog key was not the picker's Recommended file: fetched as "Gemma 3 4B" with other sizes; key now Q4_K_M | PD T13, D48 |
| F111 | The photo picker is AOSP's or Google Photos' (Done needed); `sendAnImage` passes on the phone | PD T11, design system v2 |
| F110 | The monkey's flip event aborts its run on the emulator; `--pct-flip 0` | PD T11, design system v2 |
| F109 | Design audit: gutters, gaps, type roles and states differ by screen; nothing owns them | D48, PD design system v2 |
| F108 | T12 performance: every budget met on the phone; heap gate on Heap Alloc, not ART's footprint | PD T12, Reference I |
| F107 | T11 journeys: failed download in a chat, Back after a layout switch, missing photo picker, "Try a smaller size" | PD T11, F106 |
| F106 | Fake load failures through `NativeError`; accessibility check fires mid-animation | PD T11 |
| F105 | ktlint and detekt: the plugin finds no sources under AGP 9, the CLI runs; what the first run found | code-quality pass |
| F104 | T10: derived state, estimates as plain values, downloads the app must list itself | PD T10 |
| F103 | T9 review: a handle outliving its screen, a message lost on refusal, a crash on a bad photo | PD T7, PD T9 |
| F102 | Theme choice left the status bar icons on the system's setting | PD T8, PD T9 |
| F101 | T9's checks found small long-press targets, an unlabelled sheet handle and a ghost reply row | PD T3, PD T9 |
| F100 | The message bar floated above the keyboard (resize plus imePadding) | PD T9, PD T10 |
| F99 | SDK DFlash test passed 4 of 4 after fresh installs; F95 did not recur | F95, F98, F90, D41 |
| F98 | Upstream DFlash on the NPU matched in 24 of 24 asks; F95 stays open | F95, F73, F90, D41 |
| F97 | The memory budget is internal; no thinking flag in `/props` | PD T1, PD T5, D42 |
| F96 | No `Conversation` from an app's stored messages | PD T1, D46 |
| F95 | DFlash on the NPU diverged once in 6 runs; not reproduced in 28 more (F98, F99) | D41, F73, F90 |
| F94 | End-of-phase review: Mediums and most Lows fixed with tests | P3 T9, F90, F91, D44 |
| F93 | One real download from huggingface.co, killed and resumed, verified | P3 T9, D39, D40, D43 |
| F92 | Kill and resume pass on the phone; the stall was an unanswered permission prompt | P3 T8, D9, F91 |
| F91 | Review of P3 T1–T8: fixes and kept deviations | P3 T3–T8, D40, D42, F89 |
| F90 | DFlash fit is right: 15 recurrent copies, host load within 4% | D41, P3 T6, F73 |
| F89 | One download at a time beat four on the phone | P3 T8, §11.2 |
| F88 | Header-only Hub fit matches full-file fit and load | D42, P3 T7, F85 |
| F87 | Fake Hub consumes request bodies for HTTP/1.1 reuse | P3 T2–T5 |
| F86 | App-private relative symlinks survive a tree move | D39, §11.2, P3 T1/T4 |
| F85 | Sparse headers match full main-model fit projections | D42, §11.2, P3 T1/T6/T7, F84 |
| F1 | In-process parity | D12 |
| F2 | HTTP layer size | §3.5, P5 |
| F3 | Startup sequence | P1 T3 |
| F4 | NPU determinism | P2 |
| F5 | Slot selection loses cache | §7.1, P1 T9 |
| F6 | Thermal throttling | benchmark method |
| F7 | MTP halves NPU prefill | D10 |
| F8 | Delta-net patch | D10 |
| F9 | Cancel waits for `n_batch` | D17 |
| F10 | `n_ubatch` 512 | D17 |
| F11 | Checkpoint size and budget | D17 |
| F12 | Sampler order null at T = 1 | D18 |
| F13 | GSM8K baseline and noise | D18 |
| F14 | `repeat_last_n` -1 rejected | P1 T8 |
| F15 | `samplers` per request | D18 |
| F16 | Model metadata at runtime | §6.1, P1 T3/T7 |
| F17 | GGUF sampling defaults | D18 |
| F18 | Hexagon build recipe | §9, P2 |
| F19 | Hexagon runtime env (shell only) | §9, P2 |
| F20 | Test phone and adb patterns | P1 T6/T12, P2 |
| F21 | Mac quality-test throughput | test method |
| F22 | Server logs bypass the log callback | P1 T2/T7, §12 |
| F23 | Cancel seen at next result or 1 s poll | P1 T4/T6/T12 |
| F24 | Contract 20/20; build-info fix | D12, §12, P1 T5 |
| F25 | Android packaging and sizes | D4, §9, P1 T6/T12, P5 |
| F26 | Compaction node layout (tools/ui) | §7.2, P1 T11 |
| F27 | P1 device acceptance per model | §13, P1 T12, PD |
| F28 | R8 consumer keep rules | P1, P5 |
| F29 | Backends by name, prefixed libs, private symbols | D4, §9, P2, P5 |
| F30 | ART returns real UTF-8 (Android 16) | P1 |
| F31 | check.sh: everything vs upstream, first run green | §12, method |
| F32 | Default device list takes every GPU-type device | §9, D24, P2 T6 |
| F33 | `--fit` counts all phone RAM as free | D22, §8, P2 T7 |
| F34 | Backend build needs (Hexagon SDK, NDK, Vulkan headers) | §9, P2 T1/T3–T5 |
| F35 | ggml backend scan and `GGML_BACKEND_PATH`; init retry safe | §9, D24, P2 T2/T6 |
| F36 | An app (debug or release) loads the unsigned HTP skeleton from `noBackupFilesDir`; 34/34 layers on HTP0 | §9, P2 T1–T3 |
| F37 | Request types are data classes: every field breaks ABI | D26, PA T1/T2 |
| F38 | `Conversation` can't reach most request options | PA T3 |
| F39 | Apps can't fake the SDK in tests | D27, PA T7 |
| F40 | Blocking `close()`, no load progress, Activity-owned sample | PA T4, PD |
| F41 | Errors lose cause; `ask()` hides non-answers | PA T5 |
| F42 | `Endpoint`/events can't grow; name clashes; missing routes | PA T6, §14 Q1 |
| F43 | `extraJson` merged one level deep | PA T2 |
| F44 | No CI, API check, lint, sanitizers, docs | D26, PA T1, P5 |
| F45 | ABI check: BCV tasks wired by hand on AGP 9; lint clean | D26, PA T1 |
| F46 | Builders keep P1 bodies; 69 upstream fields classified | D26, PA T2/T5, P2 T8 |
| F47 | Which Conversation requests get which options; tools/ui pre-encode | PA T3, §6.1, §7 |
| F48 | Native init 13 ms: lazy; close off the main thread | PA T4, §5, §6 |
| F49 | Closed model's memory kept by the allocator; bridge purges | PA T4, §3.2, §8 |
| F50 | Weights resident twice (mapped file + repacked copy) | P2, P5 |
| F51 | Load-failure reason only in upstream's log; bridge appends it | PA T5, §6.1 |
| F52 | Pre-encode samples one unseeded token; e2e compares counters only | PA T5 |
| F53 | Every single-model route works in-process but transcriptions; id-blind contract compare | PA T6, P4 |
| F54 | Engine seam public under an opt-in the API dump can't show; separate fakes | PA T7, D27, P5 |
| F55 | File transcription: fill the request's `files` from a path or fd, no upload | P4, §14 Q1 |
| F56 | Live transcription: implement upstream's `ws_conn` in memory, no WebSocket | P4, §14 Q1 |
| F57 | A device test run without `leaveApksInstalledAfterRun` deletes the pushed models | working rules, P5 |
| F58 | `--load-mode none`: one weight copy, 38% less PSS, about 1 s more load | §8, P2 T3–T5/T7, P5 |
| F59 | `dio` loses; why `none` is slower; a 5-line unmap patch (draft) | F58, §8, D10, P2 T3–T5 |
| F84 | Loader bounds-checks against file size, fit dry run allocates nothing: header-only fit (to verify) | D42, P3 T1/T7, D33 |
| F83 | Upstream never hashes downloads; its own resolution test and `llama download` to port against | D40, P3 T3/T4, §11.2 |
| F82 | How `-hf` resolves, chooses files and sidecars, caches, locks, resumes and removes | D39–D41, P3 T3–T5, §11.2 |
| F81 | Upstream's downloader has no HTTPS in the SDK (`LLAMA_OPENSSL OFF`) | D15, D39, P3 T2–T4, §11.4 |
| F80 | Any drafting (MTP, n-gram) aborts OpenCL even with F75's switch: GPU skipped when drafting; P2 review fixes | F67, F72, F75, D26, D33, P2 T11 |
| F79 | Sizes after P2: core AAR 20.5 MB native, NPU +5.1, GPU +3.9; full sample APK 30.2 MB | F25, D38, P5 |
| F78 | P2 acceptance: each flavor gets its device, broken NPU skeleton falls back to GPU with the reason, minified builds load | P2 T10/T11, D29, D31, D32 |
| F77 | SDK CPU uses OpenMP, the phone baseline doesn't; same median speed, both slow outliers with OpenMP | F25, F67, F72, P2 T11, P5 |
| F76 | NPU contract 75/75; on Android named `tool_choice` and bad JSON answered 500: `libllamabro.so` hid type info upstream's catches need | P2 T9, F25, D21, §12 |
| F75 | F71's cause: OpenCL Q4_K coopk DP4A kernel wrong for 2–4-row matmuls (checkpoint tail, spec verify); `GGML_OPENCL_COOPK_DP4A=0` fixes | F71, D32, P2 T6/T9, F67 |
| F74 | TTS: works on CPU and NPU+CPU codec, below real time; NPU codec aborts, GPU runs on; SDK can't return bytes | PF T4, P4, F65 |
| F73 | Draft models: DFlash JSON 2.3× off / 1.5× MTP on NPU, +2.9 GB; EAGLE-3, DSpark lose | PF T3, P3, P2 |
| F72 | n-gram (ngram-mod): code edits 7× NPU / 2.3× CPU, same replies; CPU loses on partial matches | PF T2, P2 |
| F71 | OpenCL: garbage for Qwen3.5 4B, Llama correct; F63/F65 checked only checksums | P2 T6/T9, D32, F63, F65 |
| F70 | Backend sampling: no gain on any chip (NPU 0.5–3% slower), same replies | PF T1, P2 |
| F69 | Load-time gaps: embeddings/rerank (bug), KV type, LoRA; A/B candidates | §3.3, §8, P4, P3 |
| F68 | MTP gain depends on output: NPU JSON +34–51%, prose slower; why an earlier prototype won | F67, D10, §8, P2 T8 |
| F67 | MTP slower on CPU and NPU, crashes OpenCL; Speculative.Auto off everywhere | D10, §8, P2 T8 |
| F66 | Fit misjudges phones; all-device projection as budget; OEM availMem hides driver pools | D22, D33, D34, §8, P2 T7 |
| F65 | DevicePolicy: per-device loads, Auto → NPU on the phone; GPU kernel cache once per install | D24, D32, §9, P2 T6/T7 |
| F64 | ggml-vulkan on Adreno 830: Q4_K aborts, Q8_0 wrong; Vulkan memory overcounted | D31, D23, D24, §9, P2 T5–T7 |
| F63 | OpenCL (Adreno 830): 3× prefill, same decode, slower loads (first ~1 min) | §9, P2 T4/T6/T8 |
| F62 | `llamabro-hexagon`: built in the image with the SDK's NDK; the AAR alone gives HTP0 | §9, P2 T3/T4/T6, F34 |
| F61 | Backends load by name from config; failures reported with ggml's reason, never fatal | D24, P2 T2–T6 |
| F60 | Unmap patch v2: mmap's speed, one copy, load-time peak; draft crashed on tied embeddings | F50, D30, §8, D10, P5 |

