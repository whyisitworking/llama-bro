# Serving on Wi-Fi Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A docked phone serves its loaded model to every device on the Wi-Fi over llama-server's own HTTP API
(OpenAI, Anthropic Messages, Responses), through a new `llamabro-server` module and the demo's Server page.

**Architecture:** Core gains one internal call, `Model.exchange`, that returns llama-server's status and raw body bytes
unchanged. `sdk/llamabro-server` is a Ktor CIO server that checks each request (key, media, size, route) and forwards
it to a `Model` supplied by the app through `ServedModels`. The demo supplies that model from `ModelHost`, keeps the
process alive with a `specialUse` foreground service, and shows a Server page and a docked view.

**Tech Stack:** Kotlin 2.3.10, Ktor 3 (CIO server, test host), kotlinx.serialization, coroutines, Android `NsdManager`,
`PowerManager`, `WifiManager`; Compose with `:demo:designsystem`.

**Spec:** [docs/design/server.md](../../design/server.md) (approved 2026-11-26). Read it before any task.

## Global Constraints

- Core `llamabro` stays socket-free: no Ktor, no `java.net` server code in `sdk/llamabro`.
- `rawRequest`, the typed calls and `LlamaBroException` do not change behaviour.
- Module defaults follow upstream llama-server: `host = "127.0.0.1"`, `port = 8080`, `apiKey = null`. The demo sets
  `0.0.0.0` and a key.
- `/health` and `/v1/models` need no key; every other route does when a key is set.
- Media parts must be `data:` URLs; anything else is a 400 in OpenAI's error shape.
- `POST /props`, `POST /lora-adapters`, `POST /slots/:id` are off unless enabled in `Routes`.
- No queue of ours: requests beyond the slots wait in llama-server's own queue (a closed model is a 503, Task 3).
- No native change (the C API passes the status with every chunk).
- New SDK logic is multiplatform-friendly where cheap; `android.*` only in the NSD advertiser.
- Public API changes: `./gradlew -p sdk :<module>:apiDump` and commit the `.api` diff with the change.
- Every SDK task ends with `sdk/scripts/check.sh --only <step>` passing, the whole `sdk/scripts/check.sh` before the
  docs task's commit; demo tasks with `demo/scripts/check.sh`. Never run inference on an emulator; device runs only in
  Task 13, attended.
- Next free IDs: D155, F189. Commit after each task; do not push.

## Review Focus

1. **A client that hangs up mid-stream** must stop generation at once, not after the reply finishes (Task 3 test
   `disconnectCancelsTheRequest`).
2. **A key in either header**: OpenAI clients send `Authorization: Bearer`, Anthropic clients `x-api-key`; a wrong key
   in one and the right one in the other is a 401 (Task 2 test `eitherHeaderButNotMixed`).
3. **Media hidden in nested places**: `messages[].content[].image_url.url`, `input_audio`, the Responses API's
   `input_image.image_url` and Anthropic's `image.source.url` must all be checked, not just chat completions
   (Task 4 test `everyApiShapeIsChecked`).
4. **The served model closing mid-request** (the person stops serving, or a chat swaps the model): in-flight clients
   get a clean error status, not a dropped socket with no body (Task 3 test `modelClosedMidStreamEndsCleanly`).
5. **Wi-Fi going away while serving** (router restart, phone roams): the page shows it and the server keeps or
   re-binds, it does not crash the app (Task 10 test `wifiLostShowsOfflineAndRecovers`).

---

### Task 1: `Model.exchange` in core

