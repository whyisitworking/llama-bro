# Release notes

What changed for apps, newest first. Decisions are cited as `D<n>` ([decisions](decisions.md)), measurements as `F<n>`
([findings](research/findings.md)); the [architecture](architecture.md) has the full design and the
[SDK README](../sdk/README.md) the examples.

## Voice mode phase (unreleased, since 2026-11-17)

Built on llama.cpp b12479 (upstream master of 2026-11-17, tagged by upstream since; D93) with eight carried patches:
PH's four, the three speech patches back from the voice branch (0005-0007, D90) and a guard for LFM3 speech (0008, D96). The C API gains a realtime session (D97), and Kotlin a voice session over it (D98). The SDK
matches the new llama-server on every contract case, on the Mac and on the phone's NPU (F163).

### New

- **A realtime voice session in the C API** (D95, D97): `lb_model_request(model, "realtime", …)` runs upstream's
  `/v1/realtime` conversation in memory, with no port; `lb_request_send` sends the client's events. Its events equal
  llama-server's over a WebSocket (F168). The model config takes `seed`.
- **The encoder each quantisation brings** (D101): `Quantization.encoderBytes` in `hub.files` is the image or audio
  encoder the Hub fetches with it, chosen as it downloads, from the listing alone.
- **A voice session in Kotlin** (D95, D98): `model.voiceSession { voice = …; language = …; instructions = … }`
  returns a `VoiceSession`: `append` the microphone's 16-bit PCM, `commit()` each turn, and collect `events` for the
  reply's text (captions) and speech (16-bit PCM for `AudioTrack`), its end, and refused events; `cancelReply` and
  `close` stop it. Other realtime events come as `VoiceEvent.Other`, and `sendRaw` sends any client event. The SDK sets
  nothing in the session for the app: LFM3-Omni needs a voice, a language and an instruction to answer (F166). With
  `transcribeUser { }` on a model loaded with two slots, each turn's words arrive as `VoiceEvent.UserTranscript` while
  the reply is made (D99; upstream sends none, F169). `ModelInfo.voices` (and `supportsSpeech`) lists the voices a model speaks with, empty when it doesn't speak (D100). `ModelConfig.seed` sets the sampling seed. `FakeLlamaBro.voiceReply`
  scripts replies for app tests. On the phone's NPU a reply's first audio comes 0.3 s after the commit, it
  speaks at 1.65 times real time, and a cancel stops it within 80 ms (F170); patch 0008 holds there too (F171).

### Changed, from upstream llama-server

- **Empty stop strings in a request are ignored.**
- **A `reasoning_effort` level the model's template rejects** is answered with an HTTP 400 error.
- **LFM2 may call a tool the request didn't declare**, and Gemma 4 and Ministral 3 may call tools while thinking; with
  the SDK's tool loop, an undeclared tool is answered as unknown, as before.
- **Changing the global LoRA configuration** clears the slot's prompt cache.

### Faster

