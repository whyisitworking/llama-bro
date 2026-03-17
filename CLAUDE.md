# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Llama Bro** is an Android SDK for on-device LLM inference, wrapping [llama.cpp](https://github.com/ggerganov/llama.cpp) via JNI. It consists of two modules:
- **`:sdk`** — reusable Android library (published to JitPack)
- **`:app`** — demo application showcasing the SDK

The llama.cpp engine is vendored as a git submodule at `sdk/src/main/cpp/external/llama.cpp`.

## Build Commands

```bash
# Initialize submodules (required after clone)
git submodule update --init --recursive

# Build SDK AAR
./gradlew :sdk:assembleRelease

# Build demo app (debug)
./gradlew :app:assembleDebug

# Install debug app on connected device
./gradlew :app:installDebug

# Run unit tests (SDK only; no instrumentation tests exist)
./gradlew :sdk:test

# Run a single test class
./gradlew :sdk:test --tests "com.suhel.llamabro.sdk.util.PromptFormatterTest"

# Publish SDK to local Maven repository (used by JitPack)
./gradlew :sdk:publishToMavenLocal

# Clean
./gradlew clean
```

**NDK requirement:** NDK 29.0.14206865 and CMake 3.22.1 must be installed via the Android SDK Manager. The project only builds for `arm64-v8a` — x86_64 emulators are not supported.

## Architecture

### Layer Stack

```
UI (Jetpack Compose)
  ↓
ViewModels (MVVM, Hilt-injected)
  ↓
Repositories (ChatRepository, ModelRepository)
  ↓
SDK Public API  ─────────────────────────────────────────────
  LlamaEngine → LlamaSession → LlamaChatSession
  ↓
JNI Bridge (llama_engine_jni.cpp, llama_session_jni.cpp)
  ↓
Native C++ (session.cpp, engine.cpp → llama.cpp)
```

### SDK Module

Three tiers of API, each building on the previous:

1. **`LlamaEngine`** — loads a GGUF model file from disk; creates sessions. Use `LlamaEngine.createFlow(modelConfig)` for reactive loading that emits `ResourceState<LlamaEngine>`.

2. **`LlamaSession`** — low-level token control. Call `setSystemPrompt()`, then loop `prompt()` + `generate()` to produce tokens. Wrap using `createChatSession()` to get the high-level API.

3. **`LlamaChatSession`** — high-level conversational API. `completion(message)` returns `Flow<Completion>`. Handles prompt template formatting, thinking-block extraction (`<think>...</think>`), and `OverflowStrategy`.

**`ResourceState<T>`** is the lifecycle ADT used throughout. It has subtypes `Loading(progress)`, `Success(value)`, `Failure(error)` and rich Flow extension operators (`flatMapResource`, `filterSuccess`, etc.) for composing resource loads.

**`PromptFormat`** / **`PromptFormats`** — chat template definitions. Built-in formats: `Llama3`, `Gemma3`, `ChatML` (Qwen/Yi), `Mistral`. Each model in `ModelZoo` references one of these.

**`LlamaError`** — sealed error hierarchy (`ModelNotFound`, `ModelLoadFailed`, `ContextOverflow`, `DecodeFailed`, `Cancelled`, `NativeException`, etc.).

### App Module

Standard MVVM with Hilt DI:

- **`ModelRepository`** — singleton managing model download/load/eject lifecycle. Download state is a FSM: `NotDownloaded → Downloading → Downloaded`. The currently loaded engine is exposed as `currentInferenceContextFlow: StateFlow<CurrentInferenceContext?>`.
- **`ChatRepository`** — Room-backed CRUD for conversations and messages.
- **`ModelZoo`** — hardcoded list of 6 pre-curated GGUF models (SmolLM2 135M–1.7B, Qwen2.5 0.5B, Llama-3.2 1B, DeepSeek-R1 1.5B) with download URLs and default configs.
- Navigation uses type-safe `Route` sealed class with Jetpack Navigation Compose.

### JNI / Native

- `sdk/src/main/cpp/jni/` — JNI entry points that convert Kotlin calls to C++ and map C++ exceptions to `LlamaError` subtypes via `NativeErrorMapper`.
- `sdk/src/main/cpp/session.cpp` — C++ session implementation wrapping `llama_context`.
- OpenCL/GPU is intentionally disabled (causes UI stalls on mobile). OpenMP multi-threading is enabled.

## Key Configuration Classes

| Class | Purpose |
|---|---|
| `ModelConfig` | Model path, `PromptFormat`, MMAP/MLOCK flags, thread count |
| `SessionConfig` | Context size, `OverflowStrategy`, `InferenceConfig`, `DecodeConfig` |
| `InferenceConfig` | Temperature, top-p/k, min-p, repeat penalty |
| `DecodeConfig` | Batch sizes for performance tuning |
| `PromptFormat` | Per-role prefix/suffix tokens, BOS/EOS, `<think>` tag markers |

## Testing

Unit tests live in `sdk/src/test/` — currently only `PromptFormatterTest` covering chat template formatting. There are no instrumentation tests. New SDK behavior should be covered in this test source set.
