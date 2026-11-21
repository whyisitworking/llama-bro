# The README's benchmark

The speeds, load times, drafting gains, stop latency and voice latency in the project README come from one run of the
SDK on one phone. This folder holds how that run is made and, once it has been run, its results.

- The measurement: [`BenchDeviceTest`](../../sdk/acceptance/backends/src/androidTest/kotlin/com/suhel/llamabro/sample/BenchDeviceTest.kt)
  and [`SlotCacheDeviceTest`](../../sdk/acceptance/backends/src/androidTest/kotlin/com/suhel/llamabro/sample/SlotCacheDeviceTest.kt),
  device tests of the acceptance app that only run when asked.
- The runner: [`sdk/scripts/bench.sh`](../../sdk/scripts/bench.sh).
- The merge, the charts and the check of the README's numbers: [`sdk/scripts/bench.py`](../../sdk/scripts/bench.py).
- The output: `results.json` (the median and the [min, max] range of every number, per arm) in this folder, and the
  charts in [`assets/readme/`](../../assets/readme/). The results are recorded as a numbered finding once the run has
  been made; until then the charts show their "awaiting run" state.

## What each arm asks

Every arm loads its model on one chip with a context of 8,192 tokens and no prompt cache, sends one request with
thinking off and greedy sampling (temperature 0), and records the load time, the prompt and reply token counts, the
prompt and reply speeds from llama-server's own timings, and the arm's extras.

| Arm | Model | Chip | Request |
| --- | --- | --- | --- |
| `qwen-npu`, `qwen-gpu`, `qwen-cpu` | Qwen3.5 4B Q4_K_M | NPU, GPU, CPU | a document of 2,048 tokens and "Summarise the document above."; at most 128 reply tokens |
| `lfm-npu`, `lfm-gpu`, `lfm-cpu` | LFM3-Omni 2B Q4_K_M | NPU, GPU, CPU | the same request |
| `json-off`, `json-dflash` | Qwen3.5 4B Q4_K_M; `json-dflash` also loads the DFlash Q8_0 draft | NPU | extract fields from a document into a JSON schema; at most 256 reply tokens; drafting off, then DFlash |
| `edit-off`, `edit-ngram-mtp` | Qwen3.5 4B Q4_K_M | NPU | rename a function in a Kotlin file and return the whole file; at most 256 reply tokens, the start of the edited file, almost all copied from the prompt, which is where n-gram drafting gains most; drafting off, then n-gram drafting with MTP behind it |
| `stop` | Qwen3.5 4B Q4_K_M | NPU | the summary request; the reply is cancelled 500 ms after its first token; `stopMs` is the time from the cancel until `GET /slots` reports every slot idle, `cancelReturnMs` the time the cancel call took to return |
| `voice` | LFM3-Omni 2B Q4_K_M with its Q8_0 projector | NPU | a spoken question (see below) sent in 100 ms pieces at microphone pace, then committed; `firstAudioMs` is the time from the commit to the first reply audio, `realtimeFactor` the seconds of reply audio over the seconds from the commit to the end of the reply |
| `ladder-<model>-<chip>` | LFM2.5 230M, Qwen3.5 0.8B, LFM3 1.7B, Llama 3.2 3B Instruct (Q4_K_M); Gemma 3 4B QAT (UD-Q4_K_XL); Qwen3.5 4B (Q4_K_M) | NPU, GPU, CPU | a document of 512 tokens and "Summarise the document above."; at most 64 reply tokens |
| `embed-<chip>` | bge-small F16, loaded for embeddings, context 512 | NPU, GPU, CPU | 32 sentences of the document embedded in one request, after one request to warm up; `itemsPerSecond` and `batchMs` of the second |
| `rerank-<chip>` | jina-reranker-tiny F16, loaded for reranking, context 512 | NPU, GPU, CPU | the same 32 sentences ranked against "How much will it cost to raise the quay?", timed as `embed` |
| `restore` | Qwen3.5 4B Q4_K_M | the SDK's automatic choice | `SlotCacheDeviceTest`: the time to read a long chat again after a reload, against restoring its saved slot from disk (`readAgainMs`, `restoreMs`) |

