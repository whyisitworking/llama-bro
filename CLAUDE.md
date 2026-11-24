# Llama Bro: guide for coding assistants and new contributors

An Android SDK that runs llama.cpp's llama-server core in-process with a Kotlin API, plus a demo chat app. Read
[README.md](README.md) for the overview, [CONTRIBUTING.md](CONTRIBUTING.md) for setup and the change workflow, and
[docs/architecture.md](docs/architecture.md) before changing the SDK.

## Map

- `sdk/llamabro`: the public API and its internals (`chat/`, `conversation/`, `audio/`, `internal/`, `spi/`);
  `sdk/llamabro-hub`: downloads and search; `sdk/llamabro-hub-work`: the WorkManager download worker;
  `sdk/llamabro-typed`, `sdk/llamabro-typed-ksp`: typed output and tools, and their KSP processor;
  `sdk/llamabro-hexagon`, `sdk/llamabro-opencl`: optional backends; `sdk/llamabro-testing`: `FakeLlamaBro`;
  `sdk/llamabro-hub-testing`: `FakeLlamaBroHub`; `sdk/build-logic`: the modules' shared build conventions;
  `sdk/native`: the C++ bridge, patches and the llama.cpp submodule; `sdk/acceptance`: device test apps.
- `demo/app`, `demo/designsystem`: the demo chat app and its component library; `demo/scripts`: its checks.
- `docs/`: architecture, decisions (`D<n>`), findings (`F<n>`), testing, the demo's design. `research/`: the experiments.

## Commands

```bash
sdk/scripts/check.sh                    # SDK on the host; must print ALL PASS before an SDK change is done
sdk/scripts/check.sh --only jvm         # one step while iterating
ANDROID_SERIAL=<serial> sdk/scripts/check.sh --device   # native, JNI, backends, packaging
demo/scripts/check.sh                   # demo: unit, lint, design-system gates, quality
DEMO_DEVICE=<serial> demo/scripts/check.sh --device     # instrumented tests, journeys, monkey, coverage
./gradlew ktlintFormat qualityCheck     # format and static analysis
```

## Rules that save time

- Run a check once per change; debug on the smallest slice (one step, one test), then run the full check. Benchmarks
  (`--perf`) run only when a change can move a budget.
- Never run model inference on an emulator. Journeys use a fake engine and run anywhere; real models need a device, and
  device runs are short and attended.
- Do not commit device serials, home paths, tokens, screenshots, logs, model files or build output. Device scripts read
  `ANDROID_SERIAL` or `DEMO_DEVICE`. The one exception for screenshots is the curated README images under
  `assets/readme/` (D132).
- Do not edit `sdk/native/third_party/`; use `sdk/native/patches/`.
- Public API changes: run `./gradlew -p sdk :<module>:apiDump` and commit the `.api` diff with the change.
- Demo sizes, colours and type roles come from `:demo:designsystem`'s theme tokens, anywhere outside the theme; screens are
  built from its components (Text and Icon with a `DemoType` role). The lint rule in `demo/lint` enforces it (D113).
- Findings and decisions are numbered (`F<n>`, `D<n>`) and cited from code and docs: add new entries at the end of
  [docs/research/findings.md](docs/research/findings.md) and [docs/decisions.md](docs/decisions.md); never renumber.
- Behaviour follows upstream llama-server unless a measured finding says otherwise; cite the finding next to the deviation.

## Style

Kotlin, ktlint official style (`.editorconfig`), detekt, lint as errors; four-space indent, 120 columns. Prefer small
composables and stateless screens over a view model; derive UI state declaratively (`combine`, `stateIn`). Name things for what
they are; comments explain why, not what.

Soft rule, for a later Kotlin Multiplatform port (iOS, Flutter on top): in new SDK logic, prefer multiplatform-friendly
choices where one exists at little cost (kotlinx.serialization, coroutines, Okio or kotlinx-io over `java.io`/`java.nio`,
pure-Kotlin helpers over `java.util` ones), and keep `android.*`, `java.net` and JNI at the edges (`internal/`, the
public entry points). Existing code and Android-only modules (`llamabro-hub-work`, the backends) need no change.
