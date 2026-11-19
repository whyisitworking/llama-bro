# Decision records

This file lists the design decisions of the SDK, numbered D1 to D92 in the order they were made. Each record has a status line, the context that forced the choice, the decision itself and its consequences. Decisions are cited elsewhere in the documentation as `D<n>`, and the measurements that justify them as `F<n>` (see the [findings register](./research/findings.md)). The [architecture guide](./architecture.md) describes the resulting design.

Several records amend or replace earlier ones; the amending record says so, and the amended record is kept unchanged apart from a pointer, so the order of events stays readable. All records carry the status "Decided". Where a decision was made before the dated record-keeping began, the date is that of the first design document (2026-11-03). Terms used throughout: "upstream" is llama.cpp at the pinned build (`b12479` since D93; `b12352` from D52; `b12052` before), "llama-server" is its server tool, and `tools/ui` is the web client that ships with it.

### D1. Where the SDK lives

Status: Decided (2026-11-03)

Context: The SDK and the demo app were developed together in one repository.
Decision: The SDK is its own included Gradle build under `sdk/`, and is split into a separate repository when published.
Consequences: The demo app consumes the SDK through a composite build, so it exercises the same artifacts an external app would.

### D2. Coordinates

Status: Decided (2026-11-03)

Context: Published artifacts need a Maven group, and two Android modules with the same namespace generate the same `R` class.
Decision: The Maven group is `io.github.whyisitworking`. The Kotlin package and Android namespace stay `com.suhel.llamabro`, and backend modules use sub-packages such as `com.suhel.llamabro.hexagon`. The demo app's application ID is `com.suhel.llamabro.demo`.
Consequences: Package names are stable across the SDK; the demo app cannot share a namespace with the core module.

### D3. Minimum SDK level

Status: Decided (2026-11-03)

Context: Some useful platform APIs (thermal headroom, ADPF hints) only exist from API 30 and 31.
Decision: `minSdk` is 29. APIs from 30 and 31 are used only behind runtime checks.
Consequences: Older devices than API 29 are unsupported; newer APIs degrade gracefully.

### D4. Distribution

Status: Decided (2026-11-03)

Context: Building llama.cpp with the NDK and vendor toolchains is slow and error-prone for application developers.
Decision: The SDK is distributed as prebuilt AARs on Maven Central, with no build-from-source option for consumers.
Consequences: Native builds run only in CI and on the maintainer's machine; the AARs are the only supported way to consume the SDK.

### D5. Process model

Status: Decided (2026-11-03)

Context: Running the engine in a separate process would add IPC, a service lifecycle and a second memory footprint.
Decision: The engine runs in-process only.
Consequences: A native crash takes the app down; in exchange there is no IPC cost and no local network surface (see D12).

### D6. Engine lifetime

Status: Decided (2026-11-03)

Context: ggml's backend registry, several environment variables and llama.cpp's log callback are process-global.
Decision: There is one runtime per process, and models are independent handles created from it.
Consequences: `LlamaBro.getInstance(context)` is a singleton, a second runtime configuration is rejected with an error, and several models can be loaded at once, each with its own worker thread.

### D7. Context size

Status: Decided (2026-11-03)

Context: Context size determines the memory a model needs, and a phone has little to spare.
Decision: Context size is chosen when a model loads. `Auto` fits it to available memory, capped at the model's training context; sampling settings can be set per request.
Consequences: Changing the context size needs a reload. The fitting rule was later refined by D22, D33 and D34.

### D8. Conversation helper

Status: Decided (2026-11-03)

Context: A stateless request API is flexible but leaves beginners to manage history, tools and compaction themselves.
Decision: The SDK includes a `Conversation` helper that follows `tools/ui`'s message handling, without hiding any request feature.
Consequences: The beginner path and the full request path coexist; the helper's behaviour is checked against `tools/ui` with golden tests.

### D9. Downloads

Status: Decided (2026-11-03)

Context: An earlier revision made the SDK own a WorkManager path and call the app's notification builder, which forced manifest permissions and a dependency on every consumer.
Decision: The hub downloads and caches; it does not schedule work, run in the background or touch notifications. The app runs the download wherever it likes and builds its own notification from the progress flow.
Consequences: There is no background-download artifact; a reference recipe lives in the download acceptance app instead.

### D10. llama.cpp version and patches

Status: Decided (2026-11-04)

Context: The SDK builds against an upstream library that is not a public API, and one local patch (`delta-net-head-tail-split`) speeds up prompt processing for hybrid models (F7, F8).
Decision: llama.cpp is pinned at `b12052`, and the patch mechanism and that one patch ship. Upstream pull requests wait until the implementation is complete so that they carry all the evidence; llama.cpp's contribution rules apply, including searching existing issues first.
Consequences: Every upgrade needs a compile check and the contract tests, and patches are applied to a build copy, never committed into the submodule. Amended by D52, which moves the pin to `b12352`, by D55, which adds a second patch carried from an upstream pull request, by D67, which adds a third, and by D80, which replaces the head/tail patch with a one-line Hexagon gate. Amended also by D86 (patches 0005, 0006), D90 (which moves them and 0007 to the voice-mode phase), D91 (patch 0004) and D93 (the pin moves to `b12479`).

### D11. Tool gating

Status: Decided (2026-11-03)

Context: Deciding which tools a model may call, or whether a call needs approval, is application policy.
Decision: Tool gating is out of the SDK.
Consequences: A tool handler is a suspend function, so an app can wait for its own approval step inside it; the SDK has no permission system.

### D12. Inference engine

Status: Decided (2026-11-03)

Context: Writing a pipeline on top of `libllama` would mean maintaining prompt caching, speculative decoding, multimodal input and output parsing indefinitely. Upstream's `server-context` library already provides them, with route handlers that take plain structs and need no socket.
Decision: The SDK embeds llama-server's core library in-process and calls its handlers directly. The spike passed: speed was within about 2% of the llama-server binary (F1-F4).
Consequences: Behaviour matches llama-server and its documentation applies. The coupling to a non-public API is small but needs contract tests on every upgrade.

### D13. Long conversations

Status: Decided (2026-11-03)

Context: llama-server never compacts or edits a conversation: context shift is off and a full context is a typed error. Compaction is a client feature of `tools/ui`.
Decision: The engine behaves like llama-server. Compaction lives only in `Conversation`, which follows `tools/ui`'s algorithm exactly, and the compaction node is appended at the end with a kept-step count as `tools/ui` stores it (F26).
Consequences: Apps using `Model.chat` directly implement their own compaction. Compaction is user-initiated, with no automatic threshold.

### D14. Tool execution

Status: Decided (2026-11-03)

Context: `tools/ui`'s tool loop runs calls in order, feeds errors back to the model and stops when a reply has no calls.
Decision: `Conversation` runs registered Kotlin tool handlers automatically, with the same loop and a default limit of 100 turns.
Consequences: A tool without a handler stops the loop with `ToolCallsPending`, which gives a manual mode through `addToolResult` and `resume`.

### D15. HTTP client

Status: Decided (2026-11-07)

Context: The hub needs a few JSON requests and large ranged downloads, and a dependency on an HTTP library could clash with the app's own version.
Decision: The hub uses `HttpURLConnection`, with redirects handled by the SDK (confirmed by D39).
Consequences: No dependency is added, and the SDK controls which host receives the access token.

### D16. Model storage

Status: Decided (2026-11-03)

Context: Backed-up directories silently disable cloud backup above 25 MB and copy gigabytes of models to a new phone, and removable storage could remove a mapped model.
Decision: The default cache is `noBackupFilesDir/llamabro/hub`; the app may choose another directory.
Consequences: Models are excluded from backups and are not purged under storage pressure.

### D17. Phone defaults

Status: Decided (2026-11-03)

Context: llama-server's defaults suit a server: four slots, an 8 GiB RAM cache and 32 checkpoints per slot would consume most of a phone's memory (F9-F11).
Decision: `n_parallel` is 1, `n_ctx_checkpoints` 8, `cache_ram_mib` 1024, and `n_batch` and `n_ubatch` both 512. Each value was measured on a device.
Consequences: Cancelling during a long prompt frees the slot in 0.4 s instead of 2.0-2.6 s. All values can be overridden in `ModelConfig`.

### D18. Sampler chain

Status: Decided (2026-11-03)

Context: A candidate sampler order and penalties counted over generated tokens only were tested against upstream's behaviour (F12-F15, F17).
Decision: Upstream's sampler order and its prompt-in-penalty behaviour are kept, together with the GGUF's `general.sampling.*` defaults. `samplers` is exposed per request.
Consequences: The candidate patch is not shipped. Greedy output and sampling behaviour match llama-server.

### D19. Demo app

Status: Decided (2026-11-03)

Context: The first-generation app and engine predated the new SDK and would have constrained its API.
Decision: The demo app is rebuilt from scratch in its own phase after the Hub, and the first-generation modules were deleted.
Consequences: The SDK was shaped without a legacy app to accommodate; the rebuilt app consumes only public API (see D46).

### D20. Native packaging

Status: Decided (2026-11-04)

Context: Backends must load from inside an APK, and a library's exported symbols must not collide with an app's (F25, F28, F29).
Decision: Backends load by file name, straight from the APK, using a port of `ggml_backend_load_best`; no extraction and no legacy packaging is needed. Libraries carry a `llamabro-` prefix and `libllamabro.so` exports only the C API and `JNI_OnLoad`. The AAR ships R8 keep rules for every class the JNI layer looks up. The Hexagon skeleton is the exception: the DSP loader needs a file, so it ships as an asset and is copied to `noBackupFilesDir` (F19).
Consequences: Adding a backend AAR is enough to enable it, and app shrinking does not break the JNI layer.

### D21. Verification

Status: Decided (2026-11-04)

Context: A change to the bridge or packaging can break behaviour that only shows on a device (F31).
Decision: Every SDK change passes `sdk/scripts/check.sh`, which compares each layer with upstream where upstream exists. Native, JNI or packaging changes also pass `--device`.
Consequences: Contract, golden and device tests are part of the normal workflow rather than a release step.

### D22. ContextSize.Auto, first version

Status: Decided (2026-11-04)

Context: ggml-cpu counts all RAM as free, so upstream's fit cannot see a phone's real limit (F33).
Decision: `Auto` used upstream's fit (`n_ctx` 0) fed with the phone's available memory through margins computed from `ActivityManager.MemoryInfo`, replacing an earlier fixed `min(8192, n_ctx_train)`.
Consequences: Superseded in practice by D33 and D34, which found that upstream's fit misjudges a phone (F66).

### D23. Device coverage in the backend phase

Status: Decided (2026-11-04)

Context: No non-Qualcomm phone was available for testing.
Decision: Vulkan was to be built and verified on one Adreno GPU only and kept out of `DevicePolicy.Auto` until a GPU is on its allow-list.
Consequences: Amended by D31, which removed Vulkan from 1.0 after the verification failed.

### D24. Backend discovery and device choice

Status: Decided (2026-11-07)

Context: Backend AARs must be usable by adding a dependency, and device selection must be explicit because ggml's own scan can pick unsuitable devices (F32, F35, F61, F65, F78).
Decision: Each backend AAR announces its installer through manifest `<meta-data>`; the core reads the merged manifest and loads the libraries by name. The SDK always sets an explicit device list directly in `common_params.devices`, not through `--device`, whose handler re-runs ggml's backend scan. `Auto` tries NPU, then GPU, then CPU, falls back on a load failure, and records every attempt in `loadReport`.
Consequences: The order became NPU, OpenCL, CPU once Vulkan left 1.0 (D31). A benchmarking policy is deferred to a later phase. D92 keeps `Auto` open on chips nobody has verified and says so in the docs.

### D25. API revision before the backend phase

Status: Decided (2026-11-04)

Context: A review of the first public Kotlin API found binary-compatibility traps, a beginner path that could not reach most request options, a blocking `close()`, errors without causes and no way to fake the SDK in tests (F37-F44).
Decision: A dedicated phase fixes these before the backend work builds on the API.
Consequences: The API was settled before external use, at the cost of a short delay.

### D26. API evolution rules

Status: Decided (2026-11-04); its value-type rule is replaced by D102

Context: A public type that gains a field breaks binary compatibility if it is a data class with `copy`, `componentN` or constructor defaults (F37, F42, F44).
Decision: Public types that will grow are immutable classes built through a builder or DSL, with no `copy`, no `componentN` and no public constructor defaults; small value types that will not grow may be data classes. Sealed and enum sets (`ChatEvent`, `FinishReason`, `LlamaBroException`) are settled at 1.0 and grow only in a major version. The public API dump is checked by `check.sh`, and the API is Kotlin only.
Consequences: Minor versions can add fields and options without breaking callers. The rules apply to every module.

### D27. Testing seam

Status: Decided (2026-11-04)

Context: Apps need to test their view models and flows without a device or a native library (F39).
Decision: A `llamabro-testing` artifact runs the real `Model` and `Conversation` over a scripted fake engine that speaks llama-server's event stream. The engine interfaces are public only under the `@InternalLlamaBroApi` opt-in, and the public classes stay final.
Consequences: Adding members to public classes never breaks an app, and fake-driven tests exercise the real conversation logic.

### D28. Finishing the API revision lean

Status: Decided (2026-11-05)

Context: Midway through the API revision, the work was drifting into polish with little gain for apps.
Decision: The remaining tasks were cut to what callers need: load failure reasons are limited to `FileNotFound` and `Other` (F51), the shared fake source was dropped (F54), and the documentation tasks were merged into one closing task.
Consequences: The phase ended earlier, and the load-failure reasons were extended later only where a caller needed a distinction (see D33).

### D29. Backend phase plan

Status: Decided (2026-11-05)

Context: The backend plan was checked against the revised API and needed eight changes.
Decision: The branch is cut from the API branch; `backends()` is a suspend function like `devices()`; `ContextSize.Auto`'s documentation is rewritten; `Engine.backends()` gets a default so the fake keeps compiling (F54); `DeviceInfo.type` becomes `kind: DeviceKind`; the weight-duplication issue (F50) is settled before memory is measured; speculative decoding is measured on CPU, NPU and OpenCL only; and the full acceptance suite runs on the `full` flavor only, with the `cpu` and `gpu` flavors checking device choice, one chat and stop.
Consequences: Phone time was reduced without lowering code quality (see D28).

### D30. Load weights without mmap

Status: Decided (2026-11-05)

Context: With mmap, the CPU repack copies the weights and the mapped pages stay resident, so Qwen3.5 4B used 5.5 GB PSS instead of 3.4 GB (F58, F60).
Decision: The SDK loads with upstream's `--load-mode none`. The alternative of mmap plus an unmap patch keeps mmap's speed but peaks at 5.4 GB while loading, and the direct-I/O mode lost (F59).
Consequences: Loading is about 1.2 s slower, memory never exceeds its final value, and no patch is needed. The patch is kept as material for an upstream proposal.

### D31. Vulkan out of 1.0

Status: Decided (2026-11-05)

Context: ggml-vulkan at `b12052` aborts the process on every Q4_K model load on the test phone's Adreno 830, in a way `loadReport` cannot catch, and gives wrong output for Q8_0. Both are known driver bugs with no upstream fix (F64).
Decision: Vulkan is removed from 1.0 and the GPU is served by OpenCL (F63). The build recipe is kept for a retry after a llama.cpp or driver upgrade.
Consequences: Amends D23, D24 and D29. `llamabro-vulkan` is not built or checked. Because devices are chosen by kind (D32), a returning Vulkan route would change nothing public.

### D32. DeviceKind is Npu, Gpu, Cpu

Status: Decided (2026-11-05)

Context: Apps care about which chip runs a model, not which backend library drives it.
Decision: Apps choose a chip by kind: `DevicePolicy.Auto`, `Prefer(kind)` and `Only(kind)` over `DeviceKind` with `Npu`, `Gpu` and `Cpu`. The SDK maps a kind to the backend and device the phone has and names both in `loadReport`. `DeviceInfo.kind` replaces ggml's type string (F65).
Consequences: Enums are settled at 1.0 (D26); new GPU routes need no API change. This replaces the plan's backend-named enum.

### D33. ContextSize.Auto fits all memory

Status: Decided (2026-11-05)

