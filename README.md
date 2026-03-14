# Llama Bro

**On-device LLM inference SDK for Android, powered by [llama.cpp](https://github.com/ggml-org/llama.cpp).**

[![Build](https://github.com/whyisitworking/llama-bro/actions/workflows/build.yml/badge.svg)](https://github.com/whyisitworking/llama-bro/actions/workflows/build.yml)
[![JitPack](https://jitpack.io/v/whyisitworking/llama-bro.svg)](https://jitpack.io/#whyisitworking/llama-bro)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://android-arsenal.com/api?level=24)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-orange.svg)](https://developer.android.com/ndk/guides/abis)

Run quantized GGUF models directly on Android — no server, no network call, no data ever leaving the device. Built for modern Android development with a Kotlin-first coroutine API designed around structured concurrency.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Performance](#performance)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [API Reference](#api-reference)
- [Configuration](#configuration)
- [Supported Models & Prompt Formats](#supported-models--prompt-formats)
- [Overflow Strategies](#overflow-strategies)
- [Error Handling](#error-handling)
- [Building from Source](#building-from-source)
- [Native Dependencies](#native-dependencies)
- [ProGuard / R8](#proguard--r8)
- [Limitations](#limitations)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

Llama Bro is an Android library that wraps [llama.cpp](https://github.com/ggml-org/llama.cpp) via JNI, exposing a clean, safe Kotlin API for on-device LLM inference. The entire stack — model loading, KV cache management, token sampling, and memory lifecycle — is handled by the library, letting you focus on building the product rather than wrestling with native pointers.

The library ships as a compiled AAR with a pre-built `arm64-v8a` shared library and automatically selects the optimal GGML compute backend at runtime based on the CPU's supported instruction sets (NEON, dotprod, i8mm, SVE).

**Target audience:** Android engineers building privacy-first AI features, offline assistants, on-device RAG, or any product that cannot send user data to a remote model.

---

## Features

- **Fully local inference** — zero network dependency; model weights stay on the device at all times.
- **Kotlin-first, coroutine-native API** — every operation is a `suspend` function or a typed `Flow`; no callbacks, no threading boilerplate.
- **Safe memory lifecycle** — all native engine/session pointers are wrapped in `AutoCloseable` interfaces and managed via `callbackFlow`, preventing leaks on cancellation or error.
- **Thread-safe sessions** — session operations are serialised with a Kotlin `Mutex`; supports preemptive `abort()` without deadlocking the coroutine.
- **SIMD auto-selection** — dynamically loads the best GGML backend for the host SoC; no manual CPU feature detection required.
- **Thinking-block parsing** — built-in `<think>...</think>` tag extraction for reasoning models (DeepSeek-R1, QwQ, etc.).
- **Configurable overflow handling** — three strategies for context-window exhaustion: `Halt`, `ClearHistory`, and `RollingWindow`.
- **Built-in prompt templates** — `ChatML`, `Llama3`, `Mistral`, and `Gemma3` included; custom `PromptFormat` supported for any model.
- **Token streaming with metrics** — real-time `Completion` snapshots accumulate tokens and expose `tokensPerSecond` for live performance display.
- **Conversation history** — load prior messages into a session via `loadHistory()` to restore persistent chats.
- **JitPack distribution** — single Gradle dependency, no local build required.

---

## Architecture

The SDK is a strictly layered stack with clean boundaries between Kotlin and native code.

```
┌──────────────────────────────────────────────────────┐
│  LlamaChatSession  (high-level conversational API)   │
│  ├─ message formatting via PromptFormat              │
│  ├─ thinking-tag / stop-suffix stream parsing        │
│  ├─ token accumulation via Flow.scan()               │
│  ├─ tokens-per-second metrics                        │
│  └─ conversation history (loadHistory / reset)       │
├──────────────────────────────────────────────────────┤
│  LlamaSession  (raw prompt / generate)               │
│  ├─ suspend fun setSystemPrompt / prompt / generate  │
│  ├─ Coroutine Mutex serialisation + abort()          │
│  └─ KV cache overflow strategy enforcement           │
├──────────────────────────────────────────────────────┤
│  LlamaEngine  (model loading + session factory)      │
│  ├─ progress callbacks during model load             │
│  └─ ResourceState<T> Flow lifecycle wrapper          │
├──────────────────────────────────────────────────────┤
│  JNI Bridge  (Kotlin ↔ C++)                          │
│  ├─ typed error codes mapped to LlamaError sealed    │
│  ├─ config reading from Kotlin data classes          │
│  └─ ProgressListener callback interface              │
├──────────────────────────────────────────────────────┤
│  C++ Engine & Session  (llama.cpp)                   │
│  ├─ RAII model / context pointer management          │
│  ├─ GGML backend selection (NEON / dotprod / i8mm)   │
│  ├─ KV cache batch decode + rolling window eviction  │
│  └─ UTF-16 token streaming back to JVM               │
└──────────────────────────────────────────────────────┘
```

The `internal` package is intentionally not part of the public API surface. Consumers interact exclusively through `LlamaEngine`, `LlamaSession`, `LlamaChatSession`, and the model data classes in `com.suhel.llamabro.sdk.model`.

---

## Performance

Performance is highly model- and device-dependent. The following benchmark uses `Q4_K_M` quantisation with default `SessionConfig`.

| Device     | SoC                | Model                | Tokens / sec |
|------------|--------------------|----------------------|--------------|
| OnePlus 13 | Snapdragon 8 Elite | Gemma 3n 2B (Q4_K_M) | ~20 tok/s    |

**Tuning tips:**
- `Q4_K_M` offers the best quality-to-speed tradeoff for most models on mobile.
- Increase `threads` in `ModelConfig` up to the number of performance cores (check `Runtime.availableProcessors()`; the default is `availableProcessors / 2`).
- Set `useMmap = true` (default) to avoid loading the full model into RAM on capable devices.
- Increase `decodeConfig.batchSize` to `4096` for faster prefill on long system prompts.
- Models larger than ~4 GB will require `useMlock = false` on most devices to avoid OOM.

---

## Installation

### 1. Add the JitPack repository

In your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. Add the dependency

In your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.whyisitworking:llama-bro:1.0.3")
}
```

> The AAR includes the compiled `arm64-v8a` shared library. No NDK setup is required in the consuming project.

### 3. Obtain a model

Download a GGUF-format model and place it somewhere readable by your app (e.g., `filesDir`, external storage, or downloaded via `DownloadManager`). [Hugging Face](https://huggingface.co/models?library=gguf) is the canonical source.

```
# Example: Gemma 3n E2B (Q4_K_M) — ~3.03 GB
https://huggingface.co/unsloth/gemma-3n-E2B-it-GGUF
```

---

## Quick Start

### Flow-based usage (recommended)

The `createSessionFlow` / `createChatSessionFlow` overloads return a `Flow<ResourceState<T>>` that automatically closes the underlying native resource when the flow terminates, is cancelled, or errors.

```kotlin
import com.suhel.llamabro.sdk.LlamaEngine
import com.suhel.llamabro.sdk.model.*

val modelConfig = ModelConfig(
    modelPath = "${filesDir.absolutePath}/gemma-3n-E2B-it-Q4_K_M.gguf",
    promptFormat = PromptFormats.Gemma3
)

val sessionConfig = SessionConfig(
    contextSize = 4096,
    overflowStrategy = OverflowStrategy.RollingWindow(dropTokens = 500)
)

lifecycleScope.launch {
    LlamaEngine.createFlow(modelConfig).collect { engineEvent ->
        when (engineEvent) {
            is ResourceState.Loading -> updateProgressBar(engineEvent.progress ?: 0f)
            is ResourceState.Failure -> showError(engineEvent.error)
            is ResourceState.Success -> {
                engineEvent.value.createChatSessionFlow("You are a helpful assistant.")
                    .collect { sessionEvent ->
                        when (sessionEvent) {
                            is ResourceState.Success -> {
                                val chat = sessionEvent.value
                                chat.completion("Explain coroutines in one paragraph.")
                                    .collect { completion ->
                                        updateTextView(completion.contentText.orEmpty())
                                        if (completion.isComplete) {
                                            Log.d("LlamaBro", "${completion.tokensPerSecond} t/s")
                                        }
                                    }
                            }
                            else -> { /* handle loading / error */ }
                        }
                    }
            }
        }
    }
}
```

### Manual lifecycle management

Use this pattern when you manage the engine lifetime explicitly (e.g., a singleton in a ViewModel or a Hilt-scoped component).

```kotlin
class ChatViewModel @Inject constructor() : ViewModel() {

    private var engine: LlamaEngine? = null
    private var chatSession: LlamaChatSession? = null

    fun init(modelPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            engine = LlamaEngine.create(
                ModelConfig(modelPath, PromptFormats.Llama3)
            ) { progress ->
                _loadProgress.value = progress
                true // return false to cancel load
            }
            val session = engine!!.createSession(SessionConfig())
            chatSession = session.createChatSession("You are a helpful assistant.")
        }
    }

    fun send(message: String): Flow<Completion> =
        chatSession?.completion(message) ?: emptyFlow()

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.IO) {
            chatSession = null
            engine?.close() // releases native model memory
        }
    }
}
```

---

## API Reference

### `LlamaEngine`

The top-level factory. Owns the loaded model weights. Expensive to create — keep one instance per model.

```kotlin
interface LlamaEngine : AutoCloseable {
    // Suspends until session context is initialised
    suspend fun createSession(sessionConfig: SessionConfig): LlamaSession

    // Flow-based; closes session automatically on flow completion
    fun createSessionFlow(sessionConfig: SessionConfig): Flow<ResourceState<LlamaSession>>

    companion object {
        // Blocking (call on Dispatchers.IO); optional progress callback returns false to cancel
        fun create(modelConfig: ModelConfig, onProgress: ((Float) -> Boolean)? = null): LlamaEngine

        // Flow-based with Loading / Success / Failure events
        fun createFlow(modelConfig: ModelConfig): Flow<ResourceState<LlamaEngine>>
    }
}
```

### `LlamaSession`

Raw token-level access. Use when you need fine-grained control over prompt injection and sampling.

```kotlin
interface LlamaSession : AutoCloseable {
    val modelConfig: ModelConfig

    suspend fun setSystemPrompt(text: String)
    suspend fun prompt(text: String)       // ingest text into KV cache
    suspend fun generate(): String?        // sample one token; null = EOS
    suspend fun clear()                    // reset KV cache and history
    fun abort()                            // preempt any active operation

    suspend fun createChatSession(systemPrompt: String): LlamaChatSession
    fun createChatSessionFlow(systemPrompt: String): Flow<ResourceState<LlamaChatSession>>
}
```

### `LlamaChatSession`

High-level conversational API. Handles prompt formatting, stop detection, thinking-block extraction, and metrics.

```kotlin
interface LlamaChatSession {
    // Streams accumulated Completion snapshots until EOS
    fun completion(message: String): Flow<Completion>

    // Reset KV cache and conversation history
    suspend fun reset()

    // Restore prior conversation (e.g., from a database)
    suspend fun loadHistory(messages: List<Message>)
}
```

### `Completion`

Each emission from `completion()` is a cumulative snapshot of the current generation:

```kotlin
data class Completion(
    val thinkingText: String?,      // content inside <think> blocks (reasoning models)
    val contentText: String?,       // visible response text
    val tokensPerSecond: Float?,    // rolling average; populated once generation begins
    val isComplete: Boolean         // true on the final emission (EOS reached)
)
```

### `ResourceState<T>`

Represents the lifecycle of an async resource load. All Flow-returning APIs emit this type.

```kotlin
sealed interface ResourceState<out T> {
    data class Loading(val progress: Float?)  : ResourceState<Nothing>
    data class Success<T>(val value: T)       : ResourceState<T>
    data class Failure(val error: LlamaError) : ResourceState<Nothing>
}
```

A set of extension functions is provided for idiomatic consumption:

```kotlin
// Exhaustive handler — replaces when blocks
resourceState.fold(
    onLoading = { progress -> /* show progress bar */ },
    onSuccess = { value   -> /* use loaded resource */ },
    onFailure = { error   -> /* show error UI */ }
)

// Extract value or supply a default
val engine: LlamaEngine = resourceState.getOrElse { error -> fallbackEngine }

// Extract value or null
val engine: LlamaEngine? = resourceState.getOrNull()

// Transform the success value while preserving Loading / Failure
val mapped: ResourceState<String> = resourceState.map { it.toString() }

// Transform success values in a flow — short-circuits Loading/Failure transparently
engineFlow.mapSuccess { engine -> MyWrapper(engine) }

// Chain a second resource-loading flow on success
engineFlow.flatMapSuccess { engine -> engine.createSessionFlow(config) }
```

### `Message`

Sealed type for conversation history entries:

```kotlin
sealed interface Message {
    val content: String

    data class User(override val content: String) : Message
    data class Assistant(
        override val content: String,
        val thinking: String? = null,
        val tokensPerSecond: Float? = null
    ) : Message
}
```

---

## Configuration

### `ModelConfig`

| Parameter      | Type           | Default                   | Description                                       |
|----------------|----------------|---------------------------|---------------------------------------------------|
| `modelPath`    | `String`       | required                  | Absolute path to the `.gguf` model file           |
| `promptFormat` | `PromptFormat` | required                  | Chat template for the model family                |
| `useMmap`      | `Boolean`      | `true`                    | Memory-map the model file; reduces peak RAM usage |
| `useMlock`     | `Boolean`      | `false`                   | Lock model pages in RAM to prevent swapping       |
| `threads`      | `Int`          | `availableProcessors / 2` | Inference thread count                            |

### `SessionConfig`

| Parameter          | Type               | Default              | Description                       |
|--------------------|--------------------|----------------------|-----------------------------------|
| `contextSize`      | `Int`              | `4096`               | Maximum KV cache size in tokens   |
| `overflowStrategy` | `OverflowStrategy` | `RollingWindow(500)` | Behaviour when context is full    |
| `inferenceConfig`  | `InferenceConfig`  | see below            | Sampling parameters               |
| `decodeConfig`     | `DecodeConfig`     | see below            | Batch decode tuning               |
| `seed`             | `Int`              | `-1` (random)        | RNG seed for reproducible outputs |

### `InferenceConfig`

| Parameter         | Type     | Default | Description                             |
|-------------------|----------|---------|-----------------------------------------|
| `temperature`     | `Float`  | `0.8`   | Sampling temperature; `0.0` = greedy    |
| `repeatPenalty`   | `Float`  | `1.1`   | Penalty for repeating recent tokens     |
| `presencePenalty` | `Float`  | `0.0`   | Penalty for tokens already present      |
| `minP`            | `Float?` | `0.1`   | Min-P sampling floor; `null` to disable |
| `topP`            | `Float?` | `null`  | Nucleus sampling threshold              |
| `topK`            | `Int?`   | `null`  | Top-K sampling ceiling                  |

### `DecodeConfig`

| Parameter             | Type  | Default | Description                                                 |
|-----------------------|-------|---------|-------------------------------------------------------------|
| `batchSize`           | `Int` | `2048`  | Maximum tokens processed per decode call                    |
| `microBatchSize`      | `Int` | `512`   | Internal batch chunk size                                   |
| `systemPromptReserve` | `Int` | `100`   | Token slots reserved for system prompt in overflow eviction |

---

## Supported Models & Prompt Formats

The library is model-agnostic at the inference level. Any model that runs in llama.cpp will work as long as you supply the correct `PromptFormat`. Built-in formats:

| Constant                | Format                                   | Models                                                |
|-------------------------|------------------------------------------|-------------------------------------------------------|
| `PromptFormats.ChatML`  | `<\|im_start\|>` / `<\|im_end\|>`        | Qwen 2.5, Yi, InternLM, general instruction finetunes |
| `PromptFormats.Llama3`  | `<\|start_header_id\|>` / `<\|eot_id\|>` | Llama 3 / 3.1 / 3.2 / 3.3                             |
| `PromptFormats.Mistral` | `[INST]` / `[/INST]`                     | Mistral 7B, Mixtral 8x7B                              |
| `PromptFormats.Gemma3`  | `<start_of_turn>` / `<end_of_turn>`      | Gemma 3, Gemma 3n                                     |

**Custom formats** are first-class; pass a `PromptFormat` data class with your own prefix/suffix strings:

```kotlin
val myFormat = PromptFormat(
    systemPrefix    = "<<SYS>>\n",
    systemSuffix    = "\n<</SYS>>\n\n",
    userPrefix      = "[INST] ",
    userSuffix      = " [/INST]",
    assistantPrefix = "",
    assistantSuffix = "</s>"
)
```

---

## Overflow Strategies

When the KV cache reaches `SessionConfig.contextSize`, the selected `OverflowStrategy` determines what happens:

| Strategy                                     | Behaviour                                                                     |
|----------------------------------------------|-------------------------------------------------------------------------------|
| `OverflowStrategy.Halt`                      | Throws `LlamaError.ContextOverflow`. Use when you need deterministic failure. |
| `OverflowStrategy.ClearHistory`              | Discards all conversation history and reloads the system prompt.              |
| `OverflowStrategy.RollingWindow(dropTokens)` | Evicts the oldest `dropTokens` tokens from the cache and continues.           |

`RollingWindow` is the default and is the best choice for long-running sessions. `dropTokens` trades memory pressure against the amount of history lost per eviction cycle.

---

## Error Handling

All errors cross the JNI boundary as typed exceptions mapped to the `LlamaError` sealed class:

```
sealed class LlamaError : Exception {
    class ModelNotFound(val path: String)
    class ModelLoadFailed(val path: String, cause: Throwable?)
    class BackendLoadFailed(val backendName: String)
    class ContextInitFailed(cause: Throwable?)
    class ContextOverflow
    class DecodeFailed(val code: Int)
    class NativeException(val nativeMessage: String, cause: Throwable?)
}
```

Errors surface as `ResourceState.Failure` in Flow-based usage, or are thrown from `suspend` functions in manual usage. Structured exception handling with `try/catch` or `.catch {}` on the flow is idiomatic.

```kotlin
LlamaEngine.createFlow(modelConfig)
    .catch { e ->
        if (e is LlamaError.ModelNotFound) showFilePicker()
        else throw e
    }
    .collect { /* ... */ }
```

---

## Building from Source

The project requires a recursive clone because `llama.cpp` is vendored as a Git submodule.

```bash
git clone --recursive https://github.com/whyisitworking/llama-bro.git
cd llama-bro
```

If you already cloned without `--recursive`:

```bash
git submodule update --init --recursive
```

### Build commands

```bash
# Build the SDK AAR
./gradlew :sdk:assembleRelease

# Run unit tests
./gradlew :sdk:test

# Build the demo app
./gradlew :app:assembleDebug
```

### Requirements

| Tool        | Version       |
|-------------|---------------|
| JDK         | 17+           |
| Android SDK | API 36        |
| NDK         | 29.0.14206865 |
| CMake       | 3.22.1        |

NDK and CMake are installed automatically via the Android SDK manager if listed in `local.properties` or through Android Studio's SDK Tools panel. The CI workflow (`.github/workflows/build.yml`) installs them explicitly and can be used as a reference.

---

## Native Dependencies

The SDK embeds `llama.cpp` as a vendored Git submodule at `sdk/src/main/cpp/external/llama.cpp`. The CMake build compiles it directly into the `llama_bro` shared library — no separate `.so` is shipped for llama.cpp itself.

**Key CMake flags set by the library:**

| Flag                    | Value | Reason                                                   |
|-------------------------|-------|----------------------------------------------------------|
| `GGML_OPENCL`           | `OFF` | Prevents GPU-driver-induced UI thread stalls             |
| `GGML_OPENMP`           | `ON`  | Multi-threaded decode across performance cores           |
| `GGML_CPU_ALL_VARIANTS` | `ON`  | Emits all CPU backends; best variant selected at runtime |
| `LLAMA_BUILD_COMMON`    | `ON`  | Required for backend dispatch                            |

**Supported ABI:** `arm64-v8a` only. `x86_64` (emulator) is not currently supported.

---

## ProGuard / R8

Consumer ProGuard rules are bundled with the AAR and applied automatically. No additional configuration is required in the consuming app. The rules preserve:

- JNI-accessible classes and native method declarations in `LlamaEngineImpl` and `LlamaSessionImpl`
- Fields on `NativeCreateParams` inner classes read reflectively by JNI
- `ProgressListener.onProgress` called from native code during model load

If you are using a custom shrinking configuration that strips `internal` packages, ensure the package `com.suhel.llamabro.sdk.internal` is not excluded.

---

## Limitations

- **arm64-v8a only.** No x86_64/emulator support. Run on a physical device or an arm64 emulator image.
- **Single active session per engine.** Creating two sessions from the same engine concurrently is not supported; the second `createSession` call will be serialised.
- **No GPU acceleration.** OpenCL is intentionally disabled. Vulkan and OpenGL compute are not yet implemented.
- **Model files must be local.** The library does not perform downloads; model acquisition is the caller's responsibility.
- **No multimodal support.** Vision / audio models are not currently supported regardless of llama.cpp capability.
- **Large models and RAM.** Models are not split across RAM and flash; the full model must fit in available memory. On most Android devices this limits practical use to 7B Q4 or smaller.

---

## Roadmap

- [ ] **x86_64 emulator support** — for faster development iteration
- [ ] **Vulkan GPU backend** — for significant inference speedups on supported SoCs
- [ ] **Streaming grammar / JSON-mode** — constrained decoding for structured output
- [ ] **Function calling / tool use** — structured output with tool schema
- [ ] **GGUF metadata reading** — expose embedded chat template and model metadata
- [ ] **LoRA adapter support** — dynamic adapter loading without full model reload
- [ ] **Maven Central publishing** — reduce dependency on JitPack for production use
- [ ] **Multi-model session** — allow multiple loaded models with shared context management

---

## Contributing

Contributions are welcome. Before opening a pull request, please:

1. **Open an issue first** for non-trivial changes to align on approach.
2. **Run the full test suite** (`./gradlew :sdk:test`) and ensure it passes.
3. **Build the release AAR** (`./gradlew :sdk:assembleRelease`) and verify it compiles.
4. Follow the existing code style (Kotlin official style guide; `kotlin.code.style=official` is enforced in `gradle.properties`).
5. Keep native changes to the `sdk/src/main/cpp` directory minimal and well-commented; JNI bugs are difficult to diagnose.

### Local development

```bash
# After changes to native code, a clean rebuild is recommended
./gradlew :sdk:clean :sdk:assembleRelease

# Run only unit tests (fast; does not require a device)
./gradlew :sdk:testDebugUnitTest
```

---

## License

```
Copyright 2024 whyisitworking

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

The embedded `llama.cpp` library is distributed under the [MIT License](https://github.com/ggml-org/llama.cpp/blob/master/LICENSE).
