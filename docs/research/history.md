# Research history

How the SDK reached its current shape, phase by phase. Each section states the question the phase had to answer, what was built, the key results with numbers, where the work changed course and why, and what stayed open. Findings are cited as F<n> ([findings](./findings.md)) and decisions as D<n> ([decisions](../decisions.md); the overall structure is in [architecture](../architecture.md)). The experiments themselves (scripts, method and raw results) are in [`research/`](../../research/README.md); how the result of each change is verified is in [../testing.md](../testing.md).

All phone measurements were made on one test phone: a OnePlus 13 (Snapdragon 8 Elite, Hexagon v79, Adreno 830, Android 16, 15.5 GB of RAM). The reference for every comparison is upstream llama.cpp at the pinned build b12052, with the same flags. Where upstream and the SDK differ, upstream wins unless a measured test says otherwise (D10, D18). Dates are 2026.

## Summary of the phases

| Phase | Dates | Question | Outcome |
|---|---|---|---|
| P0 Spike | 11-03 | Can llama-server's handlers run in-process on Android and match upstream? | Yes: byte-identical output, same speed and memory (F1) |
| P1 Core | 11-04 | Can that be wrapped as a C API, JNI and a Kotlin API with a beginner path? | Yes; every layer is checked against upstream (F31) |
| PA API revision | 11-05 | Is the public API safe to grow? | Rebuilt around builders, typed errors and a test fake (D25 to D28) |
| P2 Backends | 11-05 to 11-07 | Can the NPU and GPU be used from an ordinary app, with the right device and context chosen? | NPU and OpenCL work; Vulkan dropped; `Auto` picks device and context (D29 to D38) |
| PF Feature spike | 11-06 | Which llama-server features beat upstream's defaults on a phone? | n-gram speculation and DFlash as opt-ins; the rest unchanged (D35 to D37) |
| P3 Hub | 11-07 to 11-09 | Can downloads resume, verify and be fitted before they start? | Yes, verified on a fake and on the real Hub (D39 to D45) |
| PD Demo app | 11-09 to 11-13 | Does the SDK carry a real chat app end to end? | Yes, with two known defects (F113, F119) |

## Working method

Four habits came out of the early phases and shaped everything after.

- **Compare with upstream, not with expectations.** A test that compares the SDK with itself can pass while both are wrong; the OpenCL garbage (P2) and the missing picture understanding (PD) were both such cases.
- **Estimate the cost of a quality test before running it.** The laptop generated about 95 tokens a second across 16 slots, so 2,400 generations take about three hours (F21). Offline analysis of probabilities is preferred where it answers the question.
- **Benchmark within a round.** The phone throttles under sustained load (F6), so builds alternate inside a round and only same-round numbers are compared.
- **Record every finding with its evidence**, including the ones that turned out wrong; a wrong finding is marked superseded, never deleted.

## P0: the spike

**Question.** Can llama-server's own request handlers run in-process on Android, with no HTTP layer, and give exactly its behaviour at its speed? The alternative, a separate pipeline on `libllama`, would lose upstream's template handling, tool calls, reasoning, prompt cache and sampling, and every later llama.cpp upgrade would need re-porting (D12).

**Method.**

- A harness (`lb-spike`) linked `server-context` and called the route handlers directly.
- Startup copies `server.cpp`'s `main()` step by step: parameter preparation, the stream session manager, loading the model and running the loop on the same thread, then the metadata update. Leaving out any step breaks parity silently (F3).
- It was compared with the `llama-server` binary built from the same commit with the same flags, on the host and on the phone, on the CPU and on the Hexagon NPU, using Qwen3.5 4B (Q4_K_M, which carries its own MTP layer).

**Results (D12 passed).**

- Output parity: 20 of 20 fixed requests identical (chat, multi-turn, thinking on and off, tool calls, JSON schema, an image) on the CPU and on the NPU, and the NPU gives identical output to itself (F1, F4).
- Speed within -0.4% to +1.8% of upstream; peak memory 742 against 744 MB; the cache behaviour of a 10-turn conversation with tool calls identical in 13 of 13 turns.
- Size: the in-process harness adds 1.27 MB stripped, against 6.25 MB for llama-server's HTTP layer (F2).
- Build recipe for the NPU: Qualcomm's Snapdragon toolchain image, upstream's preset, and a separately built HTP skeleton, without which the NPU is silently unavailable (F18, F19).