- **Typed output and tools** compile and match their grammars faster (upstream's left factoring), and chat templates
  render with less overhead.
- **NPU prefill with MTP** keeps patch 0001's gain at the new build: +67% (F163).
- **Speech output on the NPU alone** works through `/tts` (`rawRequest`): patch 0005 keeps LFM3-Omni's speech
  depthformer whole on the NPU and patch 0006 skips the text head on audio frames, now through upstream's own embeddings-only
  output flag (D86, D94, F155, F156, F165). Before them, the NPU gave empty audio. On the phone at b12479, 2.3-2.4 times real
  time; the head skip alone saves 4.5 ms of each 80 ms frame.
- **On Apple hardware** a model load's peak memory is lower by about the size of the largest tensor (F164); Android was
  not measured.

### Fixed

- **Two fit estimates at once could crash the app** (F172): upstream's fit measurement swaps llama.cpp's process-wide
  log callback while it runs, so `estimateFit` calls on several threads, or one during a load with automatic context,
  crashed. The bridge now measures one at a time.

- **`npuPolling` no longer floods logcat.** Patch 0007 polls the NPU's queue without blocking on Android too, so the DSP
  library logs no error per empty poll and your app's own lines are no longer dropped (F162). Polling stays off by
  default (D90).
- **LFM3-Omni speech that never ends.** About one captioned reply in sixty lost its place after its text and played
  minutes of noise; patch 0008 ends it within seconds of the speech going wrong, and clean replies are unchanged (D96,
  F167).

### Fixed, from upstream

- **NPU:** Hexagon's buffer mapping under fragmentation and a flash-attention tail defect.
- **GPU (Adreno):** an out-of-bounds read in the q4_K kernel and three flash-attention prefill defects, with new Q4_K,
  Q6_K and Q8_0 kernels for A7 chips.

## PH: the headline API (unreleased, since the `v2` branch of 2026-11-15)

Built on llama.cpp b12352 with four carried patches (`sdk/native/patches/`); the voice mode phase moves to b12479.
Apps need Kotlin 2.2 or later (D82).

### Breaking changes

- **Entry points.** `LlamaBro.getInstance` and both `ConfigProvider`s are gone. Use `LlamaBro.create(context, config)` and
  `LlamaBroHub.create(context, config)`, once per process (a DI singleton). An equal config returns the same object, and
  a different one throws `InvalidRequest` (D70).
- **`ask` returns a result.** `Conversation.ask` returns `Typed<String>` (`Value`, or `Incomplete` for `Length`,
  `TurnLimit`, `ToolCallsPending` and `ToolAborted`) instead of throwing `LlamaBroException.Incomplete`, which is
  removed (D63, D71).
- **Media.** `Content` gains `Audio`, so an exhaustive `when` over `Content` needs a branch. `Content.Image.bytes` becomes
  `source`, a `MediaSource` (`file`, `descriptor` or `bytes`), and `MediaSource` lives in the root package
  `com.suhel.llamabro` beside `ModelSource` (D84).
- **Tools.** The agent loop follows upstream's tools/ui (D62). An unknown tool or invalid arguments go back to the model as an
  error. Only an `LbToolError`'s message reaches the model; any other exception's text stays out of the prompt and the
  saved chat. `LbToolAbort` ends the turn. A tool marked `requiresApproval` pauses the turn with
  `FinishReason.ToolCallsPending` until `approve(callId)` or `deny(callId, reason)`. `ChatEvent.ToolResult` carries
  the error.
- **Typed output** (`llamabro-typed`, D88):
  - Integer fields carry their Kotlin type's range in the schema by default (`Byte`, `Short`, `Int`, the unsigned types;
    not `Long`), so the schema text and grammar change. The cost is under a millisecond per request (F158).
  - Any exception while decoding a typed reply, such as a `check()` in `init`, is now `Typed.Invalid`; before, only an
    `IllegalArgumentException` was.
  - Typed tool results include fields at their default values.
  - A missing argument field reads `missing field 'x'`.
  - The KSP processor rejects, at build time, a separate `@LbToolParam` whose type kotlinx.serialization can't write,
    a `@Contextual` or `@Polymorphic` one, and an interface or abstract class that isn't sealed. A type-use
    `@Serializable` without `with =` no longer makes a type acceptable.
- **`FakeLlamaBro`** refuses pictures and audio unless `supportsVision` or `supportsAudio` is set, as a real model does.
- **Hub.**
  - `LlamaBroHub.cached()` lists only what loads from the cache. A stopped download whose image encoder, draft or split
    parts are missing is no longer listed (D89).
  - A selection's files download in the plan's order: model parts, image encoder, draft.
- **Media files must stay until the reply ends.** A path attachment (`MediaSource.file`) is read by llama-server while the
  request runs (D87).

### New

- **Typed output and typed tools** in `llamabro-typed` (D61, D63, D81 to D83, D88):
  - `generate<T>`, `ask<T>` and `askAgain<T>` hold the model to a JSON schema derived from your `@Serializable` class.
  - An annotated `@LbToolbox` becomes typed tools through `llamabro-typed-ksp`, with build-time errors.
  - `assertTypedSchema<T>()` lets your tests check a class.
  - Core gains `Conversation.askAgain()` and `Typed<T>.valueOrNull()`.
