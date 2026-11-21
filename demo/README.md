# Demo chat app

A chat app built on the SDK, in the spirit of a lighter LM Studio for the phone: find a model, download it (with
resume and a fit check), and chat. It is both a real app and the SDK's largest consumer, so its tests double as
integration tests of the public API.

## Features

- First run offers three suggested models with plain-language descriptions; the Models screen shows what is downloading and on the phone, searches Hugging Face and shows
  how each file will run on this phone before anything is downloaded.
- Downloads continue in the background, survive process death and resume where they stopped.
- Chat with streaming replies, markdown and code blocks, a thought fold ("Thought for 8 s"), think levels (Off, Quick,
  Balanced, Deep), image input for models that can see, edit and regenerate, share and copy, and a divider when the
  model is switched mid-chat.
- Voice mode for a model that talks (LFM3 Omni 2B): hold to talk, a spoken reply with captions, and each turn kept in
  the chat as a captioned voice message and a reply that plays again (D100).
- Two layouts: Sidebar (a drawer of chats) and Chat list (messaging style); light, dark and system themes.
- Plain error lines with one action each: not enough memory, context full, missing model, a gated model needing a token.

## Structure

| Module | What it is |
|---|---|
| `app/` | Screens, view models, the chat engine over the SDK's `Conversation`, Room storage, the download worker |
| `designsystem/` | The component library: Material 3 type roles, spacing and shape tokens, layout primitives that own the gutter and gaps, components with `@Preview`s |
| `benchmark/` | Macrobenchmarks and the Baseline Profile generator |
| `catalog/` | The pinned sizes of the suggested models, checked against a unit test |
| `scripts/` | The checks (`check.sh`), the end-to-end driver for real models (`e2e/`), the performance budgets |

The rules the design system enforces (no raw colours, text styles or numbers in a screen; one name per model; one
gutter) are described in [docs/design/demo-app.md](../docs/design/demo-app.md) and checked by `scripts/check.sh`.

## Run

Open the repository root in Android Studio and run `:demo:app` on an arm64 device. See the [root README](../README.md)
for the toolchain. The app downloads models from Hugging Face on first use.

## Test

```bash
demo/scripts/check.sh                                  # unit tests, lint, design-system gates, quality
DEMO_DEVICE=<serial> demo/scripts/check.sh --device    # plus instrumented tests, journeys, monkey, coverage, requirement map
```

Journeys run the whole app over a fake engine and a fake Hub, so they need no model and run on an emulator. Anything that
runs a real model needs a physical device; see [docs/testing.md](../docs/testing.md).