**The delta-net patch (shipped, D10).**

- With MTP on, upstream's NPU prefill fell from 724 to 377 tokens a second, because the fast chunked kernel needs one token per sequence and MTP uses four (F7).
- Running the prompt head with one token and only the tail with the draft width gave +56%, +69% and +63% prefill at 564, 2,368 and 9,883 tokens on the NPU, and +18% to +38% on the CPU. MTP-off was unchanged, and logit drift was no larger than upstream's own kernel switch (F8).

**Phone defaults.**

- Cancel latency: the server checks for a stop between passes of `n_batch` tokens. At upstream's 2048 freeing the slot took 1.7 to 2.6 s on the NPU; at 512 it took 0.37 to 0.44 s at the same prefill speed (F9).
- `n_ubatch` of 1024 or 2048 was not faster than 512 (F10). The SDK defaults to 512 for both.
- Cache budget: one checkpoint of Qwen3.5 4B is 50.25 MiB and a chat creates two per turn, so a saved 10-turn chat took 1,080 MiB, and upstream's defaults allow about 14 GB in the worst case (F11). The phone defaults became one slot, eight checkpoints and a 1 GiB RAM cache (D17).
- Slot selection can lose a conversation's cache when chats share a prompt prefix (F5), which fixed the single-slot design and the need to test switching between chats.
- The phone reaches thermal status 3 after about four minutes of continuous NPU work, and a one-off comparison on a warm phone looked 25% slower (F6).

**Course changes.**

- The sampler-order test (a vLLM-style order, and counting only generated tokens in penalties) was planned as a large quality experiment on a laptop. It was stopped.
- At the models' recommended settings (temperature 1.0, min-p 0) the two orders are identical (F12).
- The GSM8K baseline was 91.8% with 5.2% of problems flipping between seeds (F13), so detecting a 2-point change needs on the order of a thousand problems times several seeds, for hours.
- The order is already a per-request option (F15), so upstream's order stays (D18). Per-request `repeat_last_n: -1` is rejected by upstream's schema (F14).

**Open at the end.** The HTP skeleton inside an ordinary app, which P2 verified (F36).

## P1: core

**Question.** Can the in-process engine be made a library: a C API over the bridge, JNI, and a Kotlin API with a beginner path (`Model.chat`, `Conversation`) that follows llama.cpp's own web client?

**Built.**

- A runtime singleton and a C API (`llamabro.h`) over server-context, with JNI.
- `Model.chat` as a flow of typed events.
- `Conversation`, a port of `tools/ui`'s message handling: the agent loop that runs registered Kotlin tool handlers, regeneration, and compaction that follows `tools/ui`'s algorithm and node placement exactly (F26, D13, D14).
- Host and contract tests, the AAR and a first sample (D8, D20).

**Results.**

- The C API matches upstream llama-server byte for byte on the host: 20 of 20 on the P0 request set, then 55 of 55 for Qwen3.5 and 54 of 54 for Llama 3.2 3B (a second chat template) on the widened 57-request contract (F24, F31).
- Build info needed a fix: the patched copy is not a repository, so git answered for the SDK's own repository and `/props` reported the wrong build (F24).
- `tools/ui`'s compaction code, run in node over 400 seeded message lists, produces a golden that the Kotlin port reproduces 400 of 400 (F31).
- Host end-to-end, replayed to upstream in the same order: 17 of 17 identical, cache counters included. Kotlin unit tests: 83. Device: native 3 of 3, UI 1 of 1, acceptance 10 passes, 1 skip and 1 failure.
- A cancelled streaming request ends within about 1 s whatever the model's speed (the server's polling interval), and within 39 ms during decoding (F23).

**Packaging.**

- The CPU backend loads by name straight from the APK with no extraction, under `llamabro-` library names, and `libllamabro.so` exports only the C API and `JNI_OnLoad` (it exported 1,140 extra symbols before), shrinking from 1.60 to 1.38 MB (F29, D20).
- A minified app crashed at start because R8 renamed classes that JNI looks up by name, so the AAR ships keep rules and `JNI_OnLoad` now fails cleanly (F28).
- The core's native code was 21.6 MB at this point (F25).

