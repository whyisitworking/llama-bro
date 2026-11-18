# LlamaBro SDK

Run a GGUF model on an Android phone with llama.cpp's own server core in-process: the same chat handling,
templates, tool calls, reasoning, prompt cache and sampling as `llama-server`, without HTTP. Kotlin only
(coroutines and `Flow`). Package `com.suhel.llamabro`.

> `implementation("io.github.whyisitworking:llamabro:2.0.0-alpha")` from Maven Central, plus `llamabro-hexagon` and
> `llamabro-opencl` for the NPU and GPU, and the other modules as needed (the same group and version).
> arm64-v8a, Android 10 (API 29) and up. Kotlin 2.2 or later: the SDK is built with Kotlin 2.3, whose metadata a
> 2.2 compiler still reads (D82). `llamabro-typed-ksp` needs KSP 2.3 or later.

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
            val m =
                llama.loadModel(ModelSource.file(path)) {
                    onProgress { if (it is LoadProgress.Loading) loading.value = it.fraction }
                }
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

// in the factory: ChatViewModel(LlamaBro.create(application), path)
```

`Conversation` keeps the history the way llama.cpp's web UI (`tools/ui`) does. Observe:

- `messages`: the history; `reply`: the assistant message while it streams, null otherwise.
- `isBusy`, and `contextUsage` (tokens used of the context).
- `ask(text)` returns a `Typed<String>`: `Typed.Value` (the text and the reasoning), or `Typed.Incomplete` with its
  `reason`: `Length` (cut at the output limit), `TurnLimit`, `ToolCallsPending` (continue with `resume()`, after
  `addToolResult` for tools without a handler) or `ToolAborted` (a tool threw `LbToolAbort`). `valueOrNull()` gives the
  value or null.
- `regenerate()` (and `askAgain()`, the same collected as `ask` is), `endReasoning()` (stop thinking now), `toJson()`
  and `model.restoreConversation(json)`.

The demo chat app ([demo](../demo)) is this, with a full UI; its `ChatEngine` is the part that talks to `Conversation`.

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
Responses and Anthropic Messages formats, token counting, embeddings, rerank, transcriptions, TTS, tokenize, slots,
metrics, LoRA adapters and more. `rawRequest` sends JSON only, so a transcription goes through `transcribe` (below).

## Pictures and speech

A model loaded with its encoder (`ModelSource.file(path) { mmproj = encoderPath }`) sees pictures
(`model.info.supportsVision`), hears audio (`model.info.supportsAudio`), or both. Pictures and clips come from a
`MediaSource` (D84), however the app holds them:

- `MediaSource.file(path)`: a file on the phone, read natively on every request that sends it, so it must stay there;
- `MediaSource.descriptor(pfd)`: an open `ParcelFileDescriptor`, such as a `content://` URI from the photo picker or a
  recorder, read natively from its start and left open for the app to close;
- `MediaSource.bytes(bytes)`: bytes in memory, copied once into native memory.

A file or descriptor never enters the Java heap, and Kotlin never writes base64: the request carries a placeholder and
the native side writes the data URL llama-server reads. In a conversation a descriptor is read once into bytes when its
message is added, off the main thread; later turns and `toJson` use those bytes, so the app may close the descriptor as
soon as the message is in `messages`, and a descriptor in a `history` you start a conversation from is read once, by its
first request or by `toJson`, whichever comes first. A file must exist when its message is added (otherwise
`InvalidRequest` naming the path, and nothing is added), and a descriptor must be open until then: a closed one is
`InvalidRequest` too. `toJson` saves a file as its path (restore it on the same phone) and bytes as base64. If a
restored chat's file is gone, its requests fail with `InvalidRequest` naming the path; start a conversation from its
`messages` with that part removed or replaced (`model.conversation(system, history = …)`). Each turn re-sends the whole
history, earlier media included; the server's cache keeps the model from re-reading it, but its decoding repeats.

```kotlin
// a voice note: ask about it in a chat…
context.contentResolver.openFileDescriptor(voiceNoteUri, "r")!!.use { pfd ->
    chat.send("Reply to this voice note.", Content.Audio(MediaSource.descriptor(pfd), "audio/wav")).collect { … }
}
// …or only write down what it says
when (val text = model.transcribe(MediaSource.file(recording.path), language = "en")) {
    is Typed.Value -> showTranscript(text.value)
    is Typed.Incomplete -> showTranscript(text.json + "…")   // cut at maxTokens
    is Typed.Invalid -> Unit                                  // never for a transcript
}

chat.send("What is in this picture?", Content.Image(MediaSource.file(photo.path), "image/png"))
```

