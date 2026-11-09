# LlamaBro SDK

Run a GGUF model on an Android phone with llama.cpp's own server core in-process: the same chat handling,
templates, tool calls, reasoning, prompt cache and sampling as `llama-server`, without HTTP. Kotlin only
(coroutines and `Flow`). Package `com.suhel.llamabro`.

> Not published yet (coordinates `io.github.whyisitworking:llamabro`, P5). Until then, include this build:
> `includeBuild("path/to/sdk")` and `implementation("io.github.whyisitworking:llamabro")`.
> arm64-v8a, Android 10 (API 29) and up.

## Five minutes: a chat screen

Keep the model in a `ViewModel` so rotation doesn't reload it, and take `LlamaBro` as a parameter so tests can
pass a fake. Nothing here blocks the main thread.

```kotlin
class ChatViewModel(private val llama: LlamaBro, private val path: String) : ViewModel() {
    private var model: Model? = null
    var chat: Conversation? = null; private set
    val loading = MutableStateFlow<Float?>(0f)

    init {
        viewModelScope.launch {
            val m = llama.loadModel(ModelSource.file(path)) { onProgress { loading.value = it } }
            model = m
            chat = m.conversation(system = "You are a helpful assistant.")
            loading.value = null
        }
    }

    fun send(text: String) = viewModelScope.launch {
        val c = chat ?: return@launch
        try {
            c.send(text).collect()                 // the UI shows c.messages and c.reply (the streaming reply)
        } catch (e: LlamaBroException.ContextExceeded) {
            if (c.canCompact()) { c.compact().collect(); c.resume().collect() }   // summarise older turns, answer again
        }
    }

    override fun onCleared() { model?.close() }   // returns at once; memory is freed in the background
}

// in the factory: ChatViewModel(LlamaBro.getInstance(application), path)
```

`Conversation` keeps the history the way llama.cpp's web UI (`tools/ui`) does. Observe:

- `messages`: the history; `reply`: the assistant message while it streams, null otherwise.
- `isBusy`, and `contextUsage` (tokens used of the context).
- `ask(text)` returns the answer as a `String`, or throws `LlamaBroException.Incomplete` if it was cut off.
- `regenerate()`, `endReasoning()` (stop thinking now), `toJson()` and `model.restoreConversation(json)`.

The sample app ([samples/minimal](samples/minimal)) is this, with a few views and a JVM test.

## Tools

Register a handler and the conversation runs the tool loop for you (calls, results, the next turn):

```kotlin
val chat = model.conversation(system = "…") {
    tool("get_weather", "Current weather for a city",
        """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""") { args ->
        weather(args.string("city"))
    }
}
chat.send("Weather in Paris?").collect { event ->
    when (event) {
        is ChatEvent.PromptProgress -> showPrefill(event.processed, event.total)
        is ChatEvent.ReasoningDelta -> showThinking(event.delta)
        is ChatEvent.ContentDelta -> append(event.delta)
        is ChatEvent.ToolCallReceived -> showToolCall(event.call)
        is ChatEvent.ToolResult -> showToolResult(event.result)
        is ChatEvent.Done -> showStats(event.reason, event.timings)
    }
}
```

Leave the handler out (`tool(name, description, schema)`) to run tools yourself: the reply ends with
`FinishReason.ToolCallsPending`; add each result with `addToolResult(id, result)`, then `resume()`.

## Request options

Every llama-server field, typed where typed. Set defaults for a conversation and override per send:

```kotlin
val chat = model.conversation {
    defaults {
        sampling { temperature = 0.7f; dryMultiplier = 0.8f }
        reasoning { budgetTokens = 512 }
        responseFormat = ResponseFormat.JsonSchema(schema)
        stop("</answer>")
    }
}
chat.send("…") { sampling { temperature = 0f } }   // this send only
```

Unset fields are left out of the request, so the GGUF's recommended settings and llama-server's defaults apply.
Anything without a typed field goes in `extra`, a JSON Merge Patch over the request body:
`extra = """{"n_probs": 5}"""`.

## Without a conversation

```kotlin
// one stateless chat request (OpenAI shape)
model.chat(ChatRequest {
    messages(Message.system("Be brief."), Message.user(Content.Text("What is this?"), Content.Image(jpegBytes)))
    tools(Tool.function("get_weather", "…", schema))
    sampling { temperature = 0.2f }
}).collect { event -> … }

// any llama-server route with its JSON, exactly as llama-server's docs describe it
model.rawRequest(Endpoint.Infill, """{"input_prefix": "def add(a, b):\n    ", "input_suffix": "\n"}""").collect { … }
```

`Endpoint` has a constant for every route llama-server serves for one model: chat, completion, infill, the
Responses and Anthropic Messages formats, token counting, embeddings, rerank, TTS, tokenize, slots, metrics, LoRA
adapters and more. Audio transcription is not there yet.

## Errors

