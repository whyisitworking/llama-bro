# [Llama Bro SDK](https://github.com/whyisitworking/llama-bro)

<p align="center">
  <a href="https://github.com/whyisitworking/llama-bro/releases/latest/download/LlamaBro-Demo.apk"><img src="https://img.shields.io/badge/Download_Demo_APK-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="Download APK"></a>
  <img src="https://img.shields.io/github/v/release/whyisitworking/llama-bro?style=for-the-badge&color=blue" alt="Version">
  <img src="https://img.shields.io/badge/API-24%2B-brightgreen?style=for-the-badge&logo=android&logoColor=white" alt="API 24+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-orange?style=for-the-badge" alt="License"></a>
</p>

<p align="center">
  <a href="https://jitpack.io/#whyisitworking/llama-bro"><img src="https://img.shields.io/jitpack/v/github/whyisitworking/llama-bro?style=flat-square&logo=git&color=brightgreen&label=JitPack" alt="JitPack"></a>
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-orange?style=flat-square&logo=arm" alt="ABI">
  <a href="https://github.com/whyisitworking/llama-bro/stargazers"><img src="https://img.shields.io/github/stars/whyisitworking/llama-bro?style=flat-square&logo=github" alt="Stars"></a>
</p>

**Run a full AI model in your pocket. On your terms. No servers. No subscriptions. No data leaving your phone.**

<p align="center">
  <img src="assets/llama-bro-banner.png" width="100%" alt="Llama Bro Banner"/>
</p>

---

## The Problem with Cloud AI

Every time you send a message to a cloud LLM, that message travels to a datacenter. It's logged, processed, and potentially used to train the next model. Your health questions, your legal queries, your private relationship advice — all of it leaves your device.

**Llama Bro is the answer to that.**