Audio must be WAV, MP3 or FLAC (`audio/wav`, `audio/mpeg`, `audio/flac`), the formats llama.cpp decodes; the SDK
converts nothing, so an app that records AAC or Opus records WAV instead (`AudioRecord` gives PCM) or converts first.
Pictures are decoded by llama.cpp's stb_image: JPEG, PNG, BMP and GIF (its first frame), and the rarer TGA, PSD, HDR,
PIC and PNM. HEIC, the iPhone's and many Android cameras' format, is not supported, and neither is WebP (upstream
decodes it only through an `ffmpeg` binary, which an Android app doesn't have): ask the photo picker or camera for
JPEG, or convert first (`Bitmap.compress`). Another audio format (by MIME type, and by the first bytes of a file or
bytes, and of a descriptor's, which is read before its message is added), a missing file, an unreadable descriptor, or
a picture or clip for a model that can't see or hear it throws `InvalidRequest` naming the problem, before anything is
added to the chat. A picture only llama.cpp can judge (HEIC or WebP) fails the send's first request with
`InvalidRequest`; then the message is taken out of the chat again, so the next send works. Any other failure keeps the
message, for `resume()`. `transcribe(source, language,
prompt, options)` returns `Typed.Value(text)`, or `Typed.Incomplete(Length, textSoFar)` when cut at `maxTokens`;
`prompt` is upstream's (words to expect, or an instruction in place of the model's own), and `options`' sampling
applies.

### A spoken conversation

A model that listens and talks (LFM3-Omni) holds a spoken conversation through upstream's `/v1/realtime`, run in
memory (no port). The app sends the microphone's audio and ends each turn; the reply comes as text, for captions, and
as speech:

```kotlin
val voice = model.voiceSession {
    voice = "female"
    language = "en"
    instructions = "You are a helpful voice assistant. Answer the user's spoken question in your own words."
    maxOutputTokens = 1024
}
launch {
    voice.events.collect { event ->
        when (event) {
            is VoiceEvent.ReplyText -> captions.append(event.delta)
            is VoiceEvent.ReplyAudio -> track.write(event.pcm, 0, event.pcm.size) // AudioTrack at voice.outputSampleRate
            is VoiceEvent.ReplyDone -> saveTurn(event.text)
            is VoiceEvent.Error -> showError(event.message)
            else -> Unit
        }
    }
}
// push-to-talk: while the button is held, 16-bit mono PCM at voice.inputSampleRate
voice.append(buffer, 0, read)
voice.commit()       // on release: ends the turn and asks for the reply
voice.cancelReply()  // the user taps stop
voice.close()        // hang up
```

`model.info.voices` lists the voices a model speaks with (`supportsSpeech` when there are any). The SDK sets nothing in the session for you: without a voice, a language and an instruction to answer, LFM3-Omni can
speak noise for minutes or read the question back (F166). Upstream sends no transcript of the user's words in a
conversation; `transcribeUser { language = "en" }` adds one per turn (`VoiceEvent.UserTranscript`, after the turn's
`TurnCommitted`), transcribed on a second slot while the reply is made, so load the model with `parallelSlots = 2`. On
the phone it costs about 0.3 s before the reply's first audio (F170). Other realtime events arrive as `VoiceEvent.Other`, and `sendRaw` sends any client event.

## Embeddings and rerank

An embedding model turns text into vectors and a reranker scores documents against a query, each loaded for its task
(D65). Private semantic search is a few calls; the similarity is yours to write, here a cosine:

```kotlin
val embedder = llama.loadModel(ModelSource.file(bgePath)) { task = ModelTask.Embedding(Pooling.Mean) }
val passages = listOf("The cat sleeps on the sofa.", "Stocks fell on Monday.", "Kittens nap a lot.")
val vectors = embedder.embed(passages)                      // one FloatArray per text, in order
val query = embedder.embed("Where do cats rest?")

fun cosine(a: FloatArray, b: FloatArray): Float {
    var dot = 0f; var na = 0f; var nb = 0f
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    return dot / kotlin.math.sqrt(na * nb)
}
val best = passages.indices.sortedByDescending { cosine(query, vectors[it]) }.map { passages[it] }

// then let a reranker order the best few, best first
val reranker = llama.loadModel(ModelSource.file(rerankerPath)) { task = ModelTask.Rerank }
reranker.rerank("Where do cats rest?", best, topN = 2)       // [Ranked(index=…, score=…), …]
```

Vectors are unit length by default (`Normalization.Euclidean`, llama-server's `embd_normalize` 2), so a dot product
alone would do; `None`, `MaxAbsInt16`, `Taxicab` and `PNorm(p)` are upstream's others. `embed(Content.Text(…),
Content.Image(…))` reads text and a picture as one input on a model whose embeddings take pictures; one that can't
throws `InvalidRequest` with llama-server's reason. Pictures and clips come from a `MediaSource` and are sent as in chat
(above): a file or descriptor is read natively, and a clip gets chat's checks. Each input, and each query with a
document, is read in one batch: past `microBatchSize` (512 tokens) the call throws `InputTooLong(tokens, limit)`, and
the SDK neither splits the text nor raises the batch. Calling `embed` or `rerank` on a model loaded for another task
throws `InvalidRequest` naming the task to load with. Per-token vectors (`Pooling.None`) stay raw, through
`Endpoint.EmbeddingsNative`.

## Typed output and tools

Typed replies and typed tools are an optional library over kotlinx.serialization (D63):

```kotlin
implementation("io.github.whyisitworking:llamabro-typed")
```

`typedSchema<T>()` writes a `@Serializable` class's JSON Schema, the one llama.cpp's grammar holds a reply to.
Properties keep their declaration order, which is the order the model writes them in, required ones first: declare a
reasoning field before the answer. A property with a default is optional. An integer is held to its Kotlin type's
range (`Byte`, `Short`, `Int` and the unsigned types; a `Long` is unbounded), so the model can't write a number that
doesn't decode (D88). Add `@LbDescription`, `@LbRange` (integers, intersected with the type's range), `@LbLength` and
`@LbPattern` (strings) and `@LbItems` (lists); decimals take no bounds, since the grammar ignores them.

`@LbPattern` is sent as written, and llama.cpp turns only a subset of regex into grammar (F158). A pattern must be
anchored (`^…$`) and use literal characters, `.`, classes such as `[a-z]`, groups, `|` and repetition. Anything else
(an unanchored pattern, `\d`, `\w`, `\p{…}`, lookarounds, POSIX classes) is dropped: the field takes any string, and
only llama.cpp's log says so. A malformed pattern (unbalanced parentheses) fails the request with `InvalidRequest`; for
a typed tool's arguments that is every request in the conversation, since the tools are sent with each one.
`assertTypedSchema` can't check a pattern: write `[0-9]` for `\d`, and test against a real model.

A sealed subclass needs a short `@SerialName`. Call `assertTypedSchema<T>()` in a unit test to find what the schema
refuses (a recursive type, open polymorphism, a contextual serializer, a free-form `JsonElement`, a non-`String`
map key) before release.

```kotlin
@Serializable
data class Review(
    val reasoning: String,
    @LbRange(min = 1, max = 5) val stars: Int,
    @LbDescription("At most three") @LbItems(max = 3) val tags: List<String> = emptyList(),
)
```

Ask for a reply as that class with `ask<T>` in a conversation, or `generate<T>` for one reply with no history. The
model writes the fields in the order they are declared, so `reasoning` comes before `stars`: the model works out its
verdict in words first and the number follows from it (F143). Declared after, the number would be written first and
the reasoning would only justify it.

```kotlin
when (val r = chat.ask<Review>("Review: the battery lasts two days, but the screen scratches easily.")) {
    is Typed.Value -> show(r.value)           // r.json is the model's JSON, r.reasoning its thinking (Think on)
    is Typed.Incomplete -> retry(r.reason)    // cut at the output limit (Length), with r.json so far
    is Typed.Invalid -> report(r.cause)       // the JSON didn't decode: a failing require or check in init
}
val again = chat.askAgain<Review>()          // the last reply again, held to the same schema
val review = model.generate<Review>(text, system = "You review products.").valueOrNull()
```

The schema is checked before any request, so a type without one throws `IllegalArgumentException` at once; so do
`options` that set a `responseFormat`. A `responseFormat` in the conversation's defaults is replaced by the typed
call's, while one set in this call's `options` is refused. Think on works, but the grammar applies only after the
thinking, so the model thinks without knowing the format: it reasons as if replying to a chat. With no budget, Qwen3.5
4B thought for 114 s on the phone before writing correct JSON, and 0.8B ran out of tokens (F151). For typed calls,
turn Think off or set a thinking budget (`reasoning { budgetTokens = 256 }`), and phrase the prompt as the task
("Extract the person from: …"); a reasoning field in the class gives the model room to think inside the JSON. A typed turn keeps the conversation's tools in the prompt but cannot call them, and stores the JSON as
the assistant message, so later turns see what the model wrote. Pass `serializer =` and `json =` to use a
`KSerializer` or a `Json` of your own; the schema and the decoding both follow that `Json`. `askAgain<T>()` replaces the
last reply as `regenerate()` does, under the same schema; a plain `regenerate()` after a typed `ask` sends no schema,
since a call's options are its own (D88).

A typed tool takes a `@Serializable` arguments class and returns a value. Its parameters' schema is the arguments
class's `typedSchema`, so the same annotations describe and bound them. The handler is a suspending lambda that
captures whatever your app injected; register tools one by one or as a `Toolbox`:

```kotlin
@Serializable
data class CityArgs(@LbDescription("City name, e.g. Paris") val city: String, val days: Int = 1)

@Serializable
data class Forecast(val city: String, val sky: String, val highC: Int)

val forecast = tool<CityArgs, Forecast>("get_forecast", "Weather forecast for a city; highC is in Celsius") { args ->
    weatherApi.forecast(args.city, args.days)  // `this` is the call: callId, toolName, rawArguments
}
val sendEmail = tool<EmailArgs, Unit>("send_email", "Sends an email", requiresApproval = true) { mail.send(it) }

val chat = model.conversation { toolbox(toolboxOf(forecast, sendEmail)) }
```

Inside `conversation { }`, `tool<A, R>(name, description) { … }` makes the tool and registers it in one step.

A `String` result is sent as is (a null `String?` as `null`), `Unit` or `Unit?` as `Done`, anything else as JSON with
every field, also those at their default (D88); the model sees no schema for a result, so say what it means in the
description. Arguments that don't decode send
`Error: invalid arguments: <reason>` and the loop goes on, so the model can retry, but only kotlinx-serialization's own
decode errors (`missing field 'days'`, bad JSON, a custom serializer's `SerializationException`) give their reason.
Anything else thrown while decoding, such as a `require` or `check` in the arguments class, sends
`Error: invalid arguments` and reaches the app as `ChatEvent.ToolResult.error`. An app
that wants to explain throws `LbToolError` from its validation, and its message is what the model reads:
`init { if (days !in 1..7) throw LbToolError("days must be 1-7") }` sends `Error: days must be 1-7`, so write it for
the model and keep secrets out of it. What the handler throws is handled as for a raw tool. `tool(…)` throws
`IllegalArgumentException` for a name that doesn't match `^[a-zA-Z0-9_-]{1,64}$`, a blank description, or arguments
that aren't an object. The handler runs where the agent loop calls it: move blocking work to `Dispatchers.IO`.

### Annotated toolboxes

Tools can also be methods of a class of yours, written once with annotations and checked when the app builds (D61).
Add the KSP processor and the kotlinx.serialization compiler plugin, which compiles the arguments classes it writes:

```kotlin
plugins {
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation("io.github.whyisitworking:llamabro-typed")
    ksp("io.github.whyisitworking:llamabro-typed-ksp")
}
```

Mark the class `@LbToolbox` and each tool `@LbTool("description")`. A method takes one `@Serializable` arguments class
(where real defaults apply), or parameters that each carry `@LbToolParam` (a nullable `= null` is optional); an
`LbToolCallContext` parameter gets the call and stays out of the schema. A separate parameter's type must be one
kotlinx.serialization writes (a primitive, `String`, an enum, a `@Serializable` class, a built-in collection of them),
or carry a type-use `@Serializable(with = …)`, which the generated class keeps; `@SerialName` can't go on a parameter
(Kotlin allows it on properties only), so a JSON name other than the parameter's needs the arguments class. Bounds
(`@LbRange`, `@LbLength`, `@LbPattern`, `@LbItems`) and defaults other than `null` need the arguments class too. The name is the method's in snake_case
unless `@LbTool(name = …)` gives one, after the toolbox's `prefix`. A `suspend` method runs where the loop calls it;
any other runs on `Dispatchers.IO`, so a blocking call is safe.

```kotlin
@LbToolbox
class AssistantTools @Inject constructor(private val weather: WeatherApi, private val mail: Mailer) {
    @LbTool("Weather forecast for a city; highC is in Celsius")
    suspend fun getForecast(
        @LbToolParam("City name, e.g. Paris") city: String,
        @LbToolParam days: Int? = null,
    ): Forecast = weather.forecast(city, days ?: 1)

    @LbTool("Sends an email", requiresApproval = true)
    fun sendEmail(args: EmailArgs) = mail.send(args)  // blocking: runs on Dispatchers.IO
}
```

The build writes `fun AssistantTools.asToolbox(json: Json = Json): Toolbox` beside it, with the class's visibility, so
you register the tools of an instance you built, or one your DI container provides. The SDK constructs nothing:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object ToolsModule {
    @Provides
    fun toolbox(tools: AssistantTools): Toolbox = tools.asToolbox()
}

val chat = model.conversation { toolbox(toolbox) }
```

The `@LbTool` methods of `@LbToolbox` supertypes are included, each once; an override implements a tool and must not
be annotated again. Each misuse is a build error naming the fix: a blank description, a name that doesn't match
`^[a-zA-Z0-9_-]{1,64}$` with its prefix, two tools with one name, an annotated override, a default other than `= null`
on a separate parameter, a parameter without `@LbToolParam` beside annotated ones or a lone one that isn't a
`@Serializable` class, a separate parameter of a type kotlinx.serialization can't write, `@LbTool` outside an
`@LbToolbox`, a sealed subclass without a short `@SerialName` in the arguments, and a result that isn't a `String`,
`Unit`, a primitive, a `List` or `@Serializable`. The build doesn't check the schema itself: what `typedSchema` refuses
in an arguments class (a recursive type, a `JsonElement`, an annotation on the wrong type) throws
`IllegalArgumentException` when `asToolbox()` runs, so call `asToolbox()` (or `assertTypedSchema` on each arguments
class) in a unit test.

## Errors

Everything the SDK throws is a `LlamaBroException`, with the underlying error as its `cause`:
`InvalidRequest`, `ContextExceeded` (prompt and context sizes), `InputTooLong` (an embedding or rerank input past the
batch), `NotSupported`, `Unavailable`, `ServerError`,
`ModelLoadFailed` (`reason` `FileNotFound` or `Other`, with llama.cpp's own reason in the message), `ModelClosed`,
`CompactionTruncated`, and `Busy` (a second send on a busy conversation).

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

`embed` and `rerank` take their answers from their own queue: `embeddings(vector, …)` gives one vector per input,
`ranking(score, …)` one score per document in the documents' order (answered best first and cut at `topN`, as
llama-server does), and `inputTooLong(tokens)` the batch error. `embedRequests` and `rerankRequests` keep the bodies.
`transcribe` takes `transcript(text, cut = false)` from the same queue, and `transcribeRequests` keeps its fields. A
voice session answers each asked-for reply with the next `voiceReply { text("…"); audio(pcm) }`, and `voiceEvents`
keeps every client event it was sent; with `transcribeUser`, each turn's caption takes the next `transcript(text)` (load
with `parallelSlots = 2`). The
fake model neither sees nor hears until you set `supportsVision` or `supportsAudio` before loading, as a text model
on the phone refuses pictures and clips.

