# Contributing

Thanks for looking at Llama Bro. This guide covers setup, how changes are checked, and what a good change looks like.
For how the project is put together, read [docs/architecture.md](docs/architecture.md) first.

## Setup

- JDK 17, the Android SDK (API 37), the NDK pinned in `sdk/native/ndk.version` and CMake 4.1.2. The core library (`sdk/llamabro`) needs nothing
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

Style is enforced: `./gradlew ktlintFormat` formats Kotlin (ktlint, official style), `./gradlew qualityCheck` runs ktlint,
detekt (with type resolution on main code) and an unused-import check, lint runs with warnings as errors, and the bridge and JNI code compile with
`-Wall -Wextra -Werror`. CI (`.github/workflows/ci.yml`) runs the checks that need no model and no device; the rest stay
in the scripts above. A public API change fails the API check: review the dump diff and update
it with `./gradlew -p sdk :<module>:apiDump`, committing the `.api` file with the change.

## Upgrading llama.cpp

1. Read [`sdk/native/patches/README.md`](sdk/native/patches/README.md). For each patch, check its "Drop when": a patch
   carried from an upstream pull request is deleted once that change is in the new pin.
2. Move the submodule to the new build tag and update the pin's name where the build reports it (`LB_LLAMA_BUILD` in
   `sdk/native/CMakeLists.txt`, the version tests, the hub's user agent, the demo's About row).
3. Re-port any patch that no longer applies, then re-measure it on the phone; a patch that no longer wins is dropped.
4. Diff the upstream files the SDK ports or copies (each cites its source file and build in a comment) and port the
   changes.
5. `sdk/scripts/check.sh` and `--device`. A failing contract, `tools/ui` (Llama Chat, `ui/apps/chat` since b12479) or
   hub golden is the list of upstream changes to review (F31); accept a new golden only once the SDK's port matches it.
   A host memory bound that moves is checked against the one upstream commit that moved it (F164).
6. Record a finding for the upgrade (what changed for the SDK, what was re-measured) and a decision for the new pin.

## Releasing the demo app

[`.github/workflows/release.yml`](.github/workflows/release.yml) runs CI's checks, then builds the demo's minified
release APK signed with the upload key in the repository's secrets. A version tag makes a GitHub release with the APK
and its SHA-256, once the SDK's Maven Central upload has also succeeded. It is never marked a pre-release, even for
`-alpha`, since the README's download links use `releases/latest`; a manual run (Actions, Release, Run workflow) keeps it as a workflow artifact. The version name is
the tag; the version code is 1000 plus the run number, so it only grows. Google Play uploads are made by hand.

```bash
git tag 2.0.0-alpha && git push origin 2.0.0-alpha
```

Every release build signs with the upload key when these four Gradle properties are set, on the maintainer's machine in
`~/.gradle/gradle.properties`: `LLAMABRO_UPLOAD_STORE_FILE` (the keystore's path), `LLAMABRO_UPLOAD_STORE_PASSWORD`,
`LLAMABRO_UPLOAD_KEY_ALIAS` and `LLAMABRO_UPLOAD_KEY_PASSWORD`. Without them it signs with the debug key, so anyone's
release build still installs. The workflow sets the same properties from four repository secrets:
`LLAMABRO_KEYSTORE_BASE64` (the output of `base64 -i upload.jks`), `LLAMABRO_KEYSTORE_PASSWORD`, `LLAMABRO_KEY_ALIAS`
and `LLAMABRO_KEY_PASSWORD`. The key itself is made once and kept outside the repository:

```bash
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

With Play App Signing, Google keeps the key that signs what users install and this one only proves an upload is yours;
if it is lost, Play support can register a new one.

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

1. Branch from the default branch. Name the branch for what it does.
2. Keep the change focused. Add or update tests with the code; a behaviour with no test is not finished.
3. If you learn something measurable, add a finding (`F<n>`, with the method and numbers) and update the experiment that
   produced it; if you choose between options, add a decision (`D<n>`).
4. Run the checks above. Commit with a message whose first line is `area: what is true now` (`hub: …`, `demo: …`) and whose body says why.
5. Open a pull request describing the change, how you verified it, and anything you could not verify.

## Publishing

The SDK's modules are published to Maven Central by the maintainer, by hand. Every published module applies the
`llamabro.publish` convention (`sdk/build-logic`), which sets its coordinates, POM and signing; a new module adds that
plugin and a `description`. `./gradlew -p sdk publishToMavenLocal` shows what would be uploaded and needs no key or
account. The setup, the properties and the release steps are in [docs/publishing.md](docs/publishing.md).

## Reporting a problem

Open an issue with the device and Android version, the model and quantization, what you ran, and the log. For a wrong or
missing result from a model, say which backend ran (`Model.loadReport` lists it).
