# Llama Bro
**On-device LLM inference SDK for Android, powered by [llama.cpp](https://github.com/ggerganov/llama.cpp).**

[![](https://jitpack.io/v/whyisitworking/llama-bro.svg)](https://jitpack.io/#whyisitworking/llama-bro)

Run GGUF models directly on Android devices with a clean Kotlin coroutine API — no server, no network, and fully privacy-preserving. Built specifically for modern Android development architectures.

---

## ⚡ Features

- **Local & Private:** 100% on-device inference with zero network dependency.
- **Kotlin-First API:** Coroutine-native Flows for seamless token streaming and structured concurrency.
- **Thread-Safe & Cancellable:** Safely execute Inference from any Coroutine Scope natively supporting preemption to prevent UI stalls.
- **SIMD Auto-Selection:** Dynamically loads the optimal GGML backend tailored to the host CPU's hardware instructions.
- **Advanced Parsing:** Built-in extraction for `<think>` blocks, supporting next-generation reasoning models (e.g., DeepSeek).
- **Graceful Overflow Handling:** Easily configurable context strategies: Halt, ClearHistory, or RollingWindow.

---

## 📈 Performance
Llama Bro is highly optimized for modern mobile silicon, leveraging multi-threading and target-specific GGML backends. 
- Benchmarked **~20 tok/s** on a **OnePlus 13 (Snapdragon 8 Elite)** running the **Gemma 3n 2B (Q4_K_M)** quantized model.

---

## 📦 Installation

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.whyisitworking:llama-bro:0.1.0")
}
```

---

## 🚀 Usage

### Flow-Based Managed Usage (Recommended)
Llama-Bro provides built-in `callbackFlow` mechanisms to safely manage the unmanaged C++ pointers. By using `createSessionFlow`, the native session is automatically closed and its memory freed as soon as the coroutine flow completes, is cancelled, or errors out.

```kotlin
// 1. Initialise the engine
val engine = LlamaEngine.create(
    ModelConfig(
        modelPath = "/data/local/tmp/model.gguf",
        promptFormat = PromptFormats.ChatML
    )
)

// 2. Launch a managed session flow
lifecycleScope.launch {
    engine.createSessionFlow(SessionConfig(systemPrompt = "You are a helpful assistant."))
        .collect { event ->
            when (event) {
                is LoadEvent.Loading -> showLoadingState()
                
                // The session is ready and will remain open as long as this block holds it.
                // It will be natively closed automatically once the flow collection finishes.
                is LoadEvent.Ready -> {
                    val chat = LlamaChatSession(event.resource)
                    
                    // Chat returns a Flow of Generation chunks (Text, Thinking blocks, Metrics)
                    chat.chat("Write a poem about Kotlin.").collect { chunk ->
                        updateUI(chunk.contentText)
                        updateThinkingUI(chunk.thinkingText)
                        
                        // Fired when EOS is reached
                        if (chunk.isComplete) {
                            Log.d("LlamaBro", "Speed: ${chunk.tokensPerSecond} t/s")
                        }
                    }
                }
                
                is LoadEvent.Error -> showError(event.error)
            }
        }
}
```

### Standard Async Engine Loading
If your model payload is large, you can observe the native load progress dynamically using the `LlamaEngine.createFlow` facade.

```kotlin
LlamaEngine.createFlow(ModelConfig(
    modelPath = "/data/local/tmp/model.gguf",
    promptFormat = PromptFormats.Llama3,
)).collect { event ->
    when (event) {
        is LoadEvent.Loading -> updateProgressBar(event.progress)
        is LoadEvent.Ready -> startChat(event.resource)
        is LoadEvent.Error -> handleError(event.error)
    }
}
```

---

## 🏗 Architecture

The SDK is strictly layered to ensure robust memory boundaries between the JVM and native Android environment.

```text
┌──────────────────────────────────────────────┐
│  LlamaChatSession  (high-level chat API)     │
│  ├─ Thinking-tag parser                      │
│  ├─ Token accumulation via Flow.scan()       │
│  └─ Tokens-per-second metrics                │
├──────────────────────────────────────────────┤
│  LlamaSession  (raw prompt/generate)         │
│  ├─ Prompt formatting per PromptFormat       │
│  ├─ Kotlin Coroutine Mutex Thread-Safety     │
│  └─ KV cache overflow management             │
├──────────────────────────────────────────────┤
│  LlamaEngine  (model loading + lifecycle)    │
│  ├─ Progress callbacks during loading        │
│  └─ Session factory                          │
├──────────────────────────────────────────────┤
│  C++ / JNI  (llama.cpp integration)          │
│  ├─ RAII memory management + Graceful Abort  │
│  ├─ Typed error codes across JNI boundary    │
│  └─ UTF-8 → UTF-16 streaming conversion     │
└──────────────────────────────────────────────┘
```

---

## ⚙️ Configuration

### `ModelConfig`
| Parameter      | Type           | Default                  | Description                            |
|----------------|----------------|--------------------------|----------------------------------------|
| `modelPath`    | `String`       | —                        | Absolute path to `.gguf` file          |
| `promptFormat` | `PromptFormat` | —                        | Structural chat template for the model |
| `useMmap`      | `Boolean`      | `true`                   | Memory-map the model file              |
| `useMlock`     | `Boolean`      | `false`                  | Lock model pages in RAM (Prevents Swap)|
| `threads`      | `Int`          | `availableProcessors/2`  | Hard inference thread count            |

### `SessionConfig`
| Parameter          | Type               | Default              | Description                            |
|--------------------|--------------------|----------------------|----------------------------------------|
| `systemPrompt`     | `String`           | —                    | Root agent instruction                 |
| `contextSize`      | `Int`              | `4096`               | Context window upper-limit             |
| `overflowStrategy` | `OverflowStrategy` | `RollingWindow(500)` | How to naturally clear KV constraints  |
| `inferenceConfig`  | `InferenceConfig`  | Defaults             | Sampling constants                     |
| `decodeConfig`     | `DecodeConfig`     | Defaults             | Batch tuning properties                |
| `seed`             | `Int`              | `-1` (Random)        | Predictable RNG seed                   |

### `InferenceConfig`
| Parameter       | Type     | Default | Description                            |
|-----------------|----------|---------|----------------------------------------|
| `temperature`   | `Float`  | `0.8`   | Output randomness (0.0 = greedy)       |
| `repeatPenalty` | `Float`  | `1.1`   | Structural repetition suppression      |
| `minP`          | `Float?` | `0.1`   | Min-P sampling (null = disabled)       |
| `topP`          | `Float?` | `null`  | Top-P / nucleus sampling threshold     |
| `topK`          | `Int?`   | `null`  | Top-K absolute sampling ceiling        |

---

## 🧠 Supported Models

The architecture is prompt-format agnostic. As long as a model runs safely in `llama.cpp` natively, it will work here. Ensure you match the correct `PromptFormat`:

| PromptFormat            | Recommended Ecosystem Models               |
|-------------------------|--------------------------------------------|
| `PromptFormats.ChatML`  | Qwen, Yi, InternLM, General Finetunes      |
| `PromptFormats.Llama3`  | Llama 3, Llama 3.1, Llama 3.2              |
| `PromptFormats.Gemma3`  | Gemma 3, Gemma 3n                          |
| `PromptFormats.Mistral` | Mistral 7B, Mixtral                        |

---

## 🛠 Building from Source

To experiment or contribute to the library:

1. Clone with submodules since `llama.cpp` is deeply vendored via CMake:
```bash
git clone --recursive https://github.com/user/llama-bro.git
cd llama-bro
```

2. Perform your modifications and test locally:
```bash
# Build the core SDK AAR
./gradlew :sdk:assembleRelease

# Execute the local unit test suite
./gradlew :sdk:test

# Compile the testing/demonstration android application
./gradlew :app:assembleDebug
```

> **Requirements:** JDK 17+, Android SDK 36, NDK 29.0.14206865, CMake 3.22.1
