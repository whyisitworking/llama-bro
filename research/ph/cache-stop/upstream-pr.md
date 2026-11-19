# Upstream PR: the prompt cache on hybrid SWA models

> Facts, numbers, commands and code citations for an upstream pull request. llama.cpp asks for issues and pull-request
> descriptions written by a person (its CONTRIBUTING.md, "AI Usage Policy"), so this is material to write from, not text
> to post. Not posted.
> Upstream's process for it: open the issue first and link it; a bug-fix PR needs a regression test that fails before
> the change (the unit test below does); the template's AI-usage disclosure is filled in truthfully; every line is
> reviewed by whoever submits it; no non-ASCII characters in the commit message or comments (AGENTS.md).
>
> Searched on 2026-11-16: no existing PR for this. Related but separate: #31483 (iSWA `seq_rm`), #30683 (checkpoint
> copies).

Template: `.github/pull_request_template.md` (Overview, Additional information, Requirements).

## Title

server : SWA threshold of prompt reuse only for memory that can be partially removed

(Alternative: `server : fix prompt reuse on hybrid models with SWA layers`.)

## Overview: points to cover

- What: in `server_prompt::get_reuse`, use the SWA threshold (`pos_eval - n_swa + 2`) only when the target context
  can remove partial sequences (`COMMON_CONTEXT_SEQ_RM_TYPE_PART`); otherwise use `pos_eval`, as for any model
  without SWA. `get_reuse` gets the context's `seq_rm_type` as a new argument.
- Why: on `llama_memory_hybrid_iswa` (recurrent + SWA layers, e.g. LFM3), `seq_pos_min` is the recurrent position,
  which is the last position of the state, not the start of the SWA window. So the SWA threshold rejects the live
  state and every checkpoint within n_swa - 1 positions of the shared prefix's end, and each turn falls back at least
  255 positions (LFM3, n_swa 256). Fixes #ISSUE.
- Why it is enough to restore such a state: a hybrid checkpoint is taken just before decoding its n_tokens; its SWA
  cache still holds the last n_swa + n_ubatch positions, and the recurrent state is exact. The same holds for the live
  state. `--swa-full` (n_swa = 0 in the server) already takes this path, and gives the same cache reuse and the same
  replies as this change (numbers below).
- What does not change: pure attention (n_swa 0), SWA-only models (seq_rm PART, e.g. Gemma 3), and hybrid models
  without SWA (n_swa 0, e.g. Qwen3.5, LFM2) take the same branch as before. DeepSeek V4's compressed cache also cannot be partly
  removed, so the rule covers it too, but it already reports n_swa 0 (`llama_model_n_swa`), so nothing changes there.
- Test: new `server_prompt_get_reuse` cases in `tests/test-server-component.cpp`: attention live state, SWA-only
  window rule, recurrent checkpoint, hybrid-with-SWA checkpoint, hybrid-with-SWA live state. The two hybrid-with-SWA
  cases fail without the change (n_past 101 instead of 986 and 1000) and pass with it.

## Additional information: numbers

Built from `ef3848f`, Release, macOS (M-series), Metal unless noted. `-c 8192 -np 1`, otherwise defaults.

tinyLFM3-Omni Q2_K (`ggml-org-dev/tinyLFM3-Omni-for-testing-gguf`), the repro script from the issue (one long
message, then short ones, fixed assistant turns):

| turn | prompt | before cache_n / prompt_n | after cache_n / prompt_n |
|---|---|---|---|
| 2 | 559 | 21 / 538 | 533 / 26 |
| 3 | 581 | 43 / 538 | 555 / 26 |
| 4 | 603 | 65 / 538 | 577 / 26 |
| 5 | 625 | 87 / 538 | 599 / 26 |
| 6 | 647 | 109 / 538 | 621 / 26 |

LFM3-Omni-2B Q4_K_M, 10 turns with the model's own replies (s ~24 tokens, m ~180, l ~530):

| turn | msg | prompt | before cache_n / prompt_n | after cache_n / prompt_n |
|---|---|---|---|---|
| 2 | m | 204 | 1 / 203 | 25 / 179 |
| 3 | l | 767 | 1 / 766 | 235 / 532 |
| 4 | s | 822 | 251 / 571 | 798 / 24 |
| 5 | s | 848 | 306 / 542 | 825 / 23 |
| 6 | m | 1032 | 332 / 700 | 853 / 179 |
| 7 | s | 1087 | 516 / 571 | 1063 / 24 |
| 8 | l | 1622 | 571 / 1051 | 1091 / 531 |
| 9 | s | 1677 | 1106 / 571 | 1653 / 24 |
| 10 | s | 1705 | 1161 / 544 | 1682 / 23 |

`--swa-full` without this change gives exactly the "after" column.

- 12 short turns: before, cache_n is 1 on every turn (the whole chat is processed again each turn); after, it is the
  previous prompt plus reply and prompt_n is 23.
- Audio (30 s clip in turns 1, 2, 4): clip encodes per turn before 1, 2, 1, 2, 1; after 1, 1, 0, 1, 0. Prompt time of
  turns 2-5: before 703 / 429 / 714 / 420 ms; after 355 / 87 / 316 / 74 ms.