**Acceptance on the phone (F27).**

- Qwen3.5 4B passes every check.
- Gemma 3's template has no tool support.
- Llama 3.2 3B calls `get_weather` again after every result at temperature 0, and upstream llama-server does the same with the same request, so it is a model limit.
- One bug was found: a stop before any token kept no assistant message, and Gemma's template rejected the next request. `Conversation` now keeps the empty message as `tools/ui` does.

**Course changes.** llama-server's own log lines cannot be redirected without a patch to common code, so server lines stay out of the Kotlin logger (F22). `ContextSize.Auto` was first upstream's fit fed with memory margins (D22); P2 showed that is not enough.

**Open at the end.** Whether the public API could take additions without breaking callers, which the next phase examined.

## PA: the API revision

**Question.** An API review of P1's public Kotlin API (F37 to F44), read against its code, asked whether it could take the additions P2 and P3 would bring without breaking callers.

**What the review found.**

- Request and event types were public data classes, so every new field would change constructors and `copy()` (F37). Upstream's completion schema has 69 live fields against 11 typed ones.
- `Conversation` could not reach half of what a chat request can do (F38).
- An app could not fake the SDK in its own tests (F39).
- `close()` blocked a main thread for up to a second (F40), and errors lost their causes (F41).
- `Endpoint` was an enum of 14 of the 27 single-model routes (F42).
- `extraJson` merged one level deep and could silently undo typed fields (F43).

**Built (D25 to D28).**

- Types that will grow became classes built through builders and a DSL, with no `copy` or `componentN`. 31 sampling fields are typed, plus `stop` and three reasoning fields. Extra JSON merges as RFC 7396 merge-patch. Request bodies are reproduced byte for byte from P1's golden (F46).
- `RequestOptions` is shared by `ChatRequest`, `Conversation` defaults and per-send overrides; `regenerate`, `isBusy`, `canCompact` and saved settings format 2 were added (F47).
- A public API dump is checked by `check.sh`. The ABI tools did not configure themselves for AGP 9, so the validator's tasks were wired by hand (F45). Lint runs with warnings as errors; the core's public API is 72 classes.
- `close()` returns at once with `closeAndJoin()` for callers that must wait; load progress is reported; native initialisation (about 13 ms) moved off the caller's thread (F48); and the allocator is purged after a model closes so the memory comes back (F49).
- Typed errors with causes. `ModelLoadFailed.reason` is `FileNotFound` or `Other` with upstream's text in the message, because llama.cpp says why a load failed only in its log (F51).
- All 27 single-model routes in the C API, reachable through `rawRequest` (F53), and `llamabro-testing` with `FakeLlamaBro` behind an opt-in engine seam (D27, F54).
- The contract grew to 60 of 60 and 59 of 59, and JVM tests to 123 (F46, F51). A pre-encode samples one unseeded token, which the end-to-end test had been comparing by chance (F52).

**Course changes.**

- Midway the work was judged to be drifting into polish with little gain for apps, and it was slimmed (D28): load reasons reduced to two, the testing module kept to what callers need, and the last two tasks merged.
- Audio transcription was found not to need an upload or a socket: its handlers can be driven from a file path or a descriptor and an in-memory connection (F55, F56). This was recorded for a later phase.

**Open at the end.** Typed classes for embeddings, rerank, completion and transcription (all reachable through `rawRequest`), CI and sanitizer runs, and generated documentation (F44).

## P2: backends

**Question.** Can the Hexagon NPU and the Adreno GPU be used from an ordinary app, and can the SDK pick the right device and a context that fits in memory without help?

**Built.**

