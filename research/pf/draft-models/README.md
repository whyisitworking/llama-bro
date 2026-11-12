# Draft models: EAGLE-3, DFlash and DSpark for Qwen3.5 4B

**Question.** Do the draft-model speculation types in upstream llama.cpp (EAGLE-3, DFlash, DSpark) beat no speculation
and MTP on Qwen3.5 4B, per chip and per output kind, and at what cost in memory and prefill?

## Method

- **Sidecars.** Converted on a Mac to Q8_0 GGUF with upstream's `convert_hf_to_gguf.py --target-model-dir`, which needs
  only the target's `config.json` and tokenizer files, no target weights (`convert.sh`; checksums in `gguf.sha256`):

  | Sidecar | Source | GGUF |
  |---|---|---|
  | DFlash | `z-lab/Qwen3.5-4B-DFlash` (the DFlash authors, Apache-2.0), block 16 | 685 MB |
  | EAGLE-3 | `yuyijiong/Qwen3.5-4B-Eagle3` (community, Apache-2.0), draft vocabulary 50K | 259 MB |
  | DSpark | `shanjiaz/Qwen3.5-4B-DSpark-b16-matched6-10k-20261005` (community, speculators format) | 696 MB |

- **Runs.** OnePlus 13. Upstream llama-server from the P0 `P` build (b12052 plus the delta-net patch,
  [P0](../../p0/README.md)), the SDK's load settings, Qwen3.5-4B-Q4_K_M, a fresh load per arm, each arm started at thermal
  status 2 or lower, `/v1/chat/completions` with thinking off, seed 42, 256-token replies, about 2K-token prompt from
  `../../p2/mtp/source.txt`, four output kinds (prose and JSON extraction, each greedy and sampled, as in
  [p2/mtp](../../p2/mtp/README.md)). Arms with upstream's example flags:
  `off`; `mtp` (`--spec-type draft-mtp --spec-draft-n-max 4`, the bar to beat); `eagle3` (`-md ... --spec-type draft-eagle3`);
  `dflash` (`--spec-type draft-dflash --spec-draft-n-max 15 -fa on`); `dspark` (`--spec-type draft-dspark
  --spec-draft-n-max 7 -fa on`). Per arm: load time and the `MemAvailable` drop after load. Sidecars live in
  `/data/local/tmp/lb-spike/models/pf/`. NPU ran 2 rounds (the second reversed, because DFlash's JSON win decides a
  recommendation), CPU 1; the GPU was skipped because of the OpenCL kernel fault (F71, F75).
- **`dm.sh`.** Environment: `ROUNDS` (default 3), `CHIPS` (default `npu cpu`), `ARMS` (default `off mtp eagle3 dflash
  dspark`), `COOL`, `RTAG` (round tag for the output file names), `ANDROID_SERIAL` (required). Output goes to `replies/` and
  `logs/` next to the script.

## How to run

```bash
DL=<download dir> OUT=<gguf dir> PY=<venv python> research/pf/draft-models/convert.sh   # then adb push to models/pf/
ANDROID_SERIAL=<serial> ROUNDS=2 CHIPS=npu research/pf/draft-models/dm.sh
```

## Result

Decode t/s on the NPU (round 1 / round 2 where run), and the CPU:

| Output | NPU off | NPU MTP | NPU DFlash | NPU EAGLE-3 | NPU DSpark | CPU off | CPU MTP | CPU DFlash |
|---|---|---|---|---|---|---|---|---|
| prose greedy | 15.1 / 15.2 | 13.5 | 11.5 / 11.6 | 9.6 | 6.5 | 11.8 | 4.5 | 2.9 |
| prose default | 15.0 / 14.9 | 11.8 | 10.0 / 10.1 | 9.7 | 6.2 | 9.4 | 6.2 | 2.8 |
| JSON greedy | 14.9 / 14.8 | 22.2 | **32.6 / 33.2** | 11.8 | 9.2 | 10.1 | 11.1 | 8.2 |
| JSON 0.2/0.95/20 | 14.7 / 14.6 | 22.3 | **35.3 / 36.0** | 11.5 | 9.4 | 10.0 | 10.5 | 8.2 |

- DFlash decodes JSON 2.3 times faster than off and 1.5 times faster than MTP on the NPU (225 of 435 drafted tokens
  accepted), but loses on prose and on the CPU. EAGLE-3 and DSpark lose everywhere; EAGLE-3 cuts NPU prefill from 885 to
  222 t/s.
- Memory (`MemAvailable` drop, NPU): off 3,468 MB, MTP 3,737, DFlash 6,365 (round 2: 6,287), EAGLE-3 4,786, DSpark 5,436.
  Load: 5.4 s off against 6.6 s DFlash.
- Every drafter, MTP included, drifts from greedy `off` at the same two places on the NPU, and DFlash's replies equal
  MTP's: the drift comes from batched verification, not the draft model. All replies stay sensible; no arm crashed.

Full tables: [results.md](results.md).

## Findings / decisions

F73 (draft models); follow-on F90, F95, F98, F99 (DFlash in the SDK; see [p3/f95-dflash-repeat](../../p3/f95-dflash-repeat/README.md));
D36 and D41 (DFlash is an opt-in, shipped with the Hub's sidecar download), D10. See
[findings](../../../docs/research/findings.md) and [decisions](../../../docs/decisions.md).

## Notes

Raw outputs (`dm-r1.out`, `dm-r2.out`), replies and logs are not kept; the round-2 output header says "round 1" while its
replies were tagged r2. The reference reply used by the F95 scripts came from this experiment. Downloads totalled about
3 GB of safetensors; the converted GGUFs are not stored here.
