# Memory fit: what a load costs and what the system has left

**Question.** Can `ContextSize.Auto` size the context from llama.cpp's own memory projection on a phone? Upstream's
`--fit` assumes the host has unlimited memory and cannot see the NPU, and Android's `availMem` may understate what is
reclaimable. Which projection and which budget source are safe?

## Method

- **Device and build.** OnePlus 13 (CPH2649, SM8750, 15.1 GB RAM). Upstream llama-server from the P0 `P` build
  ([P0](../../p0/README.md)) in `/data/local/tmp/lb-spike/P`, with the SDK's settings:
  `-np 1 -b 512 -ub 512 --cache-ram 1024 --ctx-checkpoints 8 --load-mode none --fit off -lv 4`.
- **Models.** Qwen3.5-4B-Q4_K_M and Llama-3.2-3B-Instruct-Q4_K_M, each on the CPU (`-dev none`) and the NPU
  (`-dev HTP0 -ngl 99`).
- **`measure.sh`.** For each model and device: stop servers, read `MemAvailable` from `/proc/meminfo`, start the
  server, print the load's memory breakdown rows (`common_memory_breakdown_print`, the projection), read `MemAvailable`
  and PSS after load, send one prompt that fills the context (`CTX` minus 64 tokens, 32 generated), and read both again.
  "Lost" is the drop in `MemAvailable`, which includes the NPU's shared DMA buffers that PSS does not count. The script
  waits for adb to return if the phone drops off the bus. Environment: `CTX` (default 8192), `ONLY`
  (`"model.gguf:cpu|npu ..."`), `ANDROID_SERIAL` (required).
- **Other probes.** Upstream's own fit with `-c 0` and different `--fit-target` values; the kernel's `MemAvailable`
  against `ActivityManager.availMem` at the same moment; a 64K-context NPU load to watch `KReclaimable`; the SDK's
  device test (`DeviceChoiceDeviceTest`) with the memory budget logged.

## How to run

```bash
ANDROID_SERIAL=<serial> research/p2/fit-memory/measure.sh
ANDROID_SERIAL=<serial> CTX=8192 ONLY="Qwen3.5-4B-Q4_K_M.gguf:npu" research/p2/fit-memory/measure.sh
```

## Result

Projection (sum of breakdown rows at 8192 tokens) against memory lost, MiB:

| Model, device | Projected | Lost after load | Lost after a full-context prefill |
|---|---|---|---|
| Qwen3.5 4B, CPU | 3,506 | 3,137 | 3,394 |
| Qwen3.5 4B, NPU | 3,769 | 3,332 | 3,461 |
| Llama 3.2 3B, CPU | 3,207 | 3,141 | 2,996 |
| Llama 3.2 3B, NPU | 3,419 | 2,968 | 2,994 |

- The projection over all rows is 110-425 MiB above what the system loses, so it is a safe budget measure.
  Upstream's CPU check counts only the Host row (909 MiB for a 3.5 GB load) and omits `CPU_REPACK`.
- HTP0 reports 0/0 memory, so upstream's fit cannot use it, and on failure llama-server loads the training context
  (262,144 tokens for Qwen3.5).
- On this OEM build `availMem` read 3,377 MiB while `MemAvailable` read 8,239 MiB; the gap is `KReclaimable` (driver
  page pools). A 64K-context NPU load took `KReclaimable` from 4,281 to 461 MiB and nothing was killed
  (`lowmemorykiller`: "device has enough memory"). The budget is therefore `MemAvailable`.
- SDK on the phone: `Only(Cpu)`, `Only(Gpu)` and `Auto` (NPU) each loaded 16,384 tokens (4.2-4.5 GB of 5.3-5.8 GB
  available). Uncapped, the CPU and GPU loads would pick 53,760 and 67,072 tokens.

Tables and quotations: [results.md](results.md).

## Findings / decisions

F66 (fit misjudges a phone; all-device projection as the budget; `availMem` hides reclaimable pools), F33 (`--fit` counts
all RAM as free); D22, D33, D34 (`ContextSize.Auto` uses the projection, `MemAvailable` and a 16,384 ceiling). See
[findings](../../../docs/research/findings.md) and [decisions](../../../docs/decisions.md).

## Notes

Raw output (`measure.out`, `projection.out`, `measure-first-try.out`) is not kept. A first attempt was cut short when the
phone left adb; the script now waits for it. Three separate free-memory levels were not run: the budget is read at each
load and the device tests ran at different levels as the pools filled.
