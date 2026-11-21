# Release notes

What changed for apps. Decisions are cited as `D<n>` ([decisions](decisions.md)), measurements as `F<n>`
([findings](research/findings.md)); the [architecture](architecture.md) has the full design and the
[SDK README](../sdk/README.md) the examples.

## 2.0.0-alpha

A rewrite of 1.x: llama-server's core runs in-process behind a C API, and the Kotlin API is built on it. Code written
for 1.x needs changes throughout; this entry lists what an app meets, by area.

### Platform

- **llama.cpp b12479** (D93) with seven carried patches in `sdk/native/patches/` (0001-0005, 0007, 0008). The SDK
  matches llama-server on every contract case, on the Mac and on the phone's NPU (F163).
- **On Maven Central** as `io.github.whyisitworking`, with `llamabro-bom` to keep every module on one version (D133,
  D135).
- **Kotlin 2.2 or later** in the app; the SDK is built with Kotlin 2.3 (D82). arm64-v8a, Android 10 (API 29) and up.
- **Modules.** `llamabro` (core), `llamabro-hub` (downloads and search), `llamabro-hub-work` (a WorkManager download
  worker), `llamabro-typed` and `llamabro-typed-ksp` (typed output and tools), `llamabro-hexagon` and `llamabro-opencl`
  (optional NPU and GPU backends), `llamabro-testing` and `llamabro-hub-testing` (fakes). Every module's public API is
  tracked for binary compatibility.

### Core API

- **Entry points.** `LlamaBro.create(context, config)` and `LlamaBroHub.create(context, config)`, once per process (a DI
  singleton). An equal config returns the same object, a different one throws; the logger and token provider are not
  compared (D70, D106). Your logger sees native init; with none, lines go to logcat as `llamabro` (D66).
- **One error rule** (D103, D136): a call or a flow fails only with a `LlamaBroException`; misuse throws
  `IllegalArgumentException` (a bad argument: a negative `memoryReserveMib`, DFlash without a draft file, a voice the
  model doesn't have) or `IllegalStateException` (the wrong state: `create` with another config, `embed` on a chat
  model), in the core and the hub alike.
- **Flows warn when unused** (D136): `send`, `resume`, `regenerate`, `approve`, `deny`, `compact`, `Model.chat`,
  `rawRequest` and `hub.download` return cold flows, marked `@CheckResult`, so Android Studio flags a call that is never
  collected.
- **A conversation can compact itself** (D136): with `autoCompact = true`, a send that meets `ContextExceeded`
  summarises the older turns and answers in the same flow. Off by default, as in tools/ui.
- **`Speculative.Off` is the default**, and the only "off" (D136); `loadReport` says why drafting is off on the chip.
- **Value types are data classes** (D102); requests and configurations are built with their builders.
- **Shape** (D105): `ModelSource` is sealed; seeds are `Long?`; `Typed.text` holds a typed reply's raw text. The engine
  and backend seams live in `com.suhel.llamabro.spi`, marked `@InternalLlamaBroApi` (D104).
- **`ask` returns a result:** `Typed<String>`, `Value` or `Incomplete` (`Length`, `TurnLimit`, `ToolCallsPending`,
  `ToolAborted`), instead of throwing (D63, D71). `Conversation.askAgain()` and `Typed<T>.valueOrNull()` are new.
- **Cancelling a load** aborts the weight reading and frees everything; on the phone a cancelled 4B load returns in about
  1 s (D67, F148). Loads run one at a time, so two `ContextSize.Auto` loads no longer fit to the same free memory.

### Media, embeddings and speech

- **Media.** `Content` has `Image` and `Audio`, each with a `MediaSource` (`file`, `descriptor` or `bytes`) in the root
  package (D84). A file attachment is read while the request runs, so keep it until the reply ends (D87). Media reach
  llama-server as `file://` links, about 100 ms faster a turn than base64 (F157); each process has its own media folder.
  A picture or clip that fails is removed from the chat when its first request fails, so the chat isn't wedged.
- **`embed`, `rerank` and `transcribe`** are typed calls in core (D65, D84). `transcribe` returns a
  `Transcript(text, truncated)` (D136).
- **Audio formats are typed** (D136): `Content.Audio(source, AudioFormat.Wav)` with `Wav`, `Mp3` or `Flac`, the formats
  llama.cpp decodes, and `Content.Audio(bytes)` beside `Content.Image(bytes)`.

### Tools and typed output