With the hub, add `testImplementation("io.github.whyisitworking:llamabro-hub-testing")`. `FakeLlamaBroHub` is the real
`LlamaBroHub` over a scripted Hugging Face Hub and a temporary folder, so search, files, downloads, `hasUpdate`,
recommended sampling and the installed copy behave as in the app, with no network (D70, D75):

```kotlin
val fakeHub = FakeLlamaBroHub {
    downloadMillis = 400                     // pace downloads so their progress shows
    repo("unsloth/Qwen3-4B-GGUF") {
        baseModel = "Qwen/Qwen3-4B"          // search groups uploads under it
        downloads = 900
        gguf("Qwen3-4B-Q4_K_M.gguf", sampling = Sampling { temperature = 0.7f })
        gguf("mmproj-F16.gguf")
    }
    repo("Qwen/Qwen3-4B") { generationConfig("""{"temperature":0.6,"top_k":20}""") }
}
val fake = FakeLlamaBro { reply { content("Hello!") } }
val vm = ModelsViewModel(fakeHub.hub, fake.llamaBro)
…
fake.llamaBro.loadModel(fakeHub.hub.model("unsloth/Qwen3-4B-GGUF:Q4_K_M"))   // downloads, then loads
fakeHub.offline = true                       // HubException.Network from here on
```