- `llamabro-hexagon`: built from the SDK's own CMake inside Qualcomm's image with the SDK's exact NDK; the AAR alone makes the NPU available. The unsigned skeleton is copied to `noBackupFilesDir` and found through `ADSP_LIBRARY_PATH`, so an ordinary app, debug or release, runs a model on the NPU (F36, F62).
- `llamabro-opencl`: the same build, kernels embedded.
- Optional backends announce themselves through manifest metadata and load by name. A failing backend is reported with ggml's reason and never stops the runtime (F61, D24).
- `DevicePolicy` with `DeviceKind` `Npu`, `Gpu` and `Cpu`; `Auto` tries the NPU, then the GPU, then the CPU, and `loadReport` names each attempt (D32, F65).
- `ContextSize.Auto`, `UpTo(n)`, `memoryReserveMib`, `kvCacheType`, `ModelTask` (embeddings and rerank, whose endpoints always failed before) and LoRA adapters (F66, F69, D33, D34, D45).

**Backends and devices.**

- **NPU from an app (F36, F62).** Works with the skeleton in the app's private directory. The NPU contract is 75 of 75 identical to upstream (F76).
- **OpenCL (F63).** About three times the CPU's prefill (131 to 174 against 39 to 61 tokens a second for Qwen3.5 4B) and the same decode (13.8 to 15.2). Loads are 2.5 times slower: 17.9 to 18.9 s, and 58.9 s the first time while ggml-opencl compiles and caches its kernels, a once-per-install cost (F65).
- **Vulkan dropped (D31, F64).** On the Adreno 830 every Q4_K load aborts the process in a pipeline creation call and Q8_0 gives wrong output. Both are known driver bugs without an upstream fix. The GPU is served by OpenCL, and the build recipe is kept for a retry.
- **OpenCL garbage, then its cause (F71, F75).** Qwen3.5 4B answered `8888…` to every prompt on OpenCL while Llama 3.2 3B was right. The earlier device test had only compared the GPU with itself.
  - The cause is a cooperative-K DP4A Q4_K kernel that is wrong for matmuls of two to four rows, which llama-server sends for every model that makes context checkpoints and for speculative verification.
  - Upstream's own switch `GGML_OPENCL_COOPK_DP4A=0` fixes it, and the SDK sets it. The device test now requires "Paris", not just a repeatable reply.
- **A bug the NPU contract found (F76).** On Android the SDK answered 500 for a named `tool_choice` and for malformed JSON, because the exported-symbol list hid the type information that upstream's exception catches need. Exporting type info fixed it.

**Memory.**

- **Weights resident twice (F50, F58 to F60, D30).** With upstream's default mmap load, Qwen3.5 4B used about 5.5 GB PSS: the mapped file and a repacked copy. Loading with `--load-mode none` gives 3.4 GB (38% less) for about 1 s more load.
  - Direct I/O was no faster than `none`.
  - A 14-line unmap patch kept mmap's load speed at the same final memory but peaked at 5.4 GB while loading. `none` was chosen, and the patch is kept as material for an upstream report.
- **Memory fit (F66, D33, D34).** Upstream's fit cannot size a context on a phone: it compares device memory only and treats the host as unlimited, and on failure llama-server loads the full training context (262,144 tokens for Qwen3.5).
  - Its dry-run measurement is sound, so the bridge sums every row at two sizes and interpolates to a budget.
  - The budget is the kernel's `MemAvailable` less the low-memory threshold, the prompt cache, the image encoder and a 512 MiB reserve. Android's `availMem` is not used, because one OEM's figure excludes reclaimable driver pools (3,377 against 8,239 MiB).
  - `Auto` stops at 16,384 tokens and fails with `InsufficientMemory` below 4,096.

**Speculation and threading.**

- **MTP (F67, F68).** Slower for prose on the CPU (decode 10.4 to 6.7 tokens a second) and the NPU (14.9 to 12.9), and it aborts OpenCL, so `Speculative.Auto` is off on every chip. For structured output on the NPU it decodes 34 to 51% faster (14.4 to 21.7 tokens a second at 88% acceptance). An earlier prototype's gains came from measuring another workload, not another engine. It is an opt-in.
- **Another abort (F80).** Any drafting, MTP or n-gram, aborts the OpenCL backend even with the switch, so the GPU never drafts.
- **OpenMP off (F77, D38)**, as in upstream's Snapdragon preset: the same median speed, and the only two slow runs were with OpenMP. `libomp.so` (0.97 MB) left the AAR.

**Sizes and acceptance.**