- LFM3-1.7B Q4_K_M (text only), same 10-turn chat: before, prompt_n is ~571 on every short turn; after, 24.

## How it was tested

Replies: greedy (`temperature 0, top_k 1`), each run compared turn by turn with the same chat sent with
`cache_prompt: false`.

| case | result |
|---|---|
| LFM3-Omni-2B, 10 turns text, Metal | replies equal to no-cache, before and after |
| LFM3-Omni-2B, audio chat (c,c,s,c,s), Metal and CPU | equal to no-cache, before and after |
| LFM3-1.7B, 10 turns, Metal | equal to no-cache, before and after |
| `-np 2`, two concurrent chats, Metal | equal to a concurrent no-cache run; each chat reuses its own slot |
| `--cache-ram 1024`, two chats alternating on one slot | equal to no-cache; after the change the state loaded from the RAM cache is reused (cache_n 543-811 vs 1-291 before) |
| slot save / restore (`--slot-save-path`, `--cache-ram 0`) | equal to no-cache; after restore, cache_n 752 (the whole saved state) vs 232 before |
| LFM3-Omni-2B, 10 turns text, CPU (`--device none`) | same cache_n as Metal; replies equal to `--swa-full` (no change) turn for turn. Both differ from no-cache on 4 of 10 turns, and so does gemma-3-4b on master (no change involved): on CPU, reusing a state that holds the previous reply's tokens can flip a later greedy choice (my guess: those tokens were decoded one at a time). With fixed assistant turns, replies equal no-cache before and after |
| Llama-3.2-3B, gemma-3-4b, Qwen3.5-0.8B, LFM2.5-230M, 10 turns, Metal | cache_n and replies identical before and after |
| `test-server-component` | all pass; the two new hybrid-with-SWA cases fail without the change |

Not tested:
- speculative decoding (MTP, EAGLE3, DFlash and similar set `n_rs_seq > 0`, which makes the context
  `COMMON_CONTEXT_SEQ_RM_TYPE_RS`; the change also applies there, since RS is not PART);
- other OS and backends (CUDA, Vulkan, ...); only macOS Metal and CPU;
- the Python server tests in `tools/server/tests` (not run);
- models other than LFM3 that use `llama_memory_hybrid_iswa`.

## Requirements (template)

