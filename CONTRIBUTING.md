# Contributing

Thanks for looking at Llama Bro. This guide covers setup, how changes are checked, and what a good change looks like.
For how the project is put together, read [docs/architecture.md](docs/architecture.md) first.

## Setup

- JDK 17, the Android SDK (API 37), NDK 29.0.14206865 and CMake 4.1.2. The core library (`sdk/llamabro`) needs nothing
  else: Gradle's `prepareLlama` task builds the patched llama.cpp copy from the submodule and `sdk/native/patches/`
  (bash and git only).
- **Docker, running,** for the NPU and GPU backend modules (`sdk/llamabro-hexagon`, `sdk/llamabro-opencl`) and anything
  that includes them (the demo app, the acceptance apps). Their native libraries are built by the `buildBackends` task in
  Qualcomm's Snapdragon toolchain image (`ghcr.io/snapdragon-toolchain/arm64-android:v0.8`, about 10 GB, pulled on the
  first build; it carries the Hexagon SDK). On an Apple Silicon Mac it runs under x86 emulation, so the first build takes a
  while.
- Node 20+ and Python 3 (a few checks regenerate goldens or parse results).
- `git submodule update --init sdk/native/third_party/llama.cpp` (shallow).
- Open the repository root in Android Studio, or use `./gradlew` from the root. The root build covers the SDK modules and the
  demo; `./gradlew -p sdk ...` builds the SDK alone. Both write to the same `build/` folders, so run one at a time.
- The SDK's host checks run real models on your computer: `~/Downloads/Qwen3.5-4B-Q4_K_M.gguf` (and optionally
  `~/Downloads/mmproj-F16.gguf` and `~/Downloads/Llama-3.2-3B-Instruct-Q4_K_M.gguf` for the contract tests).
  `sdk/scripts/fetch-test-models.sh` downloads the small test models.

## Checks

Run the check for the area you changed, and a change is ready when it prints `ALL PASS`.

| You changed | Run |
|---|---|
| Anything under `sdk/` | `sdk/scripts/check.sh` |
| Native code, JNI, packaging, backends | `ANDROID_SERIAL=<serial> sdk/scripts/check.sh --device` |
| The demo app or its design system | `demo/scripts/check.sh` (add `DEMO_DEVICE=<serial> ... --device` for the instrumented tests and journeys) |
| Docs only | re-read every link you touched |

`--only step1,step2` runs steps while you iterate; it is not the final check. [docs/testing.md](docs/testing.md) explains what
each step proves and what it needs. Two conventions: a check runs once per change (debug on the narrowest slice, then run
it), and benchmarks run only when a change could move a budget.

Style is enforced: `./gradlew ktlintFormat` formats Kotlin (ktlint, official style), `./gradlew qualityCheck` runs ktlint
and detekt, and lint runs with warnings as errors. A public API change fails the API check: review the dump diff and update
it with `./gradlew -p sdk :<module>:apiDump`, committing the `.api` file with the change.

## Upgrading llama.cpp

1. Read [`sdk/native/patches/README.md`](sdk/native/patches/README.md). For each patch, check its "Drop when": a patch
   carried from an upstream pull request is deleted once that change is in the new pin.
2. Move the submodule to the new build tag and update the pin's name where the build reports it (`LB_LLAMA_BUILD` in
   `sdk/native/CMakeLists.txt`, the version tests, the hub's user agent, the demo's About row).
3. Re-port any patch that no longer applies, then re-measure it on the phone; a patch that no longer wins is dropped.
4. Diff the upstream files the SDK ports or copies (each cites its source file and build in a comment) and port the
   changes.
5. `sdk/scripts/check.sh` and `--device`. A failing contract, `tools/ui` or hub golden is the list of upstream changes to
   review (F31); accept a new golden only once the SDK's port matches it.
6. Record a finding for the upgrade (what changed for the SDK, what was re-measured) and a decision for the new pin.

## Principles

- **Upstream is the baseline.** Behaviour and defaults follow llama.cpp's `llama-server` at the pinned build; differences
  need a measurement behind them. The contract test compares the SDK with upstream on the same inputs.
- **Measure before deciding.** A claim about speed, memory or correctness comes with a reproducible run. Experiments live
  in [`research/`](research/README.md) and their results in [docs/research/findings.md](docs/research/findings.md)
  (`F<n>`); choices that follow are recorded in [docs/decisions.md](docs/decisions.md) (`D<n>`). Code comments cite these IDs.
- **No personal or machine-specific data in the repository:** no device serials, home paths, tokens or keys. Scripts take the
  device from `ANDROID_SERIAL` or `DEMO_DEVICE`.
- **Do not edit `sdk/native/third_party/`.** Changes to llama.cpp go through the patch set in `sdk/native/patches/` and are
  verified by the patched-copy check.

## Making a change

1. Branch from `v2`. Name the branch for what it does.
2. Keep the change focused. Add or update tests with the code; a behaviour with no test is not finished.
3. If you learn something measurable, add a finding (`F<n>`, with the method and numbers) and update the experiment that
   produced it; if you choose between options, add a decision (`D<n>`).
4. Run the checks above. Commit with a message that says what changed and why, in the imperative mood.
5. Open a pull request describing the change, how you verified it, and anything you could not verify.

## Reporting a problem

Open an issue with the device and Android version, the model and quantization, what you ran, and the log. For a wrong or
missing result from a model, say which backend ran (`Model.loadReport` lists it).