- Core AAR 8.44 MB (20.5 MB of native code), NPU AAR 2.97 MB (5.1 MB), GPU AAR 0.94 MB (3.9 MB), the Hub AAR later 0.22 MB. A minified sample APK with all three backends is 30.2 MB (F79).
- Each acceptance flavor (CPU only, GPU, full) gets the device its artifacts allow, a broken NPU skeleton falls back to the GPU with the reason, and each flavor's minified build loads a model (F78).
- After a stopped 3,000-token prompt the next reply takes 102 s on the CPU and 34 s on the GPU, because the stopped turn stays in the history as in `tools/ui` (F78).

**Course changes.**

- Vulkan left the plan; `Auto`'s context logic was replaced by the dry-run measurement; MTP went from a planned default to an opt-in.
- A review of the phase (F80) found and fixed a `Fixed(0)` that reached upstream's "use the training context", a device step in `check.sh` that passed when a flavor never ran, and an uncaught backend exception.

**Open at the end.** No non-Qualcomm phone was available, so the SDK is verified on one Adreno and one Hexagon chip only (D23). The GPU's speculative abort and Vulkan wait on upstream.

## PF: the feature spike

**Question.** Which llama-server features that the SDK does not enable by default would beat upstream's defaults on a phone? PF ran during P2, proof first, before any API was built.

**Results.**

- **Backend sampling (F70).** Never beats off: 0.5 to 3% slower decode on the NPU, noise on the CPU and GPU, identical replies. It stays a per-request option, off by default (D37).
- **n-gram speculation (F72, D35).** No extra model: it looks the recent tokens up in earlier text.
  - Code edits decode 7 times faster on the NPU (14.6 to 105 tokens a second, 138 with MTP behind it) and 2.3 times on the CPU (10.5 to 23.8), with byte-identical replies. Prose is untouched.
  - On the CPU, output that only partly repeats is 10 to 15% slower, so it is an opt-in, `Speculative.Ngram`, built afterwards.
- **Draft models (F73, D36).** For Qwen3.5 4B, DFlash decodes JSON at 32.6 to 36.0 tokens a second on the NPU against 21.9 to 22.3 with MTP and 14.6 to 14.9 off.
  - It loses on prose and on the CPU, and needs about 2.9 GB more memory.
  - EAGLE-3 and DSpark lose everywhere and were left out. Every drafter drifts from greedy output at the same two places on the NPU, so the drift is batched verification, not the draft.
- **Text to speech (F74, D37).** Qwen3-TTS 1.7B is clear but below real time on every chip (real-time factor 0.2 to 0.59). Its codec aborts on the NPU, and the SDK's `rawRequest` returns strings, which would corrupt audio bytes. Out of 1.0.

**Course change.** The spike's side effect was the most useful result: it found OpenCL answering garbage for Qwen3.5 (F71), which P2 settled.

**Open.** DFlash was built in P3. TTS needs a byte-returning request and a way to keep the image encoder on the CPU.

## P3: the Hub

**Question.** Can an app download a multi-gigabyte model on a phone, survive being killed, verify it, and know before downloading whether it will fit?

**Built (D39 to D45).** `llamabro-hub`, a pure Kotlin port of llama.cpp's `-hf` over `HttpURLConnection`:

- resolution of `user/model[:tag]`, file choice, upstream's cache tree with relative symlinks, locking, resume with `Range`, and removal;
- shared progress, listing, and a fit estimate;
- a hand-off of a local source with its image projector and DFlash draft to the core (`Speculative.DFlash`, counted in `ContextSize.Auto`);
- `ModelConfig.memoryReserveMib`, the app's to change.

**Why a port.** Upstream's downloader cannot run in the SDK: llama.cpp is built without OpenSSL, its HTTP client has no HTTPS, and Hugging Face is HTTPS only (F81). Parity is checked by goldens made with upstream's own code: its model-resolution cases, recorded real repository listings, and the cache tree it writes from the same fake server (F82, F83).

**Verification and fit.**

