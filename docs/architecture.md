# Architecture

Llama Bro is an Android SDK that runs llama.cpp's `llama-server` core inside the application process and exposes it through a Kotlin API. This guide describes what the SDK is, how its modules fit together, how a request travels from Kotlin to native code and back, and which rules govern threading, memory and lifecycle. Choices are justified by decision records (`D<n>`, see [decisions.md](./decisions.md)) and measurements (`F<n>`, see the [findings register](./research/findings.md)).

Contents:

1. [Scope](#1-scope)
2. [Module map](#2-module-map)
3. [The engine: llama-server in-process](#3-the-engine-llama-server-in-process)
4. [Request path](#4-request-path)
5. [The Kotlin API](#5-the-kotlin-api)
6. [Conversation, tools and compaction](#6-conversation-tools-and-compaction)
7. [Errors](#7-errors)
8. [Devices and backends](#8-devices-and-backends)
9. [Memory fit and ContextSize.Auto](#9-memory-fit-and-contextsizeauto)
10. [Phone defaults](#10-phone-defaults)
11. [Speculative decoding](#11-speculative-decoding)
12. [The Hub](#12-the-hub)
13. [Threading and lifecycle](#13-threading-and-lifecycle)
14. [Testing seams](#14-testing-seams)
15. [Verification](#15-verification)
16. [Out of scope](#16-out-of-scope)

---

## 1. Scope

### 1.1 What the SDK is

The SDK lets an Android application use llama.cpp through an ergonomic Kotlin API, without any native setup on the application side. It adds a dependency on a prebuilt AAR (D4) and loads a model from a file or from the Hugging Face cache.

**Scope rule.** The SDK represents llama-server's feature set faithfully and not much beyond it. Anything llama-server does not do is the application's responsibility. Tool gating, for example, is application policy and stays out (D11).

The SDK adds two kinds of things on top of llama-server:

- **Android- and Kotlin-specific concerns.** Backend packaging (NPU, GPU, CPU), device selection, Hugging Face downloads into an application-private cache, memory fitting for phones, and Kotlin types.
- **What llama.cpp's own web client (`tools/ui`) does on top of the server**, reproduced so that it behaves the same: the `Conversation` helper, the automatic tool loop and compaction (D8, D13, D14).

### 1.2 The baseline is upstream

The baseline for every behaviour and default is upstream llama.cpp at the pinned build `b12653` (D137): llama-server (`tools/server`) and its web client, Llama Chat (`ui/apps/chat`; `tools/ui` before b12479, and still called `tools/ui` in this document). A behaviour or default that differs from upstream must win an A/B test on a device against the upstream behaviour. Ideas are judged by measured results, not by where they came from (D10, D18). Examples of where upstream won: the sampler order and penalty behaviour (D18, F12-F15), and the sleep mode, which the SDK keeps off because a failed reload aborts the process.

The SDK deviates from upstream in these places, each justified by a measurement or a decision:

| Deviation | Reason |
|---|---|
| Phone defaults for slots, checkpoints, RAM cache and batch sizes | A phone cannot afford a server's defaults (D17, F9-F11) |
| Weights loaded without mmap (`--load-mode none`) | One resident copy of the weights instead of two (D30, F58-F60) |
| A memory-based `ContextSize.Auto` | Upstream's fit misjudges a phone (D33, F66) |
| No OpenMP on the CPU path | Same speed, smaller AAR (D38, F77) |
| Six patches ([register](../sdk/native/patches/README.md)): 0001 a one-line Hexagon gate for delta-net with MTP (D80), 0002 upstream PR #30432 (a NEON path for BF16 dot products) until it merges (D55), 0003 a load that can be stopped (D67), 0005 LFM3-Omni's speech depthformer with no CPU op on the NPU, 0007 Hexagon's no-block poll on Android (D86, D90), 0008 an end for LFM3 speech that runs past its text (D96) | Faster prompt processing for hybrid models with MTP, drifting no more than upstream's own switch (F149); a 5x faster BF16 image encoder on the phone's CPU (F127); a cancelled load returns in about a second (F148); speech output on the NPU alone (F155, F156); polling without flooding logcat (F162); captioned speech that never ends is stopped (F167) |
| A failed tool's exception text stays out of the prompt unless it is an `LbToolError` | The exception may hold private data (D62, D83) |
| What is on the phone loads with no network request; `cached()` lists only selections that load | A load never downloads unasked (D64, D89) |
| One file at a time per download, in the plan's order | One transfer is about 2.1x faster than four on a phone (D44, F89, D89) |
| Chat media from a file reaches llama-server as a `file://` link in the process's media folder | About 100 ms less per turn with three voice notes (D87, F157) |
| The memory check warns rather than refuses by default | The fit's estimate errs on a phone; the app decides (D57) |
| SHA-256 on every download | Upstream never verifies (D40, F83) |

### 1.3 In-process, one runtime

The engine runs in the application's process (D5). Core opens no socket (an app may add the opt-in `llamabro-server`, D163, section 5.9): a localhost port would be reachable by any other app on the phone, and an out-of-process service would add IPC and a second memory footprint. The runtime is a process singleton (D6, section 13).

---

## 2. Module map

```
 application
     |
     |  implementation        runtimeOnly (optional)          implementation (optional)
     v                        v            v                         v
 +-----------+       +-----------------+ +---------------+     +---------------+
 | llamabro  |<------| llamabro-hexagon| | llamabro-opencl|    | llamabro-hub  |
 |  (core)   |  meta-| NPU backend     | | GPU backend    |    | HF downloads  |
 |           |  data | + HTP skeletons | |                |    | (pure Kotlin) |
 +-----------+       +-----------------+ +---------------+     +---------------+
     |  JNI                                                         |
     v                                                              | ModelSource
 +--------------------------+                                       v
 | native/                  |                              llamabro.loadModel(...)
 |  C API (llamabro.h)      |
 |  C++ bridge              |        also: llamabro-typed (+ -ksp), llamabro-hub-work
 |                          |        test-only: llamabro-testing, llamabro-hub-testing
 |  llama.cpp b12653        |
 +--------------------------+
```

| Module | Path | Purpose |
|---|---|---|
| `llamabro` | [`sdk/llamabro`](../sdk/llamabro) | The core: `LlamaBro`, `Model`, chat types, `Conversation`, errors, device policy, memory fit. Ships the native library for the CPU backend and the JNI layer. Always required. |
| `llamabro-hexagon` | [`sdk/llamabro-hexagon`](../sdk/llamabro-hexagon) | Qualcomm NPU backend: `libggml-hexagon.so`, the HTP skeleton libraries (as assets) and an installer. |
| `llamabro-opencl` | [`sdk/llamabro-opencl`](../sdk/llamabro-opencl) | Adreno GPU backend through OpenCL, with kernels embedded. |
| `llamabro-hub` | [`sdk/llamabro-hub`](../sdk/llamabro-hub) | Hugging Face resolve, download, resume, verify and cache. Pure Kotlin, no native code. |
| `llamabro-hub-work` | [`sdk/llamabro-hub-work`](../sdk/llamabro-hub-work) | A WorkManager worker and `LlamaBroWorkerFactory(hub)` for background downloads, with default notifications (D75). Apart from the hub so that it doesn't depend on `androidx.work`. |
| `llamabro-testing` | [`sdk/llamabro-testing`](../sdk/llamabro-testing) | `FakeLlamaBro`, a scripted engine for JVM tests. |
| `llamabro-hub-testing` | [`sdk/llamabro-hub-testing`](../sdk/llamabro-hub-testing) | `FakeLlamaBroHub`, the real hub over a scripted Hub and disk for JVM tests (D70, D75). Apart from `llamabro-testing` so that apps without the hub never get it. |
| `llamabro-typed` | [`sdk/llamabro-typed`](../sdk/llamabro-typed) | Typed output and typed tools over kotlinx.serialization: the JSON-schema converter and its annotations (D63, section 5.8). Pure Kotlin, no native code. |
| `llamabro-typed-ksp` | [`sdk/llamabro-typed-ksp`](../sdk/llamabro-typed-ksp) | The KSP processor that turns `@LbToolbox` classes into `asToolbox()` extensions over `llamabro-typed`'s `tool(…)`, with a build error for each misuse D61 lists (section 5.8). A JVM library that apps add with `ksp(…)`; it never ships in the app. |
| `llamabro-rag` | [`sdk/llamabro-rag`](../sdk/llamabro-rag) | Documents cut into passages, embedded and searched, kept in a folder: hybrid keyword and vector search, an optional rerank, and a search tool for a conversation (D167-D172, section 5.10). Pure Kotlin, no native code. |
| `native/` | [`sdk/native`](../sdk/native) | The stable C API (`llamabro.h`), the C++ bridge, the JNI layer, build presets, patches and host tests. |
| Acceptance apps | [`sdk/acceptance`](../sdk/acceptance) | Small apps used by device tests: `backends` (device choice, lifecycle, the full suite) and `download` (background download with a worker and a user-initiated job). |
| Demo app | [`demo`](../demo) | A complete chat application built against the SDK through a composite build (D46). It uses only public API. |

Vulkan is not part of 1.0: ggml-vulkan fails on the test phone's Adreno 830, either by aborting the process while creating a compute pipeline or by producing wrong output (D31, F64). The build recipe is retained for a retry after a llama.cpp or driver upgrade.

### 2.1 Dependency shape

An application depends on the core, plus optional backend and hub artifacts:

```kotlin
dependencies {
    implementation(platform("io.github.whyisitworking:llamabro-bom:2.0.0-alpha"))  // one version for all (D135)
    implementation("io.github.whyisitworking:llamabro")             // CPU, always works
    runtimeOnly("io.github.whyisitworking:llamabro-hexagon")        // optional: Snapdragon NPU
    runtimeOnly("io.github.whyisitworking:llamabro-opencl")         // optional: Adreno GPU
    implementation("io.github.whyisitworking:llamabro-hub")         // optional: Hugging Face downloads
    implementation("io.github.whyisitworking:llamabro-hub-work")    // optional: background downloads (WorkManager)
    implementation("io.github.whyisitworking:llamabro-typed")       // optional: typed replies and tools
    ksp("io.github.whyisitworking:llamabro-typed-ksp")               // optional: annotated toolboxes (with the KSP plugin)
}
```

Backend artifacts are `runtimeOnly`: the application never calls them. Each backend announces itself through a manifest `<meta-data>` entry that names its installer class; the core reads the merged manifest at runtime and loads the libraries by name (D24, F61). Adding a backend AAR is therefore enough to enable it.

### 2.2 Coordinates and packaging

The Maven group is `io.github.whyisitworking`; Kotlin packages and Android namespaces stay under `com.suhel.llamabro` (D2). The minimum SDK level is 29, with newer platform APIs behind runtime checks (D3). The only supported ABI is `arm64-v8a`, with 16 KB page alignment. The SDK lives in its own included Gradle build (D1).

Packaging rules (D20, F25, F28, F29):

- Backends load by file name straight from the APK, so no extraction and no legacy native-library packaging are needed.
- SDK libraries carry a `llamabro-` prefix, and `libllamabro.so` exports only the C API and `JNI_OnLoad`, which avoids symbol collisions with other native code in an app.
- The AAR ships R8 keep rules for every class the JNI layer looks up.
- The Hexagon skeleton libraries are the one exception: the DSP loader needs real files, so they ship as assets and are copied to `noBackupFilesDir` (section 8.4).

---

## 3. The engine: llama-server in-process

### 3.1 What upstream provides

llama.cpp's `tools/server` builds three targets. One of them, `server-context`, is a static library that contains the slot scheduler, the task queue, the prompt cache, speculative decoding, multimodal input, chat parsing, the request schema and the route handlers. Its only dependencies are `llama-common` and `mtmd`. Another target adds the HTTP server, router mode and web UI; the SDK does not use it.

Every route handler takes a plain struct (`server_http_req`: a body, parameters and a `should_stop` callback) and returns a `server_http_res`, which holds either complete data or a streaming `next()` function. The structures need nothing but the standard library, so the SDK calls the handlers directly with no socket (D12).

### 3.2 How the bridge uses it

For each loaded model, the C++ bridge:

1. Creates a `server_context` and loads the model with `common_params`.
2. Runs `start_loop()` on a dedicated native thread, so that every llama.cpp call for that model happens on one thread.
3. Builds the routes once.
4. Turns each request from Kotlin into a `server_http_req` (an OpenAI-shaped JSON body plus the cancellation callback) and passes it to the matching handler, wrapped the way `server.cpp` wraps handlers so that exceptions become error responses.
5. Forwards streaming chunks back to Kotlin as UTF-8 bytes.

### 3.3 What this provides

Reading the source of the pinned build, the embedded core provides:

- **Chat:** Jinja templates, reasoning parsing and a reasoning budget, a control to end thinking early, tool calls with lazy grammar and parallel calls, and structured output through `response_format`, `json_schema` and `grammar`.
- **Sampling:** every llama.cpp sampler, `samplers` ordering, `logit_bias`, log probabilities, seeds and time limits. Defaults come from the GGUF's `general.sampling.*` metadata unless a request overrides them.
- **Speculative decoding** with checkpoint rollback (section 11).
- **Multimodal input:** images, audio and video through `mtmd`.
- **Prompt caching** with checkpoints, a RAM cache across conversations, and saving or restoring slots.
- **Other routes:** embeddings, rerank, tokenize and detokenize, token counting, template application, infill, per-request LoRA and server properties.
- **Prefill progress events** and per-request timings.

### 3.4 What this does not provide

- Low-level control: raw logits, direct KV-cache edits, Kotlin samplers and per-token callbacks.
- Router mode, which needs subprocesses. Several `Model` instances can be loaded instead.
- llama-server's built-in tools and MCP client. Application-side Kotlin tools cover the same need (section 6.2).
- Training and fine-tuning.

### 3.5 Trade-offs

Benefits: upstream maintains the difficult parts (prompt caching for hybrid models, speculative decoding, multimodal input, template quirks), behaviour matches llama-server so its documentation applies, and the contract is OpenAI-compatible JSON that llama.cpp keeps stable for its HTTP users. The JSON overhead per token is negligible: measured speed was within about 2% of the llama-server binary on the same phone with the same flags (F1).

Costs and risks:

- **`server-context` is not an official public API.** It changes frequently between llama.cpp builds. The SDK's coupling is small, but every upgrade needs a compile check and the contract tests.
- **Server defaults do not suit a phone** (section 10).
- **Some paths abort the process.** Waking from sleep mode aborts if the model fails to reload, so sleep mode is kept off and models are unloaded explicitly.
- **Tuning goes through `common_params`.** Anything `server-context` cannot do needs an upstream change or a patch file; patches are applied to a build copy and never committed into the submodule.

The alternative, a custom pipeline over `libllama`, would give full control but would mean maintaining prompt caching, speculative decoding, multimodal input and parsing indefinitely, with behaviour drifting away from llama-server. It remains the fallback if the in-process approach ever fails.

---

## 4. Request path

```
 Kotlin                               native                                 llama.cpp b12653
 ------                               ------                                 ----------------
 model.chat(request)
   |  ChatRequest -> JSON body
   v
 Model.rawRequest(endpoint, json)
   |  Flow<String>; cancel -> should_stop
   v
 JniEngine / NativeBridge  --JNI-->  lb_model_request(model, endpoint,
   (byte[] JSON in,                        params_json, chunk_cb, user)
    chunk callbacks out)                   |
                                           |  server_http_req{body, should_stop}
                                           v
                                    C++ bridge (request.cpp)
                                           |  handler lookup by endpoint
                                           v
                                    server_routes handler  ---->  server_context
                                           |                       (worker thread:
                                           |  res->next(chunk)      start_loop())
                                           v
                                    chunk_cb(LB_EVENT_CHUNK, bytes)
   ^                                       |
   |  SseParser -> ChatStreamDecoder       |
   |  -> ChatEvent                         v
 Flow<ChatEvent>  <------------------ LB_EVENT_DONE | LB_EVENT_CANCELLED
```

### 4.1 The C API

The C API in [`llamabro.h`](../sdk/native/bridge/include/llamabro.h) is a stable C ABI. It is the surface the JNI layer binds to and the intended surface for future Flutter (FFI) and Kotlin Multiplatform (cinterop) bindings. It is small:

| Function | Purpose |
|---|---|
| `lb_runtime_init(config_json, ...)` | Initialise the process runtime once |
| `lb_runtime_devices()` / `lb_runtime_backends()` | List devices and backend load results as JSON |
| `lb_fit_estimate(config_json, &out_json, ...)` | Project whether a model fits, as JSON |
| `lb_model_load(config_json, progress_cb, ...)` | Load a model; the configuration maps onto `common_params` |
| `lb_model_info(model, &out_json, ...)` | Metadata and server properties as JSON |
| `lb_model_request(model, endpoint, body_json, chunk_cb, ...)` | Start a request against any route |
| `lb_request_send(req, msg, ...)` | Send a client event to a realtime request |
| `lb_request_cancel(req)` / `lb_request_free(req)` | Cancel a request and release its handle |
| `lb_model_free(model)` | Cancel in-flight requests, stop the loop, join the thread |

Errors have one shape. Every call that can fail returns an `lb_status` and takes `char ** error_json` last:

- **The JSON.** On failure it is set to `{"error": "<the specific reason>"}` (`lb_model_load` and `lb_fit_estimate` add `"code"`: a load can carry `file_not_found` or `insufficient_memory`, a fit estimate `file_not_found` only), on success to NULL, and the caller frees it with `lb_string_free`; passing NULL means the caller doesn't want the reason.
- **The status** is the kind of failure and the message says which one: each of a request's refusals (an unknown endpoint, an attachment with no name, no source, more than one source, no mime or a repeated name, params that aren't a JSON object of strings) has its own.
- **`LB_ERR_CLOSED`** is what a call made while `lb_model_free` is still waiting for in-flight requests sees, so it isn't mistaken for a bad argument. It is not a licence to race the free: the model is deleted when `lb_model_free` returns, so that call must not run concurrently with, or before, any other call on the same model outside that wait. The caller serialises frees, as the Kotlin layer does with its gate lock, where `LB_ERR_CLOSED` maps to `LlamaBroException.ModelClosed`.
- **The JNI layer** passes the status and the JSON on as `NativeError` without inventing either; `lb_request_send` alone stays a status to Kotlin (`EngineRequest.send`), since a closed conversation is an expected answer on a per-chunk path.
- **Calls that can't fail** (`lb_version`, `lb_runtime_devices`, `lb_runtime_backends`) return their value directly.

A single generic request function means any llama-server feature that has no Kotlin type yet is still reachable with raw JSON (section 5.5). Pictures and clips travel beside the JSON (D84):

- **Placeholders.** Kotlin writes a placeholder (`lb-attachment:<n>`) as a part's `image_url.url` or `input_audio.data` and passes each file as an `lb_attachment` (a path or a descriptor, read natively, or bytes, copied across JNI once).
- **Base64 in the bridge.** The bridge reads them in the call and writes the base64 data URL llama-server takes into the body (chat's `messages[].content[]` parts on the chat-completion routes, and the `{"content": [...]}` entries of an embeddings input), so neither llama-server's code nor the Java heap holds a base64 copy.
- **A path on those routes isn't read at all** (D87, F157: a turn with three voice notes 195 → 94 ms). The bridge checks its first bytes, links it under a random 128-bit name into the process's media folder and writes `file://<name>`, so llama-server reads the file while the request runs, and the link is removed when it ends.
  - The folder is `noBackupFilesDir/llamabro/media/<pid>-<random>/`, llama-server's `--media-path`; one per process, since the app's processes share `noBackupFilesDir`, and at start the runtime removes only the folders of processes that ended.
  - A caller-written `file://` reaches only that folder. Bytes and descriptors keep base64.
- **A transcription's file** goes into the request's files as upstream's multipart upload would.
- **Threading.** Because the files are read inside the native call, a request with attachments starts on the IO dispatcher.

### 4.2 Streaming and cancellation

A request produces `LB_EVENT_CHUNK` callbacks with server-sent-event payloads, then one terminal event, `LB_EVENT_DONE` or `LB_EVENT_CANCELLED`. On the Kotlin side each request is an `ActiveCall` (`internal/ActiveCall.kt`), which owns the native handle's lifetime and the sink for its callbacks: `SseParser` splits the stream, a payload that is a JSON object with a top-level `error` key (however it is spaced or ordered) fails the flow through `ErrorMapping`, and `ChatStreamDecoder` turns the OpenAI-shaped deltas into `ChatEvent` values.

A realtime request (D97) is a conversation rather than a response: each chunk is one server event, passed to Kotlin as it comes, and the client's events go the other way through `lb_request_send` (JNI `requestSend`). Events sent before the native handle exists wait in Kotlin and go first, in order. Closing the `VoiceSession` cancels its request, which is the client hanging up.

Cancelling the collecting coroutine sets the request's `should_stop`. llama-server applies it between `n_batch` passes, which is why the default batch size is 512: stopping during a long prompt frees the slot in about 0.4 s instead of 2.0-2.6 s on the NPU, with equal prefill speed (D17, F9). A request notices a cancel at its next result or within about one second at the latest (F23).

---

## 5. The Kotlin API

The public API dump is checked into the repository ([`llamabro.api`](../sdk/llamabro/api/llamabro.api)) and verified on every change. Kotlin's explicit API mode is on. The API uses coroutines and `Flow`; it exposes no serialization, dependency-injection or Compose types. The quickstart is in [`sdk/README.md`](../sdk/README.md).

### 5.1 Three levels

```kotlin
val llama = LlamaBro.create(context)                // runs no native code

val model: Model = llama.loadModel(   // suspend, on Dispatchers.IO
    ModelSource.file(path),
    onProgress = { p -> /* LoadProgress: Fetching(download) for a RemoteModelSource, then Loading(0..1) (D75) */ },
) {
    contextSize = ContextSize.Auto
    devicePolicy = DevicePolicy.Auto
}

// 1) Conversation: history, tools and compaction managed for the caller.
val chat = model.conversation(system = "You are a helpful assistant.")
chat.send("Hi!").collect { event -> /* ChatEvent */ }

// 2) One stateless request with every field typed.
model.chat(ChatRequest { /* messages, tools, sampling, ... */ }).collect { /* ChatEvent */ }

// 3) Raw JSON against any route llama-server serves.
model.rawRequest(Endpoint.ChatCompletions, jsonBody).collect { chunk: String -> /* ... */ }
```

The three levels share one engine. A beginner uses `Conversation` and still reaches every request option through per-send and per-conversation configuration. An advanced caller sends full requests. Anything not yet typed is reachable as raw JSON.

### 5.2 `LlamaBro`

`LlamaBro` is the process runtime. Its members (D6, D24, D42):

- `LlamaBro.create(context, config)`: the first call creates the process's runtime; a later call with the same settings returns the same object, keeping the first call's logger, and different settings throw `IllegalStateException` before anything is built, as the hub's `create` does (D70, D106, D136). A `LlamaBroConfig` holds the log level, the logger and `npuPolling` (the NPU backend polls for its results; off by default, as upstream, D90); the log level and polling are its settings. With no logger the core logs to logcat as `llamabro` (D66). This works the same in a fresh process started by a job or worker only if that worker passes the same config: keep the config on the `Application` or in the DI graph and pass it from workers, or `create(context)` throws `IllegalStateException` against a UI process's config, or runs with a different logger in a fresh one.
- `loadModel(source, configure)`: a `suspend` function that returns a `Model`.
- `estimateFit(source, configure)`: a `FitEstimate` for a model and configuration without loading it (section 9.4).
- `memoryBudgetBytes(configure)`: the budget `ContextSize.Auto` would use now, before side files, for labelling models in a list without planning each one.
- `devices()` and `backends()`: `suspend` functions listing `DeviceInfo` (name, description, `DeviceKind`, free and total memory) and `Backend` (name, whether it loaded, an error, its devices).

### 5.3 `ModelSource`

`ModelSource` describes where a model's files are. `ModelSource.file(path) { mmproj = ...; draft = ... }` names a GGUF and optionally a multimodal projector and a speculative draft file. The hub produces the same type from the cache (`hub.source(ref)`), so a downloaded model and a file the app placed itself load through the same call; `hub.model(ref)` is a `RemoteModelSource` that fetches what is missing first (D64, D75).

### 5.4 `Model` and `ModelConfig`

`Model` exposes:

- `info` (`ModelInfo`): context size, trained context size, parameter count, size, description, chat template, vision and audio support, the voices it speaks with (`voices`, `supportsSpeech`, D100), whether the model has an MTP head, template capabilities and GGUF metadata.
- `loadReport` (`LoadReport`): which device and backend were chosen, every attempt, the context size and why, and the speculative mode and why (section 8.3).
- `chat(request)`, `conversation(...)`, `restoreConversation(json)` and `rawRequest(endpoint, json)`.
- `embed(text)`, `embed(texts)` and `embed(vararg content)`, which return `FloatArray`s with a `Normalization` that mirrors upstream's `embd_normalize` (`None`, `MaxAbsInt16`, `Taxicab`, `Euclidean` by default, `PNorm(p)`), and `rerank(query, documents, topN)`, which returns `Ranked(index, score)` best first (D65). They read the `embeddings` route as base64 and the `rerank` route. Each checks the model's `task` first and throws `IllegalStateException` naming the task to load with (D136, D142), before any request. `embed(vararg content)` sends pictures and clips as chat does, placeholders with the files beside the request, which the bridge fills in the embeddings body's content parts (D84); a clip gets chat's `supportsAudio` and format checks.
- `transcribe(source) { … }` (`TranscribeConfig`: language, prompt, `maxTokens`, `temperature`, `seed`, `extra`; a builder like `speak` and the sessions, D143), which returns a `Transcript(text, truncated)`: `truncated` when the reply is as long as `maxTokens`, since upstream's transcription response has no finish reason (D84, D136). It checks the task and `info.supportsAudio` first: a model loaded for another task is `IllegalStateException`, one that doesn't hear `NotSupported`, before any request (D142); a clip that is missing or in another format is `InvalidRequest`, a reply the SDK can't read `ServerError`.
- `voiceSession { … }`, a spoken conversation through upstream's `/v1/realtime` in memory (D95, D97, D98): it suspends until the server has taken the setup (`VoiceConfig`: voice, language, instructions, output modalities, a reply cap, the input rate, `extra`; nothing set for the app) and returns a `VoiceSession` with `append(ShortArray)`, `commit(respond = true)`, `respond`, `sendText`, `cancelReply`, `sendRaw`, `close` and a hot `events` flow of `VoiceEvent`s: the turn committed, the reply's start, its text (the captions) and speech (16-bit PCM at `outputSampleRate`), its end, refused events, and every other event as `Other`. Upstream sends no transcript of the user's words in a conversation (F169), and the session adds none: an app that captions them transcribes its own recording of each turn with `transcribe`, on a second slot while the reply is made (D109, F170). It checks the task, `info.supportsAudio` and, for captions, the slots first; a model that doesn't hear is `NotSupported` (D142). A setup the server refuses is `InvalidRequest`; a server that ends before the session opens, or sends an event the SDK can't read, is `ServerError`, from the open or from `events`.
- `transcriptionSession { … }`, live speech to text through the same in-memory `/v1/realtime`, opened as upstream's web
  client opens it (`intent=transcription`, then a transcription session; D138): `isLive` says whether text streams as
  audio arrives or comes per `commit`; `finish` ends the input; typed `TranscriptionEvent`s. A model upstream can't
  transcribe this way, or one that doesn't hear, throws `NotSupported` (D142); a refused setup is `InvalidRequest`, and an
  early end or an unreadable event `ServerError`, as for a voice session. Pause detection is the app's, as upstream's
  server has none.
- `speak(text) { … }`, upstream's `/v1/audio/speech` as a cold flow of `SpeechAudio` (16-bit PCM with its rate, read
  from the streamed WAV's header; D139). A speaker reference goes as the upload's `speaker_ref` file. There is no
  capability check up front, since a model can speak from a speaker reference with no predefined voices: a model that
  doesn't speak is upstream's `NotSupported`, a setting it refuses `InvalidRequest`, and speech that isn't 16-bit PCM
  WAV or ends before any audio `ServerError`, the server's fault (D142).
- `close()`, which returns at once and frees the model in the background, and `closeAndJoin()`, which waits for the memory to be released. Freed memory returns to the system on Android (F49).

`ModelConfig` is configured in the `loadModel` block:

| Field | Default | Meaning |
|---|---|---|
| `contextSize` | `Auto` | `Auto`, `UpTo(maxTokens)` or `Fixed(tokens)` (section 9) |
| `speculative` | `Auto` | `Auto`, `Off`, `Mtp`, `Ngram` or `DFlash` (section 11) |
| `devicePolicy` | `Auto` | `Auto`, `Prefer(kind)` or `Only(kind)` (section 8) |
| `threads` | unset | CPU threads |
| `seed` | unset | Sampling seed of requests that set none (`--seed`); a voice session's replies have no seed of their own (F168) |
| `batchSize`, `microBatchSize` | 512, 512 | Logical and physical batch sizes (D17) |
| `parallelSlots` | 1 | Concurrent sequences (D17) |
| `ramCacheMib` | 1024 | Prompt cache across conversations (D17) |
| `memoryReserveMib` | 512 | Memory `Auto` leaves free (D45) |
| `checkpointsPerSlot` | 8 | Prompt checkpoints per slot (D17) |
| `task` | `Generate` | `Generate`, `Embedding(pooling)` or `Rerank` |
| `kvCacheType` | `F16` | KV cache element type |
| `loraAdapters` | none | LoRA adapters applied at load |

### 5.5 Chat types

A `ChatRequest` is built with a DSL and carries messages, tools, a `ResponseFormat`, sampling and reasoning options, and an `extra` JSON string applied as a JSON Merge Patch over the final body (F43). Because of that, any field llama-server accepts is reachable without waiting for a typed property. Unset sampling fields fall back to the GGUF's defaults and then to the server's.

Messages are `Message.system`, `Message.user` (with `Content.Text`, `Content.Image` and `Content.Audio` parts), assistant messages and tool results. A picture or clip comes from a `MediaSource`: `file(path)`, `descriptor(pfd)` or `bytes(bytes)` (D84). Before any request, a picture on a model without `supportsVision`, a clip on one without `supportsAudio`, and audio that isn't WAV, MP3 or FLAC (by MIME type, and by header for bytes and, in a conversation, for a file; the native side checks a descriptor's header) throw `InvalidRequest`. `MediaSource` lives in the root package beside `ModelSource`, since `transcribe` and `embed` take it too. llama.cpp's decoder refusing a picture (HEIC, WebP) is upstream's `server_error`; the SDK maps it to `InvalidRequest`. Request-level overrides use `RequestOptions`.

A request streams `ChatEvent` values:

| Event | Meaning |
|---|---|
| `PromptProgress(processed, total, cached, timeMs)` | Prefill progress and how much came from the cache |
| `ReasoningDelta(delta)` | A fragment of the model's reasoning |
| `ContentDelta(delta)` | A fragment of the reply |
| `ToolCallReceived(call)` | The model requested a tool |
| `ToolResult(callId, name, result, error)` | A call got its result (from `Conversation`). `result` is the text the model saw; `error` is the exception when the model saw only the generic `Error: the tool failed` (D62) |
| `Done(reason, timings, usage, abort)` | The request ended; `abort` is the `LbToolAbort` when `reason` is `ToolAborted` (D62) |

`FinishReason` is `Stop`, `Length`, `ToolCalls`, `TurnLimit`, `ToolCallsPending` or `ToolAborted`.

### 5.6 Evolution rules

Public value types (results, events, progress and small settings) are data classes; requests and configurations with many optional fields are built with `X { }` and changed with `copy { }` (D102). Sealed and enum sets (`ChatEvent`, `FinishReason`, `LlamaBroException`, `DeviceKind`, the `Endpoint` constants) are settled at 1.0 and grow only in a major version (D26). The API is Kotlin only.

### 5.7 Typed and raw coverage

Chat, embeddings (pooled vectors), rerank (text documents) and transcription are typed (D65, D84). The other routes are reachable through `rawRequest`, with the request and response exactly as llama-server documents them (F53): completion, per-token embeddings (`EmbeddingsNative`), tokenize, token counting, slots and the Responses and Anthropic message formats. Transcription is not reachable through `rawRequest`, which carries no file (`Endpoint.Transcriptions` names the route; `transcribe` sends it), and realtime audio, a WebSocket upstream, runs in memory through the C API's `realtime` endpoint (D97) and is typed as `voiceSession` (D98), whose `Other` events and `sendRaw` reach the rest of upstream's realtime protocol. Typed calls for the other routes are planned; the raw level remains the escape hatch.

### 5.8 `llamabro-typed`

Typed output and typed tools live in their own module, `llamabro-typed` (package `com.suhel.llamabro.typed`), so that core's public API never mentions kotlinx.serialization (D63); the module depends on core and exposes `kotlinx-serialization-json`. Its base is `typedSchema(serializer, json)`, which turns a `SerialDescriptor` into the JSON Schema that `ResponseFormat.JsonSchema` and a tool's parameters carry, and that llama.cpp's json-schema-to-grammar holds the model to. The `Json` passed in is the one that decodes the reply, so its naming strategy, class discriminator (or the class's `@JsonClassDiscriminator`) and `explicitNulls` shape the schema.

- Properties appear in declaration order. The grammar writes an object's required properties first, in schema order, then its optional ones (F143), so declaration order is the order the model writes, and a reasoning field declared before the answer is written first.
- A property with a default is optional (and, with `explicitNulls = false`, a nullable one); every other property is required. Objects are closed (`"additionalProperties": false`).
- A nullable type is `{"anyOf":[X,{"type":"null"}]}`, with the property's keywords inside `X`. Enums are their serial names; an inline value class is its underlying type; a `Char` is a one-character string.
- A sealed class is an `anyOf` of its subclasses' objects, in the serialization plugin's order, each opening with the discriminator as a required `{"const":"<serial name>"}`; an `object` subclass holds only the discriminator. `Map<String, V>` is an object with `additionalProperties: V`.
- Annotations (`@SerialInfo`): `@LbDescription` writes `description` (on a property or a class); `@LbRange` on integers writes `minimum`/`maximum`, `@LbLength` and `@LbPattern` on strings `minLength`/`maxLength` and `pattern`, `@LbItems` on lists `minItems`/`maxItems`. A `@LbLength` or `@LbItems` bound left at its default is not written. Every `Byte`, `Short`, `Int` and unsigned field carries its type's range by default, intersected with any `@LbRange` and refused when nothing is left; a `Long` gets only its annotation's bounds, since int64's limits are upstream's "unbounded" (D88; the grammar grows, at under a millisecond per request, F158). `@LbPattern` is sent as written: llama.cpp supports a subset of regex and, at this version, turns a pattern it can't handle (unanchored, `\d`, `\w`, `\p{…}`, lookarounds, POSIX classes) into any string with only a log warning; only a malformed one (unbalanced parentheses) fails the request, as `InvalidRequest`, and in a conversation with typed tools every request, since the tools go with each one (F158). Anchor a pattern with `^…$` and use plain classes; `assertTypedSchema` cannot check it. Decimals take no bounds: the grammar ignores them (F143).
- Refused with an `IllegalArgumentException` naming the field's path (`$.items[].name`): a recursive type (a class inside itself with the same type arguments; `Box<Box<String>>` is fine), open polymorphism, a contextual serializer, free-form JSON (`JsonElement`, `JsonObject`, `JsonArray`, `JsonPrimitive`), a sealed serializer not laid out as the plugin's, a non-`String` map key, a bound on a type it does not apply to, and a sealed subclass whose serial name is a qualified class name (no `@SerialName`; the model would have to write the full name, and a rename would break stored replies). `assertTypedSchema<T>()` lets an app's tests find these before release.
- Typed tools (D61): `tool<A, R>(name, description) { args -> … }` builds a `TypedTool` whose parameters' schema is `typedSchema` of `A`, refused unless it is an object, with the name checked against `^[a-zA-Z0-9_-]{1,64}$` and the description non-blank. `Conversation.Builder.tool(typedTool)` and `toolbox(Toolbox)` register it through core's `toolForCall` (`@InternalLlamaBroApi`), whose handler takes the whole `ToolCall`; core's raw `tool(… handler: (JsonArgs) -> String)` adapts to the same registry entry, so the agent loop (section 6) runs both alike. The typed handler decodes `argumentsJson` with the tool's `Json` (blank is `{}`; a kotlinx decode error is `LbToolError("invalid arguments: <reason>")`; any other exception while decoding, such as an arguments class's `require`, is `LbToolError("invalid arguments")` with it as the cause (which reaches the app as `ToolResult.error`), and an `LbToolError` thrown from an `init` passes through with its message, D62), runs with an `LbToolCallContext` (call id, tool name, raw arguments) as its receiver, and sends a `String` as is, `Unit` as `Done` (D81) and anything else as JSON with every field, defaults included (`encodeDefaults`, D88).
- Annotated toolboxes (D61): `llamabro-typed` declares `@LbToolbox(prefix)` (class, interface or object), `@LbTool(description, name, requiresApproval)` and `@LbToolParam(description)`, all with binary retention. `llamabro-typed-ksp`, a JVM module that reads them by fully qualified name and writes code with a small writer of its own (no KotlinPoet), writes `X_LbToolbox.kt` for each toolbox `X`: `fun X.asToolbox(json: Json = Json): Toolbox`, with `X`'s visibility, built from `tool(…)`, so the runtime checks still run. It covers the `@LbTool` methods of `X` and of its `@LbToolbox` supertypes, each declaration once; the name is the method's in snake_case or `@LbTool.name`, after the prefix as written. A method takes one `@Serializable` class (used as is) or parameters that each carry `@LbToolParam`, for which the processor writes a private `@Serializable` class, `X_LbArgs_<tool>`, with `@LbDescription` from each parameter's text and `= null` for a nullable `= null` parameter (bounds such as `@LbRange` target properties, so they need the arguments class, as other defaults do); an `LbToolCallContext` parameter receives the call's context and stays out of the schema. A method that does not suspend is called inside `withContext(Dispatchers.IO)`. Each misuse is a `KSPLogger.error` on its symbol: a blank description, a name failing the pattern with its prefix, two tools with one name, an override annotated again, a separate parameter's default other than `= null` (read from the source, comments left out, since KSP gives no default expressions; a default it can't read, as on a supertype from a library, is an error too), a parameter without `@LbToolParam` beside annotated ones or a lone non-`@Serializable` one, `@LbTool` outside an `@LbToolbox`, a sealed subclass reached from the arguments whose serial name is missing or dotted (as the runtime schema refuses it), and a result other than `String`, `Unit`, a primitive, a `List` or a `@Serializable` class. kctfork tests each error and the generated code on snippets (D81); `AnnotatedToolboxTest` runs a toolbox the real processor wrote through the loop with `FakeLlamaBro`.

### 5.9 `llamabro-server`

`llamabro-server` (package `com.suhel.llamabro.server`) serves a loaded `Model` over HTTP with llama-server's own API, for a docked phone that other devices on the Wi-Fi call. It is a separate, opt-in module: core stays socket-free and the module listens only when the app calls `start()` (D163). It is a Kotlin server (Ktor CIO) over the SDK, not upstream's HTTP layer and not a subprocess.

- **The seam.** Core gains `Model.exchange(endpoint, body)` (`@InternalLlamaBroApi`), which returns a `RawPart.Head` (status and content type) and then the body's bytes, exactly as llama-server's handler wrote them: SSE `event:` lines and JSON error bodies included. `rawRequest` keeps only `data:` lines and turns an error into an exception, so it can't carry the Anthropic or Responses streams. No native change: the C API already passes the status with every chunk. Cancelling the collector cancels the request, so a client that hangs up stops generation.
- **API.** `LlamaBroServer(config, models)` has `state` (`Stopped`, `Starting`, `Serving(port)`, `Failed`), `events` (`RequestStarted`, `RequestEnded`, `Rejected`, for an app's request log) and `start()` and `stop()`. `ServedModels` is the app's side: `list()` for `/v1/models` and `acquire(id)`, which hands out a `ServedLease` the server releases when the request ends, however it ends; `ModelUnavailable` is a 404 for an unknown id and a 503 when none is loaded. `ServerConfig { }` holds `host`, `port`, `apiKey`, `cors`, `routes`, `maxBodyBytes`, `workDir`, `advertise`, `keyMatching` (`Exact`, or `Typable`, which ignores case, spaces and dashes and reads Crockford's O, I and L as 0, 1 and 1), `throttle` (on by default when a key is set: 429 with `Retry-After` after 5 wrong keys a minute from one address, judged and recorded in one step, D173) and `pairing` (off by default, D174).
- **Pairing (D174).** With `pairing` set, `GET /` serves a self-contained page, `POST /pair` starts a pairing with a 4-digit code (one waiting per address, three in all, 2 minutes each) and `GET /pair/{id}` tells the page `pending`, `denied`, `expired` or, once, `approved` with the key and base URL. The app's `Pairing.approve` decides, and is cancelled at the expiry. The routes need no key, share the throttle (a wrong id is a wrong guess), and answer only their own origin on a LAN host name, whatever the CORS.
- **Defaults (D164).** `127.0.0.1:8080`, no key, no CORS, 32 MiB bodies. A key goes in `Authorization: Bearer` or `x-api-key`; `/health` and `/v1/models` are open. `POST /props`, `/lora-adapters` and `/slots/:id` are off unless enabled with `Routes.enable`, as upstream gates them (`--props`, `--slot-save-path`). There is no queue of ours: a request beyond the slots waits in llama-server's own. Unknown or switched-off routes answer 404 in OpenAI's shape.
- **Routes.** Chat, completions, `/completion`, `/infill`, Anthropic messages and `count_tokens`, Responses and `input_tokens`, embeddings, rerank, tokenize, detokenize, apply-template, `/props`, `/slots`, `/metrics` and `GET /lora-adapters` go through `exchange`. `/health` and `/v1/models` are answered by the module. Audio is answered from `Model.transcribe` (multipart, `json` or `text`) and `Model.speak` (streamed `pcm` or `wav`, since there is no encoder for others; a `voice` the model lacks is dropped). The realtime WebSocket is not served.
- **Checks before a model (D165).** Size (413), JSON (the server's own 400), and media: every media key anywhere in the body must hold a `data:` URL or raw base64, so a remote client can't make the phone read `file://` under the SDK's media folder (D87) or fetch a URL. A refused request never holds a lease.
- **Discovery.** With `advertise`, `NsdManager` registers `_http._tcp` and `_llamabro._tcp` (TXT `path=/v1`, `auth=key|none`) while the server serves; a stop before NSD confirms a registration still withdraws it. A network that refuses the registration leaves the server serving.
- **Verified.** `FakeLlamaBro` host tests cover each guard, the streams and the audio routes. `server-contract` runs the same requests against upstream's `llama-server` and the module, and the official `openai` and `anthropic` Python SDKs against the module (F189).

### 5.10 `llamabro-rag`

`llamabro-rag` (package `com.suhel.llamabro.rag`) lets an app answer questions over documents that stay on the phone. It is a separate, optional module over `Model.embed` and `Model.rerank`: core keeps D65's line, no search helpers, and the module is pure Kotlin (Okio, coroutines, kotlinx.serialization) with no native code (D167). Reading file formats is the app's; the module takes text.

- **API.** `Library.open(fs, dir, embedder, format, chunker, pooling)` opens or creates a library in an Okio folder for a model loaded with `ModelTask.Embedding`, and throws `IndexMismatch(built, wanted)` for a folder built by another embedder. `add(Document)` is a cold `Flow<IngestProgress>` (`Chunked`, `Embedded(done, of)`, `Saved`); `remove(id)`, `documents()`, `search(query, limit, reranker)` returning `Hit`s (document, title, passage, offsets, headings, score, rank), `searchTool()` and `List<Hit>.asContext()`. `Library.contents(fs, dir)` reads the documents and the builder's identity without an embedder; `Library.rebuild(fs, dir, embedder, format, ...)` embeds a mismatched library's stored passages again into `<dir>.rebuild`, commits it by renaming it `<dir>.rebuilt`, and swaps it in (the next `open` finishes a swap a crash interrupted, and drops an unfinished rebuild).
- **Chunking (D169).** `Chunker` cuts at Markdown headings, then paragraphs, sentences and characters, into chunks of at most 256 tokens with 15% overlap. Each chunk is counted with `TokenCounter.Model`, the embedder's own tokenizer through `/tokenize` (an embedding load answers it, F190), special tokens and the format's document prefix included, so none exceeds the batch. A batch the embedder still refuses is cut again and retried.
- **Embedding format.** `EmbeddingFormat` holds a query prefix and a document prefix, with presets chosen from the model's architecture and name (`EmbeddingFormat.of(info)`) and `None` otherwise. It is part of the library's identity.
- **The folder (D168).** `manifest.json` (written last), append-only `vectors.f16` (float16 rows) and `chunks.jsonl`, and `deleted` tombstones, compacted once they pass a quarter of the rows. Whole files go through `.tmp` and an atomic move, trailing bytes a crash left are cut off, and an interrupted compaction is finished on open from `manifest.next`. A library is whole after a crash, and a document whose later compaction failed stays saved. `IndexMismatch` compares the model's name and weight size, the vector length, the pooling, the normalisation and the format.
- **Search (D170).** The query is embedded with the format's query prefix; the dense top 50 (exact cosine over every row, behind `VectorStore`) and the BM25 top 50 (k1 1.2, b 0.75; lowercase letter and digit runs, one token per Han character or kana) are fused by reciprocal rank fusion (k 60). With a reranker the fused top 20 are reranked; the best `limit` (6) are returned. The matching step runs under the library's mutex, so one answer comes from one state. F191 measured these defaults on this repository's docs and kept them; reranking is off unless an app passes a reranker.
- **The tool (D171, D172).** `searchTool` is a `llamabro-typed` tool taking `query` and returning the passages as `[n] <title> › <headings>` and the text, so the model can cite `[n]`; no match says so. One tool numbers on across its calls, and `onHits` hands the app the passages as the model saw them, numbered (D172). Failures are `LbToolError`s with fixed short messages (D62, D83), and cancellation propagates.
- **Verified.** Host tests cover the chunker, BM25, fusion, the store's crash safety, ingest, search and the tool; `HostLibraryTest` runs bge-small and jina-reranker-tiny end to end; `HostRagEvalTest` is the retrieval evaluation behind F191 (testing.md).

---

## 6. Conversation, tools and compaction

### 6.1 Conversation

`Conversation` follows the message handling of llama.cpp's web client (D8). It keeps the history and exposes it as state:

- `messages: StateFlow<List<Message>>`, the full history including the system message and compaction nodes
- `reply: StateFlow<Message.Assistant?>`, the reply being streamed
- `isBusy: StateFlow<Boolean>`
- `contextUsage: StateFlow<ContextUsage>`, derived from timings (prompt, cached and predicted tokens)
- `pendingApproval: StateFlow<ToolCall?>`, the call waiting for `approve` or `deny` (section 6.2)

Operations:

- `send(text) { options }`, `resume()`, `approve(callId)`, `deny(callId, reason)`, `regenerate()`, `endReasoning()`, `toJson()` and `Model.restoreConversation(json)`.
- `ask(text, vararg attachments)` returns `Typed<String>`: `Value(value, text, reasoning)`, or `Incomplete(reason, text)` for `Length`, `TurnLimit`, `ToolCallsPending` and `ToolAborted` (F41, D63); `valueOrNull()` gives the value or null (D88); engine failures still throw.
- `askAgain()` is `regenerate` collected into a `Typed<String>`, as `ask` is `send` collected; `llamabro-typed`'s `askAgain<T>()` regenerates under the same schema (D88).
- A conversation can also start from the application's own stored messages (F96).
- **Media in messages.** A descriptor in a message is read once into bytes when the message is added (D84), off the caller's thread, so later turns and `toJson` use the bytes; one in a starting `history` is read once by the first request or by `toJson`, and a closed descriptor, a missing file or a clip file in another format is `InvalidRequest` before the message is added.
- **A send that fails on its media.** When a send's first request fails with `InvalidRequest` caused by its media (the bridge's attachment errors, or the decoder's) before any token or tool call, the message is removed again, atomically and only while it is still the last one, so the chat isn't wedged by media that fails every request; any other failure keeps it for `resume()`.
- **`toJson`** saves a file source as its path and bytes as base64, in save format version 3 (audio parts and paths); version 2 saves, with inline image data, and version 1 saves still load.
- **Options.** Per-conversation `defaults { ... }` apply to every request, and per-send options apply to one (F38).

Only one operation runs at a time. A second operation fails with `Busy` when its flow is collected, not when it is created.

Who owns what. `Conversation` is the facade: its operations (the ones above, `compact()` and `addToolResult()`), the history and the state flows, the one-operation-at-a-time rule and compaction (section 6.4). The rest belongs to internal collaborators in `conversation/`, each with one job:

| Class | Owns |
|---|---|
| `AgentLoop` | The turns and the tools: the loop of section 6.2, the last batch, approval, and the error policy of D62 (what text of a handler's failure reaches the model, a handler's own timeout against a cancelled turn, the app getting the handler's own exception). It sees the history, the registry, a chat function, an append sink and the model's close signal, nothing else. |
| `ReplyStreamer` | One model request: folds its events into the streaming `reply`, appends the finished message, and decides what a stop or an error keeps of it (the text, never a partial tool call). |
| `PreEncoder` | The background pre-encode (section 6.4): the `n_predict: 0` request when every slot is idle, and its timings into `contextUsage`. |
| `MediaPrep` | A message's media before it enters the history: the checks, and reading each descriptor once into bytes (D84). |

The model holds the one `PreEncodeSlot` its conversations share, so a chat request in any conversation cancels a pre-encode another started, and closing the model ends it. `contextUsage` is `ContextUsage.of(timings, contextSize)` for a reply and for a pre-encode alike, and both read the timings with `ChatStreamDecoder`'s reader.

### 6.2 Tools run automatically

`Conversation` runs registered Kotlin tool handlers the way the web client's tool loop does (D14):

1. Send the request with the tool definitions.
2. If the reply contains tool calls, append the assistant message (content, reasoning and calls) to the history.
3. Go through the calls in order, announcing each with `ToolCallReceived` when the loop reaches it. Run each automatic call, or answer it with an error (see below).
4. Append each result as a `tool` message and go back to step 1.
5. Stop when a reply has no tool calls, or after `maxTurns` (default 100) with `Done(reason = TurnLimit)`; `resume()` continues.

```kotlin
val chat = model.conversation(system = "...") {
    tool("get_weather", "Current weather for a city", schema = jsonSchema) { args: JsonArgs ->
        weatherApi.fetch(args.string("city"))        // suspend; returns the tool result string
    }
    tool("delete_file", "Delete a file", schema = deleteSchema, requiresApproval = true) { args ->
        fileStore.delete(args.string("path"))
        "deleted"
    }
    maxTurns = 100
}
```

Errors (D62, F140). Upstream's loop sends every exception's text to the model. Here only a deliberate error does, so a token or personal data in an exception stays out of the prompt and the saved chat. These are the exact texts the model gets:

| Case | Result the model sees | `ToolResult.error` |
|---|---|---|
| The model called a name that is not registered | `Error: Unknown tool: <name>` | null |
| The arguments do not parse, are not a JSON object, or `JsonArgs` has no such argument or a mistyped argument or list item (the accessors throw `LbToolError("invalid arguments: …")`) | `Error: invalid arguments: <reason>`, for example `Error: invalid arguments: missing string argument: city` | the parse exception (`JsonArgs` throws with a cause), otherwise null |
| The handler throws `LbToolError(message)` | `Error: <message>` | its `cause`, or null |
| A typed tool's arguments fail kotlinx's decoding (a missing field, bad JSON, a custom serializer's `SerializationException`) | `Error: invalid arguments: <reason>` (a missing field reads `missing field 'x'`, D88) | the `SerializationException` |
| A typed tool's arguments throw any other exception while decoding (a `require` in `init`) | `Error: invalid arguments` | that exception |
| A typed tool's arguments class throws `LbToolError` from `init` | `Error: <message>` | its `cause`, or null |
| The handler throws any other exception | `Error: the tool failed` | the exception |
| The person denies the call | `Error: denied by the user`, or `Error: <reason>` | null |

- **An unknown name is an automatic call.** It gets its error result and the loop goes on, as upstream does. A manual call is one registered without a handler.
- **`LbToolError` and `LbToolAbort`** live in the core module, so the later typed module (D61) reuses them. Both are open for subclassing.
- **`LbToolAbort(reason)`** ends the turn. The aborted call and the reply's later calls get no result, and the flow ends with `Done(reason = ToolAborted)`, whose `abort` holds the exception. `ask()` returns `Incomplete(ToolAborted, …)`. `resume()` runs the aborted call again, then the later calls, then asks the model.
- **Cancelling and closing.** `CancellationException` and `ModelClosed` propagate unchanged. Cancelling the flow cancels the running handler through structured concurrency, and the generation with it.
- **Threads.** A handler runs in the collecting coroutine, as does a typed tool's lambda. An annotated tool that does not suspend is called inside `withContext(Dispatchers.IO)` by its generated code, so a blocking method leaves the caller's thread; a suspending one runs where it is called (D62, section 5.8).

Approval (D62). Whether to ask the person stays the application's policy (D11): the SDK only waits. `tool(name, description, schema, requiresApproval = true) { … }` makes a call wait for the person before its handler runs. `requiresApproval` without a handler fails at registration, because a manual tool already stops.

- The loop stops with `Done(reason = ToolCallsPending)` before the call and sends no further request. `pendingApproval` names the call.
- `approve(callId)` runs the handler. `deny(callId, reason = null)` answers the model with `Error: denied by the user` (or `Error: <reason>`) and emits `ToolResult` for the call. Each then continues the turn as `resume()` does. Both take per-call `options` like `resume()`, and fail when collected with `IllegalArgumentException` if `callId` is not the pending call.
- `pendingApproval` is derived from the history and the registered tools, so it is right after `Model.restoreConversation(toJson())`. It is the first unanswered call of the last batch whose tool needs approval, provided no unanswered automatic call comes before it (after an abort the batch is interrupted, not waiting). Manual calls before it are skipped over. It is null while an operation runs.
- Approval is per run. An approved call that aborts waits for approval again.

The last batch and `resume()` (D62). The last batch is the last assistant message with tool calls, while only tool results follow it. A user message, a reply or a compaction closes it. It comes from the history alone, so a restored conversation finds the same batch.

- `resume()` first finishes the last batch's unanswered automatic calls, in order, then asks the model.
- With a manual call still unanswered, or a call waiting for approval, it stops with `Done(ToolCallsPending)` and sends nothing, so the model is never sent a call without its result. A stop with no request has null timings and usage.
- After an abort it runs the aborted call again.
- Each call is announced when the loop reaches it. `resume()` announces again every unanswered call it reaches: after an abort, a waiting approval call, and every manual call still without a result. `approve` and `deny` start after their own call, so they do not announce the calls before it again.
- `send` and `regenerate` never touch an old batch, because their history ends at a user message.
- A compaction would close the last batch, so it waits for it: while a call of the last reply has no result (waiting for approval, or a manual tool), `canCompact()` is false and `compact()` fails with `IllegalStateException` without changing the history. Approve, deny or answer the call first (section 6.4).

Other behaviour:

- **A tool without a handler** stops the loop with `Done(reason = ToolCallsPending)`. The application supplies results with `addToolResult(id, text)` and calls `resume()`.
- **Cache behaviour:** each turn only appends to the end of the history, so the server reuses the whole prefix (section 6.3).

### 6.3 Prompt cache behaviour

llama-server's cache works at several levels, all of which the SDK inherits:

1. **Within a slot:** the new prompt is compared with what the slot holds and only the part after the common prefix is processed.
2. **Images:** matched by the SHA-256 of the image bytes and the token count.
3. **Hybrid, recurrent and sliding-window models** cannot be cut back to an arbitrary position, so the server restores the newest checkpoint before the point of difference, or reprocesses everything. Checkpoints are taken at user-message starts and near the end of the prompt, at most `checkpointsPerSlot` per slot.
4. **RAM cache across conversations** (`ramCacheMib`): when a slot switches prompts, its state is saved in RAM, and a later request restores the best match if it keeps at least a quarter of its tokens. With one slot (D17), switching between chats always takes this path.
5. **Disk:** slot save and restore under an application-private path.

### 6.4 When the context fills up

llama-server never compacts or edits a conversation. With context shift off (the upstream default, and unavailable for multimodal and hybrid models anyway), a prompt larger than the context is rejected with a typed error before any work, and generation that reaches the end of the context stops with finish reason `length`. The SDK does not expose context shift (D13).

Compaction is a client feature, and `Conversation` copies the web client's algorithm exactly (D13, F26):

1. It is started by the caller (`canCompact()`, `compact()`), as in the web client, or, with `autoCompact = true` on the conversation, once by the SDK when a send meets `ContextExceeded`: it compacts with the defaults and sends again in the same flow, the summary not streamed as the reply (D136). There is no threshold before the context is full.
2. A step is a user message, or an assistant message together with its tool results, so a cut never separates a tool call from its result. The last `preservedSteps` (default 10) stay word for word.
3. The summary request reuses the prefix the server already has cached and adds a summarisation instruction, so only the instruction is new work.
4. The summary is stored as a compaction node at the end of the history, with a kept-step count. The full history stays visible, but the model sees only the system prompt, a note that the conversation was compacted with the summary, the preserved steps and anything newer.
5. A summary cut off by the output limit fails with `CompactionTruncated` and is rolled back.
6. While a tool call of the last reply has no result, there is nothing to compact: a compaction would close the batch, and the model would later see the call without a result (D62). The web client never has such calls, because its loop answers every call before the user can compact.
7. Optionally (`preEncodeAfterReply`, off by default, as upstream) the shortened prompt is pre-encoded in a cancellable background request, so the next message does not wait for a full prefill.

If `send()` fails with `ContextExceeded`, an application calls `compact()` and then `resume()`. An application using `Model.chat` directly implements its own compaction.

---

## 7. Errors

Errors are a sealed `LlamaBroException`. Applications catch them; only the SDK creates them (D26). Where there is an underlying cause it is attached. Every failure of a call or a flow is one of them, including a reply the SDK can't read; only misuse of the API (an invalid argument, a call in the wrong state) throws `IllegalArgumentException` or `IllegalStateException`, as `require` and `check` do (D103).

| Exception | Meaning |
|---|---|
| `InvalidRequest` | The request is invalid: a picture or clip the model can't take, a missing file, a body llama-server refuses |
| `ContextExceeded(promptTokens, contextSize)` | The prompt does not fit; both numbers come from llama-server and are null if it omits one |
| `InputTooLong(tokens, limit)` | An embedding or rerank input is longer than the batch (`microBatchSize`), which it must fit since such inputs can't be split. Upstream sends it as a server error whose message alone has the numbers; the SDK reads them (D65) |
| `NotSupported` | The model or build cannot do it, for example embeddings without an embedding model |
| `Unavailable` | The server cannot take the request now |
| `ServerError(httpStatus, message)` | Any other llama-server error, or a successful reply the SDK can't read (status 200) |
| `ModelLoadFailed(reason)` | The load failed. `Reason` is `FileNotFound`, `InsufficientMemory` or `Other`; the message carries llama.cpp's own reason, which it only logs (F51) |
| `DeviceUnavailable(kind)` | `DevicePolicy.Only(kind)` could not be met |
| `ModelClosed` | The model was closed before or during the call |
| `CompactionTruncated` | A compaction summary hit the output limit |
| `Busy` | A `Conversation` operation was started while another was running |

`InsufficientMemory` is raised when the model does not fit at the smallest automatic context (4096 tokens) on any device and the app set `MemoryCheck.Refuse` (D33, D57); by default the model loads at that context with `LoadReport.memoryWarning` instead. The hub has its own sealed `HubException` (section 12.5).

---

## 8. Devices and backends

### 8.1 DeviceKind and DevicePolicy

Applications choose a chip by kind, not by backend library (D32):

```kotlin
public enum class DeviceKind { Npu, Gpu, Cpu }

DevicePolicy.Auto                 // NPU, then GPU, then CPU
DevicePolicy.Prefer(DeviceKind.Gpu)   // try the GPU first, then the rest in Auto's order
DevicePolicy.Only(DeviceKind.Npu)     // that kind or DeviceUnavailable
```

The SDK maps a kind to the backend and device the phone has (`hexagon` and its HTP device for `Npu`, `opencl` for `Gpu`, the built-in CPU backend for `Cpu`) and names both in `loadReport`. A model's image and audio encoder (its `mmproj`) runs on the model's device unless `encoderDevice` moves it, as llama-server's `--mmproj-device` does (D53); `loadReport` names both devices. An enum is settled at 1.0, so adding another route to a kind, such as Vulkan on the GPU if it returns, changes nothing public.

### 8.2 Selection and fallback

```
 DevicePolicy
      |
      v
 DeviceResolver.plan(policy, backends)  -->  ordered candidates + skipped (with reasons)
      |
      v
 for each candidate:  load with an explicit device list
      |      success -> done, record attempt
      |      load failure -> record attempt, try the next candidate
      |      InsufficientMemory -> stop (all devices share the phone's RAM)
      v
 LoadReport(device, backend, attempts, context, speculative)
```

Key rules (D24, D33):

- The SDK always sets an explicit device list directly in `common_params.devices`, not through the `--device` option, whose handler re-runs ggml's backend scan (F32, F35).
- A backend that is absent, failed to load, or has no device is recorded as skipped with its reason.
- A load that fails for lack of memory ends the fallback instead of trying the next device. Every device shares the phone's RAM, so another device cannot fix it, although projections differ by device by up to about 260 MiB (F80).
- Failures a policy cannot catch, such as a process abort inside a vendor driver, are avoided by keeping the affected backend out of the release (Vulkan, D31).
- Only one phone is verified: a OnePlus 13 (Snapdragon 8 Elite, Hexagon v79, Adreno 830). `Auto` still tries the NPU and the GPU on every Snapdragon (D92), so on a chip nobody has tested a backend that computes wrong output or aborts is not caught; an app that hasn't tested a chip can choose `Prefer` or `Only(Cpu)`.

### 8.3 LoadReport

`Model.loadReport` records, for the loaded model: the chosen `DeviceKind`, device name and backend; every `Attempt` (kind, device name and the reason it was skipped or failed); the resolved context size and the reason for it; and the resolved speculative mode and its reason. The report is the supported way to explain a choice to a person or to a log.

### 8.4 Backend specifics

**Hexagon (NPU).** `llamabro-hexagon` ships `libggml-hexagon.so` and the HTP skeleton libraries for several Hexagon versions as assets. When the runtime starts, every skeleton is copied to `noBackupFilesDir/llamabro/htp/` and checked against its SHA-256; the backend picks the one for its chip and opens it by absolute path from that directory, which the SDK names in `GGML_HEXAGON_LIBHTP_DIR` (upstream's mechanism since b12352; the SDK no longer sets the process-wide `ADSP_LIBRARY_PATH` and related variables, which ggml uses only as its last fallback). A copy that does not match fails the backend with the reason, and the next device is used (F78). FastRPC loads the skeleton from `noBackupFilesDir` for an ordinary app, debuggable or not (F36). `libcdsprpc.so` is declared as an optional native library through the merged manifest, since it exists only on Qualcomm phones.

**OpenCL (GPU).** `llamabro-opencl` declares `libOpenCL.so` and embeds the Adreno kernels. Its installer sets `GGML_OPENCL_COOPK_DP4A=0` before the backend loads, because the cooperative-K Q4_K kernel returns wrong results on the Adreno 830 for two- to four-row matmuls, which every model with checkpoints sends (F75). OpenCL also answered incorrectly for one model family in testing (F71), which is why device choice is recorded and overridable.

**CPU.** The CPU backend ships in the core. ggml's CPU variants are selected at runtime. OpenMP is off, so ggml uses its own thread pool (D38).

All backends build with `GGML_BACKEND_DL=ON` and a shared C++ runtime. Hexagon and OpenCL are built with Qualcomm's toolchain image, whose NDK matches the SDK's (F62, F63).

---

## 9. Memory fit and ContextSize.Auto

### 9.1 Why upstream's fit is not enough

Upstream can size a context to fit memory (`--fit`), but on a phone it fails in three ways (D33, F33, F66): it checks device memory only and treats host memory as unlimited; the NPU reports 0/0 memory; and when anything goes wrong, llama-server loads the full training context (262K tokens for one common model). On the CPU its check even omits the repacked weights.

### 9.2 The SDK's fit

The SDK turns upstream's `--fit` off, so context and placement are always explicit, and fits the context itself:

1. The bridge runs upstream's own dry-run measurement (`common_fit_measure`) at 4096 tokens and at the ceiling.
2. It sums every row of the result (devices and host together, since a phone has one RAM) and interpolates to the budget, as upstream's second step does.
3. Below 4096 tokens, `memoryCheck` decides (D57): `Warn`, the default, loads at 4096 tokens without the budget and reports the shortfall in `LoadReport.memoryWarning`; `Refuse` fails with `ModelLoadFailed(InsufficientMemory)`.

### 9.3 Budget and ceiling

The budget (D34, F66) is the kernel's `MemAvailable` from `/proc/meminfo`, which is what AOSP itself returns as available memory, less:

- Android's low-memory threshold
- the prompt cache (`ramCacheMib`)
- the size of an image encoder's file
- a reserve (`memoryReserveMib`, default 512 MiB)

If `/proc/meminfo` cannot be read, `ActivityManager.MemoryInfo.availMem` is used. Some OEM builds report less than the kernel does, because the GPU and NPU driver pools the kernel could reclaim are counted as used. A draft file for speculative decoding counts too (D41).

`Auto` stops at 16 384 tokens. Common local defaults are 4096, current models spend 1-3K tokens per turn on reasoning and tool results, and a dense 3B model's cache at 16K is about 1.8 GB, which is workable on low-end phones. `ContextSize.UpTo(maxTokens)` applies the same fit with an application-chosen ceiling, and `Fixed(tokens)` disables fitting.

The reserve is the application's to change (D45). It covers what grows during a chat, like llama-server's `--fit-target` margin. Lowering it is a deliberate risk, since Android's low-memory killer ends the whole app without an exception.

### 9.4 estimateFit

`LlamaBro.estimateFit(source, configure)` returns a `FitEstimate`: whether the model fits (`fits`), the context it would get, the minimum context and the bytes needed at the minimum and at the ceiling, the budget, and the device. It uses the same projection as loading, so an application can warn before loading. On `hub.model(ref)` it runs the projection on GGUF headers fetched with HTTP `Range` requests, so a person can learn whether a model fits before downloading it, with the same call that fits it afterwards (D42, D64, F84, F88).

### 9.5 Weights are loaded without mmap

The SDK loads with upstream's `--load-mode none` (D30). With mmap, the CPU repack copies the weights while the mapped pages stay resident, so one common 4B model used 5.5 GB proportional set size instead of 3.4 GB (F58, F60). Loading without mmap is about 1.2 s slower but never exceeds its final footprint and needs no patch.

---

## 10. Phone defaults

The starting point is llama-server's defaults. A default changes only when a phone needs it and only with a number measured on a device (D17).

| Setting | llama-server | SDK | Reason |
|---|---|---|---|
| `n_parallel` | 4 slots (unified KV) | 1 | A phone app generates one reply at a time |
| `cache_ram_mib` | 8192 | 1024 | A saved chat is its KV plus its checkpoints; 1 GiB holds about two chats on a 4B model (F11) |
| `n_ctx_checkpoints` | 32 | 8 | About 50 MiB each on a 4B model and two per turn, so eight cover about the last four turns (F11) |
| `n_batch`, `n_ubatch` | 2048, 512 | 512, 512 | Cancel applies between batch passes; larger micro-batches were not faster (F9, F10) |
| `sleep_idle_seconds` | off | off, not exposed | A failed reload after sleep aborts the process |
| `slot_save_path` | none | `noBackupFilesDir/llamabro/slots/` | Needs an application-private path |
| `load_mode` | mmap | none, not exposed | D30 |
| `n_ctx` | from flags, fit on | `ContextSize.Auto`, fit off | D33 |
| `checkpoint_min_step`, `image_max_tokens` | upstream | upstream | No phone-specific reason |

Every value except those marked as not exposed can be overridden in `ModelConfig`. The worst case for one slot with a 1 GiB RAM cache and eight checkpoints is about 1.4 GB plus the model, against about 14 GB upstream.

---

## 11. Speculative decoding

`ModelConfig.speculative` selects a mode:

| Mode | Behaviour | Source |
|---|---|---|
| `Off` | No speculation | |
| `Auto` | Chosen per device; off on every chip today | D35, F67, F68 |
| `Mtp(draftMax = 3)` | Multi-token prediction from a head inside the GGUF | F67 |
| `Ngram(then = null)` | n-gram lookup with upstream's `--spec-default` (ngram-mod, n_match 24, n_min 48, n_max 64); can be combined with `Mtp` as `Ngram(then = Mtp(n))` | D35, F72 |
| `DFlash(draftMax = 15)` | A separate draft head loaded from `ModelSource.draft` | D36, D41, F73 |

Measured behaviour that shaped the defaults:

- **n-gram** decodes code edits about 7 times faster on the NPU and 2.3 times faster on the CPU with byte-identical replies, but partly repeating output is 10-15% slower on the CPU, so `Auto` stays off (F72).
- **DFlash** decodes structured output about 1.5 times faster than MTP and 2.3 times faster than no speculation on the NPU, but loses on prose and on the CPU, needs about 2.9 GB more memory, and is never used on the GPU (F73, F80). It is never chosen by `Auto`.
- **MTP** is the opt-in for structured output on the NPU (F67, F68).
- EAGLE-3 and DSpark are not supported: they lost everywhere with the checkpoints that exist (D36).

Every mode is checked for identical greedy output against the same model without speculation. Patch 0001 makes MTP's prompt processing faster for hybrid models on the NPU: Hexagon's fast chunked delta-net kernel also takes batches longer than K, where ggml's contract promises only the final state, as CUDA's kernel already does (D80, F149; it replaced the earlier head/tail split). `loadReport.speculative` and `speculativeReason` explain what was chosen.

---

## 12. The Hub

`llamabro-hub` downloads models from Hugging Face into a cache and produces a `ModelSource`. It is pure Kotlin, a port of upstream's `-hf` behaviour over `HttpURLConnection` with no native code and no dependency (D39, D15). Upstream's own download code cannot run in the SDK, which builds llama.cpp without OpenSSL (F81).

### 12.1 What the hub owns, and what it does not

Downloading involves two separate jobs:

- **What to fetch and how to store it:** resolve a reference to files, fetch, resume, verify, store and report progress. This needs knowledge of Hugging Face and llama.cpp. It is the SDK's job.
- **When and where it runs:** foreground or background, a foreground service, WorkManager, a user-initiated job, network constraints, retry policy and notifications. Every app already decides this its own way. It is the application's job (D9).

The hub therefore schedules nothing and touches no notifications; the optional `llamabro-hub-work` (section 12.7) is one ready answer to the second job, which an app can take or leave. It makes downloads easy to run anywhere, through five properties:

1. **A cold `Flow` with cooperative cancellation.** `hub.download(ref)` runs unchanged inside a `CoroutineWorker`, a `JobService` scope, a `Service` scope or a `ViewModel`.
2. **Idempotent, with all state on disk.** The cache directory is the state: partial files and their validators. Calling `download` again resumes, and finished verified blobs are skipped. There is no database.
3. **One transfer per selection, one writer per blob.** In one process, collectors of the same selection share one transfer, and the last to leave cancels it. Each blob is written under a lock: a mutex keyed by the lock file's identity (device and inode, as the JVM's own lock table keys it; the canonical path where there is none) in the process, and a kernel lock across processes, which the kernel releases when a process dies. So a download resumed while a stopped one still lets go of the partial (a quick Pause then Resume) waits for it, then resumes the same partial.
4. **Progress anyone can watch.** `hub.progress(ref)` is a `StateFlow<DownloadState>` shared by the whole process, so the UI and a notification see the same numbers. When nothing is running, the state is read from disk (`Absent`, `Partial`, `Complete`) on `Dispatchers.IO`, so calling it on the main thread does no I/O (F91).
5. **Errors that say whether a retry helps** (section 12.5).

### 12.2 API

```kotlin
val hub = LlamaBroHub.create(context, config)       // one hub per cache folder (D70); config is optional
val ref = HfModel("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")  // or "org/repo", or "org/repo#dir/file.gguf"

val fit   = llama.estimateFit(hub.model(ref))         // does it fit this phone now? (cached files, else headers only)
val model = llama.loadModel(hub.model(ref))           // fetches if needed, then loads
hub.hasUpdate(ref)                                    // an online look at the newest commit; changes nothing

val plan = hub.plan(ref) { mmproj = true }           // files, roles, sizes, digests, offline flag
hub.download(ref).collect { p: DownloadProgress -> /* ... */ }
hub.progress(ref)                                    // StateFlow<DownloadState>
hub.cached(); hub.delete(ref)
hub.source(ref)                                      // the installed copy as a ModelSource, never the network

val page = hub.search("qwen3") { sort = SearchSort.Trending; takesImages = true }   // grouped results (D68)
page.next?.let { hub.search(it) }                    // the next page, through the Hub's cursor
hub.files("unsloth/Qwen3.5-4B-GGUF")                 // each quantisation with its bytes, image encoder or not
hub.recommendedSampling(ref)                         // fromFile and fromBaseModel, each or null (D79)
hub.capabilities(ref)                                // template, takesImages, takesAudio from installed headers, offline (D155)
```

Search (D68) asks `/api/models` for GGUF repos, and the calls around it are looks that move nothing:

- **The query.** A query, a sort (downloads by default, likes, trending, recently updated, newest), a filter by author, one for models that take pictures, a parameter range (`num_parameters`, as each upload reports its count) and one that hides gated repos; it pages through the Hub's `Link` cursor, and a cursor that leaves the endpoint's origin is not followed.
- **The cursor** is text (`SearchCursor.value`) an app may store for an endless list that outlives the process; the hub follows a stored one only on its own endpoint.
- **Grouping.** A result groups the repos tagged `base_model:quantized:X` under X; a fine-tune, merge or adapter names the same base under another tag and stays its own result (F146). Grouping is per page, so an endless list merges pages by result id.
- **`files(repo)`** lists the newest commit through the resolver's look (it moves no ref) and gives each model GGUF once, split parts summed, with an `HfModel` naming that file, and whether an `mmproj` is there; sizes, not a fit verdict.
- **`plan(ref)`** is a look too: it moves no ref and writes no record, and falls back to the cache only when the Hub can't be reached; only `download` records a selection.
- **`recommendedSampling(ref)`** returns both sources, labelled, each null when absent (D79):
  - `fromFile`: the GGUF header's `general.sampling.*` (what loads by default, F17), read from disk when the model is on the phone, else through the header fetch into a stub removed when it returns.
  - `fromBaseModel`: the base model's `generation_config.json` (`temperature`, `top_p`, `top_k`, `min_p`, `repetition_penalty` as the repeat penalty, `presence_penalty`; `do_sample: false` is greedy, temperature 0).
  - A gated base model gives a null `fromBaseModel`; a configured token refused by the model's own repo throws `Unauthorized`, as `plan` does. Nothing is applied for the app.
- **The token.** Every request goes through the hub's transport, so the token reaches only the endpoint's host and never crosses a redirect (F82).

`hub.model(ref)` is a `RemoteModelSource` (D64, D75). Its `fetch` loads the installed copy when every one of its files
is on disk, without a request or a token lookup; otherwise it joins the shared, resumable download (F114-F116) and
returns the files it left. Its `withHeaders` hands `estimateFit` the installed files when complete, else bounded GGUF
headers of the model and drafts and a sparse placeholder for the projector, removed when the estimate ends, also when it
throws.

**The installed copy, a deliberate deviation from upstream (D64).** Upstream's `-hf` re-reads `main` on every start and downloads what changed (F82), and its offline read follows `refs/main`. But `refs/main` records the newest commit the cache has seen, not the copy on the phone: a started update moves it, whether it was cancelled or failed (`hub.plan(ref)` is a look and moves nothing), and a refs-only read would then download the newer commit inside a load, or fail offline beside a complete older snapshot. So:

- **The installed record.** The hub keeps one per model and options (`.llamabro/installed/`), written only when a download completes, and `hub.model`, `hub.source` and `hub.progress` follow it.
- **Updates.** A newer copy replaces it only when its download completes: `hasUpdate` compares the newest commit with it (an online look that moves nothing, and throws on a refusal or a rate limit rather than answering "no update"), and `download` fetches it.
- **On the phone.** A copy with no installed record is not on the phone, and `hub.cached()` lists installed copies only (D107).
- **`hub.delete`** removes the record with the files:
  - Deletes of one repository run one at a time, so of two at once for one model the second returns false.
  - A delete first stops this process's running downloads of what it removes (the repo, the tag or the file, as the model or its draft) and waits for them, ending their flows with a `Network` error that is not retryable so no worker fetches the model again; a download of it that starts while the delete runs fails at once with the same error.
  - Partial files are removed under their blob's lock, and a delete removes each blob under its download lock, so a download of another tag that links it meanwhile keeps it.
- **Replacing a copy.** When a download replaces an installed copy, the files only the old copy named are removed, each decided under its blob's lock from the records as they are then, so a download of another selection reusing a blob keeps it; a removal that fails is logged and the download still completes.
- **`hub.cached()` lists only complete selections**: the installed copies with every file on disk, then the tags of `refs/main`'s snapshot with all their split parts, and, for a tag the hub holds a plan or installed record of, only when one of those records is complete or its default selection loads as `hub.source` would load it, since the snapshot alone can't tell a stopped download from a copy made without its projector.

`HfModel` is a class and converts to a plain string, so it fits in a WorkManager `Data` or an Intent extra. `HubConfig` sets the endpoint, a token provider, the cache directory, the progress interval and a logger. `create` returns the same hub for the same cache folder and an equal config, throws `IllegalStateException` for a different config on that folder, and lets hubs on different folders coexist; the logger and token provider compare by reference, so create the config once (a DI singleton scope does that).

`DownloadProgress` (in core's package since D71 and D75, so a core `RemoteModelSource` can report it) is shaped for notifications: a `phase` (`Resolving`, `Verifying`, `Downloading`, `Complete`), `bytesDone` and `bytesTotal` (known from the plan), the current file and its index of the file count, a smoothed `bytesPerSecond` and an ETA. Emissions are throttled to the progress interval (one second by default); phase changes and the last emission always arrive. `DownloadState` is `Absent`, `Partial`, `Downloading`, `Complete` or `Failed`.

### 12.3 Resolve and cache layout

Resolution follows llama.cpp's `-hf` (read from `common/hf-cache.cpp` and `common/download.cpp`, F82):

- A reference has no revision, like `-hf`, which always takes the default branch's commit.
- `GET /api/models/{repo}/refs` gives the commit, and `GET /api/models/{repo}/tree/{commit}?recursive=true` gives the files with each LFS digest and size.
- The GGUF is chosen by tag, and split parts (`-00001-of-00003`) are collected. The multimodal projector is fetched by default and a DFlash draft only on request, from the model's repository or a second one, as `-hfd` does (D41).

The cache layout is compatible with llama.cpp and `huggingface_hub`:

```
<cacheDir>/
  models--{org}--{repo}/
    blobs/{oid}                        content-addressed files
    blobs/{oid}.downloadInProgress     partials
    snapshots/{commit}/{path}          relative symlinks into blobs/
    refs/{name}                        ref -> commit
  .locks/models--{org}--{repo}/{oid}.lock
```

The default `cacheDir` is `noBackupFilesDir/llamabro/hub` (D16). Auto Backup would otherwise silently stop cloud backup above 25 MB and device-to-device transfer would copy gigabytes of models to a new phone; the no-backup directory is always excluded and, unlike the cache directory, is not purged under storage pressure. Removable external storage is avoided because a model could disappear while it is mapped. Snapshot entries are relative symlinks, falling back to a move or copy as llama.cpp does, and survive moving the tree (F86).

### 12.4 Reliability

- **Resume** uses `Range` when the server sends `Accept-Ranges`. A 416 or 200 answer drops the partial, as upstream does.
- **Verification (D40, D47).** Each LFS file is hashed with SHA-256 while it is written, and non-LFS files use the git SHA-1. A file enters the cache only when the digest matches the repository's value; otherwise the partial is deleted and the download fails with `IntegrityMismatch`. A resumed partial's existing bytes are hashed again first (phase `Verifying`, about 0.75 s per GB on the test phone, F89); a complete partial whose digest already matches is renamed with no request.
- **Durability.** Writes go to `{oid}.downloadInProgress`, are synced, and are renamed atomically. Each call makes up to three attempts, with 2 s and then 4 s waits.
- **Storage.** Free space is checked up front with `StorageManager.getAllocatableBytes` and reserved with `allocateBytes`. Running out of space keeps the partial.
- **Concurrency.** Files download one at a time by default (D44, F89, F91), in the plan's order: model parts, image encoder, draft. A download stopped partway holds a prefix of its plan.

Deviations from `-hf`, beyond hashing (D44): `delete(tag)` removes the tag's files from every snapshot of the repository, with blobs removed only when no snapshot links them; and a tree entry without a digest is skipped. A tag is matched case-insensitively, as `-hf` does (`common/download.cpp` `find_best_model`, `common_download_remove`); `HfModel` upper-cases it, so the two spellings are one selection, one record and one work name. `cached()` lists only selections that load: a tag with a split part missing, or one whose plan or installed records are all incomplete while its default selection wouldn't load either, is left out, where upstream's `common_list_cached_models` lists every tag with a first part (`common/download.cpp:1048`). A load must never download unasked (D64), and a stopped download links its first files before the rest arrive. Files download one at a time by default, in the plan's order, where upstream starts them all at once with `std::async` (`common/download.cpp:888`): a stopped download then holds a prefix of its plan, the same each time (D89).

### 12.5 Errors

`HubException` is sealed: `Network(retryable)`, `Unauthorized` (a gated repository: a token or an accepted licence is needed), `NotFound`, `RateLimited(retryAfterSeconds)`, `InsufficientStorage(neededBytes, availableBytes)` and `IntegrityMismatch`. An application's worker maps `retryable` to a retry. A download retries only network and I/O failures that may pass (a retryable `Network`); a refusal (a 4xx) fails at once, and any other exception is a bug and passes through unchanged. Being offline with the model not cached is a retryable `Network` error (F94).

### 12.6 HTTP and redirects

The SDK disables automatic redirects and follows them itself. Hugging Face redirects file downloads to a CDN host, and the SDK sends the access token only to the Hugging Face host, never to the CDN. On the real service the resolve URL redirects to a CDN that answers HEAD and `Range` (F93). A small internal `Transport` interface keeps the client testable; it is not public in 1.0 and could become public if apps need proxies or certificate pinning.

### 12.7 Background downloads

When and where a download runs stays the application's call, but most apps want the same thing, so `llamabro-hub-work` ships it (D64, D75). The app registers `LlamaBroWorkerFactory(hub)` in WorkManager's `Configuration`, or adds it to a `DelegatingWorkerFactory` beside its DI container's factory; the worker then runs on the app's own hub and config, with no global (D70). `WorkManager.enqueueDownload(ref)` enqueues unique work per model and options (`KEEP` while one is queued or running), with a network constraint and exponential backoff; `cancelDownload` cancels it. The worker collects `hub.download` as a `dataSync` foreground service, reports bytes through WorkManager's progress, retries a retryable `HubException` (each try resumes the partial files, F114-F116) and fails only after five tries in a row that moved no bytes, since WorkManager's own attempt count also counts network loss, process death and Android 15's `dataSync` time limit; any other error fails. Notification and WorkInfo progress are paced to about once a second, phase changes and the last update always included. When Android refuses the foreground service (a start from the background on Android 12+) it downloads as plain background work.

Its notifications work with no setup: the model's name, a progress bar with the size and the time left, and a Cancel action (WorkManager's cancel intent) while it runs; a short "Downloaded" or "Download failed" at the end; all in a low-importance "Downloads" channel the module creates. An app subclasses `DownloadNotifications` to change any of them, or returns null from `finished` or `failed` to show none. The module's manifest declares the `dataSync` type on WorkManager's foreground service, its permissions and `POST_NOTIFICATIONS`; it never requests that permission at run time. Without it the download runs and its notifications stay hidden. The worker reaches the hub through an internal seam, so its JVM tests run on work-testing without a device or Robolectric; WorkManager's `KEEP`, the default notifications and the factory over a real hub are an androidTest.

The [`download` acceptance app](../sdk/acceptance/download) keeps its own reference recipe: a `CoroutineWorker` that calls `setForeground` with a progress notification and maps `retryable` to a retry, plus an Android 14 user-initiated job variant. Killing the process and force-stopping the app both resume and verify on the test phone (F92). The application should hold its notification permission, or a force-stopped relaunch may stay in the background, where some manufacturers freeze it.

### 12.8 Inside the hub

`LlamaBroHub` is only the facade: the public operations, the gate a `delete` raises against a running download of what it removes, and the registry of one hub per cache folder. The rest lives in `internal/`, each part owning one thing:

- **`Planner`** owns what a selection (a model and its `PlanOptions`) resolves to. `plan` resolves it online from the Hub's listing; `isCachedComplete` asks the same question of the cached listing alone. Both choose files through `select` (model files, projector, DFlash), and through `draftSelection` for another repo's draft (its DFlash file, else its model's files); every `Resolver` is built by `Planner.resolver`. This is the one place the choice is made, so `download` and `progress` choose by the same rule (`DownloadProgressAgreementTest` pins it). One consequence follows upstream's offline resolution (F82): the cached listing holds only the files on disk, so a model downloaded without its projector is Complete for the same selection asked with one.
- **`DownloadStates`** owns what each selection shows: its `StateFlow<DownloadState>`, the selections seen (so a delete finds the ones it touches) and the first read from disk, all on `Dispatchers.IO`. The hub feeds it the transfer's progress; the disk read (`diskState`) is the only place a state is derived from files.
- **`PlanStore`, `LocalCopies`, `CacheLookup`, `Cache`** hold the records, the installed copy, byte counts and the cache listing and removal; `Transfers` shares one transfer per selection; `BlobDownloader` fetches one blob.
- **`HubSearch`** is the searcher behind `search`, `files`, `modelCard`, `baseModel` and `recommendedSampling`; the public types it returns (`SearchPage`, `SearchResult`, `RepoFiles`, `RecommendedSampling` and the rest) are in `HubSearchResults.kt`.

---

## 13. Threading and lifecycle

### 13.1 One runtime per process

What is global in a process: ggml's backend registry and the backend libraries it loaded, the environment variables the backends read, and llama.cpp's log callback (D6). So:

- **Kotlin:** `LlamaBro.create(context)` returns the process's one runtime. It runs no native code, so it is cheap on the main thread (F48).
- **Native:** the runtime is a function-local `static`, which C++ initialises exactly once and thread-safely. A second initialisation with a different configuration returns an error that Kotlin reports as `InvalidRequest`.
- **When:** native initialisation (about 13 ms on the test phone, F48) runs once on `Dispatchers.IO`, in the first `loadModel`, `devices()` or `backends()` call. A configuration conflict is refused earlier, in Kotlin: `LlamaBro.create` throws `InvalidRequest` at once (D70); the native refusal remains only as a backstop.

### 13.2 Models

Models are independent handles. Several can be loaded at once, for example a chat model and an embedding model, each with its own worker thread. Every llama.cpp call for a model happens on that model's worker thread.

### 13.3 Rules

- **Nothing blocks the caller's thread.** `create` runs no native code; `loadModel`, `estimateFit`, `devices` and `backends` are `suspend` and run on `Dispatchers.IO`; `close()` returns at once.
- **Closing.** `close()` returns immediately and frees the model in the background; `closeAndJoin()` waits for the memory. `loadModel` first waits for pending closes, so switching models never holds both in memory at once. `lb_model_free` cancels in-flight requests, waits for their terminal events and joins the worker thread.
- **Cancellation.** Cancelling the collecting coroutine sets the request's `should_stop`. In `Conversation`, it also cancels a running tool handler.
- **Cancelling a load.** `DeviceFallback` runs the native load under `NonCancellable` and answers each progress tick with whether the caller's job, read before entering it, is still active. A `false` stops llama.cpp's weight reading (the model's, a draft's, an image encoder's) at the next tick plus one tensor, through a carried patch (F145); the bridge frees what was read and returns `LB_ERR_CANCELLED`, which JNI throws as `NativeError` with that status and the runtime turns into `CancellationException`, with no device fallback. Nothing asks after the last tick, so context creation, warm-up, GPU kernels and an NPU session finish first (with an image encoder or a draft, the text model's context and warm-up come before their stages and a cancel then lands at the next stage's first tick); the runtime then frees the model they produced before rethrowing the cancellation. A `MemoryCheck.Warn` retry and the next device are not started once the caller is cancelled (D67).
- **Shape of the runtime (internal).** `LlamaBroRuntime` keeps native init (once, with `NativeError.ALREADY_INITIALIZED` marking a second config), logging, the close bookkeeping and memory settling, and wires the rest. `LoadPlanner` turns the device policy into the ordered candidates and decides, once, which are skipped (drafting never runs on the GPU, F67, F80) with the reason the report carries, and refuses an `Only` encoder with no device (D53); `DeviceFallback` loads on the first usable candidate (a `LoadAttempt` carries one try's source, config, device, encoder and the running record of failed attempts), with the encoder fallback, the `MemoryCheck.Warn` retry and the cancellation handling above; `FitEstimator` projects the first usable candidate of the same plan with the same budget, so an estimate names the device a load would use; `MediaFolders` owns `llamabro/media/<pid>-<random>/` and removes the folders of ended processes at init.
- **Conversation concurrency.** One operation at a time, enforced with `Busy` at collection.
- **Sleep mode is off.** Idle models are not put to sleep, because a failed reload aborts the process; the SDK unloads models explicitly.
- **Memory pressure.** A model that the check says doesn't fit loads at the smallest context with a warning, or fails with `InsufficientMemory` under `MemoryCheck.Refuse` (D57). Once loaded, the memory reserve (section 9.3) is the margin for growth; running out ends the app through Android's low-memory killer rather than through an exception.

### 13.4 Logging and observability

llama.cpp's and ggml's log output is routed to a `Logger` that the application supplies through `LlamaBroConfig`, with a `LogLevel`. The logger is attached before the native runtime initialises, so the lines of an init that fails or hangs still reach it (D66, F144). llama-server's own lines (`SRV_*`, `SLT_*`, `COM_*`, written through `common_log`) are not routed yet: they go to stdout and stderr, which Android discards (F22). With no logger the core logs to logcat under the tag `llamabro` and the hub under `llamabro-hub`, filtered at the configured level (`Warn` by default); an app that wants silence passes a logger that drops lines, and there is no other switch. Per-request `Timings` come from llama-server and are available on `ChatEvent.Done`. Trace sections are emitted with `android.os.Trace`.

---

## 14. Testing seams

### 14.1 `llamabro-testing`

Applications test with `llamabro-testing`, whose `FakeLlamaBro` runs the real `Model` and `Conversation` over a scripted fake engine that speaks llama-server's event stream (D27, F39). The test exercises the same request building, stream decoding, tool loop and compaction as production, with no device or native library:

```kotlin
val fake = FakeLlamaBro {
    reply {
        reasoning("Thinking...")
        content("Hello!")
        timings(promptTokens = 12)
    }
}
val model = fake.llamaBro.loadModel(/* a fake source */)
// drive a ViewModel with model, assert on fake.chatRequests
```

The script can queue replies, tool calls, a reply cut off by the length limit (`finishLength`), a context-exceeded error, an arbitrary server error, a failing load, and a slow one (`loadMillis`) that a cancelled `loadModel` stops at its next progress step, as the phone's does; `openModels` counts the models not yet closed. `chatRequests` exposes the JSON bodies the SDK built, so tests can assert on them. Only streamed chat completions are scripted; the slots listing and the reasoning control answer as an idle server would, and any other route fails with `NotSupported`.

### 14.2 The engine seam

The seams live in `com.suhel.llamabro.spi`: the `Engine` the runtime drives (with its model, request and sink types and `NativeError`) and the `BackendInstaller` a backend artifact implements. They are public only under the `@InternalLlamaBroApi` opt-in, so ordinary applications do not see them; everything in `com.suhel.llamabro.internal` is Kotlin `internal` (D104). The public classes stay final, so adding members never breaks an app. Interface members that the fake does not need get defaults, so adding a native capability does not break the fake (F54).

#### 14.2.1 `llamabro-hub-testing`

Applications that use the hub test with `llamabro-hub-testing`, whose `FakeLlamaBroHub` is a real `LlamaBroHub` over a scripted Hugging Face Hub and a temporary folder (D70, D75):

- **Built through** `LlamaBroHub.withTransport`, the hub's counterpart of `LlamaBro.withEngine`: its `Transport` and `Storage` interfaces are public only under `@InternalLlamaBroApi`, as `Engine` is.
- **The script** declares repos (GGUF files with their sizes and header sampling, a base model and its `generation_config.json`, downloads, likes, whether one takes pictures or is gated), and the scripted Hub answers search, model info, refs, tree and ranged resolve requests from them, as huggingface.co does.
- **Everything above the transport is production code**: plan, resolve, the resumable download, the installed-copy rule (D64), `hasUpdate` (declaring a repo again publishes a new commit), search grouping, `files` and `recommendedSampling`.
- **A download writes small valid GGUFs**, so `llama.loadModel(fakeHub.hub.model(…))` on a `FakeLlamaBro` fetches and loads end to end.
- **Failures**: an undeclared repo (`NotFound`), a gated one (`Unauthorized`), `rateLimit` (`RateLimited`), `offline` (`Network`) and `freeBytes` (`InsufficientStorage`). `downloadMillis` paces a download so its progress shows, and `requests` lists every request, so a test can assert that none was made.
- **The endpoint** is `https://hub.invalid/`, a reserved name no request could reach; the fake takes no token provider, since a token sends requests over the hub's own HTTP transport (F82).

### 14.3 Test layers inside the SDK

| Layer | Runs on | Covers |
|---|---|---|
| Host tests | JVM and native host | The C API and bridge with tiny GGUFs; per-endpoint JSON contracts on each llama.cpp upgrade; cache on versus off gives identical greedy output; speculation on versus off at temperature 0 |
| Golden tests | JVM | Request bodies and compaction views match `tools/ui` on the same message lists; its own compaction tests are ported |
| Hub tests | JVM, a fake Hugging Face server | Refs, tree, a 302 to a second host, `Range`, ETag change, authentication only on the Hugging Face host, kill-and-resume (D43) |
| Device tests | The test phone | Load, chat, cancel, close during generation, each backend, device choice for each AAR combination, background download |

---

## 15. Verification

Every change under `sdk/` passes `sdk/scripts/check.sh`, which compares each layer with upstream where upstream exists (D21, F31). Native, JNI and packaging changes also pass it with `--device`. The checks include host tests, the public API dump, the AAR contents (for example, that `libomp.so` has not returned, D38), 16 KB alignment and size per artifact. Upgrading llama.cpp means re-running the contract tests and the compile check, and `git apply --check` for each patch.

Hub parity with upstream is checked with golden files produced by upstream's own code: its file choice on its test cases and on real repository listings, and the cache tree it writes from the same fake server (D39, F83). Downloads are tested against the fake server for repeatability and once against the real service (F93).

---

## 16. Out of scope

Deliberately not part of the SDK, with the reason:

| Not included | Reason |
|---|---|
| Deciding which calls need approval, and gating (policy) | Application policy (D11); the SDK only pauses (D62, §6.2) |
| Scheduling, background execution and notifications for downloads | The application's choice of executor (D9) |
| Model recommendations | Which model suits an app is the app's choice; the hub searches the Hub and estimates fit (D68, D110, §12) but ranks nothing for the app |
| An out-of-process server or a subprocess `llama-server` | In-process only (D5); serving HTTP from the same process is the opt-in `llamabro-server` (D163, section 5.9) |
| Router mode | Needs subprocesses; load several `Model`s instead |
| llama-server's built-in tools and MCP | Kotlin tools cover the need |
| Context shift | Not supported for multimodal or hybrid models; compaction is a client feature (D13) |
| Compaction before the context is full | `Conversation` compacts when asked, as the web client does, or once a send meets `ContextExceeded` with `autoCompact` (D136); `contextUsage` lets an app set its own threshold |
| Low-level control (logits, KV edits, Kotlin samplers, per-token callbacks) | Outside llama-server's surface |
| Vulkan | Fails on the test phone's GPU driver at the pin; with upstream's pending fixes it runs, but slower than the CPU (D31, D112, F64, F174) |
| Training and fine-tuning | Outside llama-server's surface |
| Build-from-source consumption | Prebuilt AARs only (D4) |
| Platforms other than Android `arm64-v8a` | Flutter and Kotlin Multiplatform bindings over the C API are planned for later; the C API is kept stable for them |

Related reading: [decisions.md](./decisions.md) for every decision record, [`sdk/README.md`](../sdk/README.md) for the quickstart, and the [findings register](./research/findings.md) for the measurements cited here.
