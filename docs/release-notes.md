# Release notes

What changed for apps. Decisions are cited as `D<n>` ([decisions](decisions.md)), measurements as `F<n>`
([findings](research/findings.md)); the [architecture](architecture.md) has the full design and the
[SDK README](../sdk/README.md) the examples.

## Unreleased

## 2.1.0-alpha

- **`llamabro-server`: pairing from a browser** (D174). `ServerConfig.pairing = Pairing { request -> … }` serves a page
  at `GET /` from which a computer asks for the key; the app approves each request (`PairingRequest` has the 4-digit
  `code` the page shows, the `client` address and the `userAgent`), and the page then shows the key and the curl,
  OpenAI Python and Claude Code snippets. Off by default; it needs `apiKey`. Typable keys now also accept Crockford's
  O, I and L for 0, 1 and 1, and parallel wrong keys can no longer get past the throttle's 5 per wait (D173).
- **Demo: pair a laptop from its browser** (D174). Open the phone's address in the laptop's browser and approve the
  request on the phone: a dialog over the Server page or the docked view, or a notification with Approve and Deny
  when the app is in the background. The key is filled into the laptop's snippets.
- **`llamabro-server`: typable keys and throttling** (D173). `ServerConfig.keyMatching = KeyMatching.Typable` ignores
  case, spaces and dashes on both sides before the constant-time compare (`Exact`, the default, is unchanged).
  `ServerConfig.throttle` is on (`Throttle.Default`) whenever a key is set: after 5 wrong keys within a minute an
  address gets 429 with `Retry-After`, the wait doubling from 30 s to 5 min, until a right key; `Throttle.Off` turns
  it off. Behaviour change: a server with a key throttled nothing before.
- **Demo: a key you can type, and no QR code** (D173). The Server page's key is now 16 characters in four groups
  (`K7QM-4RTX-9HCD-2WNB`), and the QR code is gone. The old key is replaced on first use after the update, so every
  computer connected to the phone's server must enter the new key.
- **`llamabro-rag`** (D167-D172), a new optional module for asking your own documents: `Library.open` a folder,
  `add` a `Document` (a flow of progress: chunked, embedded n of m, saved), `remove`, `search` for `Hit`s with their
  document, offsets and headings, and `searchTool()` for a `Conversation`, which numbers passages on across its calls and hands the app what
  the model saw through `onHits` (or `asContext()` for a prompt). Chunks are
  cut at headings, paragraphs and sentences and counted with the embedder's own tokenizer; search is keyword (BM25) and
  vector search fused, with an optional rerank (F191 set the defaults). The folder survives a crash (D168), and opening
  it with another embedder throws `IndexMismatch`. Pure Kotlin; core is unchanged.
- **`SearchOptions.quantizedFrom`**: only the GGUF uploads quantized from one original (`Qwen/Qwen3-8B`), through the
  Hub's `base_model:quantized:` filter; an id that is not `owner/name` is refused.
- **`LlamaBroHub.capabilities`** (D155): what an installed model can do before it loads, read from its GGUF headers
  on the phone with no request: the chat template and whether an installed projector takes images and audio, or null
  when no complete copy is installed. `FakeLlamaBroHub`'s `gguf` takes `chatTemplate` and `metadata`.
- **`llamabro-server`** (D163-D165), a new opt-in module: serves a loaded `Model` over HTTP with llama-server's API
  (OpenAI chat, completions, embeddings and audio, Anthropic messages, Responses, token counts, `/health`, `/props`,
  `/metrics`), streaming included, so Claude Code, Codex, the OpenAI SDKs and `npx llama-chat` work against it.
  `LlamaBroServer(ServerConfig { … }, ServedModels)` has `state`, `events`, `start()` and `stop()`. It binds
  `127.0.0.1:8080` with no key by default (D164); `POST /props`, `/lora-adapters` and `/slots/:id` are off unless
  enabled. Media in a request must be a `data:` URL or base64 (D165), an optional `advertise` announces the server
  with NSD, and its answers match upstream's (F189). Core stays socket-free.
- **`Model.exchange`** (`@InternalLlamaBroApi`, D163): a request's status, content type and body bytes as llama-server
  wrote them, error bodies and SSE `event:` lines included. Its only user is `llamabro-server`; `rawRequest` is unchanged.