The voice question is made on the machine that runs the script: macOS `say` speaks "Can you tell me a short story
about a lighthouse keeper who finds a message in a bottle?" and `afconvert` turns it into raw 16-bit mono PCM at
16 kHz. On a machine without them the voice arm is skipped and the script says so.

## Rounds and cooling

A mode runs every arm once per round, one round by default (`LB_BENCH_ROUNDS` sets more). Every arm is its own instrumentation call, so each
starts from a fresh process and a fresh model load. The arm order reverses in every second round, so that no arm always
runs on the warmest phone; with three rounds, the first and the third run in the same order. Before each arm the script
reads the phone's thermal status and waits until it is 1 or lower, for up to ten minutes; if the phone does not cool,
the mode stops. The reported number is the median over the rounds
that succeeded, and the range is kept beside it. An arm that failed in a round, including one whose run crashed or
wrote no line, is recorded as failed with the runner's message, the other arms go on, and `results.json` names it. A
mode in which every arm failed publishes nothing.

## How to run it

Connect the phone, unlock it and keep it attended. The script installs the acceptance app (the `full` flavor) and its
test app through Gradle, pushes any model file the app does not have yet, and takes the phone's lock like the device
checks do. Each mode takes about 10 to 30 minutes of phone time with one round; the first run takes longer, because it
builds and installs the app (the backend modules build in Docker, see [CONTRIBUTING.md](../../CONTRIBUTING.md)) and
pushes the model files. As it runs, the script prints each arm's numbers, or FAILED and the reason, and at the end how
many runs went well and how many failed.

```bash
ANDROID_SERIAL=<serial> sdk/scripts/bench.sh speed    # the six chip arms
ANDROID_SERIAL=<serial> sdk/scripts/bench.sh extras   # drafting, stop, voice, restore
ANDROID_SERIAL=<serial> sdk/scripts/bench.sh ladder   # six models on every chip, embeddings and rerank
python3 sdk/scripts/bench.py check-readme README.md research/bench/results.json
```

`LB_BENCH_ROUNDS` sets the rounds and `LB_BENCH_MODELS_DIR` the folders (separated by colons) to look for the model
files in; the header of the script lists the rest. The modes add to the same work folder, so after all three,
`results.json` and the charts cover every arm. A mode writes them only when it finished; a mode that stops early (the
phone does not cool, or drops off adb) leaves the earlier ones as they were and keeps the lines it had measured in the
work folder, where its message names them. A run replaces its own mode's earlier lines.

Every mode must run on the same build. The build is the SDK commit, the last commit that touched `sdk/` (`-dirty` is
added when `sdk/` has uncommitted changes), and llama.cpp's build, `llamaCppBuild`: the submodule's latest reachable tag
(such as `b12479`), or its short commit hash when no tag is reachable, as in a shallow clone of the submodule;
committing results or documents between the modes does not change it. A mode refuses to start when another
mode's kept lines are from another build, and the merge refuses a mix. `META.json` in the work folder records the
phone's model, SoC and Android version, the date and the build; the phone's serial is never written or printed.

## What the numbers do not cover

- One phone, one SoC. Another phone, or the same phone warmer or with a different battery level, gives other numbers.
- One quantisation of each model. Other quantisations move every speed.
- Short replies (up to 64, 128 or 256 tokens) after one prompt of 512 or about 2,048 tokens. Longer replies slow down as the
  context fills and as the phone warms, and longer prompts change the prefill speed.
- One run per arm by default: a single measurement, with no spread to show; run more rounds for a median and a range.
- Greedy sampling with thinking off. Drafting gains depend on how predictable the reply is, which is why the drafting
  arms use structured and edit-like requests.
- Before each arm the script waits for the phone's thermal status to be 1 or lower, so a long chat on a warm phone runs
  slower than these numbers.
- Load times after the first arm of a run read the model mostly from the page cache, which an earlier arm filled, not
  from storage.
- The arms run in a test process with no activity on screen, which Android may schedule differently from an app in
  front; this is not measured.
