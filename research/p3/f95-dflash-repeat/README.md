# F95: does DFlash on the NPU diverge from upstream's greedy reply again?

**Question.** One run of the SDK's `DflashDeviceTest` (NPU, Qwen3.5 4B with the DFlash draft, greedy, seed 42) diverged
from upstream llama-server's reply at a single token, in 1 of 6 runs, the first after the test APK was reinstalled. Is the
divergence a property of upstream and the NPU's numerics, or specific to the SDK's first run after an install?

## Method

Two probes on the OnePlus 13 (NPU, Hexagon v79), both greedy (temperature 0, seed 42, `cache_prompt` false, thinking off):

- **`f95.sh` (upstream).** `RUNS` fresh llama-server loads (default 8), each followed by `ASKS` JSON-extraction asks
  (default 3), from the P0 `P` build ([P0](../../p0/README.md)) in `/data/local/tmp/lb-spike/P` with SDK-like flags:
  `-dev HTP0 -ngl 99 -c 8192 -np 1 -b 512 -ub 512 --cache-ram 1024 --ctx-checkpoints 8 --load-mode none --fit off
  -md models/pf/Qwen3.5-4B-DFlash-Q8_0.gguf --spec-type draft-dflash --spec-draft-n-max 15 -fa on`. The prompt is the first 2,048
  tokens of `../../p2/mtp/source.txt` with a request to list every backend as a JSON array. Each reply is compared byte
  for byte (`cmp`) with the reference reply from [pf/draft-models](../../pf/draft-models/README.md)
  (`replies/npu-r1-dflash-json-greedy.txt`). Before each run the script waits for thermal status 2 or lower (`COOL`) and
  does not start a load at status 3 or higher.
- **`sdk_probe.sh` (the SDK).** `TRIALS` times (default 4): uninstall the hexagon test app, push Qwen3.5 and the DFlash
  GGUF to the app's external files directory, then run `DflashDeviceTest` through Gradle
  (`:llamabro-hexagon:connectedDebugAndroidTest`), so each trial is the first run after an install. The test asserts the
  reply equals upstream's. It refuses to start while the phone is locked.

## How to run

```bash
ANDROID_SERIAL=<serial> RUNS=8 ASKS=3 research/p3/f95-dflash-repeat/f95.sh
ANDROID_SERIAL=<serial> TRIALS=4 research/p3/f95-dflash-repeat/sdk_probe.sh   # models in ~/Downloads and ~/llamabro-models
```

`sdk_probe.sh` takes the repository root from `git rev-parse --show-toplevel` (override with `WT`). Draft and target GGUFs
are produced as described in [pf/draft-models](../../pf/draft-models/README.md).

## Result

- **Upstream:** 24 of 24 replies identical to the reference over 8 fresh loads. Each ask: `draft_n` 435, 225 accepted,
  decode 31.8-32.8 t/s, prefill 565-604 t/s, thermal status 2-3.
- **SDK, fresh installs:** 4 of 4 trials passed (`logs/probe-t1..t4.xml`, `tests="1" failures="0"`). Logcat
  (`logs/probe-t*.logcat`): `draft_n` 435, decode 32.7-33.5 t/s, `MemAvailable` 6.9-8.1 GB before the fixed-context load
  and 1.3-1.5 GB after the reply. In trial 1 an `Auto` load with the draft was refused (6,060 MiB needed, 5,012 MiB
  available); trials 2-4 chose 5,376, 10,240 and 7,936 tokens.
- The divergence did not recur in 28 further runs. It remains unexplained and unreproduced. Candidates not tested: the
  first NPU graph build after an install, an earlier draft load in the same process, numeric ties in acceptance. If
  `DflashDeviceTest` fails again, the run's XML and logcat should be kept and compared with F95.

## Findings / decisions

F95 (the single divergence), F98 (upstream 24 of 24), F99 (SDK 4 of 4); related F73, F90; D41 (DFlash ships as an
opt-in). See [findings](../../../docs/research/findings.md) and [decisions](../../../docs/decisions.md).

## Notes

Only the four SDK probe logs (`logs/probe-t*.logcat` and `.xml`) are kept. The upstream run output, replies and
per-run server logs, and the Gradle logs, are not. The reference reply used by `f95.sh` is also not kept, so the script
cannot be re-run without regenerating it with `pf/draft-models/dm.sh`. A first attempt of the SDK probe never started
Gradle (wrong wrapper path) and a first upstream launch overlapped a second copy of the script; both were discarded.