- I have read and agree with the [contributing guidelines](https://github.com/ggml-org/llama.cpp/blob/master/CONTRIBUTING.md)
- AI usage disclosure: <!-- YES / NO; if yes, say how AI was used, in your own words -->

## The patch (`fix-master.patch`, against `ef3848f`)

```diff
diff --git a/tests/test-server-component.cpp b/tests/test-server-component.cpp
index 2ef8b04..44f2cc0 100644
--- a/tests/test-server-component.cpp
+++ b/tests/test-server-component.cpp
@@ -4,6 +4,7 @@
 #include "../tools/server/server-common.h"
 #include "../tools/server/server-decision.h"
 #include "../tools/server/server-schema.h"
+#include "../tools/server/server-slot-sched.h"
 
 #include <algorithm>
 #include <cmath>
@@ -242,6 +243,57 @@ static void test_server_tokens(testing & t) {
     t.test("prefix_hashes", test_server_tokens_prefix_hashes);
 }
 
+//
+// server_prompt::get_reuse
+//
+
+static void test_server_prompt_get_reuse(testing & t) {
+    // the cached prompt has 1000 tokens, the new prompt shares the first 990 and differs after that
+    const auto make_prompt = [](std::vector<std::pair<llama_pos, llama_pos>> ranges) {
+        server_prompt prompt;
+        prompt.tokens = server_tokens(llama_tokens(1000, 1), false);
+        for (const auto & r : ranges) {
+            auto ckpt = std::make_shared<common_prompt_checkpoint>();
+            ckpt->update_pos(r.second + 1, r.first, r.second);
+            prompt.checkpoints.push_back(ckpt);
+        }
+        return prompt;
+    };
+
+    t.test("attention, live state", [&](testing & t) {
+        const auto reuse = make_prompt({}).get_reuse(990, true, 0, COMMON_CONTEXT_SEQ_RM_TYPE_PART, 0);
+        t.assert_true("no checkpoint", reuse.ckpt == nullptr);
+        t.assert_equal("n_past", 990, reuse.n_past);
+    });
+
+    t.test("SWA, the window must reach back n_swa", [&](testing & t) {
+        // the newest checkpoint's window starts too late, the older one starts at 0
+        const auto reuse = make_prompt({{0, 100}, {800, 985}}).get_reuse(990, true, 256, COMMON_CONTEXT_SEQ_RM_TYPE_PART, 900);
+        t.assert_true("checkpoint", reuse.ckpt != nullptr);
+        t.assert_equal("n_past", 100, reuse.n_past);
+    });
+
+    // a checkpoint of recurrent memory has pos_min == pos_max, the position of the recurrent state
+    t.test("recurrent, the newest checkpoint", [&](testing & t) {
+        const auto reuse = make_prompt({{100, 100}, {985, 985}}).get_reuse(990, true, 0, COMMON_CONTEXT_SEQ_RM_TYPE_FULL, 999);
+        t.assert_true("checkpoint", reuse.ckpt != nullptr);
+        t.assert_equal("n_past", 986, reuse.n_past);
+    });
+
+    t.test("hybrid with SWA layers, the newest checkpoint", [&](testing & t) {
+        const auto reuse = make_prompt({{100, 100}, {985, 985}}).get_reuse(990, true, 256, COMMON_CONTEXT_SEQ_RM_TYPE_FULL, 999);
+        t.assert_true("checkpoint", reuse.ckpt != nullptr);
+        t.assert_equal("n_past", 986, reuse.n_past);
+    });
+
+    t.test("hybrid with SWA layers, live state", [&](testing & t) {
+        // the whole cached prompt is shared, the recurrent state is at its last token
+        const auto reuse = make_prompt({{100, 100}, {995, 995}}).get_reuse(1000, true, 256, COMMON_CONTEXT_SEQ_RM_TYPE_FULL, 999);
+        t.assert_true("no checkpoint", reuse.ckpt == nullptr);
+        t.assert_equal("n_past", 1000, reuse.n_past);
+    });
+}
+
 //
 // string / json helpers
 //
@@ -865,6 +917,7 @@ int main(int argc, char ** argv) {
     }
 
     t.test("server_tokens", test_server_tokens);
+    t.test("server_prompt_get_reuse", test_server_prompt_get_reuse);
     t.test("utf8",          test_utf8);
     t.test("sse",           test_sse);
     t.test("json_helpers",  test_json_helpers);
diff --git a/tools/server/server-context.cpp b/tools/server/server-context.cpp
index 28e01c6..6333dde 100644
--- a/tools/server/server-context.cpp
+++ b/tools/server/server-context.cpp
@@ -4182,7 +4182,7 @@ private:
                                     SLT_WRN(slot, "%s\n", st1.str().c_str());
                                 }
 
-                                auto reuse = slot.prompt.get_reuse(n_past, has_new_tokens, n_swa, pos_min);
+                                auto reuse = slot.prompt.get_reuse(n_past, has_new_tokens, n_swa, ctx_tgt_seq_rm_type, pos_min);
 
                                 if (reuse.ckpt) {
                                     const auto & cur = *reuse.ckpt;
diff --git a/tools/server/server-slot-sched.cpp b/tools/server/server-slot-sched.cpp
index 5b33292..7120a04 100644
--- a/tools/server/server-slot-sched.cpp
+++ b/tools/server/server-slot-sched.cpp
@@ -10,7 +10,7 @@
 // server_prompt
 //
 
-server_prompt_reuse server_prompt::get_reuse(int32_t n_prefix, bool has_new_tokens, int32_t n_swa, llama_pos pos_min) const {
+server_prompt_reuse server_prompt::get_reuse(int32_t n_prefix, bool has_new_tokens, int32_t n_swa, common_context_seq_rm_type seq_rm_type, llama_pos pos_min) const {
     server_prompt_reuse res;
     res.n_past   = n_prefix;
     res.pos_next = tokens.pos_next(n_prefix);
@@ -19,8 +19,11 @@ server_prompt_reuse server_prompt::get_reuse(int32_t n_prefix, bool has_new_toke
     // starting at pos_eval - n_swa + 1 is enough, while a state without SWA must end before pos_eval
     const llama_pos pos_eval = res.pos_next - (has_new_tokens ? 0 : 1);
 
+    // memory that cannot be partly removed reports pos_min at its end (a recurrent state, or DSV4's compressed state), not at the start of the SWA window
+    const bool use_swa = n_swa > 0 && seq_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_PART;
+
     // a state or a checkpoint is useful when its pos_min is below this threshold
-    const auto pos_min_thold = std::max(0, n_swa > 0 ? pos_eval - n_swa + 2 : pos_eval);
+    const auto pos_min_thold = std::max(0, use_swa ? pos_eval - n_swa + 2 : pos_eval);
 
     if (pos_min < pos_min_thold) {
         return res;
diff --git a/tools/server/server-slot-sched.h b/tools/server/server-slot-sched.h
index 8389d05..d951e02 100644
--- a/tools/server/server-slot-sched.h
+++ b/tools/server/server-slot-sched.h
@@ -42,9 +42,10 @@ struct server_prompt {
 
     // n_prefix: tokens shared with the new prompt, must be > 0
     // has_new_tokens: the new prompt is longer than n_prefix
+    // seq_rm_type: of the target context, the SWA window applies only to memory that can be partially removed
     // pos_min: min position of the live state
     // n_past == 0 without a checkpoint means that nothing can be reused
-    server_prompt_reuse get_reuse(int32_t n_prefix, bool has_new_tokens, int32_t n_swa, llama_pos pos_min) const;
+    server_prompt_reuse get_reuse(int32_t n_prefix, bool has_new_tokens, int32_t n_swa, common_context_seq_rm_type seq_rm_type, llama_pos pos_min) const;
 };
 
 struct server_prompt_data {
```
