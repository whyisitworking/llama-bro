# Delta-net patch 0001 and upstream: facts and checklist

> **Parent:** [research](../../README.md) · **About:** [`delta-net-head-tail-split.patch`](delta-net-head-tail-split.patch) (the SDK's 0001 until D80), [F149](../../../docs/research/findings.md), [D80](../../../docs/decisions.md), [F7](../../../docs/research/findings.md), [F8](../../../docs/research/findings.md), [F121](../../../docs/research/findings.md), [D10](../../../docs/decisions.md)

Working notes for the user, gathered 2026-11-15. **Nothing here has been posted, and nothing will be.** llama.cpp forbids
AI-written issues, PR descriptions, commit messages and review replies (`CONTRIBUTING.md` "AI Usage Policy" item 5,
`AGENTS.md` "Prohibited AI Usage"), so these are facts to write from, not text to paste.

## 1. Upstream's rules that apply (pinned b12352, read 2026-11-15)

- AI-written posts of any kind (issues, PR descriptions, commit messages, replies to reviewers) are prohibited and get the PR closed; undisclosed AI use can get the account banned.
- AI-written code is allowed, but the contributor must understand every line, explain it without AI help, and spend real review time on it (about one hour per 200-400 lines).
- AI use must be disclosed in the PR template's "AI usage disclosure" line (YES/NO plus how).
- Search existing issues and PRs first; duplicates are closed without discussion. If a PR exists, join it instead.
- Features start with an issue, not a PR. A speed change for one backend sits between "feature" and "fix"; opening an issue first is the safe reading.
- New contributors: at most one open PR.
- Changes to ggml ops or backends: run `test-backend-ops` against a second backend; a new op behaviour needs a test case there. Check perplexity and `llama-bench`.
- Keep it simple: "a simpler change that does 90% of the job is often preferable".
- Code style for agents' code: ASCII only in code and comments, comments of 1-2 short lines, no hard-wrapped comments.
- Owners (`CODEOWNERS`): `ggml/src/ggml-hexagon/` is `@ggml-org/ggml-hexagon` (lhez, max-krasnyansky); `src/models/` is `@ggml-org/llama-models` (CISC, danbev, ggerganov, ngxson).

## 2. Does the premise still hold?

| Check | b12352 (pinned) | master `de32b268` (2026-11-15, 64 commits later) |
|---|---|---|
| Hexagon HMX chunked GDN gated on `K == 1` (`htp-kparams.cpp`, `htp_kparams_solve_gated_delta_net`) | yes, line 565 | yes, line 567; the solver function is byte-identical |
| `src/models/delta-net-base.cpp` (where the patch applies) | unchanged since the patch was ported | identical to b12352 |
| `gated-delta-net-ops.c` (the kernels) | | no commits since 2026-11-12 |
| New: Hexagon SSM_SCAN HMX kernel (`ded522f83`, #31921, 2026-11-13) | not present | also gated on `K == 1` (line 2306): the same limit for Mamba-2 models |

So yes: on the NPU with MTP (K = n_max + 1 = 4) the whole prompt still runs on the slow HVX recurrent kernel. F121's
+66% (644 vs 387 t/s, Qwen3.5 4B, 2,071 tokens) still describes master.

## 3. What upstream has already decided (the important part)

- **CUDA hit the same problem and upstream chose a contract change, not a graph split.** In PR #29353 (CUDA/HIP chunked GDN kernel, merged 2026-10-14) a commenter pointed out the chunked kernel only ran for K == 1, so prefill with speculation never used it. ggerganov (2026-10-12) proposed declaring that `ggml_gated_delta_net` need not return the extra snapshots when `n_tokens > K`, with guardrails in `llama_context` later.
- **That contract is in `ggml.h` at b12352 and master** (line 2423): "when n_tokens > K only slot 0 is guaranteed; backends may leave slots 1..K-1 unwritten."
- **CUDA uses it**: the chunked (MMA) kernel is eligible when `K < n_tokens` (`ggml-cuda/gated_delta_net.cu:276`) and writes only the final state.
- **Hexagon has not followed.** Its gate is still `K == 1`, not `K == 1 || n_tokens > K`.
- **Upstream just removed ubatch-dependent graph topology.** #32078 (merged 2026-11-11, in b12352) removed the non-fused GDN paths so that "the graph topology is independent of the ubatch size again". Patch 0001 adds exactly such a branch (`if (n_seq_tokens > K)` adds views, a second GDN op and a `ggml_concat`), in `src/models/`, owned by the models team.
- **An open question upstream has not closed:** the batch splitter still keeps "the trailing (1 + n_rs_seq) tokens of each seq in the same ubatch so that the rollback snapshots remain valid" (`llama-memory-recurrent.cpp`, `[TAG_RECURRENT_ROLLBACK_SPLITS]`), while the `ggml.h` contract lets a long ubatch skip those snapshots. The `llama_context` guardrails ggerganov mentioned are not in b12352 as far as a grep shows. Under the patch every snapshot stays valid; under the contract they may not. Worth asking about in the issue, not guessing.

## 4. Existing issues and PRs (read-only search, 2026-11-15)

Searched issues and PRs for: gated delta net, delta net snapshot, `gated_delta_net K`, hexagon gdn / delta / HMX / MTP / speculative, htp-kparams, MTP prefill, snapdragon MTP, n_rs_seq, `llama_set_rs_snapshots`, chunked delta net.
**No issue or PR covers Hexagon's `K == 1` gate or NPU prefill with MTP.** GitHub Discussions were not searched (the CLI can't); check them by hand before posting.

Related, to cite or read:
- #29353 (merged): CUDA chunked GDN; the comment thread above is the precedent.
- #23531 (merged 2026-05): "Hexagon: OP_GATED_DELTA_NET K>1 support", the HVX kernel's K > 1 path (by ymcki, on a OnePlus 12).
- #30207 (open, draft): Vulkan chunked GDN, a port of #29353.
- #30183, #30359, #30360 (open): OpenCL chunked GDN and cache fusion (wanghqc).
- #30139 (open): graph reads recurrent states in place; edits `delta-net-base.cpp`, so it would conflict with patch 0001.
- #26617 (open): forward-telescoping rollback for recurrent models.

### Recheck, 2026-11-16 (read-only: `gh`, master `8bfd64b`)

- **Master still gates on `K == 1`** (`htp-kparams.cpp:567`; SSM_SCAN's HMX gate too, 2306). No commit to the file
  since #32228 (2026-11-14) changes it. No issue, PR or Discussion proposes relaxing it (searched: hexagon / GDN /
  gated delta net / MTP / snapshot / htp-kparams / Snapdragon; Discussion #28043 is older and unrelated).
- **#31669 "GDN: use replay instead of rollback" (am17an, open, updated 2026-11-15) changes the premise.** It removes
  the K snapshots: GDN keeps one committed state per sequence plus the inputs of a short last ubatch, and replays
  them on the next ubatch, so the op is always called with K = 1 (Hexagon rejects replay with `K != 1`).
  max-krasnyansky (a Hexagon code owner) implemented and tested the Hexagon part (2026-11-12, Qwen3.5-9B, MTP on and
  off). It is opt-in (`LLAMA_RS_REPLAY=1`, default off); the author wants all rollback machinery gone in one go.
  With replay on, a prompt batch is K = 1 and Hexagon's existing gate already lets the HMX kernel run: the problem
  this note is about should not arise. With replay off (the default), it still does.
- **#32337** ([RFC] ragged batching for GDN, am17an, stacked on #31669) touches `htp-kparams.cpp` too; not for merge.

What this means for the issue: a separate issue or PR about the snapshot gate would land while upstream is replacing
snapshots. The useful contribution is likely evidence on #31669 instead: NPU prefill with MTP, replay on against off,
on a phone (not run yet; would need #31669 built for the phone).

**Replay A/B on the phone (2026-11-16, [`replay-ab.sh`](replay-ab.sh), raw: `results/replay-ab.txt`):** #31669 at
`33a1dbf` built for the phone (upstream CMake, as the other arms), OnePlus 13, Qwen3.5 4B Q4_K_M on `HTP0`, the same
2,071-token prompt, MTP on (`draft-mtp`, n_max 3), three rotating rounds, thermal status 1 at every start:

| Arm | Rounds 1 / 2 / 3 | Mean | vs replay off |
|---|---|---|---|
| #31669, replay off (`LLAMA_RS_REPLAY=0`, snapshots, K = 4) | 383.7 / 385.5 / 387.8 | 386 | |
| #31669, replay on (`LLAMA_RS_REPLAY=1`, K = 1) | 850.5 / 824.8 / 821.0 | 832 | +116% |
| #31669 + the one-line gate, replay off | 631.8 / 670.1 / 658.0 | 653 | +69% |
| #31669, MTP off | 904.2 | 904 | |

Replay alone lets Hexagon's chunked kernel run the prompt, and it beats the gate by about 27%: it also removes the
K = 4 snapshot writes and leaves MTP's prefill only 8% under MTP off (the gate left 28%, F149). The same binary for off
and on, so the environment variable is the only difference; the server doesn't log `rs_replay` at the default
verbosity, so that line wasn't checked. Replay's replies were not checked here (upstream reports the same outputs and
drafts as snapshots on CUDA, Metal, Vulkan and WebGPU, and max-krasnyansky tested Hexagon).

**Rerun at the current PR head (2026-11-16, `a18ecc4`, raw: `results/replay-ab-a18ecc4.txt`, `results/lp-pr-*`):**
the PR moved on after the first run, so both arms were rebuilt and rerun. The server's own log (`-lv 4`) confirmed
`rs_replay = false / true / false` for the three arms before timing; thermal status 0 at every start.

| Arm (MTP on unless noted) | Rounds 1 / 2 / 3 | Mean | vs replay off |
|---|---|---|---|
| replay off | 396.4 / 400.2 / 392.3 | 396 | |
| replay on | 850.4 / 854.5 / 835.2 | 847 | +114% |
| replay off + the gate | 657.1 / 660.6 / 698.2 | 672 | +70% |
| MTP off | 910.7 | 911 | |

Correctness (the 19 greedy requests of F8/F149, `-c 16384`, each server's log checked for `rs_replay`):

| Pair | Identical | chosen-token \|Δlogprob\| p95 / max | top-2 gap at the splits | MTP acceptance |
|---|---|---|---|---|
| MTP off vs replay off (the reference) | 16/19 | 0.041 / 0.32 | 0.000, 0.073, 0.018 | 0.63 |
| MTP off vs replay on | 17/19 | 0.031 / 0.10 | 0.074, 0.114 | 0.62 |
| replay off vs replay on | 15/19 | 0.031 / 0.32 | 0.022 to 0.100 | 0.63 / 0.62 |

Replay is at least as close to MTP off as snapshots are (as the gate was in F149), every split is at a near-tie, and
draft acceptance is unchanged. Not covered: other models, more than one slot, prompts other than these.

What this means: the gate is not worth an issue or PR of its own; replay solves it better and is already in review
with the Hexagon owner. The useful post is a short comment on #31669 with this table (the user writes it). For the
SDK: once replay is the default in a pinned llama.cpp, drop patch 0001; until then, with #31669 unmerged, keep it.

**Posted (2026-11-16):** the user posted their own comment on #31669 with the `a18ecc4` speed and correctness results
above (written by the user; Claude checked the facts only). Nothing else was posted.

## 5. Options

| | What goes upstream | Fit with upstream | Size | Who reviews |
|---|---|---|---|---|
| **A** | An issue only: report that Hexagon's HMX GDN gate is `K == 1` while `ggml.h` and CUDA allow `n_tokens > K`, with the NPU numbers. Let the Hexagon team decide and implement. | Exactly the "issue first" rule; no code to own. | none | ggml-hexagon |
| **B** (recommended) | A then, if the Hexagon team wants it, a backend PR: relax the gate in `htp_kparams_solve_gated_delta_net` to match CUDA, plus a `test-backend-ops` case. | Same direction as CUDA and the `ggml.h` contract; backend-only; tiny. | probably a one-line condition, plus a test | ggml-hexagon |
| **C** | The head/tail split (patch 0001) in `delta-net-base.cpp`, as an issue then a PR. | Poor: reintroduces the ubatch-dependent graph that #32078 just removed, and solves at the graph level what upstream solved in the op contract. Its one real advantage (all K snapshots stay valid) is the open question in section 3, better raised in the issue than argued with a PR. | ~45 lines | llama-models |
| **D** | Nothing upstream; keep carrying 0001. | n/a | n/a | n/a |

**Recommendation: B, starting with A's issue.** Before posting, measure the relaxed gate as a local SDK patch: if it
matches 0001's speed and correctness, the SDK can carry a one-line Hexagon patch until upstream takes it, and the issue
carries numbers for the change upstream would actually want. The same question can be asked about SSM_SCAN's gate.

## 6. What a relaxed gate would touch (for the user to write, per AGENTS.md: guide, don't solve)

The one-line [`hexagon-gdn-gate.patch`](hexagon-gdn-gate.patch) exists only to measure (section 8). Any upstream PR should be your own edit, which you can explain line by line.

- `ggml/src/ggml-hexagon/htp-kparams.cpp`, `htp_kparams_solve_gated_delta_net`: the `can_use_hmx` condition's `(K == 1)` term. Compare CUDA's `ggml_get_op_params_i32(dst, 0) < v->ne[2]`.
- Why it should be layout-safe (to verify, not assume): the HMX kernel writes the final state at `dst + S_v*H*n_tokens*n_seqs + head offset` (`gated-delta-net-ops.c:1639-1641`), which is slot 0 of a K-slot output. Slots 1..K-1 stay unwritten, which the contract allows.
- The cache-copy fusion (`htp-fusion.cpp`, `fuse_gdn_cpy`) needs a contiguous destination; with K > 1 the snapshot copy usually isn't, so it stays unfused and copies the unwritten slots. Check that this is harmless.
- `tests/test-backend-ops.cpp`: the K > 1 cases are all `n_tokens == K` ("exact-match cases"); none covers `n_tokens > K`. Upstream will likely want one, and since the test compares the whole output, it may need to compare only the attention part and slot 0. CUDA has the same gap.
- Not affected: `src/models/`, `llama.h`, the server.

## 7. Evidence upstream would expect

- **Speed:** `llama-bench` or `llama-server` prefill, NPU (`HTP0`), MTP on and off, a few prompt lengths, before and after, alternating runs, cool phone. Name the phone (OnePlus 13, Snapdragon 8 Elite, Hexagon v79), the model (Qwen3.5 4B Q4_K_M) and the build. F8/F121's method already fits: `research/b12352/patch-ab.sh`.
- **No regression with MTP off** (F8: within noise for 0001; expected identical for a gate change, since K == 1 then).
- **Correctness:** `test-backend-ops -o GATED_DELTA_NET` on the phone, HTP0 against the CPU, including a new `n_tokens > K` case. `llama-perplexity` can't exercise snapshots (F8), so the greedy-logprob comparison from session 2 (19 requests, p95 chosen-token drift against upstream's own kernel switch) is the best end-to-end check.
- **MTP acceptance** before and after (F8: 0.50-0.62, moved both ways).
- Raw numbers stay ours; F7, F8 and F121 are the existing record.

## 8. Reproducing on the phone (only with the user's OK; short and attended; never on an emulator)

The plan is ready to run; nothing has run yet. The question: does the gate-only change ([`hexagon-gdn-gate.patch`](hexagon-gdn-gate.patch),
`(K == 1)` -> `(K == 1 || n_tokens > K)`, applies to b12352) match patch 0001's NPU prefill speed with MTP on, without
changing outputs more than upstream's own kernel switch does?

**Arms.** All three are upstream `llama-server` at b12352, built with upstream's CMake by
`research/b12352/build-at.sh`, the way upstream reviewers would build them. The SDK's other patches (0002 BF16, 0003
load stop) don't touch this path, so they're left out.

| Arm | Patch |
|---|---|
| `base` | none |
| `split` | `delta-net-head-tail-split.patch`, the SDK's 0001 then (repeats F121's arm) |
| `gate` | `hexagon-gdn-gate.patch` |

**Steps.** Run from a checkout whose llama.cpp submodule is initialised: the main checkout once this branch is merged,
or here after `git submodule update --init`.

1. Build on the Mac, no phone needed (about 3 Docker builds): `research/ph/delta-net/build-arms.sh`. With `ANDROID_SERIAL` set it also pushes to `/data/local/tmp/dn-{base,split,gate}`.
2. Speed, attended, roughly 15 minutes: `research/ph/delta-net/speed-ab.sh`. 3 rounds x 3 arms with MTP on, rotating order, then base and gate with MTP off. It waits for thermal status <= 1 before each run. Same model, flags and 2,071-token prompt as F121.
3. Correctness, attended, roughly 10 minutes: `lp-run.sh base off`, `lp-run.sh base on`, `lp-run.sh gate on`, `lp-run.sh split on`. Then `lp-diff.py` on these pairs:
   - base-off vs base-on: upstream's own kernel switch, the reference drift (F8: p95 0.041);
   - base-on vs gate-on: what the gate changes;
   - base-on vs split-on: repeats F8 at b12352;
   - base-off vs gate-on.
   It also prints MTP acceptance.
4. Record a new finding (number from the main session). If `gate` matches `split`, propose in a decision whether the SDK swaps 0001 for the one-line patch. Done: F149, D80 (the SDK now ships the gate as `0001-hexagon-gdn-long-batch.patch`).

**What counts as a pass.**
- `gate` is within run-to-run spread of `split` on prefill, and well above `base`.
- With MTP off, `gate` equals `base`, since the gate isn't reached when K == 1.
- base-on vs gate-on drift is no larger than base-off vs base-on.
- MTP acceptance moves within F8's range.

**Not covered.**
- `test-backend-ops`: none of its GATED_DELTA_NET cases has K > 1 with n_tokens > K, so on today's tests the gate changes nothing. A case for it is part of a future upstream PR, written by the user.
- Snapshot validity after a rollback into a long batch: that's the open question in section 3, and nothing in this plan exercises it.

### Speed result (2026-11-15)

OnePlus 13 (`HTP0`), upstream `llama-server` b12352 from `build-arms.sh`, Qwen3.5 4B Q4_K_M, 2,071-token prompt,
`speed-ab.sh`: arm order rotated each round, thermal status 0 before every run. Prefill t/s:

| MTP | base | split (0001) | gate (one line) |
|---|---|---|---|
| on, round 1 | 408.0 | 653.4 | 659.9 |
| on, round 2 | 400.8 | 626.1 | 610.3 |
| on, round 3 | 410.0 | 674.5 | 691.1 |
| **on, mean** | **406** | **651 (+60%)** | **654 (+61%)** |
| off | 907.2 | | 899.4 (-0.9%) |

- The one-line gate is as fast as patch 0001; the difference (0.5%) is well inside the round-to-round spread (610-691).
- With MTP off the gate isn't reached (K == 1); -0.9% from a single run each is noise.
- base and split repeat F121 (387 and 644 then).
- MTP still costs prefill even with either fix (654 against 907 t/s). That is outside both patches; not investigated.
- Before the run, five `llama-server` processes left from the b12352 experiments (2026-11-13; their directories since deleted) were still on the phone, stuck in shutdown with SIGTERM blocked, none listening. They were killed with SIGKILL. The scripts now kill with `-9` and check that the answering server runs from the arm's own directory.

### Correctness result (2026-11-15)

`lp-run.sh` (`-c 16384`, as in P0) on the same phone and builds: the 19 greedy requests of `research/p0/requests/a-lp.jsonl` (the longest about 8k tokens), logprobs with top 5. `lp-diff.py`:

| Pair | Identical | Chosen-token abs dlogprob p95 | max | Top-2 gap where they diverge | MTP acceptance |
|---|---|---|---|---|---|
| base MTP off vs base MTP on (upstream's own kernel switch: the reference) | 16/19 | 0.041 | 0.32 | 0.000, 0.073, 0.018 | - / 0.63 |
| base MTP on vs **gate** MTP on | 15/19 | **0.031** | 0.32 | 0.053, 0.022, 0.100, 0.091 | 0.63 / 0.62 |
| base MTP off vs **gate** MTP on | **17/19** | 0.031 | **0.10** | 0.074, 0.114 | - / 0.62 |
| base MTP on vs split MTP on | 16/19 | 0.037 | 0.39 | 0.086, 0.006, 0.019 | 0.63 / 0.66 |
| gate MTP on vs split MTP on | 14/19 | 0.032 | 0.13 | 0.181, 0.031, 0.201, 0.037, 0.000 | 0.62 / 0.66 |
| base MTP off vs split MTP on | 15/19 | 0.036 | 0.21 | 0.002, 0.000, 0.127, 0.018 | - / 0.66 |

- The reference and the split rows repeat F8 almost exactly (F8: p95 0.041 and 0.038, max 0.32 and 0.39), so the method is stable.
- The gate drifts no more than upstream's own kernel switch (p95 0.031 vs 0.041), and it is the closest of all to base with MTP off (17/19, max 0.10). That is expected: both run the prompt on the same HMX chunked kernel.
- Every divergence is at a near-tie (top-2 gap at most 0.20 nats); MTP acceptance stays within F8's range (0.62-0.66 here, 0.50-0.62 in F8).
- **Pass**, on the criteria set before the run. Still not covered: rollback into a long batch (section 3) and a `test-backend-ops` case for n_tokens > K.

### SDK device check after the swap (2026-11-15, D80)

`check.sh --device`: `device` passes. `device-contract` (the SDK against upstream llama-server, both built from the
same patched tree, on `HTP0`, no MTP so K = 1) gives 75/76: `t2-14-image` matches for about 80 tokens, then splits at a
near-tie ("the overall color palette" against "the color palette"). The same split came back on a rerun. The same tree
with the old split patch put back gives 76/76. Both sides batch the image request the same way (11 tokens, the
12-token image, 8 tokens, 4 tokens).

At K = 1 the gate is a no-op (`K == 1 || …`), so in this test the gate build is plain upstream. The split was not a
no-op: it acts whenever a batch has more than one token, so the last token of each batch went to the recurrent kernel.
So plain upstream's NPU path gives the SDK and llama-server different replies here, and the split hid it. Not yet
known: why two processes with the same batches differ (one guess is the chunked kernel reading padding it doesn't
initialise, chunks are 64 tokens), and whether it matters for the issue. Phone ran at thermal status 3 in all three runs.

**Probe (2026-11-15/16, [`state-probe.sh`](state-probe.sh)):** upstream llama-server from the same tools build, the
contract's flags, `t2-14-image` alone on a fresh server and after the contract's 13 earlier chat requests.

*First probe, which overlapped another session's `check.sh --device` on the same phone (from 23:40):*

| Setting | Fresh | After the 13 earlier |
|---|---|---|
| As the contract (`--cache-ram 1024`) | "the color palette" (3 of 3) | "the overall color palette" |
| `--cache-ram 0` | "the color palette" | "the overall color palette" |
| `GGML_HEXAGON_OPFILTER=GATED_DELTA_NET` (delta-net on the CPU; splits 1 → 49) | "the color palette" | "the color palette" |

*Rerun with the phone to itself (2026-11-16, no other llama-server, lb-run or check.sh):*

| Setting | Fresh | After the 13 earlier |
|---|---|---|
| As the contract | "the color palette" (2 of 2) | "the color palette" |
| `--cache-ram 0` | "the color palette" | "the color palette" |
| delta-net on the CPU | "the color palette" | "the color palette" |
| `GGML_HEXAGON_GDN_SELECT=1` (Hexagon's recurrent kernel only, no HMX) | "the color palette" (2 of 2) | "the color palette" |

So the dependence on earlier requests did not reproduce on an idle phone: it came with another process using the NPU
at the same time, and only when delta-net ran on the NPU. Every clean run gives the SDK side's contract reply. Open:
whether the original contract difference (the first run, which finished before 23:40) also had company on the NPU;
the demo app was open in the background throughout. A device-contract rerun on an idle phone would settle it.

**Device contract on the idle phone (2026-11-16, gate, nothing else running, the demo app closed):** 75/76 again,
`t2-14-image` again, but this time the other way round: upstream llama-server said "the color palette" and the SDK
"the overall color palette". So neither reply belongs to one side or to the requests before it: at this near-tie the
NPU picks either word from run to run, in either process. With the gate at K = 1 this is plain upstream's NPU path.
Tally with the gate: the contract differs in 3 runs of 3; with the split it matched in 2 of 2 (this tree and the main
checkout). The split's extra kernel switch may move the tie far enough from the edge, or the split was lucky twice.
Either way the SDK with the gate fails `device-contract` most of the time on this case.

**Decision (2026-11-16, D80):** keep the gate and let the device contract accept a near-tie flip:
`compare.py --near-tie 0.25` for streamed chat requests without tools, which `device-contract/run.sh` asks for their
top 2 logprobs (llama-server refuses logprobs with tools + stream, so tool cases stay exact). Checked on synthetic
replies (a near-tie passes; a wide gap, a token outside the other's top 2, or a split at the first token fails) and on
the phone: 76/76, all tool cases answered, no near-tie needed in that run.

## 9. Checklist before the user posts anything

- [ ] Re-read upstream `CONTRIBUTING.md` and `AGENTS.md` on master (they change).
- [ ] Search issues, PRs **and Discussions** again on the day.
- [ ] The enhancement template asks you to post the idea in Discussions (Ideas) first if there's no consensus yet. Decide: a Discussion, or an issue that cites #29353's thread as the consensus.
- [ ] The template also says "Please fill out this template yourself, copypasting language model outputs is strictly prohibited": this covers issues as well as PRs.
- [ ] Measure the gate-only variant (section 8) so the issue states what was measured, on which phone, model and build.
- [ ] Write the issue yourself, using the enhancement template (`020-enhancement.yml`); link #29353's thread and the `ggml.h` contract; ask whether the Hexagon team wants a PR and whether the trailing-snapshot rule (section 3) still matters.
- [ ] Only if they want a PR: write the change and the test yourself (or review every line until you can defend it unaided), run `test-backend-ops` and the CI steps upstream lists, fill the template's AI disclosure honestly.
- [ ] Commit message and PR description in your own words; if an assistant touched the code, `Assisted-by:` per AGENTS.md, not `Co-authored-by:`.
- [ ] Afterwards: update `sdk/native/patches/README.md`'s "Drop when" for 0001 with the issue/PR number.
