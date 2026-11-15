# llama.cpp patches

The SDK never edits the llama.cpp submodule. Every change to it is a patch here, applied in name order to a build copy
(`sdk/native/build/llama.cpp`) by `sdk/native/scripts/prepare-llama.sh`, which Gradle's `prepareLlama` task runs before
every native build. A patch that no longer applies stops the build. Each speed patch must have won an A/B on the phone
([D10](../../../docs/decisions.md)); a functional patch, such as `0003`, is checked by tests instead.

| Patch | Origin | Why | Evidence | Drop when |
|---|---|---|---|---|
| `0001-delta-net-head-tail-split.patch` | Ours | NPU prefill with MTP: Hexagon's fast delta-net kernel needs K = 1; the patch runs all but the last K tokens with K = 1. +66% at b12352 | F7, F8, F121 | Upstream's kernel accepts K > 1 (`htp-kparams.cpp`, `K == 1`), or upstream takes the split; re-measure at every upgrade |
| `0002-bf16-neon-vec-dot.patch` | Upstream PR [#30432](https://github.com/ggml-org/llama.cpp/pull/30432) (head `cde3115c`), unmerged at b12352 | ggml-cpu has no ARM path for BF16 dot products (issue [#29917](https://github.com/ggml-org/llama.cpp/issues/29917)); the CPU image encoder with a BF16 projector 16.5 s → 3.1 s | F123, F127 | #30432, or another ARM BF16 path, is in the pinned build |
| `0003-load-progress-stop-hook.patch` | Ours; written up as reference for upstream ([upstream-pr.md](../../../research/ph/load-cancel/upstream-pr.md)), nothing posted | A cancelled load can't stop reading weights: llama-server's load progress callback always says go on. The patch adds `server_context::set_load_abort_callback`, asked before the 200 ms throttle, for the text model, the draft and the image encoder ([D67](../../../docs/decisions.md)). Stages without a progress call (context creation, warm-up, kernel compile) can't be stopped, though with an mmproj or a draft a cancel during the text model's is caught at the next stage's first tick (`server-context.cpp` ~1306-1395). Functional, not speed: no A/B; checked by the `load_stops_*` native tests and the phone timing | F145 | Upstream has an equivalent hook (or its progress callback lets an embedder stop the load) |

**At every llama.cpp upgrade** (the checklist in [CONTRIBUTING.md](../../../CONTRIBUTING.md#upgrading-llamacpp)), check
each row's "Drop when" first. For a patch from an upstream PR:
`gh pr view <number> --repo ggml-org/llama.cpp --json state,mergeCommit` and, if merged,
`git -C sdk/native/third_party/llama.cpp merge-base --is-ancestor <mergeCommit> HEAD`. If the fix is in the new pin,
delete the patch and its row, and record it in the upgrade's finding. If the PR changed before merging, use the merged
version, not the one carried here.