- **The agent loop follows upstream's tools/ui** (D62). An unknown tool or invalid arguments go back to the model as an
  error; only an `LbToolError`'s message reaches the model; `LbToolAbort` ends the turn. A tool marked
  `requiresApproval` pauses the turn with `FinishReason.ToolCallsPending` until `approve(callId)` or `deny(callId,
  reason)`. A tool's own timeout answers the model "Error: the tool failed" and the turn goes on; a JVM `Error` ends it.
- **Typed output and typed tools** in `llamabro-typed` (D61, D63, D81 to D83, D88): `generate<T>`, `ask<T>` and
  `askAgain<T>` hold the model to a JSON schema from your `@Serializable` class, with integer ranges by default (F158).
  An `@LbToolbox` becomes typed tools through `llamabro-typed-ksp`, with build-time errors for what can't be
  serialized. `assertTypedSchema<T>()` checks a class in your tests.

### Voice

- **A realtime voice session** (D95, D97, D98): `model.voiceSession { voice = …; language = …; instructions = … }` runs
  upstream's `/v1/realtime` conversation in memory, with no port; its events equal llama-server's over a WebSocket
  (F168). `append` the microphone's 16-bit PCM, `commit()` each turn, and collect `events` for the reply's captions and
  speech; `cancelReply`, `close` and `sendRaw` round it out. The SDK sets nothing for the app: LFM3-Omni needs a voice,
  a language and an instruction (F166). `ModelInfo.voices` lists the voices a model speaks with (D100); another `voice` is refused before the session opens (D136).
- **Captions of the user's words** are the app's: it transcribes its own recording of each turn with `transcribe`
  (D109); upstream sends no transcript (F169).
- **On the phone's NPU** a reply's first audio comes 0.3 s after the commit and a cancel stops it within 80 ms (F170).
  Speech runs on the NPU alone (patch 0005) at about 2.4 times real time (D111). Patch 0008 ends LFM3-Omni speech that
  goes wrong after its text, about one reply in sixty, within seconds (D96, F167, F173).

### Hub

- **Loading by name** (D64, D68, D75, D79): `loadModel(hub.model("org/repo:Q4_K_M"))` downloads or finds the files and
  loads them, with one `LoadProgress`. A complete copy on the phone loads offline; tags are case-insensitive, as in `-hf`.
- **Search and sampling** stay in the hub (D110): `hub.search(...)` groups re-uploads, and `hub.recommendedSampling`
  gives the file's values and the base model's card values, each labelled.
- **Installed copies only** (D89, D107): `cached()` lists and `model()` loads only copies whose download completed. A
  selection's files download in order (model parts, image or audio encoder, draft); `Quantization.encoderBytes` names
  the encoder's size (D101). A file the SDK can't read is `HubException.Corrupt` (D114).
- **Downloads** retry only I/O and network failures, never an HTTP 4xx; a refused resume restarts from the first byte.
  `hub.plan()` only looks. `hub.delete()` stops this process's downloads of what it deletes and waits for them.
  `HubException.Network.unreachable` says the Hub couldn't be reached at all. Background downloads live in
  `llamabro-hub-work`, with `defaultDownloadConstraints()` to extend.

### Backends and speed

- **NPU (Hexagon)** and **GPU (Adreno OpenCL)** are optional modules. `LlamaBroConfig.npuPolling`, off by default as
  upstream, makes chat on the NPU 4-7% faster but the chip hotter (F156); patch 0007 keeps it from flooding logcat (F162).
- **NPU prefill with MTP** is about 67% faster (patch 0001, D80, F149, F163).
- **Voice notes in a chat:** with three, a text turn's first token comes in about 520 ms instead of 820 ms (patch 0004,
  F153).
- **Two fit estimates at once** no longer crash the app: the bridge measures one at a time (F172).

### Behaviour that differs from llama-server

- Empty stop strings in a request are ignored.
- A `reasoning_effort` level the model's template rejects is answered with an HTTP 400 error.
- With the SDK's tool loop, a tool the request didn't declare is answered as unknown.

### Testing

- `FakeLlamaBro` (`llamabro-testing`) is a real `LlamaBro` over a scripted engine: replies, tool calls, voice replies
  (`voiceReply`), and refusals of media the fake model doesn't support. `FakeLlamaBroHub` (`llamabro-hub-testing`)
  scripts search, plans and downloads, including a stopped download.

### Demo app

- A new chat app built on the SDK and its own design system (`demo/designsystem`): chats with pictures and voice notes,
  tools, thinking, a voice mode with captions, model search, downloads and fit, and per-model sampling.