- **Hashing beyond upstream (D40, F83).** Upstream names blobs by SHA-256 but never hashes them and resumes a partial by size alone. The Hub hashes every file while writing it, re-hashes a resumed partial, and admits a file to the cache only if its digest matches its recorded identifier. Per-chunk checkpoints were considered and declined (D47, F94).
- **Fit before download (F84, F85, F88, D42).** The loader checks tensor offsets against the file's size only, and the memory dry run allocates nothing, so a header-only file is enough.
  - For three real models every device-memory line matched the full file at 4,096 and 16,384 tokens (12 phone pairs, 6 host pairs).
  - The header fetch for Qwen3.5 took 24 MiB in two range requests and 775 ms, and chose the same 16,384-token context as the real load.
- **DFlash memory (F90).** Upstream keeps 15 recurrent-state copies for the target with DFlash (+754 MiB). The SDK's projection lands within 4% of a real host load (5,793 MiB projected against 5,576 MiB), and on the phone the reply matches upstream byte for byte at 34.6 tokens a second. Upstream itself cannot project it, because its fit drops the draft.

**On the phone.**

- **Resume (F92).** Against the fake Hub at 30 MiB/s, a 1.5 GiB file killed at 31% resumed with the right `Range` after both `kill -9` and force-stop, both blobs verified, no partial left. An earlier force-stop stall was the notification prompt left open: it kept the relaunch in the background, where the OEM's freezer stops it.
- **A real download (F93).** A 532 MB model and a 207 MB projector from huggingface.co over Wi-Fi: killed at 51%, resumed with `Range` and answered 206 by the CDN, both verified, done in 97.6 s at about 10 MB/s. The chat answered 6 s after the tap.
- **Concurrency (F89).** One download at a time beat four on the phone, 2.1 times faster, but the rig was a single-process fake server behind one `adb reverse` tunnel, so it says nothing about a CDN. The default stays 1 (D44).

**Reviews (F91, F94).**

- An independent review of the first eight tasks found the Hub's state read on the caller's thread, five differences from upstream and a late job notification. All were fixed with tests.
- The end-of-phase review found no Critical or High issue. Its five Medium findings were fixed, among them a cancelled transfer that left every collector waiting and `delete("org/repo#file")` removing the whole repository.

**Course changes.**

- The plan to call upstream's downloader through the C API was dropped (F81).
- A fit check before download was added after a spike proved it (D42).
- The DFlash on-device test accepts a refusal when the phone's free memory is below the projection (D44, D45).

**Open at the end.** DFlash on the NPU once diverged from upstream's greedy reply at one token in one of six runs, right after a fresh install. It did not recur in 24 of 24 asks over eight fresh loads with upstream, or in four of four SDK runs (F95, F98, F99), so it is not root-caused. A resumed partial is re-hashed in full (D47).

## PD: the demo app and its design system

**Question.** Does the SDK carry a real, everyday chat app end to end on a phone, with the design quality of a finished product? The app was built last in the cycle so that a real application used the API before it was frozen.

**Built.** An app on the public API only ([design](../design/demo-app.md), D46):

- first run with a usable chat before the download ends, Hugging Face search with fit words, a model sheet with Basic and Advanced sizes, and resumable background downloads;
- chat with streaming markdown, think levels, images, edit, regenerate and share;
- two layouts and a design system.

Two SDK additions: a `Conversation` from stored messages, because an app that keeps its own chats could not rebuild one (F96), and the memory budget for fit labels (F97).

**Test strategy.** The tests stand in for a human tester ([testing](../testing.md)): unit tests over the SDK's fake engine, component tests and screenshots, screen tests with accessibility checks, whole-app journeys over a fake engine and a fake Hub, random-input runs, a requirement map that names a test for every rule, coverage floors, and benchmarks.

- The journeys found four defects in the screens, all fixed: a failed download that read as a missing model, a layout switch that left a chat with no back, a crash on a phone with no photo picker, and a memory line that opened the wrong sheet (F107).
- Accessibility checks found long-press targets of 37 to 40 dp and an unlabelled sheet handle that component tests had missed (F101).
- The keyboard left the message bar floating 260 dp high (F100), and the status bar's icons ignored the app's own theme (F102).
- The ktlint plugin finds no sources under AGP 9, so the command-line jar runs instead (F105).

**Performance (F108).** Every budget is met on the phone:

