<p align="center">
  <img src="assets/llama-bro-banner.webp" width="100%" alt="Llama Bro: the name beside a llama's head drawn in one violet-to-orange line">
</p>

<h3 align="center">Run open models inside your Android app.</h3>

<p align="center">
  A Kotlin SDK built on llama.cpp. Load any GGUF model and chat, call tools, read images, listen and speak,<br>
  on the phone's NPU, GPU or CPU, with nothing sent off the device.
</p>

<p align="center">
  <a href="https://central.sonatype.com/namespace/io.github.whyisitworking"><img src="https://img.shields.io/maven-central/v/io.github.whyisitworking/llamabro?color=7B44F6&label=Maven%20Central" alt="Maven Central"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-7B44F6" alt="License: Apache-2.0"></a>
  <a href="#requirements"><img src="https://img.shields.io/badge/Android-10%2B%20%C2%B7%20arm64-7B44F6" alt="Android 10 and up, arm64"></a>
  <a href="docs/decisions.md#d137"><img src="https://img.shields.io/badge/llama.cpp-b12653-7B44F6" alt="llama.cpp build b12653"></a>
</p>

<p align="center">
  <a href="#highlights">Highlights</a> ·
  <a href="#what-it-can-do">What it can do</a> ·
  <a href="#the-demo-app">Demo app</a> ·
  <a href="#installation">Installation</a> ·
  <a href="#supported-hardware">Hardware</a> ·
  <a href="#performance">Performance</a> ·
  <a href="#under-the-hood">Under the hood</a> ·
  <a href="#roadmap">Roadmap</a> ·
  <a href="#how-it-started">How it started</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/readme/demo-chat-dark.webp">
    <img src="assets/readme/demo-chat-light.webp" width="240" alt="The demo app's chat with Qwen3.5 4B: two Kotlin questions, the second answered with a two-line code block and a short list, above the message bar">
  </picture>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/readme/demo-voice-dark.webp">
    <img src="assets/readme/demo-voice-light.webp" width="240" alt="The demo app's voice mode with LFM3 Omni 2B while it speaks: the orange orb, the spoken question as a caption, and the reply captioned as it is spoken">
  </picture>
  <br>
  <a href="https://github.com/whyisitworking/llama-bro/releases/latest/download/llama-bro-demo.apk"><img src="https://img.shields.io/badge/Download-the%20demo%20APK-F7883F?style=for-the-badge&logo=android&logoColor=white" alt="Download the demo APK"></a>
  <br>
  <sub>The <a href="demo/README.md">demo app</a>, built on the SDK. For arm64 phones on Android 10 and up.</sub>
</p>

## Highlights

- Qwen3.5 4B reads a 2,048-token prompt at **<!-- bench:qwen-npu.promptTps -->934<!-- /bench --> tokens a second** on
  the Snapdragon 8 Elite's NPU, more than 14× its CPU speed. That's llama.cpp's Hexagon backend, which Llama Bro runs
  inside an ordinary app.
- A spoken conversation with LFM3-Omni 2B answers in
  **<!-- bench:voice.extra.firstAudioMs/1000 -->0.38<!-- /bench --> s**, entirely on the phone.
- The engine runs inside your app's process: no server, no port, no API key, and nothing leaves the phone.
- **76 of 76** test requests on the phone's NPU get the same answer, byte for byte, as upstream's `llama-server`.

## What's new

<!-- the last two releases; older ones live in the release notes -->

- **Next release** (on `main`): live dictation with `transcriptionSession`, text to speech with `speak`, llama.cpp
  b12653, and a demo app with glass bars, dictation and read-aloud.
- **2.0.0-alpha** (November 23, 2026): a rewrite on llama-server's own engine, with NPU and GPU backends, on Maven
  Central.

Every change is in the [release notes](docs/release-notes.md).

## What it can do