A repo that isn't declared is `NotFound`, a `gated` one `Unauthorized`; `rateLimit(…)`, `offline` and `freeBytes` give
`RateLimited`, `Network` and `InsufficientStorage`. Declaring a repo again publishes a new commit, which `hasUpdate`
reports, and `requests` lists every request the hub made. `stopDownload(model, "mmproj-F16.gguf")` leaves a download
as a Pause leaves it on the phone: the files before that one in the cache, that one half received, nothing installed;
`cached()` leaves it out, `source` throws `NotFound`, and a `download` resumes it. A copy installed before stays listed
and stays the one that loads. Pass `repo =` for a draft from another repo.

## Loading

```kotlin
llama.loadModel(ModelSource.file(path)) {
    contextSize = ContextSize.Fixed(8192)    // default ContextSize.Auto, see "Context size"
    onProgress { p -> … }                    // LoadProgress.Loading(fraction); Fetching(download) for a remote source
}
```

A `RemoteModelSource` (the hub's, or an app's own) is fetched first, reporting `LoadProgress.Fetching`, and its local
files then load as any file would; `estimateFit` on one reads only its headers (D64, D75).

`model.info` gives the context size, vision and audio support, template capabilities and the GGUF's metadata.
`model.close()` returns at once; `closeAndJoin()` waits until the memory is freed. Loading the next model waits for
a previous close, so two models never share memory. Configure the process-wide runtime (log level, logger) with
`LlamaBro.create(context, config)`. The logger is attached before native initialisation, so
llama.cpp and ggml lines reach it from the first call; llama-server's own lines (`SRV_*`, `SLT_*`) do not yet, because
they go to stderr, which Android discards (F22). With none, the core logs to logcat as `llamabro` and the hub as
`llamabro-hub`, at `Warn` and above by default; to silence them, pass a logger that drops lines (D66).

