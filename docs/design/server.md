# Serving on Wi-Fi: `llamabro-server` and the demo's server mode

Status: **built, phone run pending**; the SDK part (plan Tasks 1-8: core `exchange`, `llamabro-server`, its tests and these docs) and the demo part (the Server page and the docked view) are built; the phone run that verifies them is still to do (2026-11-26); plan in [docs/superpowers/plans/2026-11-26-llamabro-server.md](../superpowers/plans/2026-11-26-llamabro-server.md). Decisions are recorded in
[decisions.md](../decisions.md) as they are taken; the module's are D163-D165 and F189, the demo's D166.

## Intent

A person docks a phone (on a charger, on a cooler) and turns it into a model server for the other machines on the
same Wi-Fi: a laptop running an agent, a coding tool or a chat UI talks to it as it would to llama-server on a Linux
box with a GPU. The phone is not used for anything else while it serves. Phones will grow bigger GPUs and more memory,
so the feature should look better every year without changing shape.

It also proves the SDK's central claim from the outside: the model on the phone answers llama-server's own HTTP API,
with the same statuses and bodies (F189), because it *is* llama-server's core (D12, F1).

## Goals

- Any device on the Wi-Fi can call the phone with OpenAI's API (chat completions, completions, embeddings, models),
  the Anthropic Messages API and OpenAI's Responses API, streaming included, so Claude Code (`ANTHROPIC_BASE_URL`),
  Codex, the OpenAI SDKs, Open WebUI, aider and `npx llama-chat` (upstream's web UI) work unchanged.
- An explicit, visible opt-in: off by default, an API key always set by the demo, and a screen that shows who is
  connected and what they ask.
- A docked dashboard worth looking at: live tokens per second, slots in use, the phone's thermal status, the last
  request and the URL. It shows no key, so a glance or a photo of a docked phone shows none; the key and the full
  request log are on the Server page, the key only while it is revealed.
- Reusable: the HTTP part is an SDK module (`llamabro-server`) other apps can embed; the demo adds the UI and the
  Android lifecycle.

## Non-goals

- Running upstream's `llama-server` binary as a subprocess (rejected below).
- Router mode (several models loaded on demand), MCP, the CORS proxy and upstream's `/models` management routes.
- TLS. Like llama-server on a LAN, it is plain HTTP with a bearer key; the screen says so.
- Serving over the internet (no tunnels, no port forwarding help).
- The realtime WebSocket (`/v1/realtime`). It is reachable in memory (D97) and can come later.

## Anti-goals (a failure even if it works)

- A remote client reading the phone's files. llama-server reads `file://` media under `media_path`
  (`tools/server/server-common.cpp:1293-1370`), and the SDK sets `media_path` to its media folder for chat
  attachments (D87). A remote request with a `file://` image would read another chat's pictures. The server must
  refuse every non-`data:` media URL.
- A remote client changing the shared model behind the chat's back: `POST /props`, `POST /lora-adapters` and
  `POST /slots/:id` (save and restore write files) are refused unless the app opts in per route.
- Anything listening when the person didn't ask: no auto-start, and the server stops when the person stops it (Stop
  on the page or the notification); swiping the app away does not stop it (D166).

## Constraints from the code as it is (main at `0896b33e`)

- **D5** says no local network surface *for the SDK*, because the engine was meant to be used only inside the app.
  This feature is an explicit opt-in in a separate module, so it **amends D5's scope** rather than reversing it: core
  `llamabro` stays socket-free. To record as a new decision.
- **`Model.rawRequest` is not enough to forward bytes faithfully.** Its `SseParser` keeps only `data:` lines
  (`internal/SseParser.kt`), but the Anthropic Messages stream and the Responses stream carry `event:` lines that
  their SDKs dispatch on. And `rawRequest` turns an error into a typed `LlamaBroException` for app code, which keeps
  the message but not the rest of llama-server's JSON error body (`type`, `code`, `param`) that OpenAI and Anthropic
  clients read. `rawRequest` and the typed calls stay exactly as they are. The native side already delivers raw bytes
  (`Model.requestBytes`, `BodyMode.Each`), so core gains one small `@InternalLlamaBroApi` call that returns
  llama-server's status, content type and body unchanged, error bodies included. `llamabro-server` is its only user.
