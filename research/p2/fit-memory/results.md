# P2 T7: memory for `ContextSize.Auto`, OnePlus 13 (CPH2649, SM8750, 15.1 GB RAM)

> **Parent:** [findings F66](../../../docs/sdk-v2/findings.md) · Script: [measure.sh](measure.sh) · 2026-11-05/06

## Upstream's fit on the phone (`-c 0`, Qwen3.5 4B, `--load-mode none`)

| placement | `--fit-target` | what fit did | context used |
|---|---|---|---|
| CPU | 1024 | "projected to use 9093 MiB of host memory vs. 15107 MiB of total host memory … no changes needed" | 262 144 |
| CPU | 11000 | "cannot meet free memory target … unable to fit model into system memory by reducing context, abort" | 262 144 (the load went on) |
| NPU / GPU | 1024 | not completed (the phone left adb); the code: HTP0 reports 0/0, so fit can't use it, then aborts because `n_gpu_layers` is set | — |

## Projected (the load's memory breakdown, all rows) against what the system loses (`MemAvailable` drop)

Context 8192, the SDK's settings, upstream llama-server (P build). Raw: [measure.out](measure.out),
[projection.out](projection.out) (`-lv 4`, fit's breakdown at an explicit context), first attempt cut short by an adb
drop: [measure-first-try.out](measure-first-try.out).

| model, device | projected rows | projected total | lost after load | lost after a 7.8K-token prefill | PSS after prefill |
|---|---|---|---|---|---|
| Qwen3.5 4B, CPU | Host 909 + CPU_REPACK 2597 | 3506 | 3137 | 3394 | 3718 |
| Qwen3.5 4B, NPU | HTP0 3244 + Host 525 | 3769 | 3332 | 3461 | 735 |
| Llama 3.2 3B, CPU | Host 1290 + CPU_REPACK 1917 | 3207 | 3141 | 2996 | 3282 |
| Llama 3.2 3B, NPU | HTP0 3079 + Host 340 | 3419 | 2968 | 2994 | 410 |

Upstream's own CPU check ("projected to use 909 MiB of host memory") counts only the Host row, not CPU_REPACK.

## `ActivityManager.availMem` against the kernel's `MemAvailable`

Same moment, after the GPU and NPU device tests: `availMem` 3377 MiB, threshold 648 MiB; `/proc/meminfo`
`MemAvailable` 8239 MiB, `MemFree` 1199, `Cached` 3121, `KReclaimable` 4383, `SReclaimable` 259. `dumpsys meminfo`:
"Free RAM 10,119,093K (… 6,262,472K cached kernel …)". AOSP's `Process.getFreeMemory` returns `MemAvailable` alone,
so this OEM build reports less. Across one device-test run: `availMem` 7959 → 4285 → 3675 MiB, `MemAvailable` ≈ 8.2 GB.

**Pool reclaim** (Qwen3.5 4B, NPU, `-c 65536`, which allocates its buffers at load):

| | MemFree | MemAvailable | KReclaimable |
|---|---|---|---|
| before | 1181 | 7992 | 4281 |
| loaded | 284 | 3012 | **461** |
| closed | 985 | 8232 | 5024 |

`lowmemorykiller` logged "device has enough memory … disable killing" throughout; no process was killed.

## The SDK on the phone (DeviceChoiceDeviceTest, Qwen3.5 4B)

`Only(Cpu)` 16384 tokens, "the ceiling of 16384 tokens fits: 4206 MiB of 5737 MiB available", 8.6 s;
`Only(Gpu)` 16384, 4232 of 5807 MiB, 16.4 s; `Auto` (NPU) 16384, 4527 of 5291 MiB, 5.6 s. Uncapped, before the ceiling
(D34), the same loads picked 53 760 (CPU) and 67 072 (GPU) tokens, and with `availMem` as the budget the NPU load
after them was refused ("4065 MiB at 4096 tokens, 752 MiB available").