**Files:**
- Create: `sdk/llamabro/src/main/kotlin/com/suhel/llamabro/RawExchange.kt`
- Modify: `sdk/llamabro/src/main/kotlin/com/suhel/llamabro/Model.kt` (a `BodyMode.Each`, `Chunks.Bytes` path that
  also reports each chunk's status)
- Modify: `sdk/llamabro-testing/src/main/kotlin/com/suhel/llamabro/testing/FakeLlamaBro.kt`, `FakeEngine.kt`
- Test: `sdk/llamabro/src/test/kotlin/com/suhel/llamabro/ModelExchangeTest.kt`,
  `sdk/llamabro-testing/src/test/kotlin/com/suhel/llamabro/testing/FakeRawTest.kt`
- Modify: `sdk/llamabro/api/llamabro.api`, `sdk/llamabro-testing/api/llamabro-testing.api`

**Interfaces:**
- Produces:
  ```kotlin
  @InternalLlamaBroApi
  public fun Model.exchange(endpoint: Endpoint, body: ByteArray, params: Map<String, String> = emptyMap()): Flow<RawPart>

  @InternalLlamaBroApi
  public sealed interface RawPart {
      public data class Head(val status: Int, val contentType: String) : RawPart
      public class Body(public val bytes: ByteArray) : RawPart
  }
  ```
  One `Head` first (from the first chunk's status), then `Body` parts; the flow completes on `DONE`, a cancelled
  collector cancels the request, a closed model throws `LlamaBroException.ModelClosed` as `rawRequest` does.
  Content type: `text/event-stream` when the body has `"stream": true` and the status is 200; `text/plain;
  version=0.0.4` for `Endpoint.Metrics`; `application/json` otherwise.
- Produces, in `FakeLlamaBro`: `fun raw(status: Int, vararg chunks: String, holdOpen: Boolean = false)` (the next
  request on any endpoint answers these chunks; `holdOpen` keeps it streaming until cancelled), and
  `val cancelledRequests: Int`.

- [ ] **Step 1: Write the failing tests** in `ModelExchangeTest` over core's `FakeEngine`:
  `headThenBodyBytesUnchanged` (chunks `"event: message_start\ndata: {...}\n\n"` and `"data: [DONE]\n\n"` arrive as
  `Head(200, "text/event-stream")` then the exact bytes, `event:` lines included);
  `errorStatusIsAHeadNotAnException` (status 400 and body `{"error":{"type":"invalid_request_error","code":400}}`
  arrive as `Head(400, "application/json")` and that exact body); `wholeBodyIsJson`; `metricsIsTextPlain`;
  `cancelCancelsTheNativeRequest`; `closedModelThrowsModelClosed`.
- [ ] **Step 2: Run** `./gradlew -p sdk :llamabro:testDebugUnitTest --tests '*ModelExchangeTest*'`. Expected: FAIL,
  `exchange` unresolved.
- [ ] **Step 3: Implement** `RawExchange.kt` over `requestFlow` with `BodyMode.Each`; carry the status from
  `RequestSink.onChunk(httpStatus, data)` into the first part.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Write `FakeRawTest`** (`rawAnswersTheNextRequest`, `holdOpenStreamsUntilCancelled`,
  `cancelledRequestsCounts`), run `./gradlew -p sdk :llamabro-testing:testDebugUnitTest`, implement `raw` and
  `cancelledRequests`, run again. Expected: PASS.
- [ ] **Step 6: API dump and check:** `./gradlew -p sdk :llamabro:apiDump :llamabro-testing:apiDump`, then
  `sdk/scripts/check.sh --only jvm,api`. Expected: `ALL PASS`.
- [ ] **Step 7: Commit** `sdk: Model.exchange returns llama-server's status and body unchanged, for llamabro-server`.

### Task 2: `llamabro-server` module, lifecycle, `/health`, `/v1/models`, key, CORS

**Files:**
- Create: `sdk/llamabro-server/build.gradle.kts` (plugins as `llamabro-typed`: android library, `llamabro.library`,
  `llamabro.api-check`, kotlin serialization, `llamabro.publish`; namespace `com.suhel.llamabro.server`;
  `explicitApi()`), `consumer-rules.pro`
- Create: `sdk/llamabro-server/src/main/kotlin/com/suhel/llamabro/server/` `LlamaBroServer.kt`, `ServerConfig.kt`,
  `ServedModels.kt`, `ServerEvents.kt`, `Auth.kt`, `Errors.kt`
- Modify: `sdk/modules.gradle.kts` (add `":llamabro-server"`), `gradle/libs.versions.toml` (Ktor 3, latest stable,
  `ktor-server-core`, `ktor-server-cio`, `ktor-server-cors`, `ktor-server-test-host`), `sdk/llamabro-bom`,
  `sdk/scripts/check.sh` (`:llamabro-server` in `jvm`, `api` and `lint` steps)
- Test: `sdk/llamabro-server/src/test/kotlin/com/suhel/llamabro/server/LifecycleTest.kt`, `AuthTest.kt`, `CorsTest.kt`

**Interfaces:**
- Consumes: `Model.exchange` (Task 1).
- Produces:
  ```kotlin
  public class LlamaBroServer(config: ServerConfig, models: ServedModels) {
      public val state: StateFlow<ServerState>
      public val events: SharedFlow<ServerEvent>
      public suspend fun start()   // binds; Serving(port) or Failed(reason)
      public suspend fun stop()    // cancels in-flight requests, unbinds
  }
  public sealed interface ServerState { Stopped; Starting; data class Serving(val port: Int); data class Failed(val reason: Throwable) }
  public sealed interface ServerEvent {
      data class RequestStarted(val id: Long, val client: String, val method: String, val path: String, val userAgent: String?)
      data class RequestEnded(val id: Long, val status: Int, val millis: Long, val promptTokens: Int?, val predictedTokens: Int?)
      data class Rejected(val client: String, val path: String, val status: Int, val reason: String)
  }
  public interface ServedModels {
      public suspend fun list(): List<ServedModel>
      public suspend fun acquire(id: String?): ServedLease   // throws ModelUnavailable
  }
  public data class ServedModel(val id: String, val created: Long)
  public interface ServedLease { public val model: Model; public fun release() }
  public class ModelUnavailable(public val id: String?) : Exception()
  public class ServerConfig  // builder `ServerConfig { }` per D102: host, port, apiKey, cors (Cors.None | Cors.Any),
                             // routes (Routes.Default, with enable(Endpoint)), maxBodyBytes = 32 MiB, advertise: Advertise?
  ```
  Port 0 binds a free port and `Serving.port` reports it (tests use 0).
  Token counts in `RequestEnded` are read from the final `timings` or `usage` object when the body has one, else null.
- Errors use OpenAI's shape: `{"error":{"code":<status>,"message":"…","type":"…"}}` (`Errors.kt`).

- [ ] **Step 1: Write the failing tests.** `LifecycleTest`: `startBindsAndReportsThePort`, `healthAnswersOk`,
  `modelsListsServedModels` (OpenAI list shape: `{"object":"list","data":[{"id":…,"object":"model","created":…,
  "owned_by":"llamabro"}]}`), `stopUnbinds`, `portInUseIsFailed`. `AuthTest`: `noKeyConfiguredLetsEverythingIn`,
  `bearerKeyAccepted`, `xApiKeyAccepted`, `eitherHeaderButNotMixed` (Review Focus 2), `wrongKeyIs401WithErrorBody`,
  `healthAndModelsNeedNoKey`, `rejectionEmitsRejected`. `CorsTest`: `noneSendsNoCorsHeaders`, `anyAnswersPreflight`.
- [ ] **Step 2: Run** `./gradlew -p sdk :llamabro-server:testDebugUnitTest`. Expected: FAIL (module missing).
- [ ] **Step 3: Implement** the module; constant-time key compare (`MessageDigest.isEqual`).
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5:** `./gradlew -p sdk :llamabro-server:apiDump`; `sdk/scripts/check.sh --only jvm,api,lint,quality`.
  Expected: `ALL PASS`.
- [ ] **Step 6: Commit** `server: llamabro-server, a Ktor server for a loaded model: lifecycle, models, key, CORS`.

### Task 3: Forwarding the model routes

**Files:**
- Create: `sdk/llamabro-server/src/main/kotlin/com/suhel/llamabro/server/Forwarding.kt`, `RouteTable.kt`
- Test: `ForwardingTest.kt`, `RouteTableTest.kt`

**Interfaces:**
- Consumes: `exchange`, `ServedModels.acquire` (read the body's `model` field; null when absent), `FakeLlamaBro.raw`.
- Produces: `internal val routeTable: List<ServedRoute>` mapping method and path to `Endpoint` and a default on/off,
  exactly the design's table (§2 Routes), minus the audio routes (Task 5).

- [ ] **Step 1: Write the failing tests.** `streamPassesBytesThrough` (event lines intact, `Content-Type:
  text/event-stream`, chunks flushed as they come); `errorStatusAndBodyPassThrough`;
  `unknownModelIs404ModelNotFound`; `disconnectCancelsTheRequest` (Review Focus 1: a real CIO server on port 0, a
  `holdOpen` raw reply, the client cancels, `cancelledRequests == 1` within 1 s); `modelClosedMidStreamEndsCleanly`
  (Review Focus 4); `leaseReleasedAfterEveryRequest` (success, error, cancel); `offRoutesAre404UntilEnabled`
  (`POST /props`, `/lora-adapters`, `/slots/0`); `enabledRouteForwards`; `paramsForSlotsAction` (`id_slot`, `action`
  passed as params); `requestEndedCarriesTokenCounts`. `RouteTableTest`: every `Endpoint` constant except
  `Transcriptions`, `Realtime` and `SystemOne` is in the table with the design's default.
- [ ] **Step 2: Run** `./gradlew -p sdk :llamabro-server:testDebugUnitTest --tests '*Forwarding*' --tests '*RouteTable*'`.
  Expected: FAIL.
- [ ] **Step 3: Implement** with `respondBytesWriter`, flushing after each `Body` part; status and content type from
  `Head`. On `ModelClosed` before the head: 503 in OpenAI's shape; after it: end the stream.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5:** `sdk/scripts/check.sh --only jvm`. Expected: `ALL PASS`.
- [ ] **Step 6: Commit** `server: forward llama-server's routes, streaming bytes unchanged, cancelling on hang-up`.

### Task 4: Request guards: media and size

**Files:**
- Create: `sdk/llamabro-server/src/main/kotlin/com/suhel/llamabro/server/MediaGuard.kt`
- Test: `MediaGuardTest.kt`, `BodyLimitTest.kt`

**Interfaces:**
- Produces: `internal fun firstNonDataMedia(body: JsonElement): String?` (the offending URL, or null).

- [ ] **Step 1: Write the failing tests.** `MediaGuardTest`: `dataUrlPasses`; `fileUrlRefused`; `httpUrlRefused`;
  `httpsUrlRefused`; `everyApiShapeIsChecked` (Review Focus 3: chat completions `image_url.url` and
  `input_audio`, Responses `input_image.image_url`, Anthropic `image.source` with `type: "url"`, nested in multi-turn
  histories); `textMentioningFileUrlPasses` (a `file://` inside a text part is not media). `BodyLimitTest`:
  `overLimitIs413`; `invalidJsonIs400`; a refused request never calls `acquire`.
- [ ] **Step 2: Run** `./gradlew -p sdk :llamabro-server:testDebugUnitTest --tests '*Guard*' --tests '*BodyLimit*'`.
  Expected: FAIL.
- [ ] **Step 3: Implement**; refusal is 400 `invalid_request_error` with message `only data: URLs are accepted for
  media` and a `Rejected` event.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `server: accept only data: media and bounded bodies`.

### Task 5: Audio routes

**Files:**
- Create: `sdk/llamabro-server/src/main/kotlin/com/suhel/llamabro/server/AudioRoutes.kt`
- Test: `AudioRoutesTest.kt`

**Interfaces:**
- Consumes: `Model.transcribe` (`Transcription.kt`), `Model.speak` (`Speech.kt`), `FakeLlamaBro.transcription`,
  `speech`, `speechError`.
- Produces: `POST /v1/audio/transcriptions` (multipart: `file`, optional `language`, `prompt`, `response_format`
  `json` or `text`) answering OpenAI's shape; `POST /v1/audio/speech` streaming the audio bytes.

- [ ] **Step 1: Write the failing tests:** `transcriptionAnswersJson`, `transcriptionAnswersText`,
  `speechStreamsAudio`, `speechErrorPassesStatus`, `audioRoutesAre404OnAModelThatCannot` (decided from
  `model.info`), `uploadGoesThroughAFileTheServerDeletes`.
- [ ] **Step 2: Run** `./gradlew -p sdk :llamabro-server:testDebugUnitTest --tests '*AudioRoutes*'`. Expected: FAIL.
- [ ] **Step 3: Implement**; the upload goes to a temporary file in the server's own cache directory
  (`ServerConfig.workDir`), never the SDK's media folder, deleted when the request ends.
- [ ] **Step 4: Run.** Expected: PASS. `./gradlew -p sdk :llamabro-server:apiDump` if `ServerConfig` changed.
- [ ] **Step 5: Commit** `server: transcription and speech routes`.

### Task 6: Discovery on the network

**Files:**
- Create: `sdk/llamabro-server/src/main/kotlin/com/suhel/llamabro/server/NsdAdvertiser.kt`
- Test: `sdk/llamabro-server/src/test/kotlin/com/suhel/llamabro/server/AdvertiseTest.kt` (Robolectric, offline, as the
  demo's tests run)

**Interfaces:**
- Produces: `public class Advertise(public val name: String)`; while `Serving`, registers `_http._tcp` and
  `_llamabro._tcp` with TXT `path=/v1`, `auth=key|none`; unregisters on `stop()` and on failure.

- [ ] **Step 1: Write the failing tests:** `registersBothServicesWhileServing`, `unregistersOnStop`,
  `txtSaysWhetherAKeyIsNeeded`, `noAdvertiseRegistersNothing`.
- [ ] **Step 2: Run** `./gradlew -p sdk :llamabro-server:testDebugUnitTest --tests '*Advertise*'`. Expected: FAIL.
- [ ] **Step 3: Implement** behind an internal `ServiceRegistry` interface so `LlamaBroServer`'s core stays free of
  `android.*`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `server: advertise on the local network with NSD`.

### Task 7: Contract against upstream and real clients

**Files:**
- Create: `sdk/llamabro-server/src/test/kotlin/com/suhel/llamabro/server/HostServerContractTest.kt`,
  `sdk/native/tests/contract/server-run.sh`, `sdk/native/tests/contract/server-requests.jsonl` (chat, messages,
  responses, completions, embeddings requests; streamed and whole; temperature 0, seed 42)
- Create: `sdk/scripts/tests/server-clients.py` (official `openai` and `anthropic` Python SDKs, one streamed and one
  whole call each, plus one error)
- Modify: `sdk/scripts/check.sh` (a `server-contract` step after `contract`)

**Interfaces:**
- Consumes: `hostLlamaBro` (core testFixtures), `http-run.py` and `compare.py` (unchanged), the host build.
- `server-run.sh` runs upstream `llama-server` and the host test (which serves the same model with
  `LlamaBroServer` on port 0 and writes the port to a file) through `http-run.py`, then `compare.py`.

- [ ] **Step 1:** write `server-requests.jsonl` and `server-run.sh`; run it. Expected: FAIL until the test exists.
- [ ] **Step 2:** write `HostServerContractTest` (skipped without `-Pllamabro.host.lib` and `.model`, as the other
  host tests).
- [ ] **Step 3: Run** `sdk/scripts/check.sh --only server-contract`. Expected: `N/N identical` and `ALL PASS`.
- [ ] **Step 4: Run** `python3 sdk/scripts/tests/server-clients.py <url> <key>` against the host server. Expected: each
  call succeeds; the error raises the SDK's own error type.
- [ ] **Step 5:** record F189: the server's bodies equal upstream's, which clients ran, the commands.
- [ ] **Step 6: Commit** `tests: llamabro-server answers as upstream llama-server; F189`.

### Task 8: SDK docs and decisions

**Files:** `docs/decisions.md` (D155: D5's scope, core stays socket-free, the server is a separate opt-in module;
D156: `llamabro-server`'s defaults follow upstream, the routes off by default and why; D157: media as `data:` only, a
deviation from upstream, with the reason), `docs/architecture.md` (a section on the module, citing D155-D157),
`docs/release-notes.md`, `README.md` modules table, `docs/design/server.md` (status: building).

- [ ] **Step 1:** write the entries; cite them from `ServerConfig`, `MediaGuard` and `RouteTable` KDoc.
- [ ] **Step 2: Run** `sdk/scripts/check.sh`. Expected: `ALL PASS`.
- [ ] **Step 3: Commit** `docs: D155-D157, llamabro-server in the architecture and release notes`.

### Task 9: Demo server mode (no UI)

**Files:**
- Create: `demo/app/src/main/kotlin/com/suhel/llamabro/demo/server/ServerMode.kt`, `HostServedModels.kt`,
  `ServerKey.kt`, `ServerSettings.kt`
- Modify: `demo/app/build.gradle.kts` (depend on `llamabro-server`), `AppContainer.kt` (`val server: ServerMode`),
  `chat/ChatEngine.kt` or the model switcher (ask before replacing a served model)
- Test: `demo/app/src/test/kotlin/com/suhel/llamabro/demo/server/ServerModeTest.kt`, `ServerKeyTest.kt`

**Interfaces:**
- Consumes: `LlamaBroServer`, `ModelHost.hold(key, config)` and `Lease`, `SettingsRepository`.
- Produces:
  ```kotlin
  class ServerMode(host: ModelHost, settings: SettingsRepository, keys: ServerKey, scope: CoroutineScope, ...) {
      val state: StateFlow<ServingState>   // Off, Starting(progress), Serving(model, urls, slots), Failed(reason)
      val log: StateFlow<List<LogLine>>    // newest first, at most 200
      suspend fun start(model: HfModel, slots: Int)   // slots 1, 2 or 4
      suspend fun stop()
      fun isServing(model: HfModel): Boolean
  }
  class ServerKey { suspend fun current(): String; suspend fun renew(): String }  // 32 random bytes, base64url
  ```
  `HostServedModels` lists the served model only and leases it per request; the serving lease is held from
  `start` to `stop`.

- [ ] **Step 1: Write the failing tests:** `startHoldsTheModelAndServes`, `stopReleasesTheLease`,
  `keyIsStableAcrossRestarts`, `renewChangesTheKey`, `chatOnTheServedModelShares`,
  `switchingTheChatModelWhileServingAsksFirst`, `failedLoadIsFailed`, `logKeepsTwoHundred`.
- [ ] **Step 2: Run** `./gradlew :demo:app:testDebugUnitTest --tests '*server*'`. Expected: FAIL.
- [ ] **Step 3: Implement** over `FakeLlamaBro`'s engine in tests; bind `0.0.0.0`, port 8080, falling back to a free
  port when 8080 is taken.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `demo: server mode, serving the held model with a key`.

### Task 10: Foreground service, locks and network state

**Files:**
- Create: `demo/app/src/main/kotlin/com/suhel/llamabro/demo/server/ServerService.kt`, `NetworkState.kt`
- Modify: `demo/app/src/main/AndroidManifest.xml` (`FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`,
  `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`; the service with
  `foregroundServiceType="specialUse"` and the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property "local model server")
- Test: `ServerServiceTest.kt` (Robolectric), `NetworkStateTest.kt`

**Interfaces:**
- Consumes: `ServerMode`.
- Produces: the service starts when the app leaves the foreground while `Serving`, stops with `stop()`; a notification
  "Serving <model> on <url>" with a Stop action; a partial wake lock and a `WIFI_MODE_FULL_LOW_LATENCY` lock held only
  while serving. `NetworkState.addresses: StateFlow<List<String>>` (IPv4 Wi-Fi addresses, empty when offline) and
  `charging: StateFlow<Boolean>`.

- [ ] **Step 1: Write the failing tests:** `startsWhenBackgroundedWhileServing`, `stopActionStopsServing`,
  `locksReleasedOnStop`, `notificationNamesModelAndUrl`, `notChargingIsShown`,
  `wifiLostShowsOfflineAndRecovers` (Review Focus 5).
- [ ] **Step 2: Run** `./gradlew :demo:app:testDebugUnitTest --tests '*ServerService*' --tests '*NetworkState*'`.
  Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `demo: a specialUse service keeps serving with the app in the background`.

### Task 11: The Server page

**Files:**
- Create: `demo/app/src/main/kotlin/com/suhel/llamabro/demo/ui/server/ServerScreen.kt`, `ServerViewModel.kt`,
  `ConnectSnippets.kt`, `QrCode.kt`, `ServerPreviews.kt`
- Modify: `ui/shell/Routes.kt` (`const val SERVER = "server"`), the sidebar and chat-list layouts (a Server entry),
  `ui/Screens.kt` (`fun serverViewModel(): ServerViewModel`), `AppContainer.kt`
- Test: `ServerViewModelTest.kt`, `ConnectSnippetsTest.kt`, a journey in `demo/app/src/androidTest` over the fake
  engine: open Server, start, see the URL, stop.

**Interfaces:**
- Consumes: `ServerMode`, `NetworkState`, `PowerManager.currentThermalStatus` and `getThermalHeadroom(10)`.
- Produces: the four states of the design (§3) built only from `:demo:designsystem` components and `DemoType` roles
  (lint D113). Snippets: curl, OpenAI Python, Claude Code (`ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`), and
  `npx llama-chat`, each with the URL and key filled in.

- [ ] **Step 1: Write the failing tests:** ViewModel `offShowsDownloadedModels`, `startingShowsProgress`,
  `servingShowsUrlKeyAndLog`, `keyIsMaskedUntilRevealed`, `failedOffersRetry`; `ConnectSnippetsTest` checks each
  snippet's exact URL and key placement.
- [ ] **Step 2: Run** `./gradlew :demo:app:testDebugUnitTest --tests '*Server*'`. Expected: FAIL.
- [ ] **Step 3: Implement** the screen stateless, state from `combine`/`stateIn`; previews for each state, light and
  dark. QR: a small encoder (or a pinned library) producing a `Painter`.
- [ ] **Step 4: Run** `demo/scripts/check.sh`. Expected: `ALL PASS`.
- [ ] **Step 5: Commit** `demo: the Server page, its key, QR code, connect snippets and log`.

### Task 12: Docked view

**Files:**
- Create: `demo/app/src/main/kotlin/com/suhel/llamabro/demo/ui/server/DockedScreen.kt`
- Modify: `ServerScreen.kt` (a Docked view action), `Routes.kt` (`SERVER_DOCKED = "server/docked"`)
- Test: `DockedViewModelTest` or the shared ViewModel's tests, a journey step.

- [ ] **Step 1: Write the failing tests:** `dockedShowsTokensPerSecondTemperatureSlotsAndLastRequest`,
  `dockedKeepsTheScreenOn` (the window flag set while shown, cleared on leave).
- [ ] **Step 2: Run** `./gradlew :demo:app:testDebugUnitTest --tests '*Docked*'`. Expected: FAIL.
- [ ] **Step 3: Implement** full screen, dimmed (`screenBrightness = 0.1f`), large numerals from the design system.
- [ ] **Step 4: Run** `demo/scripts/check.sh`. Expected: `ALL PASS`.
- [ ] **Step 5: Commit** `demo: a docked view for a phone that serves`.

### Task 13: Phone run, attended (owner present)

Only when the owner asks for it, short, on the charger.

- [ ] **Step 1:** install the debug build; serve Qwen3.5 4B on the NPU; `curl` from the Mac; Claude Code with
  `ANTHROPIC_BASE_URL` for three turns; `npx llama-chat` against the phone.
- [ ] **Step 2:** sustained tokens per second for 10 minutes docked against 10 minutes in the background, thermal
  status at the start of each.
- [ ] **Step 3:** record F190 (first-turn time for Claude Code's prompt, docked against background) and update
  `docs/design/server.md` and `docs/design/demo-app.md`.
- [ ] **Step 4: Commit** `docs: F190, serving from the phone`.
