# Llama-Bro

An Android SDK that runs llama.cpp's llama-server core in-process, with a Kotlin API
(`io.github.whyisitworking:llamabro`). Start with [docs/README.md](docs/README.md) for the design, decisions and phase
plans, and [sdk/README.md](sdk/README.md) for using the SDK.

## Open it in Android Studio

Open **this folder** (the repository root) and let Gradle sync. The root is one Gradle build over the SDK's modules and
the demo app, so the Android view shows them all and the run menu offers the app:

| To run | Gradle module | Build variant |
|---|---|---|
| The demo chat app | `:demo:app` | `debug` (or `release`, minified) |

Needs the Android SDK, NDK 29 and CMake 4.1.2 (`local.properties` holds `sdk.dir`; Android Studio writes it) and the
llama.cpp submodule (`git submodule update --init sdk/native/third_party/llama.cpp`).

`:acceptance:backends` and `:acceptance:download` are the SDK's phone test apps (no UI to speak of); `check.sh --device`
drives them, you don't run them by hand. The libraries (`llamabro`, `llamabro-hub`, ...) are not runnable either; their tests run from the Gradle tool window or the
command line.

## Folders

| Folder | What it is |
|---|---|
| `sdk/` | The SDK: `llamabro` (core), `llamabro-hub` (Hugging Face downloads), `llamabro-hexagon` and `llamabro-opencl` (optional backends), `llamabro-testing` (a fake for your tests), `acceptance/` (the phone test apps), `native/` (the C++ bridge and the llama.cpp submodule), `scripts/` (the checks) |
| `docs/` | Design, decisions (`D<n>`), findings (`F<n>`), phase plans, the working playbook |
| `spike/` | Throwaway experiments and their raw results, cited by the findings. Not part of any build |
| `gradle/` | The Gradle wrapper and the shared version catalog (`libs.versions.toml`) |
| `demo/` | The demo chat app (phase PD): `app` and `designsystem`. Part of the root build (merged from `sdk-v2/pd-demo` on 2026-11-13) |

The root `settings.gradle.kts` and `sdk/settings.gradle.kts` read the same SDK module list, `sdk/modules.gradle.kts`: the
root build (SDK plus demo) is for Android Studio and the demo's checks, and `./gradlew -p sdk …` is the SDK alone, which
the SDK's checks use. They share `build/` folders, so don't run both at once. A new SDK module is added in that one file.

## Checks

```bash
sdk/scripts/check.sh            # everything on the host; must print ALL PASS
sdk/scripts/check.sh --device   # plus the phone tests (a phone on adb)
demo/scripts/check.sh           # the demo app (add --device for its phone tests, the journeys, the monkey, coverage and the requirement map)
```