- **The demo holds one model at a time** through `ModelHost` (`hold` returns a `Lease`; a `hold` of another model
  waits for leases and then replaces it). Slots are 1, or 2 in voice mode (D99, D100). The server shares that host, so
  the chat and remote clients share a model and its slots.
- **The demo's only foreground service is `ReplyService`**, a `shortService` that Android ends after about
  3 minutes (D59). Serving needs its own long-running service.
- **Upstream's web UI moved** to `ui/apps/chat` at the llama.cpp root, built with npm and published as
  `@ggml-org/llama-chat`. `npx llama-chat` on the laptop can point at the phone, so v1 needs no web UI on the phone,
  only CORS.
- **The demo's UI** is built from `:demo:designsystem` components and tokens, enforced by lint (D113), including the
  glass bars (D148-D154).

## Approaches

| | How | For | Against |
|---|---|---|---|
| **A. Kotlin server over the SDK (chosen)** | Ktor CIO in `llamabro-server` forwards each route to the loaded `Model` and streams the raw bytes back | Shares the model the chat already loaded; ~2% from the binary (F1); multiplatform-friendly; a reusable module | A thin HTTP layer of our own to keep in step with upstream's routes |
| B. Upstream's HTTP layer in the native library | Build `server-http.cpp` (cpp-httplib) on the bridge's existing server context | Upstream's HTTP behaviour exactly | Much C++ and JNI work for start, stop and threads; the part D12 left out; nothing for iOS later |
| C. `llama-server` as a subprocess | Ship the binary as `libllama-server.so`, run it from the app | Upstream untouched, web UI and router mode included, a crash doesn't take the app down | Shows llama.cpp, not Llama Bro; a second copy of the model in memory; a second build with our patches and NPU setup; dies with the app and needs the same service anyway |

The owner chose A on 2026-11-24.

## Design

### 1. Core: a raw exchange (`llamabro`)

```kotlin
@InternalLlamaBroApi
public fun Model.exchange(endpoint: Endpoint, body: ByteArray, params: Map<String, String> = emptyMap()): Flow<RawPart>

@InternalLlamaBroApi
public sealed interface RawPart {
    /** First: the status llama-server answered and its content type (`text/event-stream` when it streams). */
    public data class Head(val status: Int, val contentType: String) : RawPart
    /** The body's bytes as the handler produced them, SSE framing included. */
    public class Body(public val bytes: ByteArray) : RawPart
}
```

- An error status arrives as a `Head` plus the error JSON body, not as an exception. A closed model or a cancel
  behaves as in `rawRequest`. Cancelling the collector cancels the request, so a client that hangs up stops
  generation.
- **No native change:** the C API already passes the status with every chunk (`lb_request_cb`, `llamabro.h`). The
  content type follows from the route and the body: `text/event-stream` for a streaming body that answered 200,
  `text/plain; version=0.0.4` for `/metrics`, `application/json` otherwise, errors included.
- `apiDump` and the `.api` diff go in with it.

### 2. `sdk/llamabro-server` (Android library, published)

Public API, small:

```kotlin
public class LlamaBroServer(config: ServerConfig, models: ServedModels) {
    public val state: StateFlow<ServerState>        // Stopped, Starting, Serving(addresses), Failed(reason)
    public val events: SharedFlow<ServerEvent>       // RequestStarted, Progress(tokens), RequestEnded(status, timings), Rejected(reason)
    public suspend fun start()
    public suspend fun stop()                        // in-flight requests are cancelled
}

public interface ServedModels {
    /** What `GET /v1/models` lists. */
    public suspend fun list(): List<ServedModel>
    /** The model for a request's `model` field (null: the default); the server releases it when the request ends. */
    public suspend fun acquire(id: String?): ServedLease   // throws ModelUnavailable for an unknown id
}

ServerConfig { host = "127.0.0.1"; port = 8080; apiKey = null; cors = Cors.None; routes = Routes.Default; maxBodyBytes = 32 MiB; advertise = null }
```