### Dependency injection

`LlamaBro.create(context, config)` is the entry point, and there is one runtime per process. The first call creates it;
a later call with an equal config returns the same object, and a different config throws `InvalidRequest` at once (D70).
A logger is compared by reference, so create the config once. A DI container's singleton scope does that; with Hilt:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object LlamaBroModule {
    @Provides
    @Singleton
    fun llamaBro(@ApplicationContext context: Context): LlamaBro =
        LlamaBro.create(context, LlamaBroConfig { logLevel = LogLevel.Info })
}
```

The config also holds `npuPolling`, off by default as in upstream: `LlamaBroConfig { npuPolling = true }` has the NPU
backend poll for the NPU's results instead of sleeping, which keeps the waiting CPU core awake. Chat on the NPU runs 4-7%
faster and speech about 30% faster, but the chip runs hotter while busy (D86, D90). Patch 0007 keeps the polls from
flooding logcat with the DSP library's errors (F162).

The SDK has no DI dependency; without a container, call `LlamaBro.create(context)` where you need it. Keep the config
on your `Application` or in the DI graph and pass it from every entry point, workers and job services included: a
fresh process that calls `create(context)` with the default config throws `InvalidRequest` if the UI's process used
another, or logs through a different logger.

Three more load settings, each a llama-server flag:

```kotlin
// an embedding model for search or RAG: embed(…), or Endpoint.Embeddings raw, and nothing else
val embedder = llama.loadModel(ModelSource.file(bgePath)) { task = ModelTask.Embedding(Pooling.Mean) }

