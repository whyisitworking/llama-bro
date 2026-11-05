# LlamaBro SDK

Run a GGUF model on an Android phone with llama.cpp's own server core in-process: the same chat handling,
templates, tool calls, reasoning, prompt cache and sampling as `llama-server`, without HTTP. Kotlin only
(coroutines and `Flow`). Package `com.suhel.llamabro`.

> `implementation("io.github.whyisitworking:llamabro:2.0.0-alpha")` from Maven Central, plus `llamabro-hexagon` and
> `llamabro-opencl` for the NPU and GPU, and the other modules as needed (the same group and version).
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
    contextSize = ContextSize.Fixed(8192)    // default ContextSize.Auto: as much as fits
    onProgress { fraction -> … }
}
```

`model.info` gives the context size, vision and audio support, template capabilities and the GGUF's metadata.
`model.close()` returns at once; `closeAndJoin()` waits until the memory is freed. Loading the next model waits for
a previous close, so two models never share memory. Configure the process-wide runtime (log level, logger) by
implementing `LlamaBro.ConfigProvider` on your `Application`.

## How it works

The SDK runs llama-server's `server_context` and calls its route handlers directly (no sockets): same output,
speed and memory as the `llama-server` binary. `sdk/scripts/check.sh` proves it on every change by sending the
same requests to both and requiring identical answers. Design, decisions and measurements:
[docs/sdk-v2](../docs/sdk-v2/README.md).
