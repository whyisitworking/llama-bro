# Decision records

This file lists the design decisions of the SDK, numbered D1 to D50 in the order they were made. Each record has a status line, the context that forced the choice, the decision itself and its consequences. Decisions are cited elsewhere in the documentation as `D<n>`, and the measurements that justify them as `F<n>` (see the [findings register](./research/findings.md)). The [architecture guide](./architecture.md) describes the resulting design.

Several records amend or replace earlier ones; the amending record says so, and the amended record is kept unchanged apart from a pointer, so the order of events stays readable. All records carry the status "Decided". Where a decision was made before the dated record-keeping began, the date is that of the first design document (2026-11-03). Terms used throughout: "upstream" is llama.cpp at the pinned build `b12052`, "llama-server" is its server tool, and `tools/ui` is the web client that ships with it.

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
Consequences: Every upgrade needs a compile check and the contract tests, and patches are applied to a build copy, never committed into the submodule.

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
Consequences: The order became NPU, OpenCL, CPU once Vulkan left 1.0 (D31). A benchmarking policy is deferred to a later phase.

### D25. API revision before the backend phase

Status: Decided (2026-11-04)

Context: A review of the first public Kotlin API found binary-compatibility traps, a beginner path that could not reach most request options, a blocking `close()`, errors without causes and no way to fake the SDK in tests (F37-F44).
Decision: A dedicated phase fixes these before the backend work builds on the API.
Consequences: The API was settled before external use, at the cost of a short delay.

### D26. API evolution rules

Status: Decided (2026-11-04)

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
Consequences: A byte-returning request and a way to keep the multimodal projector on the CPU would be needed first (F74).

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
Consequences: Cache layout stays compatible with llama.cpp. A device test accepts an `Auto` refusal that agrees with `estimateFit`, since the test phone's budget was 9-14 MiB short of DFlash's minimum (F90).

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
