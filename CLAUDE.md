# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Llama Bro** is an Android SDK for on-device LLM inference, wrapping [llama.cpp](https://github.com/ggml-org/llama.cpp) via JNI. It consists of two modules:
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

# Run unit tests (SDK only)
./gradlew :sdk:testDebugUnitTest

# Run a single test class
./gradlew :sdk:testDebugUnitTest --tests "com.suhel.llamabro.sdk.chat.internal.LlamaChatSessionImplTest"

# Clean
./gradlew clean
```

**NDK requirement:** NDK 29.0.14206865 and CMake 3.22.1 must be installed via the Android SDK Manager. The project only builds for `arm64-v8a`.

## Architecture

### Layer Stack

```
UI (Jetpack Compose)
  ↓
ViewModels (MVVM, Hilt-injected)
  ↓
SDK Public API (internal implementations)
  LlamaEngine → LlamaSession → LlamaChatSession
  ↓
Internal Pipeline (Declarative Flows)
  session.generateFlow() -> Lexer -> Semantic Chunks -> Snapshot
  ↓
JNI Bridge (Kotlin ↔ Native Structs)
  ↓
Native C++ (llama.cpp)
```

### SDK Module

Three tiers of API, each building on the previous:

1. **`LlamaEngine`** — manages model weights. Use `LlamaEngine.createFlow(modelDefinition)` for reactive loading that emits `ResourceState<LlamaEngine>`.

2. **`LlamaSession`** — mutex-serialized token control. Call `setPrefixedPrompt()`, then use `generateFlow()` to stream native tokens via `channelFlow`.

3. **`LlamaChatSession`** — high-level conversational API. `completion(ChatEvent.UserEvent)` returns `Flow<CompletionSnapshot>`. Internally uses a DFA-based `AllocationOptimizedScanner` to extract text, thinking blocks, and tool calls.

**`ResourceState<T>`** — lifecycle ADT (`Loading`, `Success`, `Failure`) with rich Flow extension operators (`flatMapResource`, `filterSuccess`).

**`ChatEvent`** — sealed hierarchy for conversation history. `AssistantEvent` is parts-based (Text, Thinking, ToolCall).

**`LlamaError`** — sealed error hierarchy mapped from native codes/exceptions.

### App Module

Standard MVVM with Hilt DI:
- **`ModelRepository`** — manages engine lifecycle.
- **`ChatRepository`** — Room-backed storage.
- **`ModelZoo`** — curated list of GGUF models with optimal `ModelDefinition` presets.

### JNI / Native

- `sdk/src/main/cpp/jni/` — JNI entry points that convert Kotlin calls to C++ and map C++ exceptions to `LlamaError` subtypes via `NativeErrorMapper`.
- `sdk/src/main/cpp/session.cpp` — C++ session implementation wrapping `llama_context`.
- OpenCL/GPU is intentionally disabled (causes UI stalls on mobile). OpenMP multi-threading is enabled.

## Key Configuration Classes

| Class | Purpose |
|---|---|
| `ModelDefinition` | `ModelLoadConfig` (path, threads, mmap) + `PromptFormat` + `FeatureMarker` |
| `SessionConfig` | `contextSize`, `OverflowStrategy`, `InferenceConfig`, `DecodeConfig` |
| `PromptFormat` | Role markers, `stopStrings`, prefix injection logic |

## Testing

Unit tests in `sdk/src/test/`:
- `AllocationOptimizedScannerTest` — DFA lexing logic.
- `PromptFormatterTest` — chat template serialization.
- `LlamaChatSessionImplTest` — full pipeline integration (replayed via FakeSession).
- `ResourceStateTest` — state transition logic.