Context: Upstream's fit checks device memory only and treats the host as unlimited; the NPU reports 0/0, and on any failure llama-server loads the full training context (262K for Qwen3.5). On the CPU the check even omits the repacked weights (F66).
Decision: The bridge uses upstream's own dry-run measurement (`common_fit_measure`) at 4096 tokens and at the ceiling, sums every row (devices and host share one RAM on a phone) and interpolates to the budget as upstream's second step does. Upstream's `--fit` is off, so context and placement are always explicit. Below 4096 tokens the load fails with `InsufficientMemory` instead of loading anyway.
Consequences: Amends D22. A load that does not fit ends the device fallback, because every device shares the phone's RAM; projections differ by device by up to about 260 MiB, so on the edge the CPU might have fitted where the NPU did not (F80).

### D34. Auto's budget and ceiling

Status: Decided (2026-11-06)

Context: A researched choice was needed that works on many kinds of phones. Some OEM builds report less available memory than the kernel does, because of GPU and NPU driver pools that the kernel can reclaim (F66).
Decision: The budget is the kernel's `MemAvailable` from `/proc/meminfo`, less Android's low-memory threshold, the prompt cache, an image encoder's file and a 512 MiB reserve (measured, F66); `availMem` is used only if `/proc/meminfo` cannot be read. `Auto` stops at 16 384 tokens, because common local defaults are 4096, current models spend 1-3K tokens per turn on reasoning and tool results, and a dense 3B model's cache at 16K is about 1.8 GB. `ContextSize.UpTo(maxTokens)` applies the same fit with the app's own ceiling.
Consequences: Amended by D45, which makes the reserve configurable. The rule is documented in the KDoc and the SDK README.

### D35. n-gram speculation is an opt-in

Status: Decided (2026-11-06)

Context: n-gram speculation decodes code edits 7 times faster on the NPU and 2.3 times faster on the CPU with byte-identical replies, but partly repeating output is 10-15% slower on the CPU (F72).
Decision: `Speculative.Ngram` loads with upstream's `--spec-default` (ngram-mod, n_match 24, n_min 48, n_max 64) and combines with `Speculative.Mtp(n)`, with n-grams tried first. `Speculative.Auto` stays off.
Consequences: Apps whose output is dominated by edits or structured text can opt in; the default never regresses prose.

### D36. DFlash is an opt-in

Status: Decided (2026-11-06)

Context: On the NPU, DFlash decodes structured output 1.5 times faster than MTP and 2.3 times faster than no speculation, but it loses on prose and on the CPU and needs about 2.9 GB more memory (F73).
Decision: DFlash is an opt-in once the hub can fetch a draft file next to the model, and it is never chosen by `Auto`. EAGLE-3 and DSpark are left out, since they lost everywhere with the checkpoints that exist.
Consequences: Carried out by D41. The draft's memory counts in the `Auto` budget.

### D37. Text to speech is out of 1.0

Status: Decided (2026-11-06)

Context: Qwen3-TTS 1.7B speaks clearly but below real time on every chip, its codec aborts on the NPU, and `rawRequest` returns strings, so the SDK cannot return audio bytes (F74).
Decision: Text to speech does not ship in 1.0; it is revisited after a llama.cpp or hardware change. Backend sampling also stays as it is, per request and off by default, since it gained nothing (F70).
Consequences: A byte-returning request and a way to keep the multimodal projector on the CPU would be needed first (F74). To be revisited by the voice-mode phase after PH (D85), now that LFM3-Omni-2B speaks faster than real time on the phone (F154). Amended by D95, which brings speech output in through a realtime voice conversation.

### D38. The CPU path runs without OpenMP

Status: Decided (2026-11-07)

Context: Upstream's Snapdragon preset builds without OpenMP, and every llama-server run the phone measurements were compared against used that build. Six rounds on the CPU gave the same median speed either way, and the only two slow runs used OpenMP (F77).
Decision: `GGML_OPENMP` is off; ggml uses its own thread pool, and the AAR no longer ships `libomp.so` (0.97 MB).
Consequences: `check-aar.sh` fails if `libomp.so` returns.

### D39. The hub is a Kotlin port of -hf

Status: Decided (2026-11-07)

Context: Upstream's download code cannot run in the SDK, which builds llama.cpp without OpenSSL (F81).
Decision: `llamabro-hub` is pure Kotlin, a port of upstream's resolve, file choice, cache layout, locking, resume and removal (F82) over `HttpURLConnection`, with no native code and no dependency. Parity is checked with golden files produced by upstream's own code from the same fake server (F83).
Consequences: Deliberate differences from upstream are listed in F82 and D44.

### D40. Every download is hashed

Status: Decided (2026-11-07)

Context: Upstream never verifies a downloaded file (F83).
Decision: Each LFS file is hashed with SHA-256 while it is written, and a resumed file re-hashes its existing part first. A file enters the cache only if the digest equals the repository's `oid`; otherwise the partial is deleted and the download fails with `HubException.IntegrityMismatch`.
Consequences: Downloads that resume are verified, at the cost of re-reading the partial (about 0.75 s per GB on the test phone, F89).

### D41. DFlash ships with the hub

Status: Decided (2026-11-07)

Context: D36 deferred DFlash until the hub could fetch a draft file.
Decision: The hub fetches the multimodal projector by default and a draft head only on request, from the model's repository or a second one, like `llama-server`'s `-hfd` (F82). The core gains a draft file on `ModelSource` and `Speculative.DFlash` (`-md`, `--spec-type draft-dflash`, `--spec-draft-n-max 15`, `-fa on`), which is never used on the GPU (F80). `ContextSize.Auto` counts the draft's memory.
Consequences: MTP heads sit inside the GGUFs measured so far (F67), so the sidecar kinds are the projector and DFlash. It was tested on the phone with a converted Qwen3.5 4B draft (F73).

### D42. A fit check before download; search stays in the app

Status: Decided (2026-11-07)

Context: An app wants to tell a person whether a model fits before downloading gigabytes, while llama.cpp has no model search.
Decision: The hub provides a pre-download estimate from llama.cpp's own projection (D33), run on the GGUF header fetched with an HTTP `Range` request (F84). Model search is not added, because it is one Hugging Face API call any app can make and falls outside the scope rule.
Consequences: If the projection had failed in a spike, the estimate would have been file size plus the context cache computed from header metadata, labelled as such.

### D43. How downloads are tested

Status: Decided (2026-11-07)

Context: Download tests must be repeatable and offline, yet resume behaviour on a real phone also matters.
Decision: Host tests and the phone's kill-and-resume test run against a fake Hugging Face server (refs, tree, a 302 to a second host, `Range`, ETag change, authentication) reached over `adb reverse`. One real download of a small public GGUF closes the phase.
Consequences: Most hub behaviour is tested without network access; real-service behaviour is confirmed once (F93).

### D44. Hub deviations after review; one download at a time

Status: Decided (2026-11-09)

Context: Review of the hub (F91) found three places where matching `-hf` exactly was worse than deviating.
Decision: A complete partial whose digest matches is renamed with no request (upstream re-downloads after a 416). `delete(tag)` removes the tag's files from every snapshot of the repository, while blobs go only when no snapshot links them. A tree entry without a digest is skipped (D40). Files download one at a time by default, as measured in F89 and F91.
Consequences: Cache layout stays compatible with llama.cpp. A device test accepts an `Auto` refusal that agrees with `estimateFit`, since the test phone's budget was 9-14 MiB short of DFlash's minimum (F90). Amended by D89, which fetches a selection's files in the plan's order.

### D45. The memory reserve is the app's to change

Status: Decided (2026-11-09)

Context: A device test was 9-14 MiB short of the DFlash minimum because of the fixed 512 MiB reserve (F90).
Decision: `ModelConfig.memoryReserveMib` (default 512, minimum 0) is the memory that `ContextSize.Auto`, `UpTo` and `estimateFit` leave free for what grows during a chat, like llama-server's `--fit-target` margin. Its documentation warns that Android's low-memory killer ends the whole app without an exception. Tests about something else trade the prompt cache (`ramCacheMib`) instead.
Consequences: Amends D34. Lowering the reserve is the app's call and risk.

### D46. The demo app's design

Status: Decided (2026-11-09)

Context: The demo app is the first real consumer of the API and should reflect everyday use rather than a feature showcase.
Decision: The demo app is an on-device chat application for newcomers and experts. It offers three curated models on first run and lets a person type before the download ends, has two layouts (a sidebar and a chat list), searches Hugging Face grouped by model with sizes tinted by how each runs on the phone, and supports images, thinking effort, edit and regenerate, sharing and custom instructions. It lives in a top-level `demo/` project built against the SDK through a composite build.
Consequences: Anything the app needs from the SDK is a finding first and then an SDK change; the one known case was starting a `Conversation` from stored messages (F96).

### D47. A resumed partial is verified by re-reading it

Status: Decided (2026-11-10)

Context: A resumed multi-gigabyte partial takes time to re-hash, and cheaper schemes would need per-chunk checkpoints or Xet chunk hashes (F94, F89).
Decision: The hub keeps D40's whole-file check: a resumed partial's existing bytes are hashed again before the download continues (phase `Verifying`), and a complete partial whose digest matches is renamed with no request (D44). No per-chunk checkpoints and no Xet chunk hashes are added.
Consequences: The slow resume of a large partial is accepted, to add no new format, code or deviation from upstream. It is revisited only if an app reports a problem.

### D48. The demo app follows one design system

Status: Decided (2026-11-12)

Context: An audit of the demo app's type, spacing and colour found inconsistencies (F109).
Decision: Type, spacing, colour and states follow Material 3 (its type roles and 4 dp grid), enforced by the build: screens are assembled from layout primitives that own gutters and gaps, and no `.sp` or spacing `.dp` literal may appear outside the design system. Navy is the single primary colour and black is ink only. The serif face is for hero moments only (first-run greeting, empty-chat line, model-sheet title). Settings, About and Storage are grouped cards, and body text is 16 sp.
Consequences: Applied as a design-system revision before the final review, so that the review covered the final design.

### D49. Where Material is silent, follow the leading chat apps

Status: Decided (2026-11-12)

Context: Material 3 gives numbers for fields, buttons, chips and lists but nothing for a chat composer, message bubbles, reply actions or scrolling (F116).
Decision: For those, the app follows common practice among leading chat apps, read from public teardowns and release notes; no leader publishes pixel values, so every number is the app's own. Four changes were made: the bubble for outgoing messages is a soft neutral in both themes (light `#E9E7E1`, dark `#2A2C31`), the thinking control sits inside the message bar, send is quiet while there is nothing to send, and a "back to the latest message" button appears when the chat is scrolled away from its end. The memory refusal offers "Try again" first and "Smaller size" second.
Consequences: Replaces D48's use of near-black for the outgoing-message bubble. Copy, share and regenerate show under the latest reply only, and the list follows a streaming reply only while at the end.

### D50. Model names, message bar and download indicator in the demo app

Status: Decided (2026-11-12)

Context: Friendly nicknames for curated models added a second name for each model and obscured which model was running.
Decision: Every surface shows a model's own name ("Qwen3.5 0.8B", "Gemma 3 4B", "Qwen3.5 4B"), with a plain line beneath saying what it is for. The message bar is text above a row of buttons: a plus at the start and, for a model that can think, a lightbulb icon just left of send, tinted when on, with its level in the description and the menu it opens. The download's ring and percentage sit beside the model's name in the chat's top bar, and the time left appears in the ring's description and in the notification.
Consequences: Replaces D46's friendly names and D48's rule of one friendly name with the real one beneath it: a model has one name, its own, everywhere. The thinking control is an icon rather than a chip.

### D51. With the model on the NPU, the image encoder runs on the CPU

Status: Decided (2026-11-13)

Context: ggml-hexagon computes some vision encoders wrongly. Qwen3.5 0.8B's encoder on the NPU reads a red and blue picture as "blue and black", and upstream llama-server does the same, because it also puts the encoder on the model's device (F120). The demo app's suggested vision models run on the NPU under `DevicePolicy.Auto`.
Decision: When the model's device is the NPU, the multimodal projector (image encoder) runs on the CPU. On the GPU it stays on the model's device, where OpenCL encodes correctly. This deviates from upstream on measured correctness (F120), not speed.
Consequences: A picture takes about 4.6 s longer to encode (0.4 s to 5.0 s of prompt time for one 640x480 picture with the 0.8B). Encoders that are right on the NPU, such as Qwen3.5 4B's, also pay this cost, because the SDK cannot tell which encoders are affected. Revisit when ggml-hexagon fixes the encoder; the acceptance app's `VisionTest` shows whether it is fixed. Superseded by D53 (2026-11-13): b12352 encodes correctly on the NPU (F121).

### D52. llama.cpp is pinned at b12352

Status: Decided (2026-11-13)

Context: b12352 fixes the NPU's image encoder (F120, F121) and brings fixes the SDK mirrors (`-hf` tag matching, the Hexagon library path). The contract and the `tools/ui` goldens stay identical (F121).
Decision: The submodule moves to `b12352`. The delta-net head/tail patch is re-ported to upstream's fused-only code and keeps shipping, since it still makes NPU prefill with MTP about 66% faster (F121, F8). The patched copy gets current file times, so an upgrade rebuilds every object (F122).
Consequences: Amends D10's pin. Statements in the documents measured "at b12052" (MTP, drafting on the GPU, Vulkan) stay as measured until retested. `Endpoint.Speech` is the new route. Amended by D93, which moves the pin to `b12479`.

### D53. The image encoder follows the model unless the app moves it

Status: Decided (2026-11-13)

Context: D51 kept the encoder on the CPU with the model on the NPU, because ggml-hexagon computed some encoders wrongly. b12352 fixes that (F121), and the CPU costs seconds per picture (F123). Apps may still want another chip, as LiteRT-LM offers a separate vision backend.
Decision: `ModelConfig.encoderDevice: DevicePolicy?`, mapped to llama-server's `--mmproj-device`. Unset (the default), the encoder follows the model's device, as upstream. `Only(kind)` fails the load with `DeviceUnavailable` when that kind isn't available, `Prefer(kind)` falls back to the model's device, `Auto` takes the fastest available. `LoadReport.encoderDevice` and `encoderDeviceName` say where it was placed; `Prefer` and `Auto` also fall back when the encoder's device fails to load. One setting covers images and audio, since llama.cpp runs both from one projector on one device.
Consequences: Supersedes D51. `VisionTest` asserts that the model names a picture's colours with the encoder on the NPU and on the CPU, on the phone.

### D54. The NPU's skeleton is found through GGML_HEXAGON_LIBHTP_DIR

Status: Decided (2026-11-13)

Context: Since b12352 ggml-hexagon opens its skeleton by absolute path, from `GGML_HEXAGON_LIBHTP_DIR` or the backend library's own directory, and falls back to FastRPC's search path and then to the DSP `*_LIBRARY_PATH` variables. Upstream's own launcher stopped setting those variables (F121). The SDK set all five process-wide (F19, F36), which could disturb an app's other DSP users.
Decision: `HexagonInstaller` sets only `GGML_HEXAGON_LIBHTP_DIR` to the installed skeletons' directory.
Consequences: Verified on the phone by the backend, acceptance and lifecycle tests with the NPU (`check.sh --device`).

### D55. Upstream PR #30432 is carried as a patch until it merges

Status: Decided (2026-11-13)

Context: ggml-cpu has no ARM path for BF16 dot products, so a BF16 image projector on the phone's CPU takes 16.5 s; the open upstream PR #30432 adds one, and on the phone it takes 3.1 s with nothing else changed (F127). The alternative, `-ffp-model=fast`, changes every CPU result for no gain on text (F126).
Decision: Ship the PR's diff (head `cde3115c`) as `sdk/native/patches/0002-bf16-neon-vec-dot.patch`, and drop it once the change is in the pinned build. Each patch's origin and drop condition are kept in `sdk/native/patches/README.md`, and the llama.cpp upgrade checklist in `CONTRIBUTING.md` checks them first.
Consequences: Amends D10's "that one patch": two patches ship. A carried upstream PR is the one kind of patch expected to go away by itself; if the PR changes before merging, the merged version replaces it.

### D56. Leaving a chat stops its reply

Status: Decided (2026-11-13)