// a reranker: rerank(query, documents), or Endpoint.Rerank raw
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
val hub = LlamaBroHub.create(application)
try {
    val fit = llama.estimateFit(hub.model("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"))   // headers only before a download
    val model = llama.loadModel(hub.model("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"))   // downloads if needed, then loads
} catch (e: HubException) {
    // the source's own failure, unchanged: Network (offline with nothing installed), Unauthorized (a gated repo),
    // NotFound (a removed repo or tag), InsufficientStorage; e.retryable says whether trying later can help
}
```

`hub.model(ref)` loads the installed copy (the last download that completed) with no network request; with none it
resolves the files as `-hf` does and downloads them, reporting `LoadProgress.Fetching` before `LoadProgress.Loading`.
Looking at the Hub (`hub.plan`) never changes what loads. A newer commit replaces the installed copy only when the app
asks and its download completes: `hub.hasUpdate(ref)`, then `hub.download(ref)`; a cancelled update leaves the installed
copy loading, offline too. Once an update completes, the files only the replaced copy used are removed, so it doesn't
double the storage; files another selection or tag still uses stay. `hasUpdate` is false offline, but an error the Hub
answers with (a refusal, a removed repo, a 5xx) is thrown, so "no update" is never a guess. For a download screen, use
the steps underneath:

```kotlin
val ref = HfModel("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
val plan = hub.plan(ref)                         // selected GGUF files and their total size
hub.download(ref).collect { progress ->         // bytes, file, speed, ETA and phase
    showProgress(progress.bytesDone, progress.bytesTotal)
}
val source = hub.source(ref)                     // the installed copy as a ModelSource, offline only
```

`download` resumes a partial transfer after the process restarts and verifies each file's published digest before
putting it in the cache. `hub.progress(ref)` gives a `StateFlow<DownloadState>` for any screen in the process. Cache
files live in the app's private `noBackupFilesDir/llamabro/hub` by default; they stay across app restarts and cannot
be read by other apps. Set `cacheDir` in `HubConfig` to another app-private directory if you want a specific location.

`LlamaBroHub.create(context, config)` gives one hub per cache folder (D70). The same folder with an equal `HubConfig`
returns the same hub; a different config for that folder throws `IllegalStateException` naming the folder. Hubs on
different folders coexist, so an app can move its models without a restart. A config compares its logger and token
provider by reference, so create it once. In a DI singleton scope, provide the hub next to the runtime:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object LlamaBroModule {
    private val hubConfig = HubConfig { progressInterval = 500.milliseconds }

    @Provides
    @Singleton
    fun llamaBro(@ApplicationContext context: Context): LlamaBro =
        LlamaBro.create(context, LlamaBroConfig { logLevel = LogLevel.Info })

    @Provides
    @Singleton
    fun llamaBroHub(@ApplicationContext context: Context): LlamaBroHub = LlamaBroHub.create(context, hubConfig)
}
```

A worker or job service started in a fresh process must reach the same config, so keep it where the `Application`
holds it (the [`download` acceptance app](acceptance/download) keeps it as a property) and pass it to `create`.

Configure a private token provider in the `HubConfig` you pass to `LlamaBroHub.create`; the Hub calls it before each
plan or download and sends the token only to the Hub endpoint, never to a redirect target:

```kotlin
// A property, built once: the config compares its token provider by reference (D70).
val hubConfig = HubConfig { token { tokenStore.read() } }
val hub = LlamaBroHub.create(context, hubConfig)
```

`HubException` distinguishes network failures, unauthorized access, missing files, rate limits, insufficient
storage and integrity failures. Its `retryable` flag tells a background worker whether another attempt can help.

### Background downloads

The optional `llamabro-hub-work` runs a download as WorkManager work that outlives the app's process, so the hub
itself doesn't depend on `androidx.work` (D64, D75):

```kotlin
implementation("io.github.whyisitworking:llamabro-hub-work")
```

Register `LlamaBroWorkerFactory(hub)` in WorkManager's configuration, so the worker uses your own hub and its config,
then enqueue:

```kotlin
class App : Application(), Configuration.Provider {
    val hub by lazy { LlamaBroHub.create(this, hubConfig) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(LlamaBroWorkerFactory(hub)).build()
}

val work = WorkManager.getInstance(context)
work.enqueueDownload(HfModel("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"))   // unique per model and options; KEEP while queued
work.cancelDownload(HfModel("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"))    // the partial files stay; enqueue again resumes

// When to run is the app's call: the default is any network
val wifiOnly =
    Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build()
work.enqueueDownload(HfModel("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"), constraints = wifiOnly)
```

Supplying your own configuration means removing WorkManager's default initializer from the merged manifest
(`androidx.work.WorkManagerInitializer`, `tools:node="remove"`), as WorkManager's guide describes. With Hilt, add the
factory next to Hilt's own:

```kotlin
@HiltAndroidApp
class App : Application(), Configuration.Provider {
    @Inject lateinit var hiltWorkerFactory: HiltWorkerFactory
    @Inject lateinit var hub: LlamaBroHub

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(
                DelegatingWorkerFactory().apply {
                    addFactory(LlamaBroWorkerFactory(hub))   // creates only its own worker
                    addFactory(hiltWorkerFactory)
                },
            ).build()
}
```

The work waits for its constraints (by default any network; with charging, idle or battery-not-low it isn't expedited,
which WorkManager refuses with those), runs expedited where the quota allows and as a `dataSync` foreground service, and
retries a retryable `HubException` with exponential backoff. Each try resumes the partial files, and only tries that
move no bytes count: after five of those in a row it fails. Watch it with `hub.progress(ref)`, or through WorkManager
with `downloadWorkName(ref)` and the `DownloadWorkProgress` keys; both the notification and that progress update about
once a second. The module's manifest declares the foreground service type and its permissions, and
`POST_NOTIFICATIONS`. `FOREGROUND_SERVICE_DATA_SYNC` merges into your app's manifest, so an app targeting API 34+ on
Google Play must complete Play Console's foreground service declaration for `dataSync`.

The notifications work with no setup: while it runs, the model's name, a progress bar with the size and the time left,
and Cancel; when it ends, "Downloaded" or "Download failed"; all in a "Downloads" channel the SDK creates. To change
them, subclass `DownloadNotifications` and pass it to the factory:

```kotlin
object MyNotifications : DownloadNotifications() {
    override fun downloading(context: Context, download: RunningDownload): Notification =
        downloadingBuilder(context, download).setSmallIcon(R.drawable.ic_download).setContentIntent(openModels).build()

    override fun finished(context: Context, model: HfModel): Notification? = null   // none
}

LlamaBroWorkerFactory(hub, MyNotifications)
```

On Android 13+ notifications show only once the app holds `POST_NOTIFICATIONS`. The SDK never asks for it: request it
at run time when it suits your app. Without it the download runs all the same, with its notifications hidden.
An app that wants no notification permission at all removes the declaration from its merged manifest, with
`xmlns:tools="http://schemas.android.com/tools"` declared on its `<manifest>` element:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" tools:node="remove" />
```

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
- If even 4096 tokens don't fit, the model loads anyway at 4096 tokens and `model.loadReport.memoryWarning` says how
  much it needed and how much was free (D57): the check can't see the memory Android frees by closing background apps,
  so it doubts models that run. Set `memoryCheck = MemoryCheck.Refuse` to fail with `ModelLoadFailed`
  (`InsufficientMemory`) instead. `model.loadReport.contextSize` and `contextReason` say what was picked and why, e.g.
  "the ceiling of 16384 tokens fits: 4527 MiB of 5291 MiB available".

## Faster chips

Add a backend artifact and the SDK uses it; nothing else to configure:

```kotlin
implementation("io.github.whyisitworking:llamabro-hexagon")   // the NPU on Snapdragon phones
implementation("io.github.whyisitworking:llamabro-opencl")    // the GPU on Adreno
```

By default (`DevicePolicy.Auto`) a model loads on the NPU, else the GPU, else the CPU, skipping any that fails.
`model.loadReport` says where it runs and why the others weren't used; `llama.backends()` lists what loaded.
Only one phone is verified so far: a OnePlus 13 (Snapdragon 8 Elite, Hexagon v79, Adreno 830). On a chip you haven't
tested, a backend that gives wrong output or crashes is not caught by the fallback, so test your target phones or
choose `Prefer` or `Only(DeviceKind.Cpu)` for them (D92).

```kotlin
llama.loadModel(ModelSource.file(path)) {
    devicePolicy = DevicePolicy.Only(DeviceKind.Gpu)   // or Prefer(…); Only throws DeviceUnavailable instead
}
```

A model's image and audio encoder (its `mmproj`) runs on the same chip as the model unless you move it, as
llama-server's `--mmproj-device` does. `loadReport.encoderDevice` says where it was placed:

```kotlin
llama.loadModel(ModelSource.file(path) { mmproj = projectorPath }) {
    encoderDevice = DevicePolicy.Only(DeviceKind.Cpu)   // or Prefer(…): falls back to the model's chip
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
[docs/architecture.md](../docs/architecture.md), [docs/decisions.md](../docs/decisions.md) and [docs/research/findings.md](../docs/research/findings.md).