We wrap [llama.cpp](https://github.com/ggml-org/llama.cpp) in a clean, idiomatic Kotlin SDK so you can run state-of-the-art models — Llama 3, Gemma, DeepSeek-R1, Qwen 2.5 — directly on the device. No API keys. No usage limits. No data residency concerns. Your model, your hardware, your rules.

---

## See it in Action

<div align="center">
  <video src="https://github.com/user-attachments/assets/b6c3b4f0-efc7-4e43-8350-4df3e0646882" width="300" autoplay loop muted playsinline></video>
  <br/>
  <sub><i>Real-time token streaming on Snapdragon 8 Elite. No cloud. No lag.</i></sub>
</div>

---

## What's New — Declarative Inference Pipeline

The headline feature of the most recent architectural refactor is the **Declarative Inference Pipeline** — a fully reactive, allocation-optimized token processing engine that maps raw native output directly to your UI without a single blocking call.

```
┌─────────────────────────────────────────────────────────────────┐
│  1. USER PROMPT                                                 │
│     chat.completion(ChatEvent.UserEvent("Hello", think = true)) │
└─────────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  2. PROMPT FORMATTER                                            │
│     Wraps the message in model-specific chat markers:           │
│     <|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n  │
└─────────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  3. NATIVE GENERATOR                                            │
│     llama_decode() → channelFlow { send(token) }                │
│     Running on Dispatchers.IO, legally cross-context.           │
└─────────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  4. DFA LEXER (AllocationOptimizedScanner)                      │
│     Scans the raw token stream character-by-character.          │
│     Detects: text | <think>...</think> | <tool_call>...</tool_call>│
│     Uses StringBuilder, not String concat — 0 GC pressure.     │
└─────────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  5. SEMANTIC CHUNKING                                           │
│     Emits typed chunks: TextChunk | ThinkingChunk | ToolChunk   │
│     Assembled into AssistantEvent.Part objects.                 │
└─────────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  6. COMPLETION SNAPSHOT                                         │
│     Each emission: { message, tokensPerSecond, isComplete }     │
│     Your UI collects this — full content, always cumulative.    │
└─────────────────────────────────────────────────────────────────┘
```

### How Pipeline Composition Works

```kotlin
LlamaEngine.createFlow(modelDefinition)          // Load model → ResourceState<LlamaEngine>
    .flatMapResource { engine ->                  // When loaded, create session
        engine.createSessionFlow(sessionConfig)
    }
    .flatMapResource { session ->                 // When session ready, create chat
        session.createChatSessionFlow(systemPrompt)
    }
    .filterSuccess()                              // Extract the chat session
    .flatMapLatest { chat ->                      // On each user turn
        chat.completion(userEvent)
    }
    .collect { snapshot ->                        // UI-ready snapshot, on every token
        updateTextView(snapshot.message.text)
        if (snapshot.isComplete) saveToDb(snapshot)
    }
```

No threading code. No callbacks. No lifecycle leaks. Cancellation is free.

---

## Features

- **Zero-Allocation Streaming** — DFA-based scanner (`AllocationOptimizedScanner`) uses StringBuilder internally and avoids per-token heap allocations, keeping the UI thread smooth
- **Thinking Block Extraction** — First-class support for `<think>...</think>` in reasoning models (DeepSeek-R1, QwQ, MiniMax). Thinking text and response text are separated automatically
- **Declarative Flow API** — `ResourceState<T>` ADT with `flatMapResource`, `filterSuccess`, `onEachLoading`, and `fold` operators for composing resource loads declaratively
- **Prompt Format Library** — 6 built-in chat templates (Gemma, Llama 3, ChatML, DeepSeek-R1, Mistral, Nemotron) + `QWEN_2_5` alias + support for fully custom formats, including "turn-start" injection for forcing thinking
- **Overflow Management** — 3 strategies for handling full KV caches: `Halt`, `ClearHistory`, `RollingWindow` — configurable per session
- **Type-Safe Errors** — `LlamaError` sealed class maps every native failure to a named subtype. No raw exceptions from the JNI boundary
- **History Replay** — `feedHistory(List<ChatEvent>)` pre-populates the KV cache with a prior conversation, so follow-up generations are contextual

### Built-In Prompt Formats

| Template        | Protocol                                              | Best For                              |
|-----------------|-------------------------------------------------------|---------------------------------------|
| `GEMMA`         | `<start_of_turn>` / `<end_of_turn>`                   | Google Gemma / Gemma 2 / Gemma 3n     |
| `LLAMA_3`       | `<\|start_header_id\|>` / `<\|eot_id\|>`              | Llama 3 / 3.1 / 3.2 / 3.3            |
| `CHAT_ML`       | `<\|im_start\|>` / `<\|im_end\|>`                    | SmolLM2, Qwen 2.5, Yi, Hermes         |
| `QWEN_2_5`      | alias for `CHAT_ML`                                   | Qwen 2.5 (convenient alias)           |
| `DEEPSEEK_R1`   | `<｜begin of sentence｜>` / `<｜end of sentence｜>`   | DeepSeek-R1 / R1-Distill family       |
| `MISTRAL`       | `[INST]` / `[/INST]`                                 | Mistral 7B, Mixtral 8x7B              |
| `NEMOTRON`      | `<extra_id_0>` / `<extra_id_1>`                       | NVIDIA Nemotron-Mini                  |

---

## Installation

### 1. Add JitPack to your repositories

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. Add the dependency

```kotlin
// build.gradle.kts (app)
dependencies {
    implementation("com.github.whyisitworking:llama-bro:<LATEST_VERSION>")
}
```

Check the JitPack badge above for the latest version.

---

## Prerequisites

### Download a GGUF Model

Grab a GGUF-quantised model from [Hugging Face](https://huggingface.co/models?library=gguf).

**Recommended starting points:**

| Model | Size | Format | Recommended Source |
|---|---|---|---|
| **Llama 3.2 1B** | ~600 MB | `LLAMA_3` | [bartowski/Llama-3.2-1B-Instruct-GGUF](https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF) |
| **Gemma 3n 2B** | ~3 GB | `GEMMA` | [unsloth/gemma-3n-E2B-it-GGUF](https://huggingface.co/unsloth/gemma-3n-E2B-it-GGUF) |
| **Qwen 2.5 0.5B** | ~400 MB | `QWEN_2_5` | [bartowski/Qwen2.5-0.5B-Instruct-GGUF](https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF) |
| **DeepSeek-R1 7B** | ~5 GB | `DEEPSEEK_R1` | [bartowski/DeepSeek-R1-Distill-Qwen-7B-GGUF](https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-7B-GGUF) |

**Quantization guide:** `Q4_K_M` is the mobile sweet spot — best quality-to-speed tradeoff. Go `Q3_K_M` for RAM-constrained devices. Go `Q5_K_M` for maximum quality (larger, slower).

---

## Quick Start

```kotlin
import com.suhel.llamabro.sdk.LlamaEngine
import com.suhel.llamabro.sdk.config.*
import com.suhel.llamabro.sdk.models.*
import com.suhel.llamabro.sdk.format.PromptFormats
import com.suhel.llamabro.sdk.model.*

lifecycleScope.launch {
    LlamaEngine.createFlow(
        ModelDefinition(
            loadConfig = ModelLoadConfig(path = "/path/to/model.gguf"),
            promptFormat = PromptFormats.CHAT_ML,
        )
    )
    .onEachLoading { progress ->
        progressBar.progress = ((progress ?: 0f) * 100).toInt()
    }
    .flatMapResource { engine ->
        engine.createSessionFlow(
            SessionConfig(
                contextSize = 4096,
                overflowStrategy = OverflowStrategy.RollingWindow(dropTokens = 500),
                inferenceConfig = InferenceConfig(temperature = 0.7f, minP = 0.1f)
            )
        )
    }
    .flatMapResource { session ->
        session.createChatSessionFlow("You are a helpful assistant.")
    }
    .filterSuccess()
    .flatMapLatest { chat ->
        chat.completion(ChatEvent.UserEvent("Explain coroutines.", think = false))
    }
    .collect { snapshot ->
        textView.text = snapshot.message.text
        if (snapshot.isComplete) {
            speedLabel.text = "${snapshot.tokensPerSecond} tok/s"
        }
    }
}
```

---

## API Overview

The SDK is layered. Each tier adds abstraction. Use what your use case demands.

### `LlamaEngine` — The Model Loader

Loads the GGUF file and manages model weights. Creates sessions on demand. Keep **one engine per model** across the app.

```kotlin
// Recommended: Flow-based (auto-cleanup on coroutine cancellation)
LlamaEngine.createFlow(modelDefinition)
    .onEachLoading { progress -> showProgress(progress) }
    .flatMapResource { engine -> /* use engine */ }

// Manual: You manage the lifecycle
val engine = LlamaEngine.create(modelDefinition) { progress -> true /* return false to cancel */ }
val session = engine.createSession(sessionConfig)
engine.close() // Releases native memory
```

### `LlamaSession` — The Token Engine

Manages the KV cache, token encoding, and sampling. Mutex-serialized for thread safety.

```kotlin
// Use the Flow API for standard sampling
session.generateFlow().collect { result ->
    print(result.token ?: "")
    if (result.isComplete) return@collect
}
```

> **When to use directly:** Implementing custom sampling loops, tool injection, or token-level diagnostics. Most apps should use `LlamaChatSession` instead.

### `LlamaChatSession` — The Chat API

Handles prompt formatting, stop-token detection, thinking-block extraction, and metrics. This is where 95% of integrations start and end.

```kotlin
chat.completion(ChatEvent.UserEvent("Hello!", think = true)).collect { snapshot ->
    // snapshot.message.text          → Visible response
    // snapshot.message.thinkingText  → Hidden reasoning
    // snapshot.tokensPerSecond       → Generation speed
    // snapshot.isComplete            → True when done
}
```

---

## Configuration Reference

### `ModelDefinition`

The root configuration object. Bundles load settings with the prompt format.

```kotlin
ModelDefinition(
    loadConfig = ModelLoadConfig(
        path = "/data/user/0/com.myapp/files/model.gguf",
        threads = 8,         // Match your device's performance core count
        useMMap = true,      // Memory-map the file (recommended)
        useMLock = false     // Lock in RAM (prevent OS swap — high-memory devices only)
    ),
    promptFormat = PromptFormats.LLAMA_3,
    features = listOf(ThinkingMarker)  // Enable thinking injection for reasoning models
)
```

### `SessionConfig`

| Option             | Default              | Notes                                                              |
|--------------------|----------------------|--------------------------------------------------------------------|
| `contextSize`      | `2048`               | Token budget for the entire conversation (prompt + response)       |
| `overflowStrategy` | `RollingWindow(500)` | What happens when the KV cache fills up                            |
| `inferenceConfig`  | See below            | Sampling parameters                                                |
| `decodeConfig`     | See below            | I/O batch sizes for performance tuning                             |
| `seed`             | `-1` (random)        | Set an integer for reproducible outputs                            |

### `InferenceConfig` — Sampling

| Option            | Default | Range     | Effect                                                             |
|-------------------|---------|-----------|--------------------------------------------------------------------|
| `temperature`     | `0.8f`  | `0.0–2.0` | Randomness. `0.0` = deterministic greedy, `1.0` = neutral.        |
| `repeatPenalty`   | `1.0f`  | `1.0–2.0` | Discourages the model from repeating recent tokens.               |
| `presencePenalty` | `0.0f`  | `0.0–2.0` | Penalizes all tokens that have appeared, not just recent ones.    |
| `minP`            | `0.1f`  | `0.0–1.0` | Min-probability filter. Cuts "hallucination tail" tokens cleanly. |
| `topP`            | `null`  | `0.0–1.0` | Nucleus sampling. `null` = disabled.                              |
| `topK`            | `null`  | `1–∞`     | Top-K sampling. `null` = disabled.                                |

### `DecodeConfig` — Performance

| Option           | Default | Notes                                                |
|------------------|---------|------------------------------------------------------|
| `batchSize`      | `2048`  | Max tokens processed per decode step.                |
| `microBatchSize` | `512`   | Internal chunking granularity. Lower = less RAM.     |

Increase `batchSize` to `4096` for faster long-prompt prefill. Reduce it on RAM-constrained devices.

### Overflow Strategies

| Strategy                 | Behavior                                                         | Best For                              |
|--------------------------|------------------------------------------------------------------|---------------------------------------|
| `Halt`                   | Throws `LlamaError.ContextOverflow`                              | Strict determinism, batch processing  |
| `ClearHistory`           | Wipes context, reloads system prompt, continues                  | Short-session apps                    |
| `RollingWindow(n)`       | Evicts oldest `n` tokens, keeps chatting                         | Long conversational flows (recommended) |

---

## Thinking Blocks & Reasoning Models

Reasoning models like **DeepSeek-R1** and **QwQ** expose their internal chain-of-thought inside `<think>...</think>` tags. Llama Bro automatically extracts these into a separate part of the `AssistantEvent`.

```kotlin
// Set think = true to inject the opening <think> tag,
// forcing the model into reasoning mode.
val userEvent = ChatEvent.UserEvent(
    content = "What is 17 × 23? Show your work.",
    think = true
)

chat.completion(userEvent).collect { snapshot ->
    // Display reasoning in a collapsible section
    val reasoning = snapshot.message.thinkingText   // "Let me calculate 17 × 23..."
    val answer    = snapshot.message.text            // "The answer is 391."

    if (snapshot.isComplete) {
        println("${snapshot.tokensPerSecond} tokens/sec")
    }
}
```

The `think = true` parameter only works on models with `ThinkingMarker` in their `ModelDefinition.features`. On non-thinking models, it is silently ignored — making the API safe to use unconditionally.

---

## Error Handling

All native failures cross the JNI boundary as typed `LlamaError` subtypes:

```kotlin
sealed class LlamaError : Exception() {
    class ModelNotFound(val path: String) : LlamaError()
    class ModelLoadFailed(val path: String, cause: Throwable?) : LlamaError()
    class BackendLoadFailed(val backendName: String) : LlamaError()
    class ContextInitFailed(cause: Throwable?) : LlamaError()
    class ContextOverflow : LlamaError()
    class DecodeFailed(val code: Int) : LlamaError()
    class NativeException(val nativeMessage: String, cause: Throwable?) : LlamaError()
}
```

Compose error recovery into the same flow chain:

```kotlin
LlamaEngine.createFlow(modelDefinition)
    .catch { e ->
        when (e) {
            is LlamaError.ModelNotFound   -> showModelPickerUI()
            is LlamaError.ContextOverflow -> onContextFull()
            else                          -> logAndRethrow(e)
        }
    }
    .collect { /* ... */ }
```

---

## Conversation History

Re-populate the KV cache with a prior conversation before the next turn:

```kotlin
val history: List<ChatEvent> = listOf(
    ChatEvent.UserEvent("What's Kotlin?", think = false),
    ChatEvent.AssistantEvent(listOf(
        ChatEvent.AssistantEvent.Part.TextPart("Kotlin is a JVM language by JetBrains.")
    )),
    ChatEvent.UserEvent("And coroutines?", think = false),
)

chat.feedHistory(history)
chat.completion(ChatEvent.UserEvent("Give me an example.", think = false))
    .collect { snapshot -> /* ... */ }
```

The session processes history tokens once — subsequent context is pre-warmed and generations are faster.

---

## ResourceState Flow Operators

`ResourceState<T>` is the lifecycle ADT powering the entire SDK:

```kotlin
sealed class ResourceState<out T> {
    data class Loading(val progress: Float?) : ResourceState<Nothing>()
    data class Success<T>(val value: T) : ResourceState<T>()
    data class Failure(val error: Throwable) : ResourceState<Nothing>()
}
```

Compose resource flows declaratively using built-in operators:

| Operator | Use |
|---|---|
| `flatMapResource { }` | Chain a resource-loading step onto an existing one |
| `filterSuccess()` | Strip the wrapper, emit only successful values as `Flow<T>` |
| `onEachLoading { }` | React to progress without leaving the chain |
| `onEachSuccess { }` | Side-effect on load completion |
| `mapSuccess { }` | Transform the inner value |
| `fold(onLoading, onSuccess, onFailure)` | Exhaustive pattern match |
| `getOrNull()` | Extract value or `null` |
| `getOrElse { }` | Extract value or a fallback |

---

## Architecture

```text
┌──────────────────────────────────┐
│  LlamaChatSession (Public API)   │  Formatting, stop tokens, metrics
├──────────────────────────────────┤
│  LlamaSession (Public API)       │  KV cache, mutex, token control
├──────────────────────────────────┤
│  LlamaEngine (Public API)        │  Model loading, session factory
├──────────────────────────────────┤
│  JNI Bridge (Internal)           │  C++ ↔ Kotlin, error mapping
├──────────────────────────────────┤
│  llama.cpp (Native C++)          │  GGML, SIMD (NEON, dotprod, i8mm)
└──────────────────────────────────┘
```

All concrete implementations are `internal`. The public surface is interface-based. Extensions and wrappers can depend on the interfaces without coupling to the implementation.

---

## Custom Prompt Formats

Any model not in the built-in list can be supported with a custom `PromptFormat`:

```kotlin
val custom = PromptFormat(
    systemPrefix = "<<SYS>>\n",
    userPrefix = "[INST] ",
    assistantPrefix = "[/INST] ",
    endOfTurn = "</s>\n",
    emitAssistantPrefixOnGeneration = true
)

LlamaEngine.createFlow(
    ModelDefinition(
        loadConfig = ModelLoadConfig("/path/to/model.gguf"),
        promptFormat = custom
    )
)
```

---

## Roadmap

- [ ] **Streaming Grammar** — Force structured JSON/function output from any model
- [ ] **Function Calling** — Registered tools that models can invoke during generation
- [ ] **Multi-Model Sessions** — Seamlessly switch models mid-conversation
- [ ] **GGUF Metadata** — Auto-detect model type and recommended format from file headers

---

## Contributing

1. [Open an issue](https://github.com/whyisitworking/llama-bro/issues) to discuss non-trivial changes first
2. Run tests before submitting: `./gradlew :sdk:testDebugUnitTest`
3. Build the release AAR: `./gradlew :sdk:assembleRelease`
4. Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
5. Keep native code minimal and its intent clear

See [CLAUDE.md](CLAUDE.md) for architecture deep-dive and build setup.

---

## License

[Apache 2.0](LICENSE)

---

<p align="center">
  <b>If Llama Bro saved you a weekend, give it a ⭐</b><br>
  Built with ❤️ for the Android + Local AI community.
</p>
