# Benchmarks

One run of 37 benchmarks on a OnePlus 13 (Snapdragon 8 Elite, Hexagon v79 NPU, Adreno 830 GPU), every one through the
SDK's public API. `sdk/scripts/check.sh` checks each figure on this page and in the README against the run's
results ([F178](docs/research/findings.md#f178)).

<p align="center">
  <picture>
    <source media="(max-width: 600px) and (prefers-color-scheme: dark)" srcset="assets/readme/board-narrow-dark.svg">
    <source media="(max-width: 600px)" srcset="assets/readme/board-narrow-light.svg">
    <source media="(prefers-color-scheme: dark)" srcset="assets/readme/board-dark.svg">
    <img src="assets/readme/board-light.svg" width="100%" alt="The benchmark in four charts. Every size, every chip: six models from LFM2.5 230M to Qwen3.5 4B writing and reading on the NPU, GPU and CPU, the fastest chip of each marked, with bge-small embeddings and jina-reranker-tiny reranking. Speed on each chip: Qwen3.5 4B and LFM3-Omni 2B reading a 2,048-token prompt and writing up to 128 tokens. Drafting: Qwen3.5 4B on the NPU with and without drafting, for a JSON extraction with DFlash and a code edit with n-gram drafting and MTP. Waiting times: model load per chip, stopping a reply, the first spoken audio, and coming back to a 3,000-token chat by reading it again or restoring its saved slot">
  </picture>
</p>

## Every number

### Two models on each chip

Reading a 2,048-token prompt and writing a reply of up to 128 tokens, in tokens per second; loading, in seconds.

| Model | Chip | Reading | Writing | Loading |
|---|---|--:|--:|--:|
| Qwen3.5 4B | NPU | <!-- bench:qwen-npu.promptTps -->934<!-- /bench --> | <!-- bench:qwen-npu.predictedTps -->15.3<!-- /bench --> | <!-- bench:qwen-npu.loadMs/1000 -->5.2<!-- /bench --> |
| Qwen3.5 4B | GPU | <!-- bench:qwen-gpu.promptTps -->188<!-- /bench --> | <!-- bench:qwen-gpu.predictedTps -->15.1<!-- /bench --> | <!-- bench:qwen-gpu.loadMs/1000 -->40.3<!-- /bench --> |
| Qwen3.5 4B | CPU | <!-- bench:qwen-cpu.promptTps -->64.5<!-- /bench --> | <!-- bench:qwen-cpu.predictedTps -->12.1<!-- /bench --> | <!-- bench:qwen-cpu.loadMs/1000 -->7.9<!-- /bench --> |
| LFM3-Omni 2B | NPU | <!-- bench:lfm-npu.promptTps -->2,358<!-- /bench --> | <!-- bench:lfm-npu.predictedTps -->36.3<!-- /bench --> | <!-- bench:lfm-npu.loadMs/1000 -->2.2<!-- /bench --> |
| LFM3-Omni 2B | GPU | <!-- bench:lfm-gpu.promptTps -->490<!-- /bench --> | <!-- bench:lfm-gpu.predictedTps -->38.4<!-- /bench --> | <!-- bench:lfm-gpu.loadMs/1000 -->9.6<!-- /bench --> |
| LFM3-Omni 2B | CPU | <!-- bench:lfm-cpu.promptTps -->167<!-- /bench --> | <!-- bench:lfm-cpu.predictedTps -->33.0<!-- /bench --> | <!-- bench:lfm-cpu.loadMs/1000 -->2.8<!-- /bench --> |

### Six models, 230M to 4B

A 512-token prompt and a reply of up to 64 tokens, in tokens per second; loading, in seconds.

| Model | Chip | Reading | Writing | Loading |
|---|---|--:|--:|--:|
| LFM2.5 230M | NPU | <!-- bench:ladder-lfm230m-npu.promptTps -->7,299<!-- /bench --> | <!-- bench:ladder-lfm230m-npu.predictedTps -->171<!-- /bench --> | <!-- bench:ladder-lfm230m-npu.loadMs/1000 -->0.58<!-- /bench --> |
| LFM2.5 230M | GPU | <!-- bench:ladder-lfm230m-gpu.promptTps -->2,243<!-- /bench --> | <!-- bench:ladder-lfm230m-gpu.predictedTps -->136<!-- /bench --> | <!-- bench:ladder-lfm230m-gpu.loadMs/1000 -->4.9<!-- /bench --> |
| LFM2.5 230M | CPU | <!-- bench:ladder-lfm230m-cpu.promptTps -->168<!-- /bench --> | <!-- bench:ladder-lfm230m-cpu.predictedTps -->149<!-- /bench --> | <!-- bench:ladder-lfm230m-cpu.loadMs/1000 -->0.80<!-- /bench --> |
| Qwen3.5 0.8B | NPU | <!-- bench:ladder-qwen08b-npu.promptTps -->2,174<!-- /bench --> | <!-- bench:ladder-qwen08b-npu.predictedTps -->53.4<!-- /bench --> | <!-- bench:ladder-qwen08b-npu.loadMs/1000 -->1.7<!-- /bench --> |
| Qwen3.5 0.8B | GPU | <!-- bench:ladder-qwen08b-gpu.promptTps -->531<!-- /bench --> | <!-- bench:ladder-qwen08b-gpu.predictedTps -->48.6<!-- /bench --> | <!-- bench:ladder-qwen08b-gpu.loadMs/1000 -->8.5<!-- /bench --> |
| Qwen3.5 0.8B | CPU | <!-- bench:ladder-qwen08b-cpu.promptTps -->259<!-- /bench --> | <!-- bench:ladder-qwen08b-cpu.predictedTps -->46.8<!-- /bench --> | <!-- bench:ladder-qwen08b-cpu.loadMs/1000 -->6.7<!-- /bench --> |
| LFM3 1.7B | NPU | <!-- bench:ladder-lfm17b-npu.promptTps -->1,682<!-- /bench --> | <!-- bench:ladder-lfm17b-npu.predictedTps -->34.1<!-- /bench --> | <!-- bench:ladder-lfm17b-npu.loadMs/1000 -->2.1<!-- /bench --> |
| LFM3 1.7B | GPU | <!-- bench:ladder-lfm17b-gpu.promptTps -->348<!-- /bench --> | <!-- bench:ladder-lfm17b-gpu.predictedTps -->33.5<!-- /bench --> | <!-- bench:ladder-lfm17b-gpu.loadMs/1000 -->11.9<!-- /bench --> |
| LFM3 1.7B | CPU | <!-- bench:ladder-lfm17b-cpu.promptTps -->143<!-- /bench --> | <!-- bench:ladder-lfm17b-cpu.predictedTps -->32.6<!-- /bench --> | <!-- bench:ladder-lfm17b-cpu.loadMs/1000 -->3.2<!-- /bench --> |
| Llama 3.2 3B | NPU | <!-- bench:ladder-llama3b-npu.promptTps -->1,392<!-- /bench --> | <!-- bench:ladder-llama3b-npu.predictedTps -->21.2<!-- /bench --> | <!-- bench:ladder-llama3b-npu.loadMs/1000 -->4.0<!-- /bench --> |
| Llama 3.2 3B | GPU | <!-- bench:ladder-llama3b-gpu.promptTps -->202<!-- /bench --> | <!-- bench:ladder-llama3b-gpu.predictedTps -->17.0<!-- /bench --> | <!-- bench:ladder-llama3b-gpu.loadMs/1000 -->16.4<!-- /bench --> |
| Llama 3.2 3B | CPU | <!-- bench:ladder-llama3b-cpu.promptTps -->69.2<!-- /bench --> | <!-- bench:ladder-llama3b-cpu.predictedTps -->6.3<!-- /bench --> | <!-- bench:ladder-llama3b-cpu.loadMs/1000 -->12.2<!-- /bench --> |
| Gemma 3 4B (QAT) | NPU | <!-- bench:ladder-gemma4b-npu.promptTps -->172<!-- /bench --> | <!-- bench:ladder-gemma4b-npu.predictedTps -->14.2<!-- /bench --> | <!-- bench:ladder-gemma4b-npu.loadMs/1000 -->5.1<!-- /bench --> |
| Gemma 3 4B (QAT) | GPU | <!-- bench:ladder-gemma4b-gpu.promptTps -->152<!-- /bench --> | <!-- bench:ladder-gemma4b-gpu.predictedTps -->12.5<!-- /bench --> | <!-- bench:ladder-gemma4b-gpu.loadMs/1000 -->19.0<!-- /bench --> |
| Gemma 3 4B (QAT) | CPU | <!-- bench:ladder-gemma4b-cpu.promptTps -->48.6<!-- /bench --> | <!-- bench:ladder-gemma4b-cpu.predictedTps -->7.9<!-- /bench --> | <!-- bench:ladder-gemma4b-cpu.loadMs/1000 -->9.8<!-- /bench --> |
| Qwen3.5 4B | NPU | <!-- bench:ladder-qwen4b-npu.promptTps -->646<!-- /bench --> | <!-- bench:ladder-qwen4b-npu.predictedTps -->15.3<!-- /bench --> | <!-- bench:ladder-qwen4b-npu.loadMs/1000 -->5.4<!-- /bench --> |
| Qwen3.5 4B | GPU | <!-- bench:ladder-qwen4b-gpu.promptTps -->126<!-- /bench --> | <!-- bench:ladder-qwen4b-gpu.predictedTps -->14.1<!-- /bench --> | <!-- bench:ladder-qwen4b-gpu.loadMs/1000 -->19.9<!-- /bench --> |
| Qwen3.5 4B | CPU | <!-- bench:ladder-qwen4b-cpu.promptTps -->45.0<!-- /bench --> | <!-- bench:ladder-qwen4b-cpu.predictedTps -->11.5<!-- /bench --> | <!-- bench:ladder-qwen4b-cpu.loadMs/1000 -->9.9<!-- /bench --> |

### Search

32 sentences in one request, after one request to warm up, in sentences per second.

| Model | NPU | GPU | CPU |
|---|--:|--:|--:|
| bge-small (embeddings) | <!-- bench:embed-npu.extra.itemsPerSecond -->108<!-- /bench --> | <!-- bench:embed-gpu.extra.itemsPerSecond -->46.2<!-- /bench --> | <!-- bench:embed-cpu.extra.itemsPerSecond -->41.6<!-- /bench --> |
| jina-reranker-tiny (rerank) | <!-- bench:rerank-npu.extra.itemsPerSecond -->227<!-- /bench --> | <!-- bench:rerank-gpu.extra.itemsPerSecond -->106<!-- /bench --> | <!-- bench:rerank-cpu.extra.itemsPerSecond -->69.4<!-- /bench --> |

### Drafting

Qwen3.5 4B on the NPU, writing at most 256 tokens, in tokens per second.

| Request | Drafting off | Drafting on |
|---|--:|--:|
| JSON extraction, with a DFlash draft model | <!-- bench:json-off.predictedTps -->15.4<!-- /bench --> | <!-- bench:json-dflash.predictedTps -->14.0<!-- /bench --> |
| Code edit, with n-gram drafting and MTP | <!-- bench:edit-off.predictedTps -->14.9<!-- /bench --> | <!-- bench:edit-ngram-mtp.predictedTps -->120<!-- /bench --> |

### Waiting times

| Moment | Time |
|---|--:|
| Stopping a reply: the cancel until every slot is idle | <!-- bench:stop.extra.stopMs -->85<!-- /bench --> ms |
| Stopping a reply: the cancel call's return, the app's side | <!-- bench:stop.extra.cancelReturnMs -->3<!-- /bench --> ms |
| Voice, LFM3-Omni 2B: the app's commit of the user's turn until the first reply audio | <!-- bench:voice.extra.firstAudioMs/1000 -->0.38<!-- /bench --> s |
| Voice, LFM3-Omni 2B: seconds of reply audio per second from the commit to the end of the reply | <!-- bench:voice.extra.realtimeFactor -->1.4<!-- /bench --> |
| A 3,000-token chat after the model was freed, from loading to the next answer: read again | <!-- bench:restore.extra.readAgainMs/1000 -->8.3<!-- /bench --> s |
| The same, with its saved slot restored before the question | <!-- bench:restore.extra.restoreMs/1000 -->5.1<!-- /bench --> s |


## How they were measured

- Each benchmark is a fresh process and a fresh load on one chip: context 8,192 tokens, no prompt cache, thinking off,
  greedy sampling, and llama-server's own timings
  ([`BenchDeviceTest`](sdk/acceptance/backends/src/androidTest/kotlin/com/suhel/llamabro/sample/BenchDeviceTest.kt)).
  The voice benchmark feeds a recorded question at microphone pace; the restore benchmark
  ([`SlotCacheDeviceTest`](sdk/acceptance/backends/src/androidTest/kotlin/com/suhel/llamabro/sample/SlotCacheDeviceTest.kt))
  lets the SDK pick the chip. The code edit's reply is mostly copied from its prompt, where n-gram drafting gains most.
- Q4_K_M models, except Gemma 3 4B (Unsloth's UD-Q4_K_XL of Google's QAT release) and the F16 search models; the
  DFlash draft and LFM3-Omni's projector are Q8_0. Each ran once, on a phone at thermal status 1 or lower.
- [`bench.sh`](sdk/scripts/bench.sh) runs it and [`bench.py`](sdk/scripts/bench.py) draws the charts; the requests
  and what the numbers do not cover are in [research/bench](research/bench/README.md#what-each-arm-asks).
