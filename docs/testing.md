# Testing and verification

Two scripts hold all verification: [`sdk/scripts/check.sh`](../sdk/scripts/check.sh) for the SDK and [`demo/scripts/check.sh`](../demo/scripts/check.sh) for the demo app. Each prints one `PASS` or `FAIL` line per step, writes every step's log to a build folder (`sdk/native/build/check/`, `demo/build/check/`), and ends with `ALL PASS` or `SOME CHECKS FAILED`. The demo script has a third outcome, `PASS, WITH SKIPPED OR PARTIAL STEPS`, for a step that could not run in full (exit code 77); a skip is reported, never counted as a pass.

The guiding rule for the SDK is that upstream is the baseline: wherever llama.cpp has a reference behaviour (llama-server's replies, the `tools/ui` message handling, the `-hf` downloader), the SDK is compared with it byte for byte, so a difference is a finding rather than a judgement call. Recorded results are in [research/findings.md](research/findings.md); the first full run is F31.

## Conventions

- **A gate runs once.** A failed gate is debugged on the narrowest slice that reproduces the failure (one step, one test, one screen) with `--only` or a Gradle test filter; the whole gate runs again only after the fix.
- **Routine runs stay short.** Anything slower than a few minutes is made faster (a slice, a smaller model, an emulator) before it is asked for.
- **Benchmarks are optional.** They run after a major phase completes, not on every change, and they record the best the build can do and the headroom under each budget rather than a pass or fail ritual.
- **Never run model inference on an emulator.** The emulator proves UI flows with the fake engine. Real models run only on a physical phone, and only for what only a real model shows: download and resume, the first token, a reply. A step that has passed once on the final build is not repeated.
- **Long or random-input runs stay off the test phone** where they can reach system settings. Monkey runs on the emulator (F117); the phone's airplane mode and other settings are never changed, and the scripts stop when airplane mode is on.
- **Device tests are started through the check scripts**, or with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`. Otherwise Gradle uninstalls the test app, and the models pushed into it, when the run ends (F57). With more than one device on adb, `ANDROID_SERIAL` (SDK) or `DEMO_DEVICE` (demo) names the target.
- **Tests wait on signals, not clocks.** A test waits for the state that proves the code got there (the demo's `eventually`, a latch, a stream event), never for a fixed time. A check that something did not happen first waits for the point where it would start, then gives the wrong action a short grace to land (`letAWrongActionLand`); where nothing marks that point the grace stands alone, with a comment naming the missing signal. A timeout bounds a hang; a latency check is bounded by the work's own pace (native cancel tests: a few token or prefill-pass gaps), not by milliseconds. Host suites are stressed under load before they are called CI-safe (F182).
- **Tests exist where a decision needs them.** A test that can only measure noise is not written (F12, F13).
- **Every SDK change passes `sdk/scripts/check.sh`**, with `--device` when native, JNI or packaging code changed. After a llama.cpp upgrade a failing contract case or `tools/ui` golden is the list of upstream behaviour to review and port (F31).

## SDK checks: `sdk/scripts/check.sh`

```bash
sdk/scripts/check.sh                          # host steps
sdk/scripts/check.sh --device                 # plus the phone steps
sdk/scripts/check.sh --only contract,jvm      # a slice, by step name
```

Environment: `LB_MODELS_DIR`, where the large GGUFs are (default `~/Downloads`); `LB_TEST_MODEL` (default `Qwen3.5-4B-Q4_K_M.gguf` there), `LB_TEST_MMPROJ` (default `mmproj-F16.gguf` there; the image cases are left out when it is missing), `LB_TEST_DFLASH` (default `~/llamabro-models/Qwen3.5-4B-DFlash-Q8_0.gguf`), `LB_CONTRACT_MODELS` (extra GGUFs for the contract, comma separated; Llama 3.2 3B when present), `LB_DEVICE_MODELS` (file names in the acceptance app's files directory; default Qwen3.5 4B, Gemma 3 4B and Llama 3.2 3B), `LB_VISION_MODEL` and `LB_VISION_MMPROJ` (a small vision model and its projector in the same directory, for `VisionTest` on the NPU; default `Qwen3.5-0.8B-Q4_K_M.gguf` and `mmproj-Qwen3.5-0.8B-BF16.gguf`: the demo's files from `unsloth/Qwen3.5-0.8B-GGUF`, with the projector renamed from the repo's `mmproj-BF16.gguf`), `LB_TEST_MODELS_DIR` (the small embedding, rerank, LoRA and audio models; fetch them once with `sdk/scripts/fetch-test-models.sh`, about 265 MB). A run of only `build`, `tools-ui`, `api`, `hub-api`, `quality`, `lint` and `aar` needs no model on disk. The audio tests use the committed `sdk/scripts/fixtures/clip.wav` (passed as `LB_TEST_CLIP`).

Ports: every server the script starts on this machine (`llama-server` for `contract` and `e2e`, the fake Hugging Face server for `hub-device` and `hub-resume`) listens on a port the OS picks, and the device steps' `adb forward` ends on this machine are picked by adb, so full checks in several worktrees run at once (F159). `LB_CONTRACT_PORT`, `LB_E2E_PORT`, `LB_HUB_API_PORT` and `LB_HUB_CDN_PORT` fix them; the ports on the phone's side are fixed per phone (`LB_DEVICE_CONTRACT_PORT`, `LB_SPEECH_PORT`, and 18190 where the apps look for the hub), which the pre-flight below makes safe.

Durations below are approximate and depend on the machine, the cached builds and the phone's thermal state.

### Host steps

| Step | What it proves | Needs | Time |
|---|---|---|---|
| `build` | `prepare-llama.sh` applies every patch in `sdk/native/patches/` to a build copy and leaves the submodule untouched; the host CMake build of the bridge, `lb-run` and the test tools succeeds | the llama.cpp submodule, CMake, a C++ toolchain | minutes cold, seconds warm |
| `native` | The C API's host tests (`ctest`, upstream's own server tests for embeddings, rerank and LoRA on its small models included; the DFlash fit projection against a real load's peak memory footprint, which on macOS counts compressed pages, F90, F160) | the test models | under a minute |
| `hub-golden` | The Hub's resolver and cache match upstream's: its model-resolution self-test and recorded real repo listings, the GGUF header offsets of three real models, and the cache tree upstream writes from the same fake server, replayed through the Kotlin Hub (`CacheGoldenTest`, which must not be skipped) | Python 3, the three models in `~/Downloads` | a few minutes |
| `contract` | The C API answers exactly like upstream `llama-server` built from the same tree with the same flags: about 75 requests (chat, multi-turn, thinking on and off, tool calls, JSON schema, one image, sampling fields, completions, `/props`, `/metrics` names, cache counters across turns, a cancel and the request after it, error bodies), compared on reply text and counters. Runs once per model (Qwen3.5 4B, then each extra model), then the audio cases (`audio.jsonl`) on upstream's audio test model: the clip through upstream's multipart `/v1/audio/transcriptions` and a data URL in chat, against the bridge's attachments by path, descriptor and bytes (F55, D84), and four `/v1/realtime` sessions (a spoken turn answered in text and speech, two text turns, a transcription session, bad events) over a real WebSocket against the in-memory `realtime` endpoint, every event compared without its random ids, both sides at seed 42 (D97, F168) | the model, the mmproj, a free port | minutes per model |
| `server-contract` | `llamabro-server` answers like upstream `llama-server` from the same build with the same flags (statuses and bodies identical, generated ids and timings masked, headers not compared) (`sdk/native/tests/contract/server-run.sh`): chat, Anthropic messages, Responses and completions, streamed and whole, token counts, `/props`, `/metrics` names and errors, every body and status compared, on the model; embeddings on `bge-small`. Then the official `openai` and `anthropic` Python SDKs (pinned in `sdk/scripts/tests/server-clients-requirements.txt`, in a venv under `sdk/native/build/host/server-contract/venv` made on the first run, which needs the network) make a whole and a streamed call each against the server with a key, and a wrong key raises each SDK's `AuthenticationError` (F189) | the model, `bge-small` | about a minute |
| `tools-ui` | llama.cpp's own `tools/ui` compaction code (Llama Chat, `ui/apps/chat` since b12479), regenerated in node, equals the committed golden of 400 seeded message lists; the Kotlin replay of the golden is part of `jvm` | node | seconds |
| `api` | The public APIs of the core, `llamabro-typed` and `llamabro-testing` equal their reviewed dumps (`sdk/<module>/api/<module>.api`). An intended change is reviewed with `./gradlew -p sdk :<module>:apiDump` and committed with the code | Gradle | seconds |
| `hub-api` | The same for `llamabro-hub`, `llamabro-hub-work` and `llamabro-hub-testing` | Gradle | seconds |
| `quality` | ktlint and detekt over every Kotlin module (the root build's `qualityCheck`); detekt baselines list what predates the rule | Gradle | about a minute |
| `lint` | Android lint on every SDK library, warnings as errors | Gradle | minutes |
| `jvm` | The Kotlin unit tests of the core, `llamabro-testing`, `llamabro-hexagon`, `llamabro-hub`, `llamabro-hub-work` and `llamabro-hub-testing`, including the 400-case `tools/ui` replay and `GgufHeaderTest` (which must not be skipped); `llamabro-hub-work`'s androidTest compiles | the golden output of `hub-golden` | a few minutes |
| `e2e` | Kotlin, JNI and native together on the host (`HostNativeTest`): every request replayed to a running upstream `llama-server` in the same order (typed chat, image, raw endpoints, an error, an agent conversation with tools, compaction, a send after it), cache counters included. Then the typed embeddings, speech input (`HostAudioTest`) and the voice session (`HostVoiceTest`: a spoken turn answered in text and speech through Kotlin, JNI's `requestSend` and the in-memory `/v1/realtime`, a refused event, a refused setup, the model closed under a session) on upstream's small test models. Fails if a test was skipped | the model, the mmproj, a free port | a few minutes |
| `aar` | The release AARs: the native libraries under their own `llamabro-` names, the exported symbols (only `lb_*`, `JNI_OnLoad` and the type information that C++ catches need), 16 KB `LOAD` alignment, the five Hexagon skeletons and their checksums, no `libomp.so`, no `jni/` folder in the Hub and hub-work AARs, the `dataSync` foreground service and its permissions in hub-work's manifest, no copy of the core's libraries in a backend AAR (`check-aar.sh`) | Gradle, the NDK | minutes |
| `scripts` | The host scripts against stubs, with no phone: `device-preflight.sh` (the phone lock and the refusals, `tests/device-preflight-test.sh`), `bench.sh` run end to end against a stub adb that plays a phone and stub Gradle, `say` and `afconvert` (a missing model file, a phone that never cools, a failed instrumentation call, two rounds with the arm order reversed in the second and the results written only after the last arm, no serial in any file or line; `tests/bench-sh-test.sh`), and `bench.py`'s merge, charts and README check (`tests/bench-test.py`) | Python, bash | seconds |

### Device steps (`--device`)

These need a phone on adb that is unlocked (the script checks `isKeyguardShowing`). A Qualcomm phone with the Hexagon NPU is needed for the NPU steps.

Before the first of them, `preflight` (`sdk/scripts/device-preflight.sh`) refuses to run them while something else uses the phone: another check holding the phone's lock (`/tmp/llamabro-device-<serial>.lock`, or `LB_DEVICE_LOCK_DIR`), an `adb forward` or `adb reverse` to the phone, or a `llama-server`, `lb-run` or `com.suhel.llamabro*` process on it (F150). Its message names what it found and how to clear leftovers; the check holds the lock (a symlink, taken atomically) until it ends. `bash sdk/scripts/tests/device-preflight-test.sh` checks it against a stub adb, with no phone.

| Step | What it proves | Needs | Time |
|---|---|---|---|
| `device` | The core's native, UI and device-choice tests on the phone; the Hexagon and OpenCL backend AARs each make their device available alone, including DFlash against upstream's reply, `ContextSize.Auto` and the embedding and rerank tasks; the acceptance app per flavor (`full` runs every test on every model, and `VisionTest`, which asks the vision model on the NPU what a red and blue picture shows, with the encoder on the NPU and on the CPU (F120, F121, D53); `cpu`, `gpu` and `noskel` run device choice, a chat and stop; `noskel` carries a broken NPU skeleton and must fall back to the GPU with the reason); each flavor's minified (R8) release build loads a model, which checks the consumer keep rules (F28). The only tolerated failure is Llama 3.2 3B's tool loop, which fails on upstream too (F27); a flavor that did not run fails the step | the phone, the three models, the DFlash draft, the small test models | tens of minutes |
| `hub-device` | The Hub's cache and download tests on the phone against the fake Hugging Face server, reached through `adb reverse` | the phone, Python 3 | minutes |
| `hub-resume` | Kill and resume: the acceptance download app is killed (`kill -9`) and force-stopped mid-transfer of a 1.5 GiB file at 30 MiB/s and must resume with a `Range` request, verify both blobs against the corpus digests and leave no partial; the notification's byte count must rise (F92) | the phone, notification permission granted at install (`adb install -g`) | several minutes |
| `device-contract` | The `contract` comparison again, on the phone's NPU: upstream `llama-server` and `lb-run` are both built in Qualcomm's Snapdragon image from the same tree and run with `-dev HTP0 -ngl 99`; passes only on "N/N identical", where a streamed chat reply without tools that matches token for token up to one near-tie (top-2 gap at most 0.25) and splits there counts, since the NPU breaks such ties either way between runs (F149, D80). The script also prints the first replies, because two identical wrong answers would pass (F71) | Docker with the Snapdragon image, the phone, the model | tens of minutes |
| `device-speech` | LFM3-Omni-2B speaks through upstream `llama-server`'s `/tts` with everything on the phone's NPU and polling on, as with `npuPolling` (the device tools `device-contract` builds): a 55-word text must give at least 10 s of audio, the speech projector must report no unsupported op, and it must speak at least 1.5× faster than real time (patches 0005-0007, D86; F155, F156, F162) | the phone, LFM3-Omni-2B and its mmproj (pushed once from `LB_TEST_MODELS_DIR`) | about a minute |

CI (`.github/workflows/ci.yml`) runs the checks that need no model and no device on every push to `main` and every pull
request: the host scripts' tests; the SDK's API dumps, lint, JVM tests and release AARs; and, in the job that builds the
backend modules' native libraries in Docker (`qualityCheck` depends on that build too), `qualityCheck` and the backend
modules' and the demo's lint, unit tests and debug build. Not covered by a script: HWASan and generated API
documentation (planned before release). The fake Hugging Face server (`sdk/scripts/hub-fake/fake_hub.py`) models refs, trees, a 302 to a second CDN host, `Range`, ETag changes, authentication and throttled transfer rates, so Hub tests are repeatable and offline; one real download from huggingface.co is run by hand per release (F93).

## Demo checks: `demo/scripts/check.sh`

```bash
demo/scripts/check.sh                                   # JVM steps: unit, lint, assemble, ds, quality
DEMO_DEVICE=<serial> demo/scripts/check.sh --device     # plus instrumented tests on that device or emulator
demo/scripts/check.sh --device --perf                   # plus the benchmarks (the phone only)
demo/scripts/check.sh --device --real                   # plus the real-model run (the phone only)
demo/scripts/check.sh --only ds,quality                 # a slice, by step name
```

`--perf` and `--real` need `--device`. The step names are `unit lint assemble ds quality device journey monkey coverage map perf real`.

| Step | What it proves | Needs |
|---|---|---|
| `unit` | JVM tests of `:designsystem` (tokens, theme mapping, contrast pairs, type scale and spacing grid) and `:app` (data, models, chat engine, every view model's states) over the SDK's `FakeLlamaBro` | Gradle |
| `lint` | Android lint on both modules, warnings as errors | Gradle |
| `assemble` | The debug APK, the minified release APK and the instrumented-test APKs build | Gradle |
| `ds` | The design-system gates described below: the Compose stability report, and no raw visuals or numbers in a screen | Gradle, Python 3 |
| `quality` | ktlint and detekt over every Kotlin module | Gradle |
| `device` | The instrumented tests of `:designsystem` (component behaviour, with pixel probes on a few nodes; there are no screenshot comparisons) and `:app` (every state of every screen, accessibility checks, StrictMode, LeakCanary), excluding journeys and monkey | `DEMO_DEVICE`, Python 3 (the fake Hub) |
| `journey` | Whole-app stories over a fake engine and the fake Hub: first run to first answer, download in the background, pause, resume and cancel, process death, search and get a model, switch models mid-chat, edit and regenerate, a voice message recorded from a scripted microphone, sent and answered (D76), share, gated model and token, memory refusal, a walk over every screen and sheet in both themes. `NotificationDeniedJourney` runs first on a fresh install, because the system asks about notifications once per install | `DEMO_DEVICE` |
| `monkey` | Three fixed seeds of 10,000 random events at the app through Android's `monkey`, restricted to the app, with the fake engine; passes on no crash, no ANR and an app that still answers. `--pct-flip 0` keeps the emulator from aborting on a lid flip (F110). Skipped (exit 77) on a phone | an emulator (`DEMO_DEVICE=emulator-5554`) |
| `coverage` | The merged unit and device JaCoCo reports against the floors below | the results of `unit` and `device` |
| `map` | Every test named in the requirement map exists and passed in the latest results. On an emulator a journey skipped for want of a photo picker is reported, not failed; on the phone no skip is allowed | the results of `unit`, `device`, `journey` |
| `perf` | The Macrobenchmarks against the budgets below, one class at a time, each after the phone's thermal status is 2 or lower (it waits up to ten minutes). About an hour; never part of a default run. Results and Perfetto traces are kept in `demo/build/check/perf` | the phone, not an emulator |
| `real` | The real-model run: a fresh debug install on the phone, seven steps with real models from huggingface.co (about 4 GB downloaded, 30 to 45 minutes). Never a routine gate | the phone, network, approval to download |

### What needs a device

| Needs | Steps |
|---|---|
| Nothing but the host | SDK: `build`, `native`, `hub-golden`, `contract`, `server-contract`, `tools-ui`, `api`, `hub-api`, `quality`, `lint`, `jvm`, `e2e`, `aar`, `scripts`. Demo: `unit`, `lint`, `assemble`, `ds`, `quality` |
| A device or an emulator (fake engine, no model) | Demo: `device`, `journey`, `monkey`, `coverage`, `map` |
| The physical phone | SDK: `device`, `hub-device`, `hub-resume`, `device-contract`, `device-speech` (a Qualcomm phone for the NPU steps). Demo: `perf`, `real`, and the journeys that need a photo picker (`sendAnImage`) |

### Fake-engine journeys and the real-model run

The journeys exercise everything the app does except the model. The app's container is built over `FakeLlamaBro` (the
engine seam in `llamabro-testing`) and `FakeLlamaBroHub` (`llamabro-hub-testing`, D70, D75), the real hub over a
scripted Hugging Face, whose downloads a scripted worker moves step by step; the journeys about WorkManager itself use
the real hub and the SDK's worker over a fake Hugging Face server on the host, listening on ports the OS picks and
reached through `adb reverse` (port 18190 on the phone, where the app looks; F159; corpus: `demo/scripts/hub-corpus.json`).
The device steps run after the same pre-flight and phone lock as the SDK's (`sdk/scripts/device-preflight.sh`, F150). The fake engine scripts replies, reasoning, per-token delays, and
the native load failures `file_not_found`, `insufficient_memory` and `other` through the SDK's own error path (F106).
The journeys therefore run on an emulator with no model, and they assert structure: a reply exists, the thought fold
exists, the divider exists.

They cannot show what only a real model shows, and they cannot kill the app's process or force-stop it between steps. The real-model run is a separate driver, `demo/scripts/e2e/real_model_e2e.py` with its small UI Automator wrapper `phone.py`, which controls the app from outside its process as a person would and reads `logcat` between steps. It runs the debug build, because only a debuggable build logs the hub requests that prove a resumed download used a `Range` request and received a 206, and it asserts on structure as well as the journeys do. Its seven steps:

1. First open, choose Qwen3.5 0.8B, Get, send a message before the download ends; a reply arrives. The seconds from first open to the first token are recorded.
2. Think it through at Quick and at Deep; the fold "Thought for N s" appears.
3. Send a solid blue picture to Qwen3.5 0.8B; the reply must name the colour (F119, F120: a reply alone passed while the model could not see the picture).
4. Get Gemma 3 4B, force-stop during the download, relaunch; the download resumes with a 206 and finishes.
5. Switch to Gemma 3 4B mid-chat (the divider and a reply), edit the first message, regenerate.
6. Switch to the Chat list layout, find the chat by search, share a reply (the share sheet appears).
7. Remove Gemma 3 4B; its chat shows the missing-model line.

Facts about driving a real phone from outside are in F114: start the activity by name (a debug build has two launcher entries), read `logcat` early because its ring buffer is 256 KiB, and note that clearing Recents kills the app. The script never toggles airplane mode.

### The design-system gates (`ds`)

The design system is enforced by the build, not by review (see [design/demo-app.md](design/demo-app.md)). The `ds` step reads source and compiler reports; it does not render components or compare screenshots. Its checks:

- The Compose compiler's stability report (`demo/scripts/stability.py`): no public composable of `:designsystem` may be restartable but not skippable, and no class may be reported unstable.
- No raw `Color(...)`, `TextStyle(...)`, `RoundedCornerShape(...)`, shadow or Material widget (`Button`, `Card`, `TextField`, `ListItem`, `ModalBottomSheet`, and so on) in `demo/app/src/main`; a new visual need becomes a component in `:designsystem` first.
- No animation spec in `demo/app/src/main` (`tween`, `spring`, `AnimatedVisibility`, `animate…AsState`, `animateItem` and the like): motion comes from `DemoMotion` through the design system's components (demo design §8.11, D58).
- No number in a screen: no `.dp` or `.sp` literal, padding, `Spacer`, `spacedBy`, `PaddingValues`, `DemoSpacing` reference or font override. Previews and the share card's pixel arithmetic are the two exceptions. No component overrides a type role's size, weight or tracking.
- At run time, the `WalkJourneys` walk asserts on every screen and sheet, in both themes, that the area kept inside the gutter starts 16 dp from the left edge and ends 16 dp from the right, that the window background follows the app's theme, and that the accessibility check passes once the surface has settled. A 20 dp gutter was shown to fail the walk.

### Coverage floors (`coverage`)

Measured over the merged unit and device reports of `:app`, with generated Room code, `BuildConfig` and `@Preview` files excluded (`demo/scripts/coverage.py`):

| Group | Lines | Branches |
|---|---|---|
| `data`, `models`, `chat` packages, each | 90% | 80% |
| every `*ViewModel` | 85% | none |
| `:app` overall | 75% | none |

The script prints each group against its floor and, for a group below one, the files furthest from it with the lines nobody ran.

### The requirement map (`map`)

The demo's requirements (each rule of the design) are listed in a table with the tests that prove them, tagged U (unit), C (component), S (screen), J (journey) and R (real model). `demo/scripts/map_check.py` reads that table, takes every backticked test name, and looks each up in the JUnit XML of the latest run (`demo/build/check/results/`, which keeps each connected run because the next run overwrites it). A named test that did not run or failed is an open item. A name ending in `Test` stands for the whole class: at least one of its tests must have run and none may have failed. `--list` prints every test, not only the problems. A requirement without a passing test is an open item, not a pass.

### Performance budgets (`perf`)

Measured on the test phone (a 120 Hz screen, 8.3 ms per frame), with the release build, R8 and the Baseline Profile, 10 iterations per journey, thermal status 2 or lower before each run. The fake engine and fake Hub are swapped in through a benchmark container so that the numbers are the app's own cost and the model does not disturb them. Macrobenchmark refuses a phone whose clocks are not locked and only a rooted phone can lock them, so the check is suppressed and the budgets are written for an unlocked, cooled phone. `perf.py` checks the budgets from the result files, because `MacrobenchmarkRule.measureRepeated` returns nothing a test can assert on.

| Journey | Metric | Budget | Measured (F108) |
|---|---|---|---|
| Cold start to the first frame | time to initial display, median | 450 ms | 116 ms (123 ms without the profile) |
| Cold start to home fully drawn | time to full display, median | 700 ms | 198 ms (236 ms without the profile); 255 ms after the design system rework |
| Fling the Chat list (300 chats) | frame overrun P90 / P99 | 0 / 4 ms | P90 -9.0 ms |
| Fling a chat of 300 messages with code blocks | frame overrun P90; CPU frame time P90 | 0 / 7 ms (6 ms before the glass, F186) | P90 -7.3 ms; with the glass -1.8 ms and 6.8 ms (F185) |
| A reply streaming at 60 tokens a second for 20 s | frame overrun P90; `ChatEngine.onDelta` and `Markdown.parse` medians | 0 ms; 0.5 ms each | -1.4 ms; 5 microseconds and 0.18 ms per call |
| Open the model sheet and switch Basic and Advanced 10 times | frame overrun P90 | 0 | -6.0 ms |
| Switch the layout back and forth 10 times | frame overrun P90 | 0 | -4.5 ms |
| Memory while streaming in a 300-message chat, model excluded | Java heap held (Heap Alloc from `dumpsys meminfo`, median of the run peaks) | 64 MB | 21.2 MB |

The heap budget measures what the heap holds, not what the runtime sets aside: Macrobenchmark's own heap metric saw-tooths from 19 MB to 75 MB inside a stream because of the generational collector's headroom, while the held heap stayed at 10 to 23 MB and the baseline stayed flat across ten runs, so there is no leak (F108). Both message styles in the chat fling are not covered, and the cost of the model itself is outside these numbers. The Baseline Profile is generated by the benchmark module's generator over startup, opening a chat, streaming and the model sheet, and is regenerated when the composables change enough to matter; startup is measured with and without it.

SDK-side speed, memory and device measurements (prefill and decode rates per chip, fit projections, load times) are experiments, not gates. Their scripts, method and raw results are under [research/](../research/), and the summaries are in [research/history.md](research/history.md) and [research/findings.md](research/findings.md). They alternate the builds within a round and compare only within a round, because the phone throttles under sustained NPU load (F6). Three device tests in the acceptance app are of this kind and are skipped unless a runner argument asks for them: `SlotCacheDeviceTest` (`slotCache=<file.gguf>`, F135), `ReplyLimitDeviceTest` (`replyLimit=<file.gguf>`: the reasoning budget's forced end and Continue after a cut, F137) and `BenchDeviceTest` (`bench=<arm>`, `benchRound=<n>`: the README's numbers, one arm per run, one JSON line each in `filesDir/bench.jsonl`); each class's KDoc has the command. The README's numbers are measured by `sdk/scripts/bench.sh speed` and `bench.sh extras` (the phone, attended): it installs the acceptance app once, runs `BenchDeviceTest` (and `SlotCacheDeviceTest` for the `restore` arm) one arm per call after the phone has cooled to thermal status 1, reverses the arm order every second round, and writes `research/bench/results.json` and the charts in `assets/readme/` only when a mode finished; the method is in [research/bench/README.md](../research/bench/README.md).

## Where the tests live

| Area | Location |
|---|---|
| C API host tests | `sdk/native/bridge/tests/` (built as `lb-tests`) |
| Contract against upstream | `sdk/native/tests/contract/` (`run.sh`, the request set from `make-requests.py`, `compare.py`) |
| Server contract and client smoke | `sdk/native/tests/contract/server-run.sh`, `server-requests.jsonl`; `sdk/scripts/tests/server-clients.py`; `HostServerContractTest` in `sdk/llamabro-server` |
| Device contract on the NPU | `sdk/native/tests/device-contract/` |
| Speech on the NPU | `sdk/native/tests/device-speech/` |
| Hub goldens from upstream's code | `sdk/native/tests/hub-golden/`, `sdk/scripts/hub-fake/` |
| `tools/ui` golden | `sdk/scripts/tools-ui/`, `sdk/llamabro/src/test/resources/tools-ui/` |
| Kotlin unit tests | `src/test` of each module under `sdk/` |
| Phone tests and acceptance apps | `sdk/llamabro/src/androidTest`, `sdk/llamabro-hexagon/src/androidTest`, `sdk/llamabro-opencl/src/androidTest`, `sdk/llamabro-hub/src/androidTest`, `sdk/llamabro-hub-work/src/androidTest` (compiled by `jvm`; no `--device` step runs it yet), `sdk/acceptance/backends/`, `sdk/acceptance/download/` |
| Demo unit, screen, journey and monkey tests | `demo/app/src/test`, `demo/app/src/androidTest` (`journey/`, `monkey/`, `testing/`), `demo/app/src/sharedTest` |
| Demo component tests | `demo/designsystem/src/test`, `demo/designsystem/src/androidTest` |
| Demo benchmarks | `demo/benchmark/` |
| Real-model driver | `demo/scripts/e2e/` |

See also: [the SDK README](../sdk/README.md), [the demo README](../demo/README.md), [the demo's design](design/demo-app.md).