Context: Each open chat runs through an engine handle owned by its screen's view model (`ChatEngine.ChatHandle`). An architecture review asked whether a reply should keep streaming after the user leaves its chat. The handle's KDoc settles it: everything a handle runs lives in its own scope, so a chat that was left stops observing the database, stops pumping held messages and stops its reply, which is saved as stopped.
Decision: Leaving a chat (its screen's view model is cleared) closes its handle and stops its reply; what had arrived is kept as a stopped reply, as a Stop would keep it. A rotation or process death does not leave the chat: the view model survives the one, and the other kills the reply anyway (it is marked stopped at the next launch, before the first turn).
Consequences: Only the chat on screen ever uses the model, so a chat left mid-reply never holds memory or the turn while the user is elsewhere. A new chat keeps its view model when its id resolves (the id goes into the screen's saved state, not a new route), so its first reply is not stopped by that.

### D57. The memory check warns by default

Status: Decided (2026-11-13)

Context: `ContextSize.Auto` and `UpTo` fit the context to `MemAvailable` less Android's low-memory threshold, the prompt cache, an encoder's file and a reserve. When even 4096 tokens did not fit, the load failed with `InsufficientMemory` (D33). On a phone with background apps this refuses models that run: Android closes cached apps to make room for the app in front, and the check can't see that memory. Qwen3.5 4B was refused with 5.7 GB available and then loaded and answered (F128). The demo had worked around it with a fixed context.
Decision: `ModelConfig.memoryCheck` chooses. `MemoryCheck.Warn`, the default, loads at the smallest context (4096, or a smaller `UpTo` ceiling) without the budget when the check says no, and `LoadReport.memoryWarning` carries the check's numbers. `MemoryCheck.Refuse` keeps the old failure. `Fixed` has no check, as before. The budget formula itself is unchanged.
Consequences: A model that loads is no longer refused for a figure the SDK can't measure. An app tells the person in its own words, as the demo does with a note and "Smaller size". A model that really doesn't fit now fails in the load itself, or later through Android's low-memory killer, which is the risk D45 named; an app that can't take it sets `Refuse`. `estimateFit` still reports `fits = false` before a download.

### D58. The chat moves with Material 3's motion

Status: Decided (2026-11-14)

Context: The chat had motion only in the wake hairline, the download ring and the thinking fold; messages, notes and the jump button appeared and vanished in a frame, and the time between sending and the first word showed nothing in the list. Material 3's motion guidance gives durations and easings by role (an arrival, a departure, a change in place), and the leading chat apps fade new messages in, show a typing indicator until the first word, and animate a "thinking" label.
Decision: `DemoMotion` carries Material's values: arrivals 300 ms emphasized decelerate, departures 150 ms emphasized accelerate, changes in place 250 ms standard, a small swap 200 ms. The chat uses them through design-system pieces only (`messageMotion`, `Reveal`, `TypingDots`, the fold's shimmer and chevron, `JumpToLatest(visible)`, the send button), as demo design §8.11 lists; `ds` fails on an animation spec in `:app`. Tokens are not animated. With the animator duration scale at 0 everything is instant and every loop is still.
Consequences: The chat says what is happening in the gap before a reply, and what comes and goes does so without a jump. A new motion is a design-system change, reviewed against the table in §8.11.

### D59. The person controls the model's memory; a notification only while a reply runs in the background

Status: Decided (2026-11-14)

Context: The demo freed the model a minute after the app left the screen (unless a reply was running) and did nothing else: no answer to Android's memory signals, no way for the person to see or free the model, and a reply still being written in the background could die with the process. A persistent "model loaded" notification was considered. It needs a foreground service, whose only fitting type for an idle model is `specialUse` (justified to Play), and it would keep gigabytes from the low-memory killer while the person uses other apps, which Android then has to close instead; an idle background process is killed anyway. Releasing a model also broke the next message in the same chat (F133).
Decision: The app keeps owning the one model (`ModelHost`, a flow). It frees it after the time chosen in Settings › Memory (a minute by default, 5 minutes, or until Android needs it), and at once on `onTrimMemory` at `TRIM_MEMORY_BACKGROUND` or above, never while a reply runs (F134). The person sees the model in memory, with Unload, in Settings › Memory and the chat's model sheet. A reply still under way when the app leaves the screen runs under a `shortService` foreground service with a "Writing a reply…" notification and Stop; at Android's limit the reply stops, keeping what arrived. A conversation is tied to the loaded model, not only its key, so a chat rebuilds it after a release. The SDK stays free of app lifecycle: this is the app's policy.
Consequences: The memory is freed when it matters and kept when the person asks, and they can free it themselves. No notification sits in the shade for an idle model. A reply that runs longer than the short service allows in the background is stopped, not lost. Saving the chat's slot to disk on release (llama-server's slot save, reachable through `rawRequest`) cuts the reload's prompt from 3.5 s to 0.3 s for a 3k-token chat, at 97 KB a token on disk (F135); whether to build it is open.

### D60. Each model starts from its card's sampling, a chat can change it again, and a reply has a length limit with Continue

Status: Decided (2026-11-14)

Context: The demo sent no sampling values, so every model ran on llama-server's generic defaults (temperature 0.8, top-k 40, no presence penalty) although the models' cards recommend others; the GGUF uploads carry no `general.sampling.*` to apply (F17). Qwen3.5 0.8B looped in its answer until Stop, three tries in four (F113): no length limit, no penalty. The leading local apps keep settings per model, Open WebUI lets a chat override them, and only GPT4All has a guard on by default (F136).
Decision: The catalog carries each suggested model's card values, per mode where the card differs (Qwen3.5 thinking and not). Five values can be changed, temperature, top P, top K, min P and presence penalty, in two layers: a model's own (Settings › Sampling, per model key) and a chat's own (the model sheet's "This chat"), the chat's over the model's over the card's over llama-server's. A value nobody set is not sent, so a model file's own value still applies. Reset returns a model to its card and a chat to its model. Every reply has a length limit: about 2,000 tokens for the answer plus the Think level's budget (2,048 Off, 2,560 Quick, 4,096 Balanced, 8,192 Deep). A reply that reaches it is kept as cut, "Stopped at the length limit.", and the chat's last reply offers Continue: the chat is sent with that reply as its last message and llama-server goes on writing it, its thinking or its answer (`prefill_assistant`, F137).
Consequences: Qwen3.5 runs with its presence penalty, and a loop ends at the limit instead of at Stop; a long answer that was merely long goes on with one tap. The page shows each value's source, so a person sees what they changed. Search finds models without card values; bringing a searched model's recommended values from the Hub is proposed for PH (sdk-review item 8). Qwen3.5's values for pictures, the repeat penalty, DRY, XTC and named presets are left out. The reply length can be chosen in Settings › Sampling, No limit included (D78).

### D61. Typed tools: annotated toolboxes generated by KSP, over a functional form, in `llamabro-typedtools`

Status: Decided (2026-11-15)

Context: A tool is declared today as a name, a description and its parameters' JSON Schema written as a string, with a handler that reads untyped arguments (`args.string("city")`) and returns a string. Real tools call APIs, so they suspend and need injected dependencies. Annotated declarations made Retrofit easy to adopt, and Spring AI, LangChain4j and Koog declare tools the same way. Reflection could read such declarations at runtime, but even with keep rules it needs `kotlin-reflect`, fails at runtime instead of at build time and can't follow the planned Multiplatform bindings (F142). Small models choose tools worse as the tools grow (F141), the model chooses by the tool's description, and llama-server passes it only a tool's name, description and parameters (F139). Not every app uses tools.
Decision: Typed tools are a separate dependency in three layers, each built on the one below. (1) A tool is a name, a description, an arguments serializer, a result serializer and a `suspend (A) -> R`; its schema comes from the arguments' serial descriptor through the converter that typed structured output (sdk-review item 2) also uses, which lives in core with `@LbDescription`, the field description both read. (2) A functional form, `tool<Args, Result>("name", "description") { args -> … }`, whose lambda captures whatever the app injected. (3) A KSP processor (`llamabro-typedtools-ksp`) that writes layer-2 code from annotations; no reflection. `@LbToolbox` marks an interface, class or object; tools declared in an `@LbToolbox` supertype are included, an override may not be annotated again, and a tool reached twice counts once. `@LbTool("description")` marks a method; the description is required, KDoc is never used, the name is the method's in snake_case unless given, checked at build time against `^[a-zA-Z0-9_-]{1,64}$` with the toolbox's optional prefix, and colliding overloads are a build error. A method takes one `@Serializable` class without `@LbToolParam` (the whole arguments object, where kotlinx.serialization applies real defaults) or separate parameters, each with `@LbToolParam` (the processor generates the arguments class and copies the text into `@LbDescription`); in the separate form a parameter is optional only as nullable `= null`, and another default is a build error that points to the data-class form. A parameter's description is optional. A parameter of type `LbToolCallContext` (call id, tool name, raw arguments) is passed in and left out of the schema. A tool returns a `String` (sent as is), a `@Serializable` value (sent as JSON), `Unit`, a primitive or a list; result classes carry no annotations, since no schema for a result reaches the model (F139): what a result means goes in the tool's description. `@LbTool(requiresApproval = true)` pauses the turn for the app (D62). The generated `asToolbox()` extension gives the app a `Toolbox` from its own instance, so a missing processor is a build error and a DI container can provide toolboxes. The SDK constructs nothing and depends on no DI library. Tools are registered per conversation, as today: changing them mid-chat would reprocess the whole prompt, which they open (F139). The raw string form stays in core for tools defined at runtime, with `Tool`, `ToolCall`, `ToolChoice` and the agent loop.
Consequences: Declaring a tool is a typed, suspending method on an injected class, checked when the app builds. Apps without tools add nothing. The KSP artifact is an extra build dependency for apps that annotate; the functional form needs none. Where typed tools are shown (demo, sample) is decided after they are built and tested; the demo's design leaves tools out today. Amended by D63, which moves typed tools, the schema converter and `@LbDescription` into one `llamabro-typed` module (with `llamabro-typed-ksp`), so core keeps no typed API.

### D62. The agent loop: unknown tools and invalid arguments answer the model, only `LbToolError` messages reach it, blocking tools leave the caller's thread, and approval pauses the turn

Status: Decided (2026-11-15)

Context: The loop, ported from tools/ui, treats a call to an unregistered name as a manual tool and stops the turn (F140). tools/ui sends every exception's message to the model as `Error: <message>`; such messages can carry tokens, URLs or personal data into the prompt and into the saved chat, and a tool has no way to end the turn and tell the app instead. Handlers run in the collecting coroutine, which is often the main thread, so a blocking tool would freeze the app. Some tools, such as sending a message, need the person's approval first; a tool waiting on approval inside its own call does not survive the process dying, while a pending call does (`toJson`).
Decision: A call to an unknown name gets `Error: Unknown tool: <name>` as its result and the loop goes on, as in tools/ui (F140). Arguments that don't decode get `Error: invalid arguments: <reason>`, as tools/ui does with arguments that don't parse, so the model can retry. A deliberate deviation from tools/ui, for privacy: an `LbToolError` sends its message to the model as written; any other exception sends a generic error and reaches the app through the event stream; an `LbToolAbort` ends the turn with a typed reason. Cancellation and a closed model propagate as before. A tool that does not suspend runs on `Dispatchers.IO`; a suspending tool runs where it is called, being main-safe like Retrofit's and Room's. Calls in one reply run in order up to the first that needs approval, and the turn stops there with `ToolCallsPending`; the app approves it, which runs it through the generated code and continues the rest in order, or denies it, which sends `Error: denied by the user` or the app's reason as its result.
Consequences: A small model that invents a tool name recovers instead of stalling the chat. A failing tool cannot leak its exception text into the prompt unless it chooses to. A tool that needs approval survives the app being closed while it waits. These apply to raw tools too, since the loop is core's. Amended by D83, which makes typed tool arguments follow this strictly and lets an `LbToolError`'s cause reach the app.

### D63. Typed output and typed tools share one module, `llamabro-typed`; a typed reply is a `Typed<T>`

Status: Decided (2026-11-15)

Context: An app wants a model's reply as a Kotlin object, not a JSON string to parse. `ResponseFormat.JsonSchema` takes a schema string, and upstream's grammar then holds the reply to it, after any thinking (F143). D61 put the schema converter and `@LbDescription` in core and typed tools in their own module, which split the typed machinery across two modules and made kotlinx.serialization part of core's public API. Core's scope is llama-server's own features. Today's `ask(text)` throws `LlamaBroException.Incomplete` when a reply is cut.
Decision: Everything typed is one dependency, `llamabro-typed`, with `llamabro-typed-ksp` for apps that annotate toolboxes; this amends D61's module layout. Core keeps strings, `ResponseFormat`, raw tools and the agent loop (D62), and its public API has no kotlinx.serialization. `llamabro-typed` holds the schema converter, the annotations (`@LbDescription`, the limits `@LbRange` for integers, `@LbLength` and `@LbPattern` for strings, `@LbItems` for lists; no decimal bounds, which upstream ignores, F143), typed tools (D61) and typed output: `conversation.ask<T>(text)` and a one-shot `model.generate<T>(prompt, vararg attachments, system, options)` with no history, each also taking a `KSerializer<T>` and an optional `json: Json` that the converter and the decoder both read. A typed reply is a `Typed<T>`: `Value(value, json, reasoning)`, `Incomplete(reason, json)` when it was cut, or `Invalid(json, cause)` when the grammar's JSON does not decode (a number past `Int`, a failing `init` check). Failures of the engine (context exceeded, a closed model, cancellation) stay exceptions. The plain `ask(text)` also returns a result instead of throwing, so the conversation API has one style. The schema follows declaration order (required fields first, F143) and covers primitives, strings, enums, nullable and optional fields, lists, nested classes, sealed classes and `Map<String, V>`; recursive types are refused. A sealed subclass without `@SerialName` is an error at the first call for typed output and a build error for typed tools, since its default name is the full class name; `assertTypedSchema<T>()` lets an app's tests catch schema errors before release. A typed turn in a conversation keeps the tools in the prompt but cannot call them, as upstream does (F143), and stores the model's JSON as the assistant message, so regenerate and later turns see what it wrote. Streaming a partial `T` is left out.
Consequences: Apps get typed objects with Think on, from text or a picture, by adding one dependency. A field's order is the order the model writes it in, which the documentation teaches (a reasoning field before the answer). The change to the plain `ask` is breaking, which v2 allows. Clean JSON with thinking on and off is to be confirmed in one short, attended run on the phone with Qwen3.5 once built. Amended by D71, which moves `Typed<T>` into core. Amended by D88, which bounds integers by their type by default and makes any exception while decoding `Invalid`.

### D64. One call from a Hugging Face name to a loaded model, through an open `ModelSource`; what is on the phone loads without the network

Status: Decided (2026-11-15)

Context: Getting a model from Hugging Face took two modules and three steps: `hub.download` (a `Flow`), `hub.source`, then `llama.loadModel`, whose progress is a fraction of the load only. `ModelSource` was sealed in core, so the hub could not offer a source of its own, and fitting a model before a download (`hub.estimateFit(plan)`) and after it (`llama.estimateFit`) were two calls in two places. The hub's download runs in the caller's coroutine; the demo keeps it running in the background with its own WorkManager worker, which every app would write again. Online, upstream's `-hf` re-reads `main`'s commit on every start and downloads whatever changed (F82), which on a phone is a large, silent download inside a load.
Decision: `ModelSource` is no longer sealed: core gains a public form for files that must be fetched before loading, and knows nothing of Hugging Face. The hub provides one, `hub.model("Qwen/Qwen3.5-4B-GGUF:Q4_K_M")`, so `llama.loadModel(hub.model(…))` resolves, downloads what is missing and loads, and `llama.estimateFit(hub.model(…))` projects from the GGUF headers before any download. `loadModel` stays a `suspend` call returning the `Model`; `onProgress` reports a sealed `LoadProgress`, `Fetching(DownloadProgress)` then `Loading(fraction)`, in place of the fraction (breaking, which v2 allows). It is not a `Flow` ending in a model, which would leave a loaded model without an owner when the collector stops. Cancelling during the download keeps the partial file for the next call (F114-F116); concurrent calls for one model share the transfer; cancelling a load is sdk-review item 7. A deliberate deviation from upstream, for phones: when a complete copy is on the phone it loads with no network request, and a newer commit is fetched only when the app asks (an explicit update call); a model with nothing on the phone is resolved and downloaded as `-hf` does. With no tag the choice is upstream's (`Q4_K_M`, then `Q8_0`, then the first GGUF), with its image encoder. The SDK makes no policy for the app: no fit check and no metered-network rule before a download; the app calls `estimateFit` and decides. A ready WorkManager worker for downloads that outlive the screen ships as its own module, `llamabro-hub-work`, so the hub does not depend on `androidx.work`.
Consequences: The five-minute example is `llama.loadModel(hub.model("…"))`, with one progress for both phases. A load never starts a large download unasked once the model is on the phone, and works offline. The fit before a download and after it is one call. Apps that want background downloads add one dependency instead of writing a worker. `ModelSource`'s new extension point is public API to keep stable. Amended by D75, which fixes the extension point's shape and how the worker gets its hub. Amended by D71, which moves `DownloadProgress` into core. Amended by D89, which lists only complete selections in `cached()`.

