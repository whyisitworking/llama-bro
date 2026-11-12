# Research

Experiments that decided the SDK's design. Each directory holds the scripts, inputs, notes and result tables of one
experiment, with a README stating the question, method, how to run it, the result and the findings and decisions it
fed. Raw run output (logs, replies, audio, pictures) is not kept. Findings are `F<n>` in
[findings](../docs/research/findings.md); decisions are `D<n>` in [decisions](../docs/decisions.md).

Device experiments ran on one phone (OnePlus 13, Snapdragon 8 Elite, Adreno 830, Hexagon v79, Android 16) against
llama.cpp b12052. Scripts take the adb serial from `ANDROID_SERIAL` in the environment. Builds, comparisons and
benchmarks alternate arms within a round, because the phone throttles under sustained load (F6).

| Phase | Directory | Question | Findings |
|---|---|---|---|
| P0 | [p0](p0/README.md) | Does llama-server's core run in-process with the same output, speed and memory? Which patch, batch sizes, cache budget and sampler order suit a phone? | F1-F15 |
| P2 | [p2/f50-load-mode](p2/f50-load-mode/README.md) | Which load mode keeps one copy of the weights, and at what load-time cost? | F50, F58-F60 |
| P2 | [p2/fit-memory](p2/fit-memory/README.md) | Can llama.cpp's memory projection and the kernel's `MemAvailable` size a context on a phone? | F33, F66 |
| P2 | [p2/gpu-drafting](p2/gpu-drafting/README.md) | Does speculative drafting run on OpenCL after the Q4_K kernel switch? | F80 |
| P2 | [p2/htp-probe](p2/htp-probe/README.md) | Can an ordinary app load the unsigned Hexagon skeleton and run on the NPU? | F36 |
| P2 | [p2/mtp](p2/mtp/README.md) | Does multi-token prediction speed up decoding per chip and output kind? | F67, F68 |
| P2 | [p2/opencl-speed](p2/opencl-speed/README.md) | How do OpenCL and Vulkan compare with the CPU on the Adreno 830? | F63, F64 |
| P2 | [p2/openmp](p2/openmp/README.md) | Does OpenMP change CPU speed? | F77 |
| P3 | [p3/f95-dflash-repeat](p3/f95-dflash-repeat/README.md) | Does DFlash on the NPU diverge from upstream's greedy reply again? | F95, F98, F99 |
| P3 | [p3/header-fit](p3/header-fit/README.md) | Does a header-only GGUF give the same memory projection as the full file? Do relative cache symlinks work in app storage? | F84-F86 |
| P3 | [p3/real-download](p3/real-download/README.md) | Does a download from Hugging Face resume after a kill and verify? | F93 |
| PF | [pf/backend-sampling](pf/backend-sampling/README.md) | Does backend sampling help? Why does OpenCL answer garbage for Qwen3.5? | F70, F71, F75 |
| PF | [pf/draft-models](pf/draft-models/README.md) | Do EAGLE-3, DFlash and DSpark beat no speculation and MTP? | F73 |
| PF | [pf/ngram](pf/ngram/README.md) | Does n-gram speculation help without hurting other output? | F72 |
| PF | [pf/tts](pf/tts/README.md) | Does text to speech run on the phone, and can the SDK return audio? | F74 |

## How the experiments relate to the design

P0 fixed the engine: embedding llama-server's `server-context` behind a C API gives its exact behaviour at a small size
cost, so the SDK is a thin native bridge rather than an HTTP server. It also produced the two things every later run
depends on: the delta-net patch that the SDK carries, and the phone defaults (one slot, `n_batch` 512, a bounded
checkpoint and RAM-cache budget). The P2 experiments then decided how the engine meets the hardware: where to load the
weights (`--load-mode none`), how to size the context from a projection and the kernel's available memory, which backends
ship (Hexagon and OpenCL yes, Vulkan no) and how the NPU skeleton reaches the process.

The speculation and drafting experiments (MTP, n-gram, draft models) share one method and one result: gains depend on the
output kind, so speculation is opt-in rather than automatic, and OpenCL cannot take part until its Q4_K kernel is
avoided. The OpenCL bisection, which began as a side check of backend sampling, is the clearest case of an experiment
changing a shipped default: the SDK sets an upstream environment switch before loading the backend. P3 experiments tested
the Hub's assumptions (header-only fit, cache layout, resume against the real service) and re-checked DFlash's determinism
on the NPU.

Decisions and findings cite these directories by ID, and each README ends with the IDs it fed. When the llama.cpp pin
moves, the P2 and PF scripts are the means to repeat a measurement: MTP and drafting on OpenCL, backend sampling, the
OpenCL switch and the OpenMP comparison are the ones marked for re-measurement.
