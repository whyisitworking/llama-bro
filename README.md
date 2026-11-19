# Llama Bro

<p align="center">
  <img src="assets/llama-bro-banner.png" width="100%" alt="Llama Bro"/>
</p>

Run GGUF models on Android with llama.cpp's own server core, in-process. The SDK exposes what `llama-server`
exposes (chat completions with templates, tool calls, reasoning, prompt cache, sampling, pictures and speech in,
embeddings, rerank) through a
small Kotlin API built on coroutines and `Flow`, without an HTTP hop. It picks the right backend for the phone
(CPU, Adreno OpenCL, Hexagon NPU), fits the context to free memory, and downloads models from Hugging Face with
resume and verification.

> **Status:** pre-1.0. The core, backends, Hub, typed output and tools, an agent loop, embeddings, rerank, speech in
> and out, a realtime voice session and a complete demo chat app with voice mode are built and tested; publishing is
> next. What changed is in [docs/release-notes.md](docs/release-notes.md). Coordinates will be
> `io.github.whyisitworking:llamabro`. arm64-v8a, Android 10 (API 29) and up.
>
> Known issues: Llama 3.2 3B repeats a tool call until the turn limit, as it does on upstream (F27); speculative
> decoding aborts the OpenCL GPU backend, so the SDK never drafts on the GPU (F80); Vulkan runs on the Adreno 830 only
> with patches and is slower there than the CPU and OpenCL, so it is not built (F174).

## What is in this repository

| Folder | What it is |
|---|---|
| [`sdk/`](sdk/README.md) | The SDK: `llamabro` (core), `llamabro-typed` and `llamabro-typed-ksp` (typed output and tools), `llamabro-hub` (Hugging Face search and downloads), `llamabro-hub-work` (background downloads), `llamabro-hexagon` and `llamabro-opencl` (optional backends), `llamabro-testing` and `llamabro-hub-testing` (fakes for your own tests), `acceptance/` (device test apps), `native/` (the C++ bridge and the llama.cpp submodule), `scripts/` (the checks) |
| [`demo/`](demo/README.md) | A chat app built on the SDK: find, download, resume and run models, think levels, image input, edit and regenerate, two layouts, a design system on Material 3 |
| [`docs/`](docs/README.md) | Architecture, decisions, testing, the demo's design, and the research record |
| [`research/`](research/README.md) | The experiments behind the design: scripts, method and results |
| `gradle/` | The Gradle wrapper and the shared version catalog |

## Five minutes

```kotlin
val llama = LlamaBro.create(application)
val model = llama.loadModel(ModelSource.file(path))
val chat = model.conversation(system = "You are a helpful assistant.")
chat.send("Hello").collect()          // observe chat.messages and chat.reply
model.close()
```

By its Hugging Face name, with a typed reply (`llamabro-hub`, `llamabro-typed`):

```kotlin
val hub = LlamaBroHub.create(application)
val model = llama.loadModel(hub.model("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"))   // downloads once, then loads offline

@Serializable data class Review(val stars: Int, val summary: String)
val review = model.generate<Review>("The battery lasts two days, but the screen scratches.").valueOrNull()
```

[sdk/README.md](sdk/README.md) has a full chat screen, tools, the Hub, backends and error handling.

## Build

Open the repository root in Android Studio. It is one Gradle build over the SDK modules and the demo app; run
`:demo:app`. You need the Android SDK, NDK 29 and CMake 4.1.2, and the llama.cpp submodule:

```bash
git submodule update --init sdk/native/third_party/llama.cpp
```

The root build and `./gradlew -p sdk ...` (the SDK alone) share `build/` folders; do not run both at once.

## Verify

```bash
sdk/scripts/check.sh              # the SDK on the host; must print ALL PASS
sdk/scripts/check.sh --device     # plus tests on an attached device
demo/scripts/check.sh             # the demo app (add --device for the instrumented tests and journeys)
```

What each check proves, and what it needs, is in [docs/testing.md](docs/testing.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Design questions start in [docs/architecture.md](docs/architecture.md) and
[docs/decisions.md](docs/decisions.md); measured behaviour is in [docs/research/findings.md](docs/research/findings.md).

## License

Apache License 2.0, see [LICENSE](LICENSE). Built on [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT). The
demo bundles Source Serif 4 under the SIL Open Font License (`demo/designsystem/src/main/res/raw/source_serif_4_ofl.txt`).
