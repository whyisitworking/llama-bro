# PF T3: draft models, EAGLE-3, DFlash and DSpark for Qwen3.5 4B (F73)

Sidecars for Qwen3.5 4B (the SDK's model, so no Qwen3 4B stand-in was needed), downloads approved by the user
2026-11-06, converted on the Mac with upstream's `convert_hf_to_gguf.py --target-model-dir` to Q8_0 (`convert.sh`,
checksums `gguf.sha256`):

| Sidecar | Source | GGUF |
|---|---|---|
| DFlash | `z-lab/Qwen3.5-4B-DFlash` (the DFlash authors, Apache-2.0), block 16 | 685 MB |
| EAGLE-3 | `yuyijiong/Qwen3.5-4B-Eagle3` (community SpecForge, Apache-2.0), draft vocab 50K | 259 MB |
| DSpark | `shanjiaz/Qwen3.5-4B-DSpark-b16-matched6-10k-20261005` (community, speculators format) | 696 MB |

Upstream `llama-server` (`/data/local/tmp/lb-spike/P`), the SDK's load settings, a fresh load per arm, each arm at
thermal ≤ 2, F68's four output kinds, 256-token replies, thinking off, seed 42. Flags as upstream's examples:
`draft-eagle3`; `draft-dflash --spec-draft-n-max 15 -fa on`; `draft-dspark --spec-draft-n-max 7 -fa on`; MTP n-max 4
(F68) as the bar. NPU 2 rounds (the second in reverse order, since DFlash's JSON win decides a recommendation), CPU 1.
GPU skipped (F71). Script `dm.sh`; raw `dm-r1.out` (NPU and CPU), `dm-r2.out` (NPU, reversed; its header says
"round 1", the replies and logs are tagged r2); smoke test `logs/smoke-npu-*.log`.

## Decode t/s (NPU: round 1 / round 2)

| Output | NPU off | NPU MTP | NPU DFlash | NPU EAGLE-3 | NPU DSpark | CPU off | CPU MTP | CPU DFlash | CPU EAGLE-3 | CPU DSpark |
|---|---|---|---|---|---|---|---|---|---|---|
| prose greedy | 15.07 / 15.15 | 13.51 / 13.46 | 11.51 / 11.63 | 9.63 | 6.48 | 11.76 | 4.45 | 2.88 | 3.01 | 2.61 |
| prose default | 15.03 / 14.89 | 11.76 / 11.74 | 9.98 / 10.12 | 9.74 | 6.15 | 9.37 | 6.24 | 2.75 | 2.61 | 2.48 |
| JSON greedy | 14.92 / 14.84 | 22.21 / 22.06 | **32.64 / 33.15** | 11.81 | 9.24 | 10.11 | 11.12 | 8.16 | 3.85 | 3.62 |
| JSON agent | 14.74 / 14.55 | 22.34 / 21.93 | **35.27 / 36.02** | 11.50 | 9.38 | 9.96 | 10.53 | 8.23 | 2.62 | 3.59 |

Draft acceptance (accepted / drafted, NPU JSON greedy): MTP 196/233, DFlash 225/435, EAGLE-3 129/378, DSpark 123/914.

## Prefill, load, memory (NPU round 1; memory = MemAvailable drop after the load, as F66)

| Arm | Prefill t/s (prose greedy) | Load s | Memory MB | CPU load s | CPU memory MB |
|---|---|---|---|---|---|
| off | 885 | 5.4 | 3468 | 13.6 | 3483 |
| MTP | 770 | 4.2 | 3737 | 8.3 | 3797 |
| DFlash | 602 | 6.6 | **6365** (r2: 6287) | 17.1 | 5007 |
| EAGLE-3 | **222** | 6.0 | 4786 | 9.5 | 3962 |
| DSpark | 711 | 6.0 | 5436 | 15.4 | 4603 |

## Output

Every drafter, MTP included, drifts from greedy `off` at the same two places on the NPU (JSON char 117: one item's
platform list loses "iOS"; prose char 1160, a rephrased clause), and DFlash's replies match MTP's: the drift is the
batched verification, not the draft model. EAGLE-3's prose drifts earlier (char 548). All replies stay sensible; all
JSON replies are cut by the 256-token limit (every arm). No arm crashed or failed to load.