Everything the SDK throws is a `LlamaBroException`, with the underlying error as its `cause`:
`InvalidRequest`, `ContextExceeded` (prompt and context sizes), `NotSupported`, `Unavailable`, `ServerError`,
`ModelLoadFailed` (`reason` `FileNotFound` or `Other`, with llama.cpp's own reason in the message), `ModelClosed`,
`CompactionTruncated`, `Busy` (a second send on a busy conversation) and `Incomplete` (from `ask`).

## Testing your app

Add `testImplementation("io.github.whyisitworking:llamabro-testing")` and script the replies. Your code runs
against the real `Model` and `Conversation` (the tool loop and compaction included) on the JVM, with no phone and
no model:

```kotlin
val fake = FakeLlamaBro {
    reply { reasoning("…"); content("Hello!") }
    reply { toolCall("get_weather", """{"city":"Paris"}""") }
    reply { content("It is sunny.") }
    contextExceeded(promptTokens = 9000, contextSize = 8192)
}
val vm = ChatViewModel(fake.llamaBro, "/any.gguf")
…
assertTrue(fake.chatRequests[1].contains("\"role\":\"tool\""))
```

Tool handlers can be tested alone with `JsonArgs.parse("""{"city":"Paris"}""")`.

## Loading

```kotlin
llama.loadModel(ModelSource.file(path)) {
    contextSize = ContextSize.Fixed(8192)    // default ContextSize.Auto, see "Context size"
    onProgress { fraction -> … }
}
```

`model.info` gives the context size, vision and audio support, template capabilities and the GGUF's metadata.
`model.close()` returns at once; `closeAndJoin()` waits until the memory is freed. Loading the next model waits for
a previous close, so two models never share memory. Configure the process-wide runtime (log level, logger) by
implementing `LlamaBro.ConfigProvider` on your `Application`.

Three more load settings, each a llama-server flag:

```kotlin
// an embedding model for search or RAG: answers Endpoint.Embeddings (OpenAI shape) and nothing else
val embedder = llama.loadModel(ModelSource.file(bgePath)) { task = ModelTask.Embedding(Pooling.Mean) }
embedder.rawRequest(Endpoint.Embeddings, """{"input": ["first passage", "second passage"]}""").collect { … }

// a reranker: Endpoint.Rerank with {"query": …, "documents": [...]}
llama.loadModel(ModelSource.file(rerankerPath)) { task = ModelTask.Rerank }

// a context cache in 8 bits: about half the memory, so ContextSize.Auto fits about twice the tokens
llama.loadModel(ModelSource.file(path)) { kvCacheType = KvCacheType.Q8_0 }

// LoRA adapters for this base model; a request's "lora" field ([{"id": 0, "scale": 0.5}]) changes a scale
llama.loadModel(ModelSource.file(path)) { loraAdapters = listOf(LoraAdapter(adapterPath, scale = 1f)) }
```

A chat model and an embedding model can be loaded at the same time, each with its own `Model`. Small embedding
models run on the NPU with the same vectors as the CPU; a reranker there still picks the right top documents, but its
near-zero scores move, so load it with `DevicePolicy.Only(DeviceKind.Cpu)` if the full order matters.

## Downloading from Hugging Face

Add the optional Hub library to resolve and cache a model before loading it:

```kotlin
implementation("io.github.whyisitworking:llamabro-hub")
```

```kotlin
val hub = LlamaBroHub.getInstance(application)
val ref = HfModel("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
val plan = hub.plan(ref)                         // selected GGUF files and their total size
val fit = hub.estimateFit(plan, llama)           // bounded headers only; no weights downloaded
hub.download(ref).collect { progress ->         // bytes, file, speed, ETA and phase
    showProgress(progress.bytesDone, progress.bytesTotal)
}
val source = hub.source(ref)                     // local ModelSource, including projector or draft
val model = llama.loadModel(source)
```

`download` resumes a partial transfer after the process restarts and verifies each file's published digest before
putting it in the cache. `hub.progress(ref)` gives a `StateFlow<DownloadState>` for any screen in the process. Cache
files live in the app's private `noBackupFilesDir/llamabro/hub` by default; they stay across app restarts and cannot
be read by other apps. Set `cacheDir` in `HubConfig` to another app-private directory if you want a specific location.

An app owns scheduling and the notification. The [background download sample](samples/background-download) uses a
`CoroutineWorker` with a foreground notification and returns `Result.retry()` for retryable Hub errors:

```kotlin
override suspend fun doWork(): Result = try {
    setForeground(foregroundInfo())
    hub.download(HfModel(inputData.getString("ref")!!)).collect { update ->
        setForeground(foregroundInfo(update))
    }
    Result.success()
} catch (error: HubException) {
    if (error.retryable) Result.retry() else Result.failure()
}
```

Configure a private token provider on your `Application`; the Hub calls it before each plan or download and sends
the token only to the Hub endpoint, never to a redirect target:

```kotlin
override val llamaBroHubConfig = HubConfig {
    token { tokenStore.read() }
}
```

`HubException` distinguishes network failures, unauthorized access, missing files, rate limits, insufficient
storage and integrity failures. Its `retryable` flag tells a background worker whether another attempt can help.

## Context size

The context is how many tokens the model sees at once: the conversation so far plus the reply. Its cache is reserved
when the model loads (fully on the GPU and NPU, as it fills on the CPU), so it costs memory up front.

```kotlin
llama.loadModel(ModelSource.file(path))                                          // ContextSize.Auto
llama.loadModel(ModelSource.file(path)) { contextSize = ContextSize.UpTo(65536) } // Auto, with your ceiling
llama.loadModel(ModelSource.file(path)) { contextSize = ContextSize.Fixed(8192) } // exactly this
```

- **`Auto`** (the default) picks the largest context up to **16 384 tokens** that fits in the memory available when
  the model loads, and at least 4096. "Fits" is llama.cpp's own projection of what the model, its cache and its
  compute buffers take on the chosen chip, against the kernel's available memory less Android's low-memory threshold,
  the prompt cache (`ramCacheMib`), an image encoder and a reserve for what grows during a chat (`memoryReserveMib`,
  512 MiB). Phones with less free memory get a smaller context. If a model is just short, a smaller prompt cache is
  the safe trade; lowering the reserve is possible, but then a long chat can grow into memory Android needs, and its
  low-memory killer ends the app without an exception.
- **Why 16 384:** common local defaults are 4096 tokens (Ollama, LM Studio, Apple's on-device model). Current models
  spend 1–3K tokens a turn on reasoning and tool results, so four times that leaves room for a real conversation,
  while a dense 3B model's cache stays near 1.8 GB. `Conversation` compacts a chat that outgrows it.
- **`UpTo(n)`** does the same with your ceiling, for long documents; **`Fixed(n)`** takes exactly `n` and fails if it
  can't be allocated.
- If even 4096 tokens don't fit, `loadModel` throws `ModelLoadFailed` with reason `InsufficientMemory` and a message
  with the numbers. `model.loadReport.contextSize` and `contextReason` say what was picked and why, e.g.
  "the ceiling of 16384 tokens fits: 4527 MiB of 5291 MiB available".

## Faster chips

Add a backend artifact and the SDK uses it; nothing else to configure:

```kotlin
implementation("io.github.whyisitworking:llamabro-hexagon")   // the NPU on Snapdragon phones
implementation("io.github.whyisitworking:llamabro-opencl")    // the GPU on Adreno
```

By default (`DevicePolicy.Auto`) a model loads on the NPU, else the GPU, else the CPU, skipping any that fails.
`model.loadReport` says where it runs and why the others weren't used; `llama.backends()` lists what loaded.

```kotlin
llama.loadModel(ModelSource.file(path)) {
    devicePolicy = DevicePolicy.Only(DeviceKind.Gpu)   // or Prefer(…); Only throws DeviceUnavailable instead
}
```

On the OnePlus 13 the NPU loads Qwen3.5 4B in about 6 s. The GPU reads prompts about 3× faster than the CPU but
takes about 15 s to load, and about a minute the first time after install while the driver compiles its programs
(cached in the app's cache directory).

Multi-token prediction (a model's MTP head guessing the next tokens) is off by default. With Qwen3.5 4B on the NPU
it makes JSON and tool-call output about 1.5× faster, but prose slower, and it slows prompt reading a little. If
your app mostly produces structured output (an agent, tool calls, JSON), turn it on:

```kotlin
llama.loadModel(ModelSource.file(path)) { speculative = Speculative.Mtp(draftMax = 4) }
```

It is a load setting (llama-server can't switch it per request). Not worth it on the CPU (no gain). Never on the
GPU: any drafting, MTP or n-gram, aborts llama.cpp's OpenCL backend, so with either set `DevicePolicy.Auto` skips the
GPU (`loadReport` says why) and `DevicePolicy.Only(DeviceKind.Gpu)` throws `DeviceUnavailable`.
On the NPU it changes replies slightly: a greedy reply with MTP isn't always word for word the one without it (the
NPU checks drafted tokens in a batch, with a little different rounding), though it reads just as well. n-gram
drafting, below, keeps replies identical.

n-gram drafting needs no extra model: it looks the last few tokens up in the prompt and earlier replies and guesses
that what followed them comes again. When a reply copies its input (code edits, rewrites, quoting), it is much
faster: a code edit with Qwen3.5 4B decoded about 7× faster on the NPU and 2.3× on the CPU, with the same reply. Prose
is unaffected, but on the CPU a reply that only partly repeats its input can be 10–15% slower. On the NPU it combines
with MTP (n-grams first, MTP when they have nothing):

```kotlin
llama.loadModel(ModelSource.file(path)) { speculative = Speculative.Ngram() }
llama.loadModel(ModelSource.file(path)) { speculative = Speculative.Ngram(then = Speculative.Mtp(draftMax = 4)) }
```

## How it works

The SDK runs llama-server's `server_context` and calls its route handlers directly (no sockets): same output,
speed and memory as the `llama-server` binary. `sdk/scripts/check.sh` proves it on every change by sending the
same requests to both and requiring identical answers. Design, decisions and measurements:
[docs/sdk-v2](../docs/sdk-v2/README.md).