- cold start 116 ms to the first frame and 198 ms fully drawn, against 450 and 700 (255 ms fully drawn after the design system rework);
- flings and streaming finish every frame early;
- `Markdown.parse` 0.18 ms and `ChatEngine.onDelta` 5 microseconds a call;
- the held Java heap 21.2 MB against 64.

**The design audit and rework (F109, D48).**

- A review of every screen found no shared layout system: gutters of 12, 16, 20, 36 and 41 dp, labels as far from their content as from the content above, too little type contrast and two primary colours.
- The rework moved to Material 3's type roles and 4 dp grid verified against its token sources, layout primitives that own the gutter and gaps, one navy primary, a serif for hero moments only, grouped Settings, 16 sp body text, and build gates that ban numbers in screens. The gutter is asserted on every screen.
- An independent review then found and verified fixes for a send button's grey ring, one model under several names, four signals for "selected" and an unmarked open chat.
- Large font scales, a tablet and right-to-left were checked afterwards. The tablet stretched content across the window (now capped at 640 dp). Right-to-left found bubbles pinned to fixed edges, icons that did not mirror and a code block that started scrolled the wrong way.

**A pattern audit (F116, D49, D50).**

- Where Material is silent the app had used its own numbers. Comparing with the leading chat apps changed the sent-message bubble to a soft neutral, made the send button quiet while empty, added a back-to-latest button and brought the Think control into the message bar.
- Later the nicknames were dropped so that every model shows its own name, and the bar became the text over a row of buttons. One catalog name had also appeared twice for one file, because the catalog's key was not the size picker's recommended file (F112).

**Real-model runs on the phone (F114 to F119).** An external UI Automator driver ran seven steps with real models: first open to a held message to a reply, think at Quick and Deep, an image, a force-stopped download resuming with a 206, a model switch with edit and regenerate, the Chat list layout with search and share, and removing a model. Each step passed once on the final build, and the emulator gate and the SDK check pass. The runs also found:

- A model sheet that parked itself with its Get button below the screen on some first opens: 2 of 11 cold opens before the fix, 0 of 27 after (F118).
- Facts about driving a phone from outside: a debug build has two launcher entries, the log ring buffer is 256 KiB, and clearing Recents kills the app (F114). A stale divider after an edit (F115).
- A phone found in airplane mode after a run, for which no cause was established; random-input runs were then moved off the phone (F117).
- **A looping model (F113).** Qwen3.5 0.8B asked to think through a question looped in its answer on three of four tries at Quick, and Deep never finished within 150 s. The thinking budget is not the problem: the loop is in the answer. Stop ended every one at once, but nothing in the app ends such a loop.
- **The pictures that models did not see (F119).** The end-to-end step only asserted that a reply arrived, so it passed while Qwen3.5 0.8B said it could not see an image and Gemma 3 4B invented a description of a solid blue picture.
  - The stored files, the thumbnail and the bytes passed to the SDK were right, and the SDK's image case against upstream passes on the host and on the NPU. But no test anywhere asserted that a model understands a picture.
  - On the host, upstream and the SDK's own path read a red-and-blue picture correctly with Qwen3.5 4B and either projector format, so the model files and the image handling are not the cause. The fault is on the phone, likely the NPU backend or the app's use of it. It was recorded as a defect to fix after the phase.

**Open at the end.** The phase closed with two known defects (F119 and F113) and the items below.

## Open items

- **The models do not see an attached picture on the phone (F119).** The suggested vision models answer as if no picture were attached. Next steps: send the same file through the acceptance app and the host test with the phone's models, try the CPU backend, read the native log (it does not reach logcat), then check the image's MIME type. No test yet asserts that a model names the colours of a known picture.
- **A looping small model has no guard (F113).** Stop is the only exit. A cap on the reply length per Think level and the model card's recommended presence penalty are candidates.
- **A Settings page for sampling values**, with a reset to the model's default.
- **A test of the reasoning budget's forced end** on a small model.
- Carried from earlier phases: DFlash's one unexplained divergence (F95); Vulkan on the Adreno 830 and GPU drafting, both waiting on upstream (F64, F80); typed classes for embeddings, rerank, completion and transcription (reachable now through `rawRequest`); CI, sanitizer runs and generated documentation; no non-Qualcomm device verified; and publishing, which needs a decision on the NPU runtime's licence terms.