### D65. Typed embeddings, rerank and speech input in core; speech output stays out

Status: Decided (2026-11-15)

Context: A model loaded with `ModelTask.Embedding` or `ModelTask.Rerank` answers only through `rawRequest`, so an app writes JSON and parses vectors out of a string. llama-server's embeddings take text, token arrays, lists and, for models that support them, pictures and audio, with a per-request normalisation (`embd_normalize`, default 2: Euclidean); its rerank takes a query and documents and returns indices and scores, best first. An embedding or rerank input can't be split across batches, so one longer than the batch size (512 tokens, upstream's default and the SDK's) fails (`server-context.cpp:4081`). On a model loaded for another task llama-server answers "start it with `--embeddings`", which means nothing in an SDK. Speech input exists upstream as `input_audio` chat content and `/v1/audio/transcriptions`, which can read a path with no upload (F55); live transcription can run on an in-memory connection (F56); none is built, and the test set has no audio model. Speech output is out of 1.0 (D37, F74). Several models can be loaded at once (D6).
Decision: In core, since these are llama-server's features and return plain values: `Model.embed(text)`, `embed(texts)` and `embed(vararg content)` return `FloatArray`s, with a `Normalization` that mirrors upstream's (`None`, `MaxAbsInt16`, `Taxicab`, `Euclidean` by default, `PNorm(p)`), read as base64 internally; `Model.rerank(query, documents, topN)` returns `Ranked(index, score)`, best first, text documents only for now. A call on a model loaded for another task throws `InvalidRequest` naming the task to load with; an input past the batch size throws a typed `InputTooLong(tokens, limit)`, and the SDK neither raises the batch size nor splits text, which stays the app's choice (`microBatchSize`). Speech input: `Content.Audio` in chat messages and `Model.transcribe(file)` through the path route of F55. Left out: live transcription (later, F56), speech output (D37) and with it a byte-returning request, per-token vectors (raw only, `Endpoint.EmbeddingsNative`), similarity or search helpers (the documentation shows a cosine similarity), and pictures as rerank documents (an overload once a test model ranks pictures). The embedding and rerank calls are tested on the models the test set already fetches, upstream's own (`bert-bge-small`, `jina-reranker-v1-tiny-en`). Speech input is tested on the host with upstream's audio test model, `ggml-org-dev/tinyLFM3-Omni-for-testing-gguf` Q2_K (about 39 MB with its encoder; a damaged copy that writes gibberish but runs the real paths), and checked once on the phone, briefly and attended, with the real `ggml-org/LFM3-Omni-2B-GGUF` Q4_K_M and its Q8_0 encoder (about 1.6 GB), chosen with the user 2026-11-15; both are under Liquid AI's LFM Open License v1.0 and are downloaded for tests only, never shipped.
Consequences: Private, offline semantic search is a few typed calls next to a chat model. Errors name what to change. Speech input is verified only once an audio model is in the test set. Amended by D84, which gives media three sources (a file, a descriptor, bytes), has the bridge write chat media's base64, and makes `transcribe` return `Typed<String>`.

### D66. Logs from the first native call, and logcat when the app gives no logger

Status: Decided (2026-11-15)

Context: The app's logger is attached after native init succeeds, so init's lines, and all of a failing init's, never reach it; with no logger, every native line goes to stdout and stderr, which Android discards, so by default nothing reaches logcat; the hub is silent the same way; and backend failures found in Kotlin are only kept for `backends()` (F144). Upstream's default destination is the console, which on Android is logcat.
Decision: The runtime attaches the app's logger before native init, so every line from the first native call on reaches it, a failing init's included. With no logger, the core logs to logcat under the tag `llamabro` and the hub under `llamabro-hub`, at the configured level (`Warn` by default: warnings and errors); an app that wants silence passes a logger that drops lines. A backend that fails before native code (a missing manifest entry, a skeleton install that throws) is also logged as a warning with its reason. Tested on the host by a native init made to fail (its error lines reach the app's logger) and by the default destination through a seam; checked once in logcat on the phone, with no inference.
Consequences: An init that fails or hangs says why in logcat with no setup. Apps that route logs elsewhere see init too.

### D67. Cancelling a load aborts its weight reading, through a carried patch

Status: Decided (2026-11-15)

Context: A cancelled `loadModel` runs to its end and is then freed, so the caller waits for the whole load; llama.cpp can abort reading weights when the progress callback returns `false`, but llama-server's own callback always returns `true` (F145). llama-server has no need to cancel a load, so upstream has no hook for it.
Decision: A small patch, carried in `sdk/native/patches/` as D55 carries its patch, makes llama-server's load-progress callback ask a stop hook before its 200 ms throttle, so an abort lands within one chunk of reading; the change is written up in the repository as reference material for upstream, and nothing is posted (the user's rule). The C API's load progress callback returns a `bool`, `false` to stop, as `llama_progress_callback` does, and Kotlin returns whether the caller's coroutine is still active. A cancelled load frees what it allocated and throws a plain `CancellationException`. The runtime also checks between device attempts, as now, and before `MemoryCheck.Warn`'s second load. The stages without a callback (creating the context, the warm-up, compiling GPU kernels, setting up the NPU session) finish first and the model is then freed, as now; the KDoc lists them. With an image encoder or a draft, the text model's context and warm-up come before those stages, so a cancel during them is caught at the next stage's first tick. Tested natively by a callback that returns `false` at once (the load comes back cancelled with nothing allocated), in Kotlin on the fake engine, and once on the phone, briefly and attended, timing a cancelled 4B load on the CPU and the NPU with no inference.
Consequences: Leaving a screen during a load stops it in well under a second while weights are read. The patch is dropped when upstream has an equivalent. A first GPU load still can't stop during its kernel compile. Measured on the phone in F148: 0.4-1.8 s on the CPU and about 1 s on the NPU, not always under a second.

### D68. Search on the Hub in `llamabro-hub`, grouping re-uploads, with recommended sampling where the Hub has it

Status: Decided (2026-11-15)