- **llama.cpp b12653** (D137) with six carried patches: upstream fixed the cache bug 0004 carried (F180). New with
  the pin: Qwen3-ASR's audio tail, LFM2.5-VL's template, the realtime sleep state and transcription prompt, recurrent
  state that no longer keeps a NaN, and safer reads of untrusted GGUF and grammars.
- **`Model.transcriptionSession`** (D138): live speech to text through upstream's realtime transcription, live on a
  model that transcribes as it hears (LFM3), per commit on one that holds realtime conversations, and refused with
  `NotSupported` on any other. `FakeLlamaBro` scripts it.
- **`Model.speak`** (D139): text to speech as a flow of 16-bit PCM pieces, with a voice, a speaker reference to clone,
  speed, seed and more, each sent only when set. `FakeLlamaBro` scripts it.
- **Audio errors** (D142): changes from 2.0.0-alpha: `transcribe` on a model that doesn't hear throws `NotSupported`
  (it was `InvalidRequest`; `voiceSession` already threw `NotSupported`), and a `voiceSession` the server ends before
  it opens or whose events can't be read throws `ServerError` (it was `InvalidRequest`): the server's fault, not the
  request's. New in this section, so with no earlier behaviour: `transcriptionSession` and `speak` throw the same
  `NotSupported` and `ServerError` (`speak` also for speech that can't be read or ends before any audio). A clip in a
  chat message on a model that doesn't hear stays `InvalidRequest`.
- **The audio family made symmetric** (D143), breaking changes from the lines above and from 2.0.0-alpha:
  - The package `com.suhel.llamabro.voice` is now **`com.suhel.llamabro.audio`**, and `Transcript` moved into it
    (import `com.suhel.llamabro.audio.Transcript`). The entry points (`Model.voiceSession`, `transcriptionSession`,
    `transcribe`, `speak`) stay in `com.suhel.llamabro`.
  - **`Model.transcribe(source) { … }`** takes a `TranscribeConfig` builder like `speak` (language, prompt, `maxTokens`,
    `temperature`, `seed`, `extra`) in place of `language`, `prompt` and `options`. Besides the sampling fields
    (`top_k`, `top_p`, …), `stop`, the reasoning fields (`reasoning_format`, reasoning budget) and the chat-template
    fields (`chat_template_kwargs`, e.g. `enable_thinking`) now go through `extra`, as in
    `extra = """{"reasoning_format":"none"}"""`. The request is the same for the same settings. Breaking: a
    `maxTokens` below 1 is refused (`IllegalArgumentException`; null means no limit), and `extra` may not set the
    fields the SDK sets, `response_format` (upstream accepts only json and the SDK parses a JSON reply), `stream`,
    `stream_options` and `return_progress` (refused when set).
  - For Java callers the JVM facade classes changed: `TranscriptionKt` now holds `transcribe`; `transcriptionSession` is
    in `TranscriptionSessionsKt` and `voiceSession` in `VoiceSessionsKt`; `TranscribeKt` and `VoiceKt` are gone.
  - `TranscriptionConfig` has `extra`, a JSON Merge Patch for the transcription `session.update`, as `VoiceConfig` has.
  - `FakeLlamaBro.voiceEvents` is now **`realtimeEvents`** (it records transcription sessions too). New:
    `speaks = false` makes every `speak` fail `NotSupported`, and `speechError(status, type, message)` queues an error
    for the next one.
- **Value types made consistent** (D102), breaking changes from 2.0.0-alpha:
  - **`ModelConfig` is an immutable value**, built and derived like `LlamaBroConfig`: `ModelConfig { contextSize = … }`,
    `config.copy { threads = 6 }`, `ModelConfig.Default`. `loadModel`, `estimateFit` and `memoryBudgetBytes` take either
    the builder lambda, whose receiver is now `ModelConfig.Builder` (code that only sets fields in the lambda compiles
    unchanged; a `ModelConfig.() -> Unit` you stored becomes `ModelConfig.Builder.() -> Unit`), or a `ModelConfig`.
    It is a plain class, not a data class, so settings can be added without breaking callers: it has value `equals`,
    `hashCode` and `toString` over its settings, and no `componentN` or generated `copy` (use `copy { … }`). A negative
    `memoryReserveMib` throws `IllegalArgumentException` when it is set, like the audio configs' settings, not when the
    config is built or used.
  - **The progress listener is not part of a `ModelConfig`**: `ModelConfig.Builder.onProgress { … }` is gone, so no
    config can hold a listener that a call then ignores. `loadModel` takes it as a parameter `onProgress`, null by
    default, on both overloads: `loadModel(source, onProgress = { p -> … }) { contextSize = … }` where the builder
    lambda was, and `loadModel(source, config) { p -> … }` with a config value. A builder lambda you hold in a variable
    is passed as `configure = lambda`. `estimateFit` and `memoryBudgetBytes` never took a listener.
  - **`FitEstimate` is a data class** (value `equals`, `hashCode`, `toString`), and `FitEstimate.Method`
    and `FitEstimate.method` are gone: the estimate was always a projection.
  - **`LlamaBro.withHostEngine` is removed** from the artifact. It was a test seam; the repository's own host tests
    use `hostLlamaBro` from core's test fixtures.
  - **`FakeLlamaBro` is `AutoCloseable`**: `close()` deletes the temporary directory it keeps its slots and media in.
    After it, loading a model or sending a request on one fails with `IllegalStateException("FakeLlamaBro was closed")`
    instead of an error about a missing directory. A fake nobody closes has its directory removed when the JVM exits.
- **`compact`, `addToolResult` and `askAgain` are `Conversation` members again**: the same calls compile unchanged
  once their imports (`com.suhel.llamabro.conversation.compact`, `.addToolResult`, `.askAgain`) are removed; for Java
  callers and binaries, `CompactionKt.compact` and the whole `ConversationKt` class are gone.
  **`canCompact` is a member too** (it was an extension of 2.0.0-alpha's and this section's earlier builds): remove the
  `com.suhel.llamabro.conversation.canCompact` import; for Java callers and binaries, `CompactionKt.canCompact(chat, n)`
  becomes `chat.canCompact(n)`.
- **A streamed server error is recognised by its `error` key**, not by the payload starting with `{"error"`, so one
  spaced or ordered differently fails the flow with its `LlamaBroException` as the usual shape does.
- **API review fixes**, breaking changes from the lines above:
  - `Model.speak` is `@CheckResult` like every public function that returns a cold flow (D136): a call whose flow is
    never collected is a lint warning.
  - `Transcript` has an internal constructor (D102), as the SDK's other result types do: an app reads one, and a test
    reads `text` and `truncated` instead of building one to compare with.
  - `SpeechConfig.Builder` no longer refuses `temperature < 0` or `speed <= 0` when set: upstream checks sampling
    values (D143), so a model that doesn't take one refuses the request with upstream's message, as for the other
    fields.
  - The hub's seams for tests, `Transport`, `Response` and `Storage` (all `@InternalLlamaBroApi`), moved from
    `com.suhel.llamabro.hub.internal` to **`com.suhel.llamabro.hub.spi`**, as core's engine seam is in `spi` (D104).
    Only `llamabro-hub-testing` and the hub's own tests use them; change the imports.
  - `EngineRequest.send` (the engine seam, `@InternalLlamaBroApi`) answers the `lb_status` as a `NativeError` constant:
    `SEND_OK`, `SEND_NOT_REALTIME` and `SEND_CANCELLED` are gone for `NativeError.OK`, `NativeError.INVALID_ARG` and
    `NativeError.CANCELLED`.
- **Demo:** a tap on the mic dictates into the message bar, a hold records a voice message (D140); a typed reply on a
  model that speaks can be read aloud (D141).
- **Demo:** pages scroll edge to edge under frosted glass bars (D148), a setting under Appearance (D152) that scales to
  what the phone can draw smoothly (D153, D154) and swells under the finger when pressed (D158).
- **Demo:** bottom sheets are glass too, over the whole window, on phones that draw the full glass (D176, F192); the
  bars' blur eases out more gently toward the sharp page, with a Fade softness setting (D175).

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