- **Loading by name** (D64, D68, D75, D79):
  - `loadModel(hub.model("org/repo"))` downloads (or finds) and loads in one call, with one progress (`LoadProgress`). A
    complete copy on the phone loads offline.
  - `hub.search(...)` groups re-uploads.
  - `hub.recommendedSampling` gives the file's values and the base model's card values, each labelled.
  - Background downloads live in `llamabro-hub-work`, with `defaultDownloadConstraints()` as the base to extend.
- **Embeddings, rerank and speech in** as typed calls in core: `embed`, `rerank` and `transcribe` (D65, D84).
- **Cancelling a load** aborts the weight reading and frees everything; on the phone a cancelled 4B load returns in about 1
  s (D67, F148).
- **Logs from the first call.** Your logger sees native init. With no logger, lines go to logcat as `llamabro` (D66).
- **`LlamaBroConfig.npuPolling`**, off by default as in upstream: on, the NPU backend polls for its results, chat on the
  NPU 4-7% faster but the chip hotter (F156). On Android it floods logcat with the DSP library's errors (F162); the fix,
  with speech output on the NPU, comes with voice mode (D90).
- **Test fakes.**
  - `llamabro-hub-testing` has `FakeLlamaBroHub`, with `stopDownload(model, path, repo)` to reproduce a stopped download
    (D70, D89).
  - `llamabro-testing`'s API is now tracked for binary compatibility.

### Faster

- **Voice notes in a chat.** With three voice notes, a text turn reaches its first token in about 520 ms instead of 820 ms:
  patch 0004 fixes llama-server's prompt cache on the LFM3 family (F153). The voice notes also reach llama-server as
  `file://` links rather than base64, which takes another 100 ms off each turn (D87, F157).
- **NPU prefill with MTP** is about 61% faster through a one-line Hexagon gate (patch 0001, D80, F149).

### Fixed

- **`hub.plan()` only looks:** it no longer moves `refs/main`, writes a plan record or adopts an old copy; only
  `download()` records. It falls back to the cache only when the Hub can't be reached.
- **`hub.delete()` stops this process's running downloads** of what it deletes and waits for them; their flows end with a
  non-retryable `HubException.Network`, so a deleted model no longer comes back. A download of it that starts while the delete runs fails
  at once with the same error.
- **A download no longer retries an HTTP refusal** (a 4xx such as 400 or 410); a resume the server refuses (416 or 200)
  restarts from the first byte.
- **A programming error inside a download** passes through unchanged instead of becoming a retryable
  `HubException.Network`; only I/O and network failures are retried.
- **Quantisation tags are case-insensitive**, as in `-hf`: `HfModel("x:q4_k_m")` equals `HfModel("x:Q4_K_M")`.

- **Two `loadModel` calls at once** no longer both fit `ContextSize.Auto` to the same free memory; loads run one at a time.
- **A tool that times out** with its own `withTimeout` answers the model "Error: the tool failed" and the turn goes on,
  instead of the turn ending silently. A JVM `Error` from a tool (out of memory, a stack overflow) ends the turn.
- **Each process of an app has its own media folder**, so a second process starting no longer deletes the first one's
  `file://` links.

- **Hub.**
  - Two `delete` calls for one model at once no longer throw; the second returns false. A delete no longer removes a
    file that a download of another tag has just reused.
  - `HubException.Network.unreachable` is public: true when the Hub couldn't be reached at all.
  - `recommendedSampling` for a model on the phone whose header has no sampling values no longer asks the Hub for the
    same header, so it works offline.
  - A download that replaces an installed copy completes even if removing the old files fails. The failure is logged at
    Warn (D89).
- **Media errors.** A picture or clip that fails every request no longer wedges the chat. The message is removed when its
  first request fails on the media before any token (D84).