Defaults follow upstream's `llama-server`: localhost only and no key unless the app sets one, because the SDK makes
no policy for the app. The demo sets both.

**Routes:**

| Route | Through | Default |
|---|---|---|
| `POST /v1/chat/completions`, `/v1/completions`, `/completion`, `/infill` | `exchange` | on |
| `POST /v1/messages`, `/v1/messages/count_tokens` | `exchange` | on |
| `POST /v1/responses`, `/v1/responses/input_tokens` | `exchange` | on |
| `POST /v1/embeddings`, `/embeddings`, `/v1/rerank` | `exchange` on a model loaded for that task | on, when `ServedModels` has one |
| `POST /tokenize`, `/detokenize`, `/apply-template`, `/v1/chat/completions/input_tokens` | `exchange` | on |
| `GET /v1/models`, `/health`, `/props`, `/slots`, `/metrics` | `ServedModels.list`; the rest through `exchange` | on |
| `POST /v1/audio/transcriptions` | multipart → `Model.transcribe` | on, when the model hears |
| `POST /v1/audio/speech` | `Model.speak`, streamed | on, when the model speaks |
| `POST /props`, `/lora-adapters`, `/slots/:id` | `exchange` | **off**: they change or save the shared model. Upstream gates two of them the same way: `POST /props` needs `--props` (`endpoint_props`), slot save and restore need `--slot-save-path` (`server-context.cpp`) |
| `/v1/realtime` | | not in v1 |

**Request checks**, before a body reaches the model:
- The key: `Authorization: Bearer <key>` or `x-api-key` (Anthropic clients send that), compared in constant time;
  `/health` and `/v1/models` stay open, as upstream's `--api-key` leaves them.
- Media: every `image_url`, `input_audio` and file part must be a `data:` URL. `file://`, `http(s)://` and anything
  else get a 400 in OpenAI's error shape. This closes the media-folder read (anti-goals) and stops the phone being
  used to fetch URLs on the LAN. Refusing `http(s)` media is a deviation from upstream, so it gets its own decision
  citing the reason, as the project's rule asks.
- The body size limit (`maxBodyBytes`), and a JSON parse error as a 400.

**Concurrency:** as upstream: a request beyond the model's slots (`-np`) waits in llama-server's own task queue,
with no extra queue or 503 of ours. A request whose client disconnects is cancelled.

**CORS:** `Cors.None` by default; `Cors.Any` lets a browser on the laptop (`npx llama-chat`) call the phone.

**Discovery:** `advertise = Advertise("Llama Bro")` registers `_http._tcp` and a `_llamabro._tcp` record through
Android's `NsdManager` while serving, so the laptop finds `<name>.local`. This is Android API, at the edge (the
multiplatform soft rule).

**Engine:** Ktor CIO server (pure Kotlin, multiplatform-ready), one dependency added to the version catalog. The
module depends on `llamabro`, plus `llamabro-testing` for its tests.

### 3. The demo: server mode

**Where:** a **Server** entry in the sidebar (a mode the phone is in, not a setting).

**States**, all on one page:

```
Off       "Serve a model to the computers on this Wi-Fi"  [model picker: one of the downloaded models]
          [slots: 1 | 2 | 4]  [Start serving]   (explains: plain HTTP, a key, the phone should be charging)
Starting  loading progress (ModelHost's Waking)
Serving   http://192.168.1.20:8080      key ••••••  [copy] [new key]   (no QR code since §6)
          ── now ──  2 of 2 slots busy · 18.4 tok/s · 41 °C, thermal: light
          ── clients ──  192.168.1.12 (Claude Code) · 3 requests
          ── log ──  12:04:51  POST /v1/messages  200  1,912 in / 233 out  14.1 s
          ── connect ──  tabs: curl · OpenAI Python · Claude Code · llama-chat  (snippets with the URL and key filled in)
          [Docked view]  [Stop]
Failed    no Wi-Fi · port in use · the model didn't load   [Try again]
```