Context: Search lives in the demo (`HfSearch.kt`), so every app would write the query, the grouping of a model's uploads, the file list and the token handling again; the demo groups a fine-tune into its original (F146). The review proposed returning each model's recommended sampling from the base model's `generation_config.json`, but that file has values for only some open models, none for Qwen3.5, and gated models need a token; a GGUF's own `general.sampling.*` is rare and llama.cpp applies it anyway (F17, F146). The SDK makes no policy for the app.
Decision: `llamabro-hub` searches the Hub for GGUF models with a query, a sort order (downloads by default, likes, trending, recently updated), the next page through the Hub's cursor, a filter for models that take pictures and a filter by author. A result groups the uploads tagged `base_model:quantized:X` under X; fine-tunes, merges and adapters stay entries of their own. A repo's files list each quantisation with its bytes (split files once, all parts summed) and whether an image encoder is there. Search gives sizes, not a fit verdict: the app compares them with `memoryBudgetBytes()` or calls `estimateFit(hub.model(…))` (D64). A model's recommended sampling is returned where the Hub has it, as a `Sampling` with its source: the base model's `generation_config.json` (found through the `base_model:quantized:` tag; the app's token reads gated ones) mapped field by field (`temperature`, `top_p`, `top_k`, `min_p`, `repetition_penalty`, `presence_penalty`; `do_sample: false` is greedy), or the GGUF header's `general.sampling.*`; null when neither has values. Model card text is not parsed. Nothing is applied for the app; a GGUF's own values still apply at load, as upstream does. The demo's search moves onto this API.
Consequences: Apps get search without writing it. Recommended values are honest about where they came from, and absent for models like Qwen3.5, whose values only the card states; an app that wants them for such models keeps its own list, as the leading apps do (F136). One set of values per model: thinking and non-thinking values can't be told apart from these sources. Amended by D79, which returns both sources, labelled.

### D69. The demo goes neutral, near-black on AMOLED, with the new icon's warmth only in the orb

Status: Decided (2026-11-15)

Context: The demo's palette (demo design §8.6) is warm paper in light and graphite in dark, with one navy primary. The app gets a new icon from the maintainer: a cream-outlined llama on an indigo-black night, its mane violet, its ears rose and its neck amber. The maintainer asked for a cleaner, darker feel, taking colour inspiration (not layout) from ChatGPT's neutral scheme, and noted that most phones are AMOLED. Demo design §12 rejected a violet accent ("generic AI") and warns against lifeless and soulless directions. On AMOLED a black pixel is off; true black can smear briefly while scrolling on some panels, which a near-black avoids at almost the same saving.
Decision: Light is a near-white neutral and dark a near-black, both untinted: canvas `#FAFAFA` / `#0A0A0A`, surface `#FFFFFF` / `#141414`, ink `#0D0D0D` / `#ECECEC`, muted `#5D5D5D` / `#A8A8A8`, hairline `#E5E5E5` / `#222222`, user bubble `#EFEFEF` / `#1E1E1E`. The primary is the ink (white on it in light, `#0A0A0A` on it in dark), with a soft primary of `#EBEBEB` / `#262626`; navy goes. States that navy told apart get a second signal: links are underlined and Think on is a filled ink circle; the selected border and progress are ink. The fit words and Remove keep their colours, the only hues that carry meaning. The icon's rose-to-amber (`#C35F5A` to `#FDB969`) appears in one place, the orb, as the app's single spark; the icon's violet stays in the icon. Every text pair passes 4.5:1 in both themes, as the contrast test requires. The app follows the system theme, as before. The icon becomes the launcher icon: an adaptive icon with the llama scaled into the 66 dp safe zone on its night background, and a one-colour themed icon traced from its outline, shown to the user before use. The splash shows the icon on the app's canvas, so no colour flashes before the first screen. Demo design §8.1 (which said never pure white or black) and §8.6 are rewritten when this is built.
Consequences: The app reads as one quiet, dark-first family with its icon, saves power on AMOLED and keeps colour for meaning and one living moment. The warm-paper character of PD is replaced; the fonts, shapes and layout are unchanged. Send and Think on take an indigo fill instead of the ink (D73).

### D70. `LlamaBro.create` and `LlamaBroHub.create` for DI containers, and a fake hub for tests

Status: Decided (2026-11-15)

Context: `LlamaBro.getInstance(context)` and `LlamaBroHub.getInstance(context)` are process-wide singletons configured through the `Application` (`ConfigProvider`) and read once, so a DI container can neither build nor configure them, and the hub's cache folder can't change without a restart (sdk-review item 11). The native runtime is one per process and refuses a second, different config, and each `LlamaBroRuntime` tracks the models still closing that a new load waits for, so two independent runtimes would miss each other's. Two hubs on one cache folder would download the same files twice. The hub has no test fake, so the demo wraps it in its own `HubGateway` interface; core has `FakeLlamaBro`, a real `LlamaBro` over a scripted engine (D27). `LlamaBroConfig` compares its logger by reference.
Decision: `LlamaBro.create(context, config)` replaces `getInstance` and `ConfigProvider`: the first call creates the process's runtime, a later call with an equal config returns the same object, and a different config throws `InvalidRequest` at once instead of at the first native call. `LlamaBroHub.create(context, config)` replaces the hub's `getInstance` and `ConfigProvider`: one hub per cache folder, the same folder returning the same hub and a different config for it throwing; hubs on different folders may coexist, so an app can move its models without a restart. Both are breaking, which v2 allows. The documentation shows creating each once in a DI singleton scope (a Hilt module as the example, with no DI dependency in the SDK), since a logger is compared by reference. `llamabro-testing` gains `FakeLlamaBroHub`: a real `LlamaBroHub` over a scripted network and disk (search results, download progress, failures), as `FakeLlamaBro` is over a scripted engine, so an app injects the real types and needs no wrapper.
Consequences: The SDK's entry points are plain objects a container provides; the demo drops its `HubGateway` for the real hub and the fake. Code without DI calls `create` where it called `getInstance`. Amended by D75, which moves `FakeLlamaBroHub` to its own `llamabro-hub-testing`.

### D71. `Typed<T>` and `DownloadProgress` live in core

Status: Decided (2026-11-15)

Context: Planning PH's build found two types that D63 and D64 put on the wrong side of a module boundary. D63 makes the plain `ask(text)` return a result too, but core can't depend on `llamabro-typed`, where `Typed<T>` was placed, and `Typed.Invalid` carried a kotlinx `SerializationException`. D64's `LoadProgress.Fetching(DownloadProgress)` is a core type, but `DownloadProgress` is the hub's, and core can't depend on the hub.
Decision: `Typed<T>` moves to core with `Value(value, json, reasoning)`, `Incomplete(reason, json)` and `Invalid(json, cause: Throwable)`, so core's API stays free of kotlinx.serialization; the plain `ask` returns `Typed<String>`, where `Invalid` never occurs, and `llamabro-typed` reuses the type. `DownloadProgress` and `DownloadPhase` move to core as the progress of any fetched source, and the hub uses them. Both amend D63 and D64; both are breaking, which v2 allows.
Consequences: One result type for every ask and one progress type for every download, whichever module produces them.

### D72. The demo offers a choice of chip in Settings › Memory

Status: Decided (2026-11-15)

Context: The demo always loads with `DevicePolicy.Auto` and only reports the chip, so demo design §2 (the design of D46) lists a choice of chip as not in the app. The maintainer wants the user to be able to choose: to compare chips, or to keep a model off one. The SDK already offers `Prefer(kind)`, which falls back in `Auto`'s order when the kind can't load the model, and `LoadReport` says where the model runs and why each other device wasn't used (D32), in words written for developers. One model is in memory at a time (D59).
Decision: Settings › Memory gains "Runs on": Automatic (the default, `Auto`), NPU, GPU and CPU (`Prefer(kind)`, never `Only`, so a chip that can't load the model, or that this phone lacks, falls back instead of failing); a kind the phone doesn't have stays listed, marked "Not on this phone". The choice is saved with the other settings. A change frees a model loaded for another choice as Unload does, once no reply holds it, and the next message loads it on the new chip. While another reply is being written, a new turn shares the model in use whatever choice it was loaded for, so a reply is never cut off; once nothing else is running, a model loaded for another choice is not reused, so a wait that is cut short still can't leave the old one in use. When a load doesn't land on the chosen chip the chat shows a closable note on that load, "<Name> is running on the CPU: the GPU couldn't load it.", with **Settings**, and Settings › Memory says "Running on the CPU: …" under the choice, only while the model in memory was loaded for that choice; a model still loaded for an earlier choice says "Moves to the CPU after this reply." (or "… with the next message."), and no line when the model in memory already runs on the chip chosen. The SDK's reason is told only by its gist: "this phone has no NPU" when no backend for it is in the app, "the NPU isn't available here" when its backend loaded no device, and "the NPU couldn't load it" when a load on it failed. A load that fails for memory doesn't fall back, since the SDK treats it as final, so there is no memory wording. Of the chosen chip's attempts, the model's own counts, not a line about the image encoder moving. Automatic never notes a fallback: it asks for the fastest chip that loads and gets it. When the memory note (F128) also applies, it wins the one line, since it warns that other apps may close and offers a fix; where the model runs stays in Settings › Memory and the model sheet. Fit and speed estimates use the chosen chip when this phone has it, else the one Automatic would pick; when the phone's chips can't be read, no choice is marked missing. D69 is not affected.
Consequences: Amends demo design §2, §4.6 and §9. The app still reports where the model actually runs. The SDK is unchanged.

### D73. Indigo, smaller send and Think circles

Status: Decided (2026-11-15)

Context: Since D69 made the primary the ink, the message bar's send (and stop) and Think-on buttons are 40 dp circles filled with the ink, and the maintainer found them too heavy and too high in contrast for the buttons a person looks at most. The options shown were a smaller ink circle, a muted grey fill, a tonal fill, and then accent colours: indigo, blue and rose. The maintainer picked indigo, taken from the new icon's night and violet mane, for these two buttons only.
Decision: Send (when it can act, and as stop while a reply comes) and Think on are filled with a new send accent, indigo `#4F5BD5` in light and `#8E9BFF` in dark, with the glyph in the on-send-accent colour, white `#FFFFFF` in light and `#0A0A0A` in dark: 5.5:1 and 7.8:1, above text's 4.5:1 though an icon needs 3:1, and the circle is at least 3:1 on the bar. All three circles of the bar (+, Think, send) shrink from 40 dp to 36 dp and their glyphs from 24 dp to 20 dp, still in 48 dp touch targets (F130) that stay where they were, 4 dp from the bar's edge: each circle's centre stays 28 dp in, so the circles remain concentric with the bar's 28 dp corner (18 + 10 = 28), the bar keeps its height, and the circles sit 10 dp from the edge, 12 dp apart and 14 dp under the text. The idle states keep their colours (+ and Think off: the hairline's tonal circle with an ink glyph; send with nothing to send: the tonal circle with a muted glyph), and the fill and glyph still ease between states (250 ms) with send cross-fading into stop. The tokens are `sendAccent` and `onSendAccent` in `DemoColors`, `barCircle` and `barGlyph` in `DemoSize`; the contrast test checks the new pair in both themes.
Consequences: Amends D69's "the primary is the ink" for send and Think on only; every other primary (Get, Save, New chat, the floating button, progress, selected borders, the selected navigation item) stays ink, and D69's neutral palette is otherwise unchanged. The orb stays the app's warm spark. The indigo doesn't collide with the fit words' green, sand and grey or with danger's red, so it carries no meaning beyond "this button acts". Demo design §8.6, §8.8 and §8.11 follow.

### D74. The demo parses markdown with commonmark-java and its GFM extensions

Status: Decided (2026-11-15)

Context: The demo's markdown came from a hand-written parser of about 150 lines, added with the chat engine and never decided: paragraphs, `#` to `###` headings, `**` and `*`, inline code, fences, one level of `-`/`*`/`1.` lists, one-line quotes and links. Using the app, the maintainer found what models write and it missed: tables (shown as rows of pipes), nested lists (flattened to one level), `####` and deeper headings (shown with their hashes), horizontal rules (`---` as text, `***` and `* * *` as a stray bullet), underscore emphasis, `***bold italic***`, strikethrough, escapes (`\*` shown with its backslash), multi-line and nested quotes (a bar per line, the inner `>` as text), `+` bullets and bare URLs. The audit's before screenshots (`MarkdownScreenshotsTest`, one picture per construct in both themes) show each. The renderer also drew heading levels 1 to 3 in one style.
Decision: The demo parses with commonmark-java 0.32.0 and its GFM tables, strikethrough and autolink extensions, and maps the tree into the design system's own blocks, so replies keep the design system's look and screens still render only `:demo:designsystem` components. The block model grows a table (header, rows of inline cells, a GFM alignment per column), a rule, list items that hold blocks (a nested list, more paragraphs, code) and quotes that hold blocks; spans gain strikethrough. Headings 1 and 2 take the `screenTitle` role and 3 to 6 `title`. An image shows its alt text, HTML (blocks and inline) shows as typed, and maths is not rendered: it stays text. A link stays a link only for http, https and mailto; any other target (`intent:`, `tel:`, `file:`, `content:`, an app's deep link, a relative path) shows as its plain text, since a model can be steered into writing one. Streaming keeps the old parser's property: the reply's prefix is stable, so each append parses again only from the block the line being written can still join (a setext underline, a paragraph's next line) and keeps the instances of the blocks before it; blocks parsed again that come out equal keep theirs too. In a top-level list that block is the last item, parsed as a list of its own and numbered on from it (falling back to the whole list if it doesn't read as the same kind of list), and in a table the last row, read again under the table's header and delimiter lines, so a reply that is one long list or table costs the same per append as a short one. A quote is still parsed again whole. While a reply streams, a last line of only `-` or `=` waits for its end, so "Steps:" doesn't flash as a heading before "- step" is written. A link reference definition, wherever it sits (in a quote or a list too), reaches back to links before it, so a reply with one is parsed whole, keeping the instances of blocks that come out equal. A test streams a reply of every construct one character at a time and checks each incremental parse against a whole one. R8 needs no keep rules: commonmark uses no reflection, and its entity table is a resource in its jar that the release APK keeps.
Consequences: Replies render as CommonMark and GFM do, including tables, nested lists and rules. Measured on the host only (`MarkdownStreamingTest`, four characters per append), the median parse per append is about 0.01 ms for a mixed reply, for a 10 KB reply that is one list and for a 10 KB reply that is one table (0.30 ms for the table before rows were resumed), against the hand-written parser's 0.02 ms for the mixed reply; a phone is about 5 to 10 times slower, so the 0.5 ms budget should hold there, but the phone is not re-measured. A long quote is parsed again whole on each append. The release APK grows by about 75 KB (76,559 bytes, of a 35.2 MB APK mostly native libraries): about 62 KB of dex after R8 and the 11 KB compressed entity table. Demo design §4.4, §8.10 and §10 follow.

### D75. The fetched-source interface, the worker's hub from a WorkerFactory, and a separate hub test module

Status: Decided (2026-11-15)

Context: Planning W5 (D64, D68, D70) left three public shapes open: the core extension point for files fetched before loading, how a background download worker in a fresh process gets its hub now that D70 removed `ConfigProvider` (a token provider and a logger can't travel in WorkManager's input data), and where `FakeLlamaBroHub` lives without making every app that uses `llamabro-testing` depend on the hub. The demo-feedback branch (D72-D74) changes many of the demo files W5's demo migration touches.
Decision: Core gains an open interface, `RemoteModelSource : ModelSource`, with a `suspend fun fetch(onProgress: (DownloadProgress) -> Unit): ModelSource` that downloads what is missing and returns local files, and a header-only form for `estimateFit`; anyone can implement it (the hub, an app's own server, a test fake). `llamabro-hub-work` ships `LlamaBroWorkerFactory(hub)`, which the app registers in WorkManager's configuration (or a DI container's worker factory supplies the hub), so the worker uses the app's own hub and config with no global. `FakeLlamaBroHub` lives in a separate `llamabro-hub-testing`, so apps without the hub never get it; this amends D70's placement. The demo-feedback branch is merged into `ph-main` before W5's demo task, which then moves the demo onto the SDK's search, `hub.model()`, the fake hub and the worker.
Consequences: Loading by name, fitting before a download and background downloads all go through public, DI-friendly types. A hub-using app's tests add one test dependency.

### D76. The demo takes voice messages for models that hear, sent as audio with no transcription

Status: Decided (2026-11-15)

Context: Demo design §12 planned voice dictation as the first feature after the app. ChatGPT has two features: dictation, a mic in the message box that turns speech into editable text, and a spoken conversation; both send audio to OpenAI's servers (checked 2026-11-15 through search summaries of OpenAI's help pages, forum threads and third-party guides, not on a phone). Speech output is out (D37, F74). llama-server's transcription is a chat completion on the same model with an ASR prompt (`server-chat.cpp:711`, `convert_transcriptions_to_chatcmpl`), so dictating and then sending the recording too costs two passes over the audio; `input_audio` takes audio in memory as base64 (`server-common.cpp:1355`), as `Content.Image` takes bytes. The SDK reports whether a loaded model hears (`ModelInfo.supportsAudio`, from llama-server's `modalities`). Live transcription needs a realtime-capable model (F56). No suggested model hears, and the app holds one model at a time (D59).
Decision: The demo sends voice messages, not dictation: the recording is the message, so a model that hears gets the words and the voice's tone in one pass, with no second model and no transcription. The mic takes Send's slot while the text field is empty (Send once there is text, Stop while a reply runs), only for a model that hears; other models keep the muted Send. With photos attached and no text, the mic sits just before Send instead, so photos alone can still be sent (amended 2026-11-16 with the user, after the build showed the conflict). A model is known to hear once it has loaded (`ModelInfo.supportsAudio`); the app remembers that for the model's next load, so the mic shows from then on. A tap starts recording: the text area becomes a live waveform and timer, ✕ in the + slot discards and ↑ in Send's slot sends. Photos already in the bar go with the voice message. A voice message is a sent-side bubble with play and pause, the recorded levels as a waveform and its length; it has no Edit, and Regenerate on its reply works. Recording stops at 2 minutes, about 1,500 tokens at 12.6 a second (F152), with the timer in ink for the last 10 s; leaving the screen, a call or a loss of audio focus stops it and keeps it in the bar for ✕ or ↑, never sent by itself. `RECORD_AUDIO` is asked on the first tap; refused, a limit line ("Llama Bro needs the microphone for voice messages.") offers Open settings. Audio is 16 kHz mono WAV in app-private storage, deleted with its chat and never before it, and reaches the SDK as `Content.Audio(MediaSource.file(path))` (D65, D84), read natively and saved in a chat as its path. A chat that switches to a model that can't hear sends each voice message as a short placeholder saying one was there, and a limit line offers to switch to a model that hears. The catalog gains no hearing model; one is found through search. The mic is labelled "Record a voice message", starting and stopping are announced, the waveform holds still with animations off, and a bubble's description gives its length. Left out: dictation and transcripts, a spoken reply (D37), live transcription (F56) and hold to record.
Consequences: The demo needs only `Content.Audio` from W7, so it is built after W7 lands; no transcription is involved. Each earlier voice message slows a chat's later turns while the prompt cache stops before the last clip (F152, under investigation in W9). Pictures move to `MediaSource.file(path)` in the same change, since they are already files in app storage. Voice messages appear only once someone uses a model that hears. Demo design §2, §4.4, §8.8 and §12 follow this.

### D77. Section labels line up with the text under them; the sidebar's actions move to one row of icons, its chats to 48 dp rows

Status: Decided (2026-11-15)

Context: Using the app, the maintainer found section labels sitting on the 16 dp gutter, where a card's surface starts, while the text in the cards under them starts 16 dp further in: on Settings, Appearance, Memory, Sampling, the library and first run. Only sheets lined up, because demo design §8.3 inset a label on a sheet alone. The muted notes under a section's cards sat on the gutter too. The Sidebar drawer put New chat at the top as a full-width ink button, Models as a card under it and Settings as a card at the foot, around 56 dp chat rows. The maintainer asked for the actions in one row of icons at the bottom and the chat rows closer together; previews of three arrangements and two row heights were shown.

Decision: `SectionLabel` is inset 16 dp, the padding of the cards and rows under it, on every screen and sheet, so a label starts on its section's text; the drawer's date labels, over full-width rows that pad 16 dp themselves, take only that inset. A new `SectionNote` gives a muted note under a section's cards the same inset, replacing a screen's own note. The drawer keeps only the chats, grouped by date, as dense one-line rows 48 dp tall (`DemoSize.rowDense`, `ListRow(dense = true)`), and ends in `DrawerActions`: a hairline, then Settings and Models as icons at the start and New chat at the end in a tonal circle (accent on accent soft), each a 48 dp target (F130) whose description a screen reader says ("Models, 2 on this phone" carries the count the card showed). The drawer is one traversal group with its actions first, so a screen reader reaches them before a long history. Amends demo design §8.3 (labels inset on sheets only) and §4.2's drawer.

Consequences: Every section reads as one column of text under its label. The drawer shows about a seventh more chats, keeps New chat under the thumb and loses the visible model count, which the Models icon still speaks. The real-model e2e script finds the drawer's actions by description.

### D78. The demo lets the person choose the context length and the reply length

Status: Decided (2026-11-15)

Context: The demo leaves the context to the SDK (`ContextSize.Auto`: the most that fits, up to 16,384 tokens) and fixes each reply's length by the Think level (D60): 2,048 tokens for the answer plus the level's thinking budget, 8,192 at Deep. The SDK already takes both (`ModelConfig.contextSize`, `Sampling.maxTokens`). The maintainer asked for both to be configurable; previews of where they would live were shown and the maintainer chose them.

Decision: Settings › Memory gains Context length: Automatic (`Auto`), 4k, 8k, 16k and 32k, each `ContextSize.UpTo(n)`, a ceiling the SDK still fits to free memory and the model's training context and never below 4,096, so a choice never fails a load. The host keys a load on the chip and the context together (`LoadConfig`), generalising D72: a change frees a model loaded under another config once no reply holds it, a turn beside another reply shares the model in use, one pending wait serves both settings, and the saved choice is never cancelled with it. The In memory row shows the context picked, in whole k rounded down. Settings › Sampling gains Reply length, for every model and chat: Automatic (D60's limits), Short (1,024 tokens) and Long (8,192), which replace the total and keep the Think level's thinking budget, and No limit, which sends no `max_tokens`. A reply that ends because prompt and reply filled the loaded context is not offered Continue, which could not fit: it ends failed with the context-full line and Start a new chat. Amends D60's fixed limits.

Consequences: A person can trade memory for a longer chat and length for a guard against loops; No limit gives up F113's guard on purpose. Short with Balanced or Deep often stops inside the thinking, and Continue goes on from there (F137). A change of either length reloads the model as a chip change does.

### D79. Recommended sampling returns the file's values and the base model's, labelled

Status: Decided (2026-11-15)

Context: D68 names two sources for a model's recommended sampling, the base model's `generation_config.json` and the GGUF header's `general.sampling.*`, without saying which wins. A GGUF's own values are what llama.cpp applies at load (F17), and they can differ from the base model's: LFM3-Omni's header is greedy (F146). Returning the base model's values when both exist would lead an app that applies the recommendation to overwrite what the file's author chose. Reading a header costs a ranged read of about 8 MiB unless the file is on the phone.
Decision: The hub returns both, each null when absent: `fromFile`, the GGUF header's values (what loads by default), and `fromBaseModel`, the base model's `generation_config.json` values. Neither is applied; the app chooses, as the SDK makes no policy for it. The header is read from disk when the model is on the phone (free, and it works offline), otherwise by a ranged read. This amends D68.
Consequences: An app sees exactly what the file will do and what the base model's authors recommend, and can show or apply either. The KDoc says the file's values are what loads by default.

### D80. Patch 0001 becomes a one-line Hexagon gate instead of the delta-net head/tail split

Status: Decided (2026-11-15)

Context: Patch 0001 split each prompt batch in `delta-net-base.cpp` so Hexagon's fast delta-net kernel, gated on K == 1, ran the head (F7, F8, F121). ggml's contract already lets a backend fill only slot 0 when `n_tokens > K`, as CUDA does, and upstream removed batch-dependent graph shapes in #32078, which the split brings back. A one-line change to the Hexagon gate measured as fast and drifted less (F149).
Decision: `sdk/native/patches/0001-hexagon-gdn-long-batch.patch` lets the gate accept `K == 1 || n_tokens > K`; the split is dropped from the SDK and kept under `research/ph/delta-net/` for the record. The user proposes the gate upstream in their own words (issue first); nothing is posted for them. This amends D10.
Consequences: The SDK's only speed patch is one backend line and the change upstream would review. Its drop condition is upstream's gate accepting `n_tokens > K`. If upstream finds MTP rollback needs every snapshot of a long batch, the gate, like CUDA, gives slot 0 only, and the patch is revisited. Without MTP the gate is upstream's own path, where the NPU breaks a near-tie between two tokens either way from run to run (F149); the split's extra kernel switch had hidden this from the device contract's image case. So the device contract asks streamed chat requests without tools for their top 2 logprobs and passes a reply that matches token for token up to one near-tie (top-2 gap at most 0.25) as "near-tie"; the host contract stays exact.

### D81. Typed tools that return nothing send "Done"; one-shot generation is typed only; the processor's build errors are tested with kctfork

Status: Decided (2026-11-16)

Context: Planning W6 found three choices that D61, D63 and D71 leave open. A typed tool may return `Unit`, but the model still needs a tool result. tools/ui has no typed tools, so there is no upstream baseline: Spring AI sends "Done" for a void method and LangChain4j sends "Success". An empty result renders oddly in some chat templates, and a small model can read it as a failure and retry. D63 gives `llamabro-typed` a one-shot `model.generate<T>`, while core has no plain one-shot call. D61 promises build errors for wrong annotations, and testing those needs a harness that compiles snippets with the processor. For KSP2 that is kctfork (`dev.zacsweers.kctfork`).
Decision: A typed tool returning `Unit` sends `Done` as its result. Core gains no plain `model.generate`. One-shot generation is `generate<T>` in `llamabro-typed` only, and a plain one-shot answer goes through `conversation().ask()`. `llamabro-typed-ksp` tests its build errors with kctfork as a test-only dependency, pinned in the version catalog. It never ships to apps.
Consequences: Each build error D61 lists has a test. A `Unit` tool costs one token of prompt. Core's API grows only by the typed `ask` result (D71).

### D82. The SDK compiles with the catalog's Kotlin; apps need Kotlin 2.2 or later

Status: Decided (2026-11-16)

Context: `llamabro-typed-ksp` (D61) builds on the KSP 2.3 API, which AGP 9.5's bundled Kotlin 2.2.10 can't read, so the SDK build now applies the catalog's Kotlin 2.3.10 (`kotlin.jvm` and `ksp` with `apply false` in `sdk/build.gradle.kts`) and every module compiles with it. Measured with `javap -v` on a compiled class of each module: the release classes of `llamabro`, `llamabro-hub`, `llamabro-hub-work`, `llamabro-testing`, `llamabro-hub-testing`, `llamabro-hexagon`, `llamabro-opencl`, `llamabro-typed` and `llamabro-typed-ksp` carry Kotlin metadata `mv=[2,3,0]`; a `llamabro` AAR built before the change carried `mv=[2,2,0]`. Of the dependencies the SDK ships, kotlinx-serialization-core-jvm and -json-jvm 1.10.0 (`api` in `llamabro-typed`, `implementation` elsewhere) carry `mv=[2,3,0]`, and kotlinx-coroutines-core-jvm and -android 1.10.2 (`api` in `llamabro`) carry `mv=[2,1,0]`. A Kotlin compiler reads metadata up to one minor version ahead of its own, so 2.3 metadata needs a 2.2 compiler or later.
Decision: Keep compiling with the catalog's Kotlin; the floor is what it measures to be, Kotlin 2.2 for every module, and the SDK sets no lower `languageVersion` or `apiVersion` to pull it down. `llamabro-typed-ksp` needs KSP 2.3 or later, the API it is built on. The SDK README states both.
Consequences: Core's floor rises from Kotlin 2.1 (its own 2.2 metadata) to 2.2; `llamabro-typed`'s doesn't move, since kotlinx-serialization 1.10.0 already set it. Each Kotlin upgrade in the catalog moves the floor with it, so the README's requirement is re-measured when the catalog's Kotlin changes.

### D83. Typed tool arguments follow D62 strictly; an LbToolError's cause reaches the app

Status: Decided (2026-11-16)

Context: W6 first sent the message of any `IllegalArgumentException` thrown while decoding a typed tool's arguments as `Error: invalid arguments: <reason>`, which included a `require` in the app's arguments class and a custom serializer's error. That bent D62's line that only an `LbToolError`'s message reaches the model, since an app's `require` message may hold anything.
Decision: Only kotlinx-serialization's own decode errors (a `SerializationException`: a missing field, bad JSON, a custom serializer's own `SerializationException`) send their reason. Any other exception thrown while decoding (a `require` in `init`, a custom serializer's other exception) sends `Error: invalid arguments` and is kept as the cause. An app that wants to explain throws `LbToolError` from its validation, such as from `init`, and its message is sent. An `LbToolError`'s `cause`, when it has one, reaches the app as `ChatEvent.ToolResult.error`, so the swallowed exception is not lost; with no cause, `error` stays null.
Consequences: A message in an app's `require` never enters the prompt or the saved chat unless the app chooses it by throwing `LbToolError`. The app logs the real exception from the event stream. Typed output is unchanged: nothing there reaches the model. Amended by D88, which words a missing field as `missing field 'x'` and sends tool results with every field.

### D84. Audio and images reach the model from a file, a descriptor or bytes; the bridge writes chat media's base64; `transcribe` returns `Typed<String>`

Status: Decided (2026-11-16)

Context: D65 gives speech input as `Content.Audio` in chat and `Model.transcribe(file)` through F55's path route. Apps get audio in three forms: a file (a recording kept on disk), a descriptor (a `content://` URI, which has no path) and bytes (a recording held in memory). llama-server's transcription handler takes the audio as a raw buffer in the request's files (F55). Its chat handler takes media only inside the JSON body, as a base64 data URL (or http, or `file://` under `--media-path`), which is why `Content.Image` is sent as base64 built in Kotlin. A 2-minute 16 kHz mono WAV is about 3.8 MB; base64 in a Java string briefly costs about 4-5 times that in the heap. A JNI copy is about a millisecond for 4 MB. Estimated, not measured: base64 through the bridge costs about 6-10 MB more native memory than a patch that lets chat take raw buffers, and 30-60 ms more time, against several seconds of encoding. llama.cpp decodes WAV, MP3 and FLAC only (`mtmd-helper.cpp` `is_audio_file`). A chat request re-sends every earlier message's media on each turn, and `toJson` saves the chat.
Decision: One source type for media, a file path, a file descriptor or bytes, used by `Content.Audio`, `Content.Image` and `transcribe`. Path and descriptor are read natively. Bytes cross JNI once, with no base64 in Kotlin. For chat, Kotlin sends a placeholder in the JSON and the attachment beside the request, and the bridge writes the base64 data URL natively, so llama-server's code is unchanged. A descriptor in chat content is read once into bytes when the message is added, so later turns and `toJson` use the bytes and the app may close it at once; `toJson` saves a file's path and bytes as base64. `transcribe` takes any of the three and returns `Typed<String>`: `Value(text)`, or `Incomplete(Length, textSoFar)` when cut, like `ask` (D63, D71); engine failures stay exceptions. Only WAV, MP3 and FLAC are accepted, as llama.cpp decodes; the SDK converts no other format. W7's phone run measures the bridge's base64 against a patched build where chat takes raw buffers, and the patch is carried only if it clearly wins (upstream is the baseline).
Consequences: Apps hand over audio however they hold it, and the Java heap never holds a base64 string. A chat holding a descriptor's audio keeps it as bytes for the chat's life. Apps recording AAC or Opus must record WAV or convert. This amends D65.

### D85. Interactive voice mode is its own phase after PH

Status: Decided (2026-11-16)

Context: LFM3-Omni-2B speaks on the phone at 1.7 times real time with the model on the NPU and its speech part on the CPU, the first audio in under 0.2 s (F154), and with patch 0005 also on the NPU alone (F155). Long texts garble in one request, so a reply would be spoken a sentence at a time, and then the voice drifts between sentences, with a random seed or one fixed seed alike (F154). A spoken chat reply exists only through `/v1/realtime`; the SDK would need a byte-returning request (F74) and that endpoint on an in-memory connection (F56). D37 keeps speech output out of 1.0.
Decision: An interactive voice mode joins the roadmap as its own phase after PH, not inside W9. That phase revisits D37, builds the byte-returning request and `/v1/realtime` in memory, and tests first what holds the voice steady across sentences: one realtime session, the first sentence's audio as the voice reference (`speaker_ref`) for the rest, or a lower audio temperature. The maintainer heard the drift on the NPU too (patch 0005) and chose to leave it to that phase (2026-11-17).
Consequences: PH closes as planned (W9 steps 3 and 4). Speech output stays reachable only through upstream llama-server until that phase. Its first step is upgrading llama.cpp to the latest (user, 2026-11-17): upstream adds speech models and rewrote the speech helper, and three of our patches need porting (`research/ph/upstream/review-2026-11-17.md`).

### D86. The NPU polls for its results by default (`LlamaBroConfig.npuPolling`), and two patches make speech on the NPU fast

Status: Decided (2026-11-17)

Context: With all of LFM3-Omni's speech on the NPU, the host thread sleeps while the NPU works, and the host work between NPU steps then runs 3-4 times slower: speech at 1.75 times real time. Upstream's `GGML_HEXAGON_OPPOLL=1` (the host polls) keeps the core awake: 2.34 times, the same energy per second of speech (4.44 W against 3.50 W, but sooner), the chip hotter while busy (NPU 90 against 79 °C), chat 4-7% faster. Polling with naps between checks gains little, so the core must stay awake (F156). The setting is read once, when the backend starts. Upstream's default is off. Separately, the speech graph hopped to the CPU in every pass (`ARANGE`), and every audio frame computed and sampled an unused text head (F156).
Decision: `LlamaBroConfig` gains `npuPolling`, on by default; the core passes it to the backend installers (`BackendOptions`), and `llamabro-hexagon` sets `GGML_HEXAGON_OPPOLL=1` when it is on. An app that prefers a cooler chip over speed turns it off. Patch 0005 makes LFM3's depthformer read its tables directly and take its positions as an input; patch 0006 skips the text head and the text sample in speech-only frames. Keep-flat (the former 0005, F155) is dropped and kept in `research/`. Flash attention for the speech graph stays upstream's (off on auto for LFM3's speech generator, ~3% on the NPU) unless decided otherwise.
Consequences: Speech on the NPU alone runs at about 2.3-2.4 times real time on the phone, faster than the CPU split and with about 24% less energy per second of speech. A config with `npuPolling = false` is a different config: a second `create` with it throws, as for any config change (D70). The polling cost is heat while busy. In-app scheduling (top-app, background) was not measured; the voice-mode phase measures it (D85). Patch 0007 (F162) makes polling read the queue without the vendor library logging an error per empty poll, which otherwise drowns the app's log. Amended by D90, which turns polling off by default again and moves patches 0005-0007 to the voice-mode phase.

### D87. A chat's path attachments reach llama-server as file:// links in the SDK's media folder; bytes and descriptors keep base64

Status: Decided (2026-11-17)

Context: D84 has the bridge write every chat attachment into the body as a base64 data URL, and a chat sends every earlier message's media again on each turn. On the phone, a text turn after three voice notes takes 192-200 ms with base64, 93-94 ms when llama-server reads the files itself through `file://` with `--media-path` (no llama.cpp change), and 80-93 ms with a research patch that hands the buffers over directly (F157). A media root of `/` would let any request body name any readable file, on every route, including `/v1/responses` and `/v1/messages`, which convert their own media formats inside llama-server.
Decision: The model config gains `media_dir` (llama-server's `--media-path`); the SDK sets it to `noBackupFilesDir/llamabro/media/`, emptied when the runtime starts. On the chat-like and embeddings routes, the bridge checks a path attachment's first bytes (the audio format check), links it into `media_dir` under a random name and writes `file://<name>`; the link is removed when the request ends (or never starts). A link that can't be made falls back to base64. Bytes and descriptors keep base64. No raw-buffer patch: it gained nothing measurable over `file://`. Amends D84.
Consequences: Turns with voice notes or pictures from files are about 100 ms faster with three clips. A path attachment is read when the request runs, so the file must stay until the reply ends (it already had to stay for later turns). A caller-written `file://` reaches only the media folder, whose links have random 128-bit names. The C API without `media_dir` is unchanged. Since W9 step 5 (A7) each process of the app has its own folder, `media/<pid>-<random>/`, and a runtime start removes only the folders of processes that ended, since every process of an app shares `noBackupFilesDir`.

### D88. Typed output and tools after W6's review: integer bounds by default, `askAgain`, `valueOrNull`, results with every field

Status: Decided (2026-11-17)

Context: W6's final review left optional additions, and the maintainer chose to do them all (2026-11-17). Without bounds, a model can write a number past an `Int`, which only fails as `Typed.Invalid` or `invalid arguments`; bounding by the type's range costs a larger grammar but under a millisecond per request and microseconds per token (F158). `regenerate()` after `ask<T>` sends no schema, since options belong to the call. kotlinx's `Json` drops fields at their default when encoding, so a typed tool's result could miss values the model needs. A `check()` in a typed class's `init` escaped `ask<T>` as an exception, and a `Node<T>` holding a `Node<List<T>>` overflowed the stack.
Decision: `typedSchema` writes each integer's Kotlin range by default (`Byte`, `Short`, `Int`, unsigned), intersected with `@LbRange`; a `Long` gets only its annotation's bounds, since int64's limits are upstream's "unbounded". Core gains `Conversation.askAgain()` (`regenerate` collected, as `ask` is `send` collected) and `Typed<T>.valueOrNull()`; `llamabro-typed` gains `askAgain<T>()`, which regenerates under the same schema, while `regenerate()` stays schema-free, with no hidden state. Typed tool results are encoded with `encodeDefaults = true`. Any exception while decoding a typed reply is `Typed.Invalid`. A class open eight times on one schema path is refused as recursion through its type arguments. A missing argument field reads `missing field 'x'`. The KSP processor checks each separate parameter's type and keeps a type-use `@Serializable(with = …)`; `@SerialName` can't go on a parameter, as Kotlin rejects it there. Renames: `descriptionAnnotations`, `fileOwners`. Amends D63 and D83.
Consequences: Schemas and grammars change for every integer field, so apps comparing schema text see the bounds. A typed reply can't hold an out-of-range number, so `Invalid` for one is rarer. Tool results grow by the defaulted fields. `askAgain` joins the public API in both modules, as an extension in core to keep `Conversation`'s function count.

### D89. Hub downloads after W5's reviews: files in the plan's order, one writer per blob by canonical path, deletes one at a time, reclaim never fails a download

Status: Decided (2026-11-17)

Context: W5's reviews left these to W9. `hub.cached()` listed a selection whose projector or split parts were missing, so a demo load could download unasked (D64). Two `delete`s of one model at once could both report a removal, or throw `NoSuchFileException`. An update's reclaim could fail a download that had installed (M1), or remove a blob that a concurrent download of another selection had just reused (M2). A quick Pause→Resume looked like it could run two writers on one partial; within one hub `Locks` already serialised them, but two hubs on one cache under different path spellings did not wait for each other, because the JVM refused the second kernel lock (F161). With one download at a time, files went in no fixed order, so a stopped download's state was not reproducible.
Decision: `cached()` lists installed copies only when every file is on disk, and a refs-view tag only when all its split parts are there and, when the hub holds a plan or installed record of the tag, only when one of those records is complete or the tag's default selection loads as `source()` would (checked read-only), so the list agrees with what a load does. `delete` runs under a per-repository lock (in-process mutex plus kernel lock), the second of two returns false, and it re-checks and removes each blob under that blob's download lock. Reclaim decides each file under its blob's download lock from the records as they are then; a downloader checks for an existing entry of a file with a hash only under that lock (files without one are never reclaimed under a lock); reclaim locks only blobs that exist; a reclaim that fails, in any way, is logged at Warn and the download completes. The in-process lock is keyed by the lock file's identity (`fileKey`, as the JVM's lock table), else its canonical path. Files download in the plan's order (model parts, image encoder, draft). `defaultDownloadConstraints()` stays public and documented as the base to extend (M3). `FakeLlamaBroHub.stopDownload(model, path, repo)` reproduces a stopped download. `HubException.Network.unreachable` is public: whether the Hub was reached is a fact an app may need, not app policy. Amends D44 and D64.
Consequences: Apps no longer see half-downloaded models in `cached()`. Progress counts files up in plan order. A reclaim that fails leaves the old files until a later update or a delete. The fake gains one public function.

### D90. NPU polling goes back to upstream's default (off), and the speech-output patches move to the voice-mode phase

Status: Decided (2026-11-17)

Context: D86 turned NPU polling on by default and carried patches 0005 and 0006 for LFM3-Omni's speech output on the NPU, and patch 0007 (F162) then stopped polling from flooding an Android app's log. All of it was measured on `/tts` speech output (F155, F156), which the SDK can't hand to an app until the voice-mode phase (D37, D85). For chat, the only use that ships, polling gave 4-7% more speed and a hotter NPU (90 against 79 °C), and its chat energy was never measured. Patch 0006 adds a function to libllama, the hardest kind of patch to port, and the llama.cpp upgrade opens the voice-mode phase. The architecture review of W9 step 5 raised this from three sides; the user ruled (2026-11-17).
Decision: `LlamaBroConfig.npuPolling` stays, off by default, as upstream has it. Patches 0005, 0006 and 0007 and the `device-speech` check leave `ph-main` for branch `voice/speech-npu` (all seven patches and their research), which the voice-mode phase starts from; it re-decides polling with chat energy and ADPF measured. `ph-main` carries patches 0001-0004. Amends D86.
Consequences: The upgrade ports four patches instead of seven. Speech output on the NPU alone gives empty audio again on `ph-main` (F155) until the voice phase, which `rawRequest` can't return to an app anyway. An app that turns polling on gets about 5% faster NPU chat and, without patch 0007, a log flooded with the DSP library's errors (F162).

### D91. Patch 0004: the prompt cache's SWA rule applies only to memory that can be partly removed

Status: Decided (2026-11-16; recorded 2026-11-17)

Context: On the LFM3 family (hybrid with SWA layers), llama-server reuses neither the live state nor the newest checkpoint, because its SWA threshold compares against a position that on hybrid memory is the state's end; every turn re-encoded the last voice note (F152, F153). Patch 0004 was carried in W9 step 1 with a finding but no decision record, though it changes llama-server's behaviour; the W9 step 5 review noticed.
Decision: Carry patch 0004: the SWA threshold applies only to partly removable memory (`seq_rm_type == PART`); otherwise a state must end before the first token to evaluate, upstream's rule without SWA. Models without SWA layers and with partly removable memory are unchanged. Drop it when upstream applies the threshold the same way (`server_prompt::get_reuse` on master). Amends D10.
Consequences: A text turn after three voice notes on LFM3-Omni-2B reaches its first token in 522 ms instead of 824 on the phone; up to `--ctx-checkpoints` checkpoints survive per slot (about +11 MiB on LFM3-Omni-2B). Fact sheets for upstream are in `research/ph/cache-stop/`, for the user to post.

### D92. `Auto` keeps trying the NPU and the GPU on every Qualcomm chip; only one phone is verified

Status: Decided (2026-11-17)

Context: `DevicePolicy.Auto` tries the NPU, then the GPU, then the CPU (D24, D32), and its fallback catches load failures. Only one phone was ever tested (OnePlus 13: Hexagon v79, Adreno 830), yet the v73-v85 skeletons ship; a backend that computes wrong output (F120, F71) or aborts in native code is not caught, and an abort ends the app's process (D5). The W9 step 5 review proposed using the NPU and GPU under `Auto` only on verified chips; the maintainer decided against it (2026-11-17).
Decision: `Auto` stays open: NPU, then GPU, then CPU on every chip, as before. The documentation says plainly that one phone is verified and which chips that covers, so an app can choose `Prefer` or `Only(Cpu)` for chips it hasn't tested.
Consequences: Apps get the NPU's speed on every supported Snapdragon by default, at the risk of wrong output or a crash on an untested chip until more phones are verified. Widening verification (a device-farm session running `device-contract` and the vision test) stays on the release list.

### D93. llama.cpp is pinned at b12479 (upstream master on 2026-11-17)

Status: Decided (2026-11-17)

Context: The voice-mode phase starts by upgrading llama.cpp to the latest (D85): upstream adds speech models and rewrote the speech helper, and the server was refactored. The maintainer asked for the newest master at the time of the upgrade (2026-11-17), `d92ea9bb1`, 127 commits after b12352; its build number is b12479, and upstream tagged it `b12479` after the upgrade. At that commit the contract is identical on the Mac and on the phone's NPU, every device gate passes, patches 0003 and 0004 port without a change in behaviour, and the delta-net gate (0001) is still worth +67% for NPU prefill with MTP (F163). One host test's bound moved because upstream lowered the Mac's peak memory during a load (F164).
Decision: The submodule moves to `d92ea9bb1`, named b12479. The same four patches ship: 0001 and 0002 unchanged, 0003 and 0004 ported to `server_prompt::get_reuse` and the refactored server. The `tools-ui` golden follows the web client to `ui/apps/chat`. `dflash_fit_matches_load_rss` allows a ratio up to 1.20 (the maintainer chose, 2026-11-17). Amends D10 and D52.
Consequences: Statements measured at b12352 stay as measured until retested; F75 (OpenCL's DP4A setting), OpenCL drafting (F80, F125) and the effect of #31438 on Android are open. The speech patches on `voice/speech-npu` (0005-0007, D90) are rebased onto this pin as the voice phase's next step. The SDK names the pin by its build number, as upstream's `build_info` does; upstream has since tagged that commit `b12479` (`git describe` said `b12474-5-gd92ea9bb1` on the day of the upgrade).

### D94. Patch 0006 rides on upstream's two output flags: an output that asks for embeddings only skips the LM head

Status: Decided (2026-11-18)

Context: Patch 0006 saves the text head on every speech-only frame (F156). At b12352 it did so with a new context-wide switch, `llama_set_logits` in the staging header `llama-ext.h`, which LFM3's speech helper turned off while speaking alone and the server turned back on before each normal batch. b12479's `llama_batch_ext` has per-entry `set_output_embd` and `set_output_logits`, documented as "for now equivalent", with a TODO for dedicated output flags. Making them differ gives the same skip with byte-identical audio and the same speed on the Mac and on the phone's NPU (F165).
Decision: 0006 (now `0006-embd-only-output-skips-lm-head.patch`) gives each batch entry both flags; a decode whose outputs all asked for embeddings only builds its graph without the head (`cparams.logits`, beside upstream's skip for pooled embeddings), and `llama_get_logits_ith` then fails. Graph reserves, backend samplers, `llama_batch` callers and the rows `output_all` adds keep the head. LFM3's speech helper asks for embeddings only on a frame while speaking alone; `/tts` keeps skipping the text sample while the next step is audio. `llama_set_logits`, its staging header entry and the server's toggles are gone. The maintainer chose the re-cut (2026-11-18). Amends D86's patch 0006.
Consequences: The patch is shaped as upstream's own next step, so most of it goes when upstream makes the flags differ. Upstream's YuE2 NAR decode, which asks for embeddings only, skips the head too. On the phone it saves 4.5 ms per frame like the old version, and `device-speech` passes at 2.31 times real time (F165).

### D95. Voice mode is a realtime conversation with one omni model, through upstream's `/v1/realtime` on an in-memory connection

Status: Decided (2026-11-18)

Context: At b12479 upstream llama-server holds a spoken conversation on `/v1/realtime` for models that both listen and talk (`server-realtime.cpp`, LFM3-Audio): audio is prefilled as it arrives, a response comes as text, speech or both (`output_modalities`), and `response.cancel` stops it. The session has no voice-activity detection (`turn_detection` is null), so the client commits each turn. The handler is written against an abstract connection that the bridge can supply in memory (F56). LFM3-Omni-2B, such a model, speaks on the phone's NPU at 2.3-2.4 times real time (F165). Upstream's new speech models do one job each: VibeVoice-ASR and Higgs v3 STT listen, Zonos3-mini and Fish Audio S2 speak.
Decision: The voice mode covers the SDK and the demo. It runs LFM3-Omni-2B through upstream's `/v1/realtime` conversation, unchanged, on an in-memory connection: a C API session (open with params, push an event, an event callback, close) and a Kotlin session over it, with no port and no HTTP layer. The SDK takes explicit turn commits and cancels and makes no turn-taking policy; the demo starts with push-to-talk and adds hands-free (voice-activity detection, talking over the reply to stop it) as a setting once detection and echo are measured on the phone. The demo shows the reply's text as it is spoken and the user's words as transcribed, and saves the conversation as an ordinary chat. The Kotlin API is shaped so that a pipeline of separate listening, chat and speaking models can sit behind it later; those models are measured in a later step. Speech output thereby enters the SDK. Amends D37. The maintainer chose each point (2026-11-18).
Consequences: Builds F56's session and a contract test that feeds the same scripted audio to llama-server over a real WebSocket and to the in-memory session and requires identical events. Audio reaches Kotlin as PCM through the session's events, so no byte-returning `rawRequest` is needed for the voice mode (F74 stays open for `/tts`). D85's open question, what keeps the voice steady across sentences, is tested first inside one realtime session, where a reply is one generation. Hands-free mode needs on-device voice detection and echo handling, both still to measure.

### D96. Patch 0008 ends LFM3 speech that runs past its text

Status: Decided (2026-11-18)

Context: About 1.6% of LFM3-Omni's captioned replies never sample the end of audio after their text ends and play minutes of noise or garbled speech; the prompt does not prevent it, and upstream's loop matches Liquid's reference (F167). A guard that ends the speech at 3 s of near-silence, or at 5 audio frames per text token plus 13, stopped all 10 failures in 600 replies and no clean reply. It could live in the SDK's voice session (cancelling the response) or in llama.cpp's LFM3 speech helper.
Decision: Patch 0008 puts the guard in LFM3's speech helper (`tools/mtmd/mtmd-helper-gen.cpp`), so every client of llama-server's speech routes gets it: once an interleaved response's text has ended, the guard turns the next frame into the end-of-audio frame and ends the response, whose text is complete. Clean replies are unchanged byte for byte. The maintainer chose the helper over the SDK and validation on fresh seeds before shipping (2026-11-18).
Consequences: A deviation from upstream, cited to F167 in the patch. The thresholds hold for LFM3-Omni-2B in English as measured; another LFM3 model or language is measured before relying on them. The phone check (NPU) follows. The SDK's voice session still sends the trained prompt and a maximum reply length (D95).

### D97. The realtime session is a C API request: endpoint `realtime`, client events through `lb_request_send`

Status: Decided (2026-11-18)

Context: D95 runs upstream's `/v1/realtime` conversation on an in-memory connection. The handler runs unchanged on such a connection and sends the same events as over a WebSocket (F168). The C API already has a request type with a callback, a cancel, a free that never blocks, and a model free that cancels and waits for every request; a new session type would repeat that lifecycle.
Decision: `lb_model_request(model, "realtime", params, NULL, 0, cb, user, &req)` opens a session (no body or attachments; params are the socket's query, such as `intent`). Each server event is one `CHUNK` with status 200. `lb_request_send(req, msg, len)` queues one client event, the one new function; it answers `LB_ERR_INVALID_ARG` for another endpoint's request and `LB_ERR_CANCELLED` once the session has closed. `lb_request_cancel` is the client closing the socket (`CANCELLED`); `DONE` when the server ends it. The model config gains `seed` (upstream's `--seed`), which a realtime response's sampling takes. The maintainer chose extending `lb_request` over a new session type (2026-11-18).
Consequences: JNI and Kotlin reuse the request plumbing for the voice session (D95 step 3). The callback of a realtime request may run on either of upstream's two conversation threads, never two at once. The audio contract compares four realtime cases with upstream over a real WebSocket, both sides at seed 42.

### D98. The Kotlin voice session: a suspending open, a handle with sends, typed events with a raw escape hatch

Status: Decided (2026-11-18)

Context: D95's step 3 puts a Kotlin session over the C API's realtime request (D97). Upstream's conversation takes PCM16 in base64 at the model's rate (16 kHz for LFM3) and sends PCM16 at its own (24 kHz), a reply as text and speech deltas, and errors as events that leave the session open. It sends no transcript of the user's speech (F169). Without a voice, a language and an answering instruction, LFM3-Omni's captioned mode can speak noise for minutes (F166).
Decision: `suspend fun Model.voiceSession(configure)` opens the session and returns once the server has answered the setup with `session.updated`, so a setting it refuses fails the open (`InvalidRequest`, upstream's message). The `VoiceSession` handle has a hot `events: Flow<VoiceEvent>` (one collector), `append(ShortArray)` (16-bit mono at `inputSampleRate`), `commit(respond = true)` (the commit and, after audio, `response.create`: one call per push-to-talk turn), `respond()`, `sendText`, `cancelReply`, `sendRaw` and `close` (hang up); sends never block and answer false once the session has ended. `VoiceEvent` types the events a voice app needs (`TurnCommitted`, `ReplyStarted`, `ReplyText`, `ReplyAudio` with `ShortArray` PCM at `outputSampleRate`, `ReplyDone`, `Error`) and passes every other event as `Other(type, json)`. `VoiceConfig` passes upstream's session fields through (`voice`, `language`, `instructions`, `outputModalities` defaulting to text and audio, `maxOutputTokens`, `inputSampleRate`, `extra`) and sets nothing else: the SDK makes no app policy, and the app (the demo) sets what F166 found LFM3-Omni needs. There is no event for the user's words yet: their cost is measured in step 4 (F169). `ModelConfig.seed` exposes D97's `seed`. `FakeLlamaBro.voiceReply` scripts replies for app tests. The maintainer chose each point (2026-11-18).
Consequences: Amends D96's consequence that the SDK's voice session sends the trained prompt and a reply cap: it sends neither unless the app sets them. The session reuses `Model`'s request plumbing (registration, the close gate, `ModelClosed`); client events sent before the native handle exists are queued and sent in order. A pipeline of separate listening, chat and speaking models (D95) can sit behind the same handle and events later. JNI gains `requestSend`.

### D99. The voice session captions the user's words when asked, on a second slot, while the reply is made

Status: Decided (2026-11-18)

Context: Upstream's realtime conversation sends no transcript of the user's speech (F169), and D98 left the user's words out until their cost was measured. On the phone's NPU an open session holds its slot, so a transcription on one slot waits until the session ends; on a second slot, transcribing a 5.3 s turn while the reply is made moves the first audio from 0.3 to 0.6 s and slows the speech from 1.65 to 1.5 times real time while it runs, and transcribing it before the reply moves the first audio to 1.0-1.3 s; the second slot takes about 0.1-0.4 GB more (F170).
Decision: `VoiceConfig.Builder.transcribeUser { language; prompt }` turns captions on; they are off by default. The session keeps the audio of the open turn, and at each commit with audio it transcribes it with `Model.transcribe` on the WAV of the turn, in parallel with the reply, and sends `VoiceEvent.UserTranscript(itemId, text)` after that turn's `TurnCommitted`. The caption's language is its own option (the user's language, unset: the model tells), apart from the session's `language`, which is the reply's speech, as upstream separates input transcription from output speech. With fewer than two slots `voiceSession` fails with `InvalidRequest` naming `parallelSlots = 2`. A failed caption is a `VoiceEvent.Error` of type `user_transcription_failed`, and the session goes on. The maintainer chose each point (2026-11-18).
Consequences: Amends D98 (no event for the user's words). A deviation from upstream's conversation, cited to F169 and F170: the SDK adds an event upstream doesn't send, through upstream's own transcription route. An app that captions loads with two slots and pays their memory; one that doesn't pays nothing. A turn's audio is held in memory until its commit.

### D100. The demo's voice mode: a full-screen view entered from the message bar, hold to talk, saved as an ordinary chat

Status: Decided (2026-11-18)

Context: D95 puts a voice mode in the demo, push-to-talk first, with captions, saved as a chat. The SDK has the voice session (D98) and optional captions of the user's words on a second slot (D99); on the phone's NPU a reply's first audio comes 0.3 s after a turn ends and speaks at 1.65 times real time (F170). LFM3-Omni needs a voice, a language and an answering instruction (F166). The demo already records voice messages for models that hear (D76), and shows the mic only once a model has said it hears.
Decision: For a model that speaks, the message bar shows a voice-mode button; it opens a full-screen voice view over the chat: the orb in the middle shows listening, thinking and speaking, the reply's captions under it, the user's last words under those, a large talk button and stop at the bottom, and a close. Talking is hold to talk: release sends the turn (`commit`), and pressing while the model speaks stops the reply (`cancelReply`) and listens. Each turn is saved to the chat: the user's as a voice message (D76's bubble) with its caption, the reply as its text with its speech, which the reply's actions replay; the chat goes on by typing as any other. The SDK gains `ModelInfo.voices` (upstream's `/props` `voices`, empty for a model that doesn't speak), from which the app learns that a model speaks and remembers it, as for hearing. Captions of the user's words are on by default, with a switch in Settings; with them the model loads with two slots. Settings › Voice picks the voice from the model's list (the first by default); the spoken language follows the phone's when the model has it, else English; the built-in answering instruction comes first, the user's custom instructions after it. LFM3-Omni-2B joins the suggested models as the one that talks. Leaving the screen (Home, a call, the lock) stops recording and playback, as Stop does, and closes the session; the next press opens a new one; there is no background voice service. Playback goes to the speaker or the connected headset; the mic uses Android's voice-communication source, with the phone's echo cancellation and noise suppression. The maintainer chose each point (2026-11-18).
Consequences: Amends the demo design's "not in the app: text to speech" (section 2) for voice mode only; dictation stays out (D76). The voice view was drawn with the user (`docs/design/mockups/voice-mode.html`, demo design section 4.8): the orb shows each state, the reply's caption takes a new type role, `voiceCaption` (22/28 Regular sans), and Stop sits at the start of the talk button while the model speaks. Voice mode starts without the chat's earlier messages: upstream's conversation takes the person's items but not the model's replies, and putting the chat into its instructions or a made-up user turn would bend its API, so a chat that already has messages says so in the voice view ("Voice starts fresh: it doesn't see the messages above."), and leaving the screen starts the next session fresh too. The maintainer chose this over passing the chat in (2026-11-19); it is revisited if upstream's conversation takes assistant items. Talking is tap or hold: a tap starts a turn that the next tap sends, a longer press sends on release (the user's choice, 2026-11-19); hands-free waits for turn detection upstream, which b12479 lacks (`turn_detection` is always null), as the maintainer wants neither a patch nor a detector in the app. Journeys run on `FakeLlamaBro`'s voice replies; the real model runs once on the phone, attended. Reply audio is stored app-private (about 1 MB per 20 s) and deleted with its chat.

### D101. The demo's fit is a formula on file sizes against half the phone's total RAM; no network read, no free-memory reading

Status: Decided (2026-11-19)

Context: The demo labelled a model's fit two ways (demo design §7.1, D64): lists by a quick estimate from file sizes against the SDK's memory budget, and the model sheet and first run by the SDK's projection of each size, which reads every file's GGUF header over the network. The sheet's labels changed as the reads landed (the flicker read as irritating), the reads ran several at once and crashed the app (F172), and the budget follows the phone's free memory, which moves with what else is open: with voice mode's model loaded the phone had 3.6 GB free of 15.5 GB, so most sensible models read "Too big" though they run (F128 saw the same).
Decision: Every fit in the demo is the formula: a model needs its file plus its image or audio encoder, against half the phone's total RAM (the CPU device's `memoryTotal`); the room left gives the context word as the quick estimate did (at least 1.5 GiB large, 0.5 GiB normal, anything above zero small), and the speed comes from the file's size per chip as before. The same estimate shows in lists, the model sheet and first run, so nothing changes after it shows, and no file is read. The encoder's size comes from the catalog for its models and, for any other repo, from the Hub's listing: `Quantization.encoderBytes`, the encoder the Hub would fetch with that quantisation by the same rule it downloads with. The maintainer chose formula only, total RAM and the 50% share (2026-11-19).
Consequences: The SDK's `estimateFit` stays, for apps that want the projection; the demo no longer calls it, and the bridge measures one at a time anyway (F172). The KV cache is not modelled: a model with a large cache per token can label "Runs well" and then fit a shorter context than the word suggests, which the load's own automatic context still handles (D34). The 50% share is a judgement, not a measurement on several phones.

### D102. Public value types are data classes; builder-built requests and configurations keep their builders

Status: Decided (2026-11-19)

Context: D26 kept public types that may grow out of data classes, because a new field changes the JVM signatures of a data class's constructor and `copy`, and bytecode compiled against the old ones fails with `NoSuchMethodError` (F37). That failure needs a library compiled against one Llama Bro version running in an app that resolves a newer one; an app that depends on the SDK directly recompiles and is unaffected. No such library exists, breaking changes are accepted before release, and the rule had cost 24 hand-written `equals`/`hashCode`/`toString` triples (about 250 lines) and `copy` for callers.
Decision: Public value types are data classes: results (`ModelInfo`, `LoadReport`, `Timings`, `Usage`, `DeviceInfo`, `Backend`, `DownloadProgress`, `ChatEvent.Done`, `Ranked`, `Typed`'s outcomes, `VoiceEvent`'s events), progress (`LoadProgress`), and small settings and inputs (`Speculative.Mtp`, `Ngram`, `DFlash`, `LoraAdapter`, `Normalization.PNorm`, `Content.Image`, `Content.Audio`, `MediaSource.File`, `HfModel`). A type the SDK creates keeps its internal or private constructor, which also keeps its `copy` non-public. Two stay plain classes because a data class compares arrays by reference: `MediaSource.Bytes` and `VoiceEvent.ReplyAudio`. Requests and configurations built with `X { }` and changed with `copy { }` (`ChatRequest`, `Sampling`, `Reasoning`, `LlamaBroConfig`, `HubConfig`, `VoiceConfig`, `UserTranscription`) keep their builders, which name upstream's many optional fields better than a long constructor. D26's other rules stand: sealed and enum sets are settled at 1.0, the API dump is checked, and the API is Kotlin only. The maintainer chose data classes (2026-11-19).
Consequences: Adding a field to a data class is source-compatible for callers that recompile and a binary break for libraries compiled against the older SDK; a release that adds one says so in its notes. `ModelInfo` keeps a short `toString`, since its template and metadata run to kilobytes.

### D103. One error rule: a call or flow fails with a `LlamaBroException`; only misuse throws Kotlin's own exceptions

Status: Decided (2026-11-19)

Context: The README said everything the SDK throws is a `LlamaBroException`, but a reply the SDK couldn't read escaped as the parser's exception: `SerializationException` from a malformed chat chunk during collection, `NoSuchElementException` from an empty embeddings, rerank or transcription body, `ClassCastException` from a field of the wrong type. The README's list also missed `InsufficientMemory` and `DeviceUnavailable`. Misuse (an invalid argument, a call in the wrong state) already threw `IllegalArgumentException` and `IllegalStateException`, as `require` and `check` do.
Decision: A call or a flow fails only with a `LlamaBroException`. A successful reply the SDK can't read is `ServerError` with status 200 and the parser's error as its cause; `ErrorMapping.readReply` wraps every reply the SDK parses (chat chunks, embeddings, rerank, transcription). Misuse of the API stays `IllegalArgumentException` or `IllegalStateException`, listed under each function's `@throws`. The README and architecture section 7 list the full set. The maintainer chose the rule (2026-11-19).
Consequences: Apps catch one sealed type for every runtime failure and see Kotlin's own exceptions only for bugs in their own calls. `ServerError` gains an internal cause.

### D104. The engine and backend seams are a `spi` package; `internal` is Kotlin-internal

Status: Decided (2026-11-19)

Context: The package `com.suhel.llamabro.internal` held both the runtime's implementation and the seams other modules implement: `Engine` for the testing fakes (D27), `BackendInstaller` for the backend artifacts (D24). The seams were public under `@InternalLlamaBroApi`, so a package named internal was public API; the `SEND_*` constants lacked the opt-in, and `Engine.readFd` had a JVM-only `/dev/fd` reader as its default, in production code.
Decision: `Engine`, `EngineModel`, `EngineRequest`, `EngineAttachment`, `RequestSink`, `NativeError`, the `SEND_*` constants, `BackendInstaller`, `BackendOptions`, `BackendLibraries` and `LogcatLogger` (the hub's default logger) move to `com.suhel.llamabro.spi`, all under `@InternalLlamaBroApi`. Everything left in `internal` is Kotlin `internal`. `Engine.readFd` has no default: the JNI engine reads natively and `FakeLlamaBro`'s engine reads through `/dev/fd`. JNI and the consumer R8 rules name the new package. The maintainer chose the split (2026-11-19).
Consequences: The API dump shows the seams under one package name that says what they are. Backend artifacts and fakes import from `spi`.

### D105. API shape: a sealed `ModelSource`, internal speech endpoints, `Long` seeds, `Typed.text`, no `@Jvm` annotations

Status: Decided (2026-11-19)

Context: A review of the public API found small mismatches. `ModelSource` was an open interface whose loader refused anything but its own file source and a `RemoteModelSource`. `Endpoint.Tts` and `Endpoint.Speech` were public though `rawRequest`'s strings can't carry their audio (F74). `ModelConfig.seed` was `Int?` while `Sampling.seed` is `Long?`, and llama-server's seed is a 32-bit unsigned number. `Typed`'s outcomes called the reply's text `json`, though an incomplete reply's text is not JSON. `ChatRequest` exposed `options` beside seven getters that read through it. D26 says the API is Kotlin only, yet about sixty `@JvmStatic`, `@JvmOverloads` and `@JvmField` annotations shaped it for Java.
Decision: `ModelSource` is sealed; `RemoteModelSource` stays open for the hub and apps. `Endpoint.Tts` and `Endpoint.Speech` are internal until a request returns bytes. Both seeds are `Long?`. `Typed.Value`, `Incomplete` and `Invalid` name the reply's text `text`. `ChatRequest.options` is internal; the getters stay. The `@Jvm*` annotations go, except one `@JvmName` that resolves a JVM signature clash. `LbToolCallContext` keeps its public constructor, since apps build one to unit-test a typed tool. The maintainer chose each point (2026-11-19).
Consequences: Breaking for callers of the renamed and hidden members, which v2 allows. Java callers reach companion members through `Companion`.

### D106. `create` compares settings, not the logger or token provider; hub locks key by canonical path; saves read version 3 only

Status: Decided (2026-11-19)

Context: `LlamaBro.create` and `LlamaBroHub.create` returned the existing object for an equal config and threw for a different one (D70), with the logger and the hub's token provider compared by reference. A config built twice with a new logger lambda threw, and the README warned three times to build it once. The hub's file locks were keyed by the lock file's inode (`fileKey`), which covers hard-linked folders and bind mounts that an Android app doesn't meet, and created the file only to read its identity; `create` already keys hubs by canonical path (D89). `Conversation.fromJson` still read save formats 1 and 2, which only development builds ever wrote.
Decision: A repeated `create` compares the settings only (the log level and NPU polling; the hub's endpoint and progress interval). The first call's logger and token provider stay in use. The hub keys its locks by the lock file's canonical path, as `create` keys hubs. `fromJson` reads version 3 only and refuses the others as unsupported. The maintainer chose each point (2026-11-19).
Consequences: Amends D70 and D89. A DI module that builds the config on each call works; a logger passed to a later `create` is ignored, as the KDoc says. Saves from development builds before version 3 no longer load.

### D107. The hub loads and lists installed copies only; a copy without an installed record is not on the phone

Status: Decided (2026-11-19)

Context: Since D64 the hub writes an installed record when a download completes and loads exactly that copy. Copies downloaded before the record existed had none, so `LocalCopies` reconstructed one from the cached listing and upstream's selection rule, wrote it from read paths (`source`, `cached`, a plan's start), and `cached()` merged that reconstruction (the refs view) with the installed records under extra conditions (D89). That was about a third of `LocalCopies`, a second answer to what is on the phone, and the source of one of D89's bugs, and it served only development phones: no release wrote the old layout.
Decision: `source`, `cached()`, `hasUpdate` and the sampling lookup read installed records only. A copy without one is not on the phone and downloads again, reusing the blobs whose hashes match. The cache layout stays upstream's. The maintainer chose this (2026-11-19).
Consequences: Amends D64 and D89. Development phones download their pre-record models once more. The upstream-listing golden for `cached()` goes, since `cached()` no longer follows `refs/main`.

### D108. Streaming markdown re-parses from the last top-level block; lists and tables are not resumed row by row

Status: Decided (2026-11-19)

Context: D74's streaming parse re-read only the block the line being written could still join, and inside a list or table it resumed from the last item or row: a top-level list's last item parsed as a list of its own and renumbered, a table's last row read again under the table's header. That kept a 10 KB list or table at about 0.01 ms per append on the host, against about 0.3 ms for the whole table, at the cost of the subtlest code in the demo, while the budget is 0.5 ms and replies that are one 10 KB table are rare.
Decision: The parse restarts from the last top-level block that starts before the text's last line; a list's items share their list's start, so a list or table being written is parsed whole on each append. Earlier blocks keep their instances, and blocks parsed again that come out equal keep theirs. The maintainer chose this (2026-11-19).
Consequences: Amends D74's streaming paragraph. A long list or table costs more per append as it grows, still under the budget on the host; the phone is not re-measured.

### D109. Captions of the user's words move to the app: it transcribes its own recording of each turn

Status: Decided (2026-11-19)

Context: D99 put captions of the user's words in the SDK's voice session: it kept each turn's audio, transcribed it with `Model.transcribe` on a second slot while the reply was made, and sent `VoiceEvent.UserTranscript`, an event upstream's conversation doesn't send (F169). That composed two upstream features into app behaviour, against the rule that the SDK exposes llama-server and makes no app policy, and it paired captions to turns by counting commits, which a commit sent through `sendRaw` would misalign. The app already keeps each turn's recording as a voice message and knows which message it is.
Decision: The voice session stays upstream's: `transcribeUser`, `UserTranscription`, `VoiceEvent.UserTranscript` and the two-slot check go. The demo transcribes each turn's saved recording with `Model.transcribe`, one turn at a time and in order, on the second slot while the reply is made, and writes the text to that turn's message. The SDK README shows the recipe. The maintainer chose this (2026-11-19).
Consequences: Amends D99 and D100. Captions pair with their turns exactly. F170's costs stand: about 0.3 s later to the first audio and a second slot's memory, paid by apps that caption. A failed caption leaves the turn as a voice message without text, as before.

### D110. Hub search and recommended sampling stay in the hub, as a deliberate exception to "expose llama-server"

Status: Decided (2026-11-19)

Context: The SDK exposes llama-server's features and little beyond them. The hub's search (paging, base-model grouping, quantisation listing, D68) and `recommendedSampling` (the GGUF header's and the base model's `generation_config.json`, D79) go beyond upstream's `-hf`, which only fetches a named model. A review proposed moving them to their own module. They rest on about twelve of the hub's internals (the resolver, transport, header reader, plan resolution, installed-copy lookup, token handling), so a module of their own would make those public behind the opt-in, or copy them, and move `FakeLlamaBroHub`'s search scripting with them.
Decision: They stay in `llamabro-hub`, recorded as a deliberate scope exception: choosing a model to download is what a hub is for, and every app that lets people pick a model needs it. The maintainer chose this after weighing the split (2026-11-19).
Consequences: The hub's public surface keeps search and sampling; its internals stay internal.

### D111. Patch 0006 is dropped: speech computes the text head on every frame again

Status: Decided (2026-11-19)

Context: Patch 0006 (D86, D94) made `llama_batch_ext`'s two output flags differ so that LFM3's speech-only frames skip the 2048 × 128,000 text head: 38.3 → 33.8 ms per frame on the NPU, speech 2.0 → 2.3 times real time (F156, F165). It is 297 lines over nine files of llama.cpp, `llama.h`'s semantics included, and the costliest patch to carry at each upgrade. Without it speech still runs at about twice real time on the NPU, ample for playback; captions on the second slot slow it further (F170).
Decision: Patch 0006 goes from `sdk/native/patches/`; it is kept in `research/ph/speech-output/` for upstream. Patch 0008, which edits the same speech helper, is re-cut on the remaining patches with its changed lines unchanged. The maintainer chose this (2026-11-19).
Consequences: Amends D86 and D94. Speech output on the NPU costs about 4.5 ms more per frame; `device-speech` still requires at least 1.5 times real time; the phone gave 2.41 without the patch (19.8 s of audio in 8.2 s). One patch fewer to port at each llama.cpp upgrade.

### D112. The Vulkan recipe leaves the live build for research/vulkan-adreno

Status: Decided (2026-11-19)

Context: D31 took Vulkan out of 1.0 but kept its recipe in the live build for a retry: a `vulkan` value of `LB_BACKENDS` in the CMake file, the SPIRV-Headers include, `build-backends.sh`'s host build behind `LB_BUILD_VULKAN=1`, and `fetch-deps.sh`. The retry at b12479 (F174) made ggml-vulkan run correctly on the Adreno 830 with four patches, and found it slower than the CPU and OpenCL for prompts and replies there; its build recipe and patches live in `research/vulkan-adreno/`.
Decision: The live build loses its Vulkan branches. `fetch-deps.sh` moves to `research/vulkan-adreno/`, beside the build script and the patches that use it. `DeviceKind` keeps mapping a `vulkan` backend to the GPU, so a returning route needs no API change (D32). The maintainer chose this (2026-11-19).
Consequences: Amends D31's "the build recipe is kept". A Vulkan retry starts from `research/vulkan-adreno/README.md`.
