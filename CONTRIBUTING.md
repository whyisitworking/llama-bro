# Contributing to Llama Bro

Thanks for being here. Llama Bro has one maintainer, so every report, fix and run on a new phone helps. This guide
covers how to help, how to set up, and what a change needs before it's merged.

- [Ways to help](#ways-to-help)
- [Ground rules](#ground-rules)
- [Setup](#setup)
- [Making a change](#making-a-change)
- [Checks](#checks)
- [Style](#style)
- [Pull requests](#pull-requests)
- [For maintainers](#for-maintainers)

## Ways to help

**Run it on your phone.** Only one phone is tested so far, a OnePlus 13. A report from any other phone is the most
useful contribution there is, and it takes ten minutes: install the
[demo APK](https://github.com/whyisitworking/llama-bro/releases/latest/download/llama-bro-demo.apk), download a small
model, chat with it on each chip (Settings › Processor, where the phone offers a choice), and open a
[device report](https://github.com/whyisitworking/llama-bro/issues/new?template=device-report.yml). Failures are as
useful as successes: "the NPU gave garbage" is a good report.

**Report a bug.** Open a [bug report](https://github.com/whyisitworking/llama-bro/issues/new?template=bug-report.yml)
with the phone, the model, what you ran, and the log. Say which chip the model ran on: `Model.loadReport.device` in your
app, or Settings › Processor in the demo.

**Fix something.** Bugs, documentation, tests and examples are all welcome as pull requests. For anything that changes
the public API or how the SDK behaves, open an issue first, so we can agree on the shape before you spend time on it.

**Propose a feature.** The SDK's scope is what llama.cpp's `llama-server` does, exposed well in Kotlin. A feature that
upstream has and the SDK lacks is in scope. A feature upstream doesn't have usually belongs upstream first, or in your
app.

## Ground rules

These keep the project trustworthy. Every merged change follows them.

- **Upstream is the baseline.** Behaviour and defaults follow `llama-server` at the pinned llama.cpp build. The
  contract test sends the same requests to the SDK and to upstream's server and expects the same answers. A deviation
  needs a measurement that shows it's better, cited next to the code.
- **Measure before deciding.** A claim about speed, memory or correctness comes with a run anyone can repeat.
  Experiments live in [`research/`](research/README.md), results in [findings](docs/research/findings.md) (`F<n>`),
  and the choices that follow in [decisions](docs/decisions.md) (`D<n>`).
- **The SDK makes no policy for the app.** It does what it's told and reports facts, such as whether a model fits.
  Whether to refuse, warn or wait for Wi-Fi is the app's call.
- **Changes to llama.cpp are patches.** Never edit `sdk/native/third_party/`. Every change goes in
  [`sdk/native/patches/`](sdk/native/patches/README.md), with why it exists, its evidence, and when to drop it.
- **Nothing personal or machine-specific in the repository:** no device serials, home paths, tokens, keys, logs,
  screenshots, model files or build output. Scripts read the device from `ANDROID_SERIAL` or `DEMO_DEVICE`. The one
  exception is the curated README images in `assets/readme/` ([D132](docs/decisions.md#d132)).

## Setup

What you need depends on what you're changing.

| You're working on | You need |
|---|---|
| The core SDK (`sdk/llamabro` and the pure-Kotlin modules) | JDK 17 or later, the Android SDK (API 37), the NDK pinned in `sdk/native/ndk.version`, CMake 4.1.2, bash and git |
| The NPU or GPU backends, the demo app, the acceptance apps | All of the above, plus **Docker, running** |
| The SDK's host checks | Python 3, Node 20 or later, and the test models below |

Then:

```bash
git submodule update --init sdk/native/third_party/llama.cpp   # a shallow clone
```

- **Opening the project.** Open the repository root in Android Studio, or run `./gradlew` from it. The root build covers
  the SDK and the demo; `./gradlew -p sdk …` builds the SDK alone. Both write to the same `build/` folders, so run one
  at a time.
- **llama.cpp.** Gradle's `prepareLlama` task copies the submodule and applies the patches before every native build.
- **The backends** build in Qualcomm's Snapdragon toolchain image (`ghcr.io/snapdragon-toolchain/arm64-android:v0.8`,
  about 10 GB, pulled on the first build), which carries the Hexagon SDK. On an Apple Silicon Mac it runs under x86
  emulation, so the first build takes a while.
- **Test models.** The host checks run real models on your computer: `~/Downloads/Qwen3.5-4B-Q4_K_M.gguf`, and
  optionally `~/Downloads/mmproj-F16.gguf` and `~/Downloads/Llama-3.2-3B-Instruct-Q4_K_M.gguf` for the contract tests.
  `sdk/scripts/fetch-test-models.sh` downloads the small ones (about 265 MB).

A map of the repository is in [CLAUDE.md](CLAUDE.md#map), and how the pieces fit is in the
[architecture](docs/architecture.md).

## Making a change

1. **Branch** from `main`, named for what the change does.
2. **Keep it focused.** One change per pull request. Add or update tests with the code: behaviour without a test isn't
   finished.
3. **Record what you learn.** A measurement becomes a finding and a choice between options becomes a decision. Add new
   entries at the end of [findings](docs/research/findings.md) or [decisions](docs/decisions.md), with an
   `<a id="f<n>"></a>` or `<a id="d<n>"></a>` anchor on the line above the heading. Never renumber. Code cites them as
   bare IDs (`F75`); public KDoc links to the entry on GitHub (`…/blob/main/docs/decisions.md#d<n>`).
4. **Public API.** A change to the public API fails the API check. Review the dump diff, update it with
   `./gradlew -p sdk :<module>:apiDump`, and commit the `.api` file with the change.
5. **Docs.** A new capability gets a section in the [SDK guide](sdk/README.md), and a line in the README's examples if
   it's one an app developer would look for. A change apps will notice goes in the
   [release notes](docs/release-notes.md) under Unreleased.
6. **Commit** with a first line that says what is true now, prefixed by its area (`hub: downloads resume after a
   reboot`, `demo: …`, `docs: …`), and a body that says why.

## Checks

Run the check for the area you changed. A change is ready when it prints `ALL PASS`.

| You changed | Run |
|---|---|
| Anything under `sdk/` | `sdk/scripts/check.sh` |
| Native code, JNI, packaging or the backends | `ANDROID_SERIAL=<serial> sdk/scripts/check.sh --device` |
| The demo app or its design system | `demo/scripts/check.sh`, plus `DEMO_DEVICE=<serial> demo/scripts/check.sh --device` for the instrumented tests and journeys |
| The README or `BENCHMARKS.md` | `sdk/scripts/check.sh --only scripts`, which checks every benchmark number and link anchor |
| Other docs | Re-read every link you touched |

While you iterate, `--only step1,step2` runs single steps. It isn't the final check. Run the full check once, after the
fix, rather than over and over. [Testing](docs/testing.md) explains what each step proves and what it needs.

Two rules for real models: never run inference on an emulator (the demo's journeys use a fake engine and run anywhere),
and keep phone runs short and attended. Benchmarks (`demo/scripts/check.sh --device --perf`, `sdk/scripts/bench.sh`)
only run when a change could move a budget.

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs the checks that need no model and no device. The rest
are yours to run, and the pull request says which ones you ran.

## Style

- **Kotlin:** ktlint's official style, detekt, and Android lint with warnings as errors. Four-space indent, 120 columns.
  `./gradlew ktlintFormat` formats the code, and `./gradlew qualityCheck` runs ktlint, detekt and the unused-import
  check.
- **Native:** the bridge and the JNI code compile with `-Wall -Wextra -Werror`.
- **Names and comments:** name things for what they are. Comments explain why, not what.
- **The demo app:** sizes, colours and type come from `:demo:designsystem`'s tokens, and screens are built from its
  components. A lint rule enforces it ([D113](docs/decisions.md#d113)). Prefer small composables and stateless screens,
  with UI state derived declaratively (`combine`, `stateIn`).
- **New SDK logic** prefers multiplatform-friendly choices where they cost little (kotlinx.serialization, coroutines,
  Okio or kotlinx-io, pure-Kotlin helpers), and keeps `android.*`, `java.net` and JNI at the edges, ready for a later
  Kotlin Multiplatform port.

## Pull requests

A pull request should say:

- **What changed and why**, in a sentence or two.
- **How you verified it:** which checks you ran, on which phone if any, and anything you couldn't verify.
- **The findings or decisions** it adds or relies on.

[The template](.github/pull_request_template.md) asks for exactly this. You own every line you submit, however you
wrote it: run the checks, and be ready to explain it in review.

By contributing, you agree that your work is licensed under the project's [Apache License 2.0](LICENSE).

## For maintainers

### Upgrading llama.cpp

1. Read [`sdk/native/patches/README.md`](sdk/native/patches/README.md). Check each patch's "Drop when": a patch
   carried from an upstream pull request is deleted once that change is in the new pin.
2. Move the submodule to the new build tag, and update the pin's name wherever the build reports it (`LB_LLAMA_BUILD` in
   `sdk/native/CMakeLists.txt`, the version tests, the hub's user agent, the demo's About row).
3. Re-port any patch that no longer applies, then re-measure it on the phone. A patch that no longer wins is dropped.
4. Diff the upstream files the SDK ports or copies (each cites its source file and build in a comment) and port the
   changes.
5. Run `sdk/scripts/check.sh` and `--device`. A failing contract, `tools/ui` (Llama Chat, `ui/apps/chat` since b12479)
   or hub golden is the list of upstream changes to review ([F31](docs/research/findings.md#f31)). Accept a new golden
   only once the SDK's port matches it. A host memory bound that moves is checked against the one upstream commit that
   moved it ([F164](docs/research/findings.md#f164)).
6. Record a finding for the upgrade (what changed for the SDK, what was re-measured) and a decision for the new pin,
   and update the llama.cpp badge in the README.

### Releasing the demo app

[`.github/workflows/release.yml`](.github/workflows/release.yml) runs CI's checks, then builds the demo's minified
release APK, signed with the upload key from the repository's secrets. A version tag makes a GitHub release with the
APK and its SHA-256, once the SDK's Maven Central upload has also succeeded. It's never marked a pre-release, even for
`-alpha`, because the README's download links use `releases/latest`. A manual run (Actions › Release › Run workflow)
keeps the APK as a workflow artifact instead. The version name is the tag, and the version code is 1000 plus the run
number, so it only grows. Google Play uploads are made by hand.

```bash
git tag 2.0.0-alpha && git push origin 2.0.0-alpha
```

Every release build signs with the upload key when these four Gradle properties are set, on the maintainer's machine in
`~/.gradle/gradle.properties`: `LLAMABRO_UPLOAD_STORE_FILE` (the keystore's path), `LLAMABRO_UPLOAD_STORE_PASSWORD`,
`LLAMABRO_UPLOAD_KEY_ALIAS` and `LLAMABRO_UPLOAD_KEY_PASSWORD`. Without them it signs with the debug key, so anyone's
release build still installs. The workflow sets the same properties from four repository secrets:
`LLAMABRO_KEYSTORE_BASE64` (the output of `base64 -i upload.jks`), `LLAMABRO_KEYSTORE_PASSWORD`, `LLAMABRO_KEY_ALIAS`
and `LLAMABRO_KEY_PASSWORD`. The key is made once and kept outside the repository:

```bash
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

With Play App Signing, Google keeps the key that signs what users install, and this one only proves an upload is
yours. If it's lost, Play support can register a new one.

### Publishing the SDK

The SDK's modules are published to Maven Central by the maintainer. Every published module applies the
`llamabro.publish` convention (`sdk/build-logic`), which sets its coordinates, POM and signing; a new module adds that
plugin and a `description`. `./gradlew -p sdk publishToMavenLocal` shows what would be uploaded and needs no key or
account. The setup and the release steps are in [publishing](docs/publishing.md).

### After a release

- Move the release notes' Unreleased entries under the new version.
- In the README, update What's new (it keeps the last two releases) and the version in the installation snippets.
- A milestone worth telling gets a row in the README's timeline.