- **Docked view:** full screen, the screen kept on and dimmed, large numbers (tok/s, temperature, slots, the last
  request). While it is in front the app is the top app, which needs no service and likely serves faster (an app in
  `background` gets weaker CPU scheduling than `top-app`, F156); to measure.
- **Leaving the app while serving:** a foreground service of type `specialUse`
  (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`: "local model server") with a "Serving <model> on <URL>" notification and a
  Stop action. It holds a partial wake lock and a Wi-Fi lock (`WIFI_MODE_FULL_LOW_LATENCY`), without which Wi-Fi
  naps with the screen off. If the phone is not charging, the notification and the Server page say so.
- **The key:** generated on first start, stored with the demo's settings, shown masked, copyable, renewable (renewing
  drops connected clients); leaving serving masks the key again. Since §6 it is 16 typable characters shown in four
  groups, and there is no QR code (it was 32 random bytes in base64url, with a QR code of the URL and key).
- **Sharing with the chat:** serving holds a `ModelHost` lease on the chosen model for as long as it serves, and
  every request is answered from that lease's model, never a hold of its own. A chat on the same model uses it too and
  takes a slot like any client. Deleting the served model asks "Stop serving <model> to delete it?" first. Choosing another model in a chat asks first: "Stop
  serving <model> to use <other>?". Voice mode's second slot (D99) is honoured when the served model was loaded with
  two or more.
- **Temperature:** `PowerManager.currentThermalStatus` and `getThermalHeadroom`, shown, not acted on (the throttle is
  a separate piece of work).
- **One model in v1.** `/v1/models` lists the served model, and every request is answered by it whatever its
  `model` names, as upstream's single-model llama-server answers (D164, amended): Claude Code sends its own model's
  name. The module's own `ModelUnavailable` 404 stays for an app whose `ServedModels` refuses an id.

### 4. Tests

- **Module, on the host:** Ktor's test engine over `FakeLlamaBro`. Covers routing, the key on each header, the media
  refusal for `file://`, `http://` and `data:`, body limits, a client disconnect cancelling the request, CORS, and SSE bytes passing through unchanged with `event:` lines intact.
- **Contract, on the host with a real model** (the existing contract machinery): the same requests to upstream's
  `llama-server` binary and to `llamabro-server` give identical bodies at temperature 0 for chat, messages, responses
  and embeddings, streamed and whole.
- **Client smoke, on the host:** the official OpenAI and Anthropic Python SDKs run against `llamabro-server` on the
  host build.
- **Demo:** state rendering and previews for each page state, ViewModel tests over a fake server, a journey that starts
  and stops serving over the fake engine.
- **On the phone, attended and short:** `curl` from the Mac, Claude Code pointed at the phone for a few turns, the
  docked view against the background service (tok/s, sustained over 10 minutes). Results become findings.

### 5. Risks and things to measure

- **Big agent prompts.** Claude Code sends a system prompt and tool list of tens of thousands of tokens. At the NPU's
  ~650 tokens/s prefill (F8-class numbers on Qwen3.5 4B) the first turn waits about 30 s, and the context must hold
  it. The prompt cache makes later turns cheap. Measure it, and document which clients feel good (aider, Open WebUI and
  `llama-chat` are lighter first targets).
- **Wi-Fi client isolation** on guest networks blocks everything; the Failed and empty states say so.
- **Heat.** Sustained serving is exactly the workload the throttle idea is for; the dashboard shows it first.
- **Play.** `specialUse` needs a short justification in the Play Console.


### 6. A key you can type, throttling, and pairing from a browser (added 2026-11-26)

The client is almost always a laptop, which can't scan a QR code, and a 43-character key is painful to type. So:

- **The QR code goes.** The Address section lists the URLs with Copy; nothing else.
- **A typable key (demo).** 16 characters of Crockford base32 (no 0/O, 1/I/L, U), 80 bits, shown as
  `K7QM-4RTX-9HCD-2WNB` and stored without dashes, upper case. Over plain HTTP length protects only against guessing,
  which goes through the phone one request at a time, so 80 bits with throttling is ample.
- **Key matching (module).** `ServerConfig.keyMatching`: `Exact` (default, as upstream) or `Typable`, which ignores
  case, spaces and dashes on both sides before the constant-time compare. The demo sets `Typable`.
- **Throttling (module, on by default when a key is set).** Per client address: after 5 wrong keys within a minute,
  further requests from that address get 429 with `Retry-After`, the wait doubling from 30 s up to 5 min; a right key
  resets it. `ServerConfig.throttle` can turn it off. A deviation from upstream (which has none), recorded as a
  decision.
- **Pairing (module, off by default; the demo turns it on).** `ServerConfig.pairing: Pairing?`, where the app supplies
  `suspend fun approve(request: PairingRequest): Boolean` (`code`, `client`, `userAgent`). With it set:
  - `GET /` (no key) serves a small self-contained page: no external resources, the demo's look in plain CSS.
  - The page `POST /pair` gets `{id, code}`, a 4-digit code, at most one pending pairing per address and three in
    all, expiring after 2 minutes; the server asks the app to approve, showing the same code.
  - The page polls `GET /pair/{id}`: `pending`, `denied`, `expired`, or `approved` with the key and the base URL,
    delivered once, after which the pairing is gone.
  - The page then shows the curl, OpenAI Python and Claude Code snippets with the key filled in, ready to copy on the
    laptop. The pairing routes share the throttle.
- **Approving on the phone (demo).** A dialog over the Server page or the docked view showing the code and the
  client's address, with Approve and Deny; when the app is in the background, the serving notification shows the
  request with Approve and Deny actions. No answer within the 2 minutes is a deny.

## Plan

Each step ends with its checks passing and a commit (`sdk/scripts/check.sh` for SDK steps, `demo/scripts/check.sh`
for demo steps).

1. **Core raw exchange.** Add `Model.exchange` and `RawPart` under `@InternalLlamaBroApi`; `FakeLlamaBro` support; tests; `apiDump`.
2. **Module skeleton.** `sdk/llamabro-server` with the library convention, Ktor in the catalog, `ServerConfig`,
   `ServedModels`, `start` and `stop`, `/health` and `/v1/models`; host tests.
3. **Forwarding.** The `exchange` routes, streaming passthrough, cancel on disconnect, the error shape.
4. **Guards.** The key, the media refusal, body limits, the off-by-default routes, CORS; tests for each.
5. **Audio routes.** Transcriptions (multipart) and speech.
6. **Discovery.** NSD advertising.
7. **Contract and client smoke tests** against upstream's binary and the official SDKs; record a finding.
8. **Docs.** The decisions (the D5 amendment, the module, its defaults), architecture.md's new section, release notes,
   the README modules table.
9. **Demo service.** The `specialUse` service, wake and Wi-Fi locks, notification, and lifecycle with `ModelHost`.
10. **Demo page.** Server page states, key, QR, connect snippets, the log; previews; the chat's model-switch question.
11. **Docked view.**
12. **Phone run, attended:** Claude Code, `curl`, sustained tok/s docked against background; findings; the demo
    design doc updated.

Rough size: steps 1-8 about a week, steps 9-11 about a week, step 12 an attended hour.

## Left to the builder

Ktor plugin choices, the event types' exact fields, the QR library (or a small encoder), the log's length, and how
the connect snippets are laid out within the design system.
