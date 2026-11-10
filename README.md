# Llama-Bro

An Android SDK that runs llama.cpp's llama-server core in-process, with a Kotlin API
(`io.github.whyisitworking:llamabro`). Start with [docs/README.md](docs/README.md) for the design, decisions and phase
plans, and [sdk/README.md](sdk/README.md) for using the SDK.

## Open it in Android Studio

Open **this folder** (the repository root) and let Gradle sync. The root is one Gradle build over the SDK's modules
and samples, so the Android view shows them and the run menu offers the sample apps (choose the build variant in Build Variants first):

| To run | Gradle module | Build variant |
|---|---|---|
| The SDK's sample chat app | `:samples:minimal` | `cpuDebug` (CPU only), `gpuDebug` (+ Adreno OpenCL) or `fullDebug` (+ Hexagon NPU) |
| The download-in-the-background sample | `:samples:background-download` | `debug` |

Needs the Android SDK, NDK 29 and CMake 4.1.2 (`local.properties` holds `sdk.dir`; Android Studio writes it) and the
llama.cpp submodule (`git submodule update --init sdk/native/third_party/llama.cpp`).

Library modules (`llamabro`, `llamabro-hub`, …) are not runnable; their tests run from the Gradle tool window or the
command line.

## Folders

| Folder | What it is |
|---|---|
| `sdk/` | The SDK: `llamabro` (core), `llamabro-hub` (Hugging Face downloads), `llamabro-hexagon` and `llamabro-opencl` (optional backends), `llamabro-testing` (a fake for your tests), `samples/`, `native/` (the C++ bridge and the llama.cpp submodule), `scripts/` (the checks) |
| `docs/` | Design, decisions (`D<n>`), findings (`F<n>`), phase plans, the working playbook |
| `spike/` | Throwaway experiments and their raw results, cited by the findings. Not part of any build |
| `gradle/` | The Gradle wrapper and the shared version catalog (`libs.versions.toml`) |
| `demo/` | The demo chat app (phase PD). Its own Gradle build; it lives on the `sdk-v2/pd-demo` branch until merged |

The root `settings.gradle.kts` and `sdk/settings.gradle.kts` read the same module list, `sdk/modules.gradle.kts`: the root
build is for Android Studio, and `./gradlew -p sdk …` is the SDK as its own build, which the checks and the demo use. A
new module is added in that one file.

## Checks

```bash
sdk/scripts/check.sh            # everything on the host; must print ALL PASS
sdk/scripts/check.sh --device   # plus the phone tests (a phone on adb)
```