Each snippet assumes `llama = LlamaBro.create(context)`, `hub = LlamaBroHub.create(context)`, a loaded `model` and
`chat = model.conversation()`. The modules each one needs are under [Installation](#installation), and every group
links to its full guide.

### Get a model

```kotlin
// by its Hugging Face name: downloads once, resumes if cut off, verifies every file, then loads offline
val model = llama.loadModel(hub.model("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"))

// or a file already on the phone
val local = llama.loadModel(ModelSource.file(path))
```

```kotlin
val page = hub.search("qwen")                                       // browse Hugging Face
val fit = llama.estimateFit(hub.model("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"))   // will it fit? reads only the headers

WorkManager.getInstance(context).enqueueDownload(HfModel("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"))   // outlives your app
```

```kotlin
model.loadReport.device     // Npu: it tried the NPU, then the GPU, then the CPU, and took the first that worked
model.loadReport.attempts   // every chip it tried, and why it passed over the others
```

[Loading](sdk/README.md#loading) · [Hugging Face](sdk/README.md#downloading-from-hugging-face) ·
[Background downloads](sdk/README.md#background-downloads) · [Faster chips](sdk/README.md#faster-chips)

### Chat

```kotlin
chat.send("Explain Kotlin coroutines in one paragraph.").collect()   // streams into chat.reply as it's written
val answer = chat.ask("Now show me one.").valueOrNull()             // or wait for the whole reply
```

```kotlin
val long = model.conversation { autoCompact = true }   // older turns are summarised when the context fills up
chat.endReasoning()                                     // stop thinking and answer now

val saved = chat.toJson()                               // store it anywhere
val restored = model.restoreConversation(saved)         // and carry on later
```

[A chat screen in five minutes](sdk/README.md#five-minutes-a-chat-screen) ·
[Every conversation call](sdk/README.md#what-each-call-does)

### Structured output and tools

```kotlin
@Serializable data class Event(val title: String, val day: String, val time: String?)

val event = model.generate<Event>("Dinner with Priya at Olive, Friday 8pm").valueOrNull()
```

llama.cpp's grammar holds the reply to the class's JSON schema, so the model can't write JSON that doesn't fit.

```kotlin
val assistant = model.conversation {
    tool<NoteQuery, List<Note>>("search_notes", "Searches the user's notes") { notes.search(it.text) }
    tool<Reminder, Unit>("add_reminder", "Adds a reminder", requiresApproval = true) { reminders.add(it) }
}
assistant.ask("What did my landlord say about the deposit? Remind me to reply tomorrow.")
```

The conversation calls your function, sends the result back, and keeps going until the model answers. A tool marked
`requiresApproval` stops the turn at `pendingApproval` instead, and `approve` or `deny` carries on.

[Typed output and tools](sdk/README.md#typed-output-and-tools) · [Annotated toolboxes](sdk/README.md#annotated-toolboxes)

### Search

```kotlin
val embedder = llama.loadModel(ModelSource.file(bgePath)) { task = ModelTask.Embedding() }
val reranker = llama.loadModel(ModelSource.file(rerankerPath)) { task = ModelTask.Rerank }

val vectors = embedder.embed(paragraphs)                     // one vector per paragraph
val best = reranker.rerank(question, candidates, topN = 3)   // the closest matches, best first
```

On the NPU it computes <!-- bench:embed-npu.extra.itemsPerSecond -->108<!-- /bench --> embeddings or
<!-- bench:rerank-npu.extra.itemsPerSecond -->227<!-- /bench --> reranks a second, without the text leaving the phone.

[Embeddings and rerank](sdk/README.md#embeddings-and-rerank)

### See and hear

```kotlin
chat.ask("What's the total on this receipt?", Content.Image(photoBytes))
chat.ask("Reply to this voice note.", Content.Audio(clipBytes))

val text = model.transcribe(MediaSource.file(recording.path)).text
```

```kotlin
val dictation = model.transcriptionSession { language = "en" }
dictation.append(pcm, 0, read)   // the words stream back on dictation.events as they're spoken
```

Live dictation and `speak` below are in the next release, on `main`.

[Pictures and speech](sdk/README.md#pictures-and-speech) · [Live transcription](sdk/README.md#live-transcription)

### Speak

```kotlin
model.speak("Your order has shipped.") { voice = "female" }
    .collect { audio -> track.write(audio.pcm, 0, audio.pcm.size) }
```

```kotlin
val voice = model.voiceSession { voice = "female"; language = "en"; instructions = "Answer briefly." }
voice.append(pcm, 0, read)   // while the mic button is held
voice.commit()               // on release: the reply plays as it's spoken, with captions
```

[Speech](sdk/README.md#speech) · [A spoken conversation](sdk/README.md#a-spoken-conversation)

### Go faster

```kotlin
// drafting: code edits and rewrites that copy their input are written up to 8× faster
llama.loadModel(source) { speculative = Speculative.Ngram(then = Speculative.Mtp(draftMax = 4)) }

// the largest context this phone's free memory can hold (the default), or one you choose
llama.loadModel(source) { contextSize = ContextSize.Auto }
```

[Faster chips](sdk/README.md#faster-chips) · [Context size](sdk/README.md#context-size)

### Ship it

```kotlin
// your real Conversation, tool loop included, in a JVM unit test: no phone, no model
val fake = FakeLlamaBro { reply { content("Hello!") } }
val viewModel = ChatViewModel(fake.llamaBro)
```

```kotlin
// and everything else llama-server does, with the JSON its docs describe
val completion = model.rawRequest(Endpoint.Infill, """{"input_prefix": "fun add(a: Int, b: Int) = ", "input_suffix": ""}""")
    .first()
```

`Endpoint` covers llama-server's routes: completion, infill, tokenizing, LoRA adapters, metrics, and the OpenAI Responses and
Anthropic Messages formats.

[Testing your app](sdk/README.md#testing-your-app) · [Without a conversation](sdk/README.md#without-a-conversation)

## The demo app

The [demo app](demo/README.md) is a full chat app built on the SDK, and the quickest way to see what it can do on your
phone. [Download the APK](https://github.com/whyisitworking/llama-bro/releases/latest/download/llama-bro-demo.apk), or
build `:demo:app` from source.

- **Models.** Six suggested models that fit your phone, Hugging Face search that shows how each file will run before
  you download it, and downloads that carry on in the background and resume where they stopped.
- **Chat.** Streaming replies with markdown and code, a fold for the model's thinking, four think levels, pictures, and
  edit and regenerate.
- **Voice.** Hold to talk and hear a spoken reply with captions. Tap the mic to dictate, or hold it to send a voice
  message, and have a reply read aloud.
- **Glass.** Frosted bars that blur the chat scrolling beneath them and light up under your finger, scaled to what your
  phone can draw smoothly.
- **Your setup.** Choose the chip under Settings › Processor, a sidebar of chats or a messaging-style list, and a light
  or dark theme.

The APK is the latest release. Glass, dictation and read-aloud arrive with the next one.

## Installation

### Requirements

- Android 10 (API 29) or later, on arm64-v8a
- Kotlin 2.2 or later, and KSP 2.3 or later for `llamabro-typed-ksp`
- The NPU backend runs on Snapdragon's Hexagon NPU and the GPU backend on Adreno. Without them, or on any other phone,
  models run on the CPU.

### With a version catalog

Llama Bro is on Maven Central. The BOM keeps every module on one version, which the backends need, since they load
native libraries built alongside the core's.

```toml
# gradle/libs.versions.toml
[versions]
llamabro = "2.0.0-alpha"

[libraries]
llamabro-bom        = { module = "io.github.whyisitworking:llamabro-bom", version.ref = "llamabro" }
llamabro-core       = { module = "io.github.whyisitworking:llamabro" }
llamabro-hexagon    = { module = "io.github.whyisitworking:llamabro-hexagon" }
llamabro-opencl     = { module = "io.github.whyisitworking:llamabro-opencl" }
llamabro-hub        = { module = "io.github.whyisitworking:llamabro-hub" }
llamabro-hubWork    = { module = "io.github.whyisitworking:llamabro-hub-work" }
llamabro-typed      = { module = "io.github.whyisitworking:llamabro-typed" }
llamabro-typedKsp   = { module = "io.github.whyisitworking:llamabro-typed-ksp" }
llamabro-testing    = { module = "io.github.whyisitworking:llamabro-testing" }
llamabro-hubTesting = { module = "io.github.whyisitworking:llamabro-hub-testing" }

[bundles]
llamabro = ["llamabro-core", "llamabro-hexagon", "llamabro-opencl"]
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(platform(libs.llamabro.bom))
    implementation(libs.bundles.llamabro)   // the engine, the NPU and the GPU
    implementation(libs.llamabro.hub)       // optional: Hugging Face search and downloads
    implementation(libs.llamabro.typed)     // optional: typed replies and tools

    testImplementation(platform(libs.llamabro.bom))
    testImplementation(libs.llamabro.testing)
}
```

<details>
<summary>Without a version catalog</summary>

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(platform("io.github.whyisitworking:llamabro-bom:2.0.0-alpha"))
    implementation("io.github.whyisitworking:llamabro")
    implementation("io.github.whyisitworking:llamabro-hexagon")
    implementation("io.github.whyisitworking:llamabro-opencl")
}
```

Or give every module the same version and leave out the BOM:
`implementation("io.github.whyisitworking:llamabro:2.0.0-alpha")`.

</details>

<details>
<summary>Annotated toolboxes with KSP</summary>

`ksp` is a configuration of its own, so the BOM on `implementation` doesn't reach it. Apply the KSP and
`org.jetbrains.kotlin.plugin.serialization` plugins, then:

```kotlin
ksp(platform(libs.llamabro.bom))
ksp(libs.llamabro.typedKsp)
```

</details>

### Modules

| Module | What it adds | Configuration |
|---|---|---|
| `llamabro` | The engine: chat, conversations with tools and compaction, images, transcription, speech, voice conversations, embeddings, rerank, and every llama-server route | `implementation` |
| `llamabro-hexagon` | The Snapdragon Hexagon NPU | `implementation` |
| `llamabro-opencl` | The Adreno GPU | `implementation` |
| `llamabro-hub` | Hugging Face search, and downloads that resume and verify each file's SHA-256 | `implementation` |
| `llamabro-hub-work` | Downloads that run in WorkManager and outlive your app's process | `implementation` |
| `llamabro-typed` | Replies and tools typed by `@Serializable` classes | `implementation` |
| `llamabro-typed-ksp` | Annotated toolboxes, checked when the app builds | `ksp` |
| `llamabro-testing`, `llamabro-hub-testing` | `FakeLlamaBro` and `FakeLlamaBroHub`: the real API over a scripted engine and Hub | `testImplementation` |

## Supported hardware

| Phone | Chip | NPU | GPU | CPU |
|---|---|:-:|:-:|:-:|
| OnePlus 13 | Snapdragon 8 Elite (Hexagon v79, Adreno 830) | Tested | Tested | Tested |
| Other Snapdragon phones | Hexagon v73, v75, v81, v85 | Ships, not yet tested | Ships, not yet tested | Yes |
| Any other arm64 phone | | | | Yes |

On a chip that hasn't been tested, a backend that gives wrong output isn't caught by the fallback, so test your target
phones or pin them to the CPU ([Faster chips](sdk/README.md#faster-chips)). A report from your phone is the most useful
issue you can open.

## Performance

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/readme/speed-dark.svg">
    <img src="assets/readme/speed-light.svg" width="100%" alt="Speed on each chip, in tokens a second: Qwen3.5 4B and LFM3-Omni 2B reading a 2,048-token prompt and writing up to 128 tokens on the NPU, GPU and CPU of a OnePlus 13">
  </picture>
</p>

Measured on a OnePlus 13 through the SDK's public API. Six models from 230M to 4B parameters, drafting, voice,
search and waiting times are in [BENCHMARKS.md](BENCHMARKS.md), with how each number was measured.

## Under the hood

The speed comes from llama.cpp's backends. Llama Bro's work is getting them into an app, and fixing what broke on
the way.

- **The NPU, inside an ordinary app.** llama.cpp's Hexagon backend ran from a shell. Llama Bro ships its NPU programs
  in an AAR and loads them in a normal release build, with no root and no special signing
  ([F36](docs/research/findings.md#f36), [F62](docs/research/findings.md#f62)).
- **One line, +67% prompt speed.** With multi-token prediction on, the NPU fell back to a slow kernel. A one-line gate
  in the Hexagon backend lets it keep the fast one ([F163](docs/research/findings.md#f163)).
- **The GPU said "8888888…" to every question.** On the Adreno 830, Qwen3.5 answered with eights and Gemma 3 with
  nothing. Six rounds of bisecting traced it to one OpenCL kernel, which the SDK now turns off ([F75](docs/research/findings.md#f75)).
- **Speech entirely on the NPU.** LFM3-Omni's speech step hopped between the NPU and the CPU for every audio frame.
  Rewriting part of its graph keeps it on the NPU, halves that step from 30 ms to 16 ms per frame, and speech runs
  more than twice as fast as real time ([F156](docs/research/findings.md#f156)).
- **A voice that knows when to stop.** In about 1 reply in 60, the voice model kept talking long after it had finished.
  A guard tuned on 600 replies ends those, and leaves every clean reply untouched
  ([F167](docs/research/findings.md#f167)).
- **A context that fits.** llama.cpp's own memory check sees all of a phone's RAM as free. The SDK sizes the context
  against the memory Android actually has available ([F33](docs/research/findings.md#f33),
  [D33](docs/decisions.md#d33)).
- **Same answers as the server.** Every build is checked against the `llama-server` binary from the same source, request
  by request ([testing](docs/testing.md#host-steps)).

Every change to llama.cpp is a patch with its own measurement, listed in [`sdk/native/patches`](sdk/native/patches/README.md).
The design is in the [architecture](docs/architecture.md), and every choice and measurement behind it is in the
[decisions](docs/decisions.md) and [findings](docs/research/findings.md).

## Roadmap

- **Now:** serving a loaded model to other devices on your Wi-Fi, and search over your own documents (RAG).
- **Next:** testing on more phones, starting with other Snapdragon generations.
- **Later:** Kotlin Multiplatform, then iOS and Flutter.

More in [ROADMAP.md](ROADMAP.md).

## Known issues

<!-- at most four; the rest stay in the findings -->

- A DFlash draft model no longer speeds up JSON on the NPU, measured at llama.cpp b12479 and not re-checked since
  ([F178](docs/research/findings.md#f178)).
- Llama 3.2 3B repeats a tool call until the turn limit, as it does on upstream ([F27](docs/research/findings.md#f27)).
- Any drafting aborts the OpenCL GPU backend, so the SDK never drafts on the GPU ([F80](docs/research/findings.md#f80)).
- Vulkan runs on the Adreno 830 only with patches, and slower there than the CPU, so it isn't built
  ([F174](docs/research/findings.md#f174)).

## How it started

<!-- one row per milestone; newest at the bottom -->

| | |
|--:|---|
| **Early 2026** | **Hooked.** I was pulling open models from Hugging Face and running them on my Mac, and every road led to llama.cpp: almost every architecture, on almost every chip. I build Android apps for a living, and phones are powerful now. Could mine do this too? |
| **March 11** | **Into an app.** Guides showed llama.cpp running on a phone through Termux or adb. They proved it could be done, but no normal person opens a terminal to talk to a model. I wanted it inside an app, so I wired llama.cpp in through the NDK. |
| **Days later** | **The first reply.** A build loaded a model and printed an answer. I couldn't believe the phone in my hand could do that. |
| **March 13–26** | **0.1 to 1.2.3.** Seventeen releases in two weeks. |
| **April onward** | **Stalled.** The layer I'd written around llama.cpp was too thin to grow past the basics, and I'd started a new job. The project sat on my GitHub. |
| **Later that year** | **An interview.** An interviewer lit up when I showed it to them, and it reminded me the project existed. I remembered exactly where v1 had broken down, so first I built a small proof of concept, NPUFun, to answer one question: can a phone's NPU run these models? On my OnePlus 13, it could. |
| **November 3** | **Rebuilt.** Not a wrapper this time: llama-server's own engine runs inside the app's process and answers exactly as the server does. |
| **November 5** | **The NPU, from an app.** The first answer from the NPU in an ordinary app, not a shell. |
| **November 6** | **"8888888…"** The GPU's answer to every question, until I found the kernel behind it. |
| **November 13** | **The cooler.** The phone ran so hot during benchmarks that it has worn a cooler on its back for every run since. |
| **November 18** | **600 replies.** The voice model sometimes wouldn't stop talking. Going through 600 replies found the pattern. |
| **November 23** | **2.0.0-alpha.** On Maven Central. |
| **Next** | The [roadmap](#roadmap). |

## Documentation

- [SDK guide](sdk/README.md): the API, task by task.
- [Architecture](docs/architecture.md): how the engine, the backends and the memory fit work.
- [Decisions](docs/decisions.md) and [findings](docs/research/findings.md): every choice, and the measurement behind it.
- [Testing](docs/testing.md): what each check proves. [`research/`](research/README.md) holds the experiments.
- [Release notes](docs/release-notes.md) and [benchmarks](BENCHMARKS.md).

## Contributing

Open the repository root in Android Studio and run `:demo:app`. It needs the Android SDK, NDK 29, CMake 4.1.2 and the
llama.cpp submodule (`git submodule update --init sdk/native/third_party/llama.cpp`).

```bash
sdk/scripts/check.sh            # the SDK on the host; must print ALL PASS
sdk/scripts/check.sh --device   # plus tests on an attached phone
demo/scripts/check.sh           # the demo app
```

[CONTRIBUTING.md](CONTRIBUTING.md) has the setup and the workflow for a change.

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). Built on
[llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT) by Georgi Gerganov and its contributors. The demo bundles
Figtree and Source Serif 4 under the SIL Open Font License.
