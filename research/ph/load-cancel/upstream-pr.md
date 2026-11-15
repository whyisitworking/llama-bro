<!--
NOT TEXT TO POST. This is reference material for the maintainer of Llama Bro: the problem, the patch and the evidence,
with line citations. llama.cpp's rules (CONTRIBUTING.md "AI Usage Policy", AGENTS.md "Prohibited AI Usage", both in
sdk/native/third_party/llama.cpp) prohibit AI-written PR descriptions and posts of any kind, so none of this prose is
to be pasted into an issue or a PR.
- Upstream's route is an issue first (CONTRIBUTING.md: "Features must begin with an issue, not a PR"), written by the
  user in their own words.
- Any PR needs the PR template's AI-usage disclosure (Requirements section) and a manual review of every line.
- Search existing issues and PRs first (CONTRIBUTING.md "Before you start"; duplicates are closed). This draft did not.
Title:
-->
# server : let an embedder stop a model load through the load progress callback

### Problem

`llama_model_params.progress_callback` can stop a load: when it returns `false`, the loader stops reading weights
(`src/llama-model-loader.cpp`, `load_all_data`) and `llama_model_load` reports a cancelled load. The image encoder's
loader honours the same answer (`tools/mtmd/mtmd-model.cpp`).

llama-server installs its own progress callback for the text model, the draft model and the image encoder
(`server_context_impl::load_progress_callback`, `tools/server/server-context.cpp:1179-1202` at b12352). That callback
throttles its reports to one per 200 ms and always returns `true`. A program that embeds the server core, and calls
`server_context::load_model` itself, therefore has no way to stop a load once it has started: it can only wait for the
whole load and then free the model, which for a few-GB model on a phone or a slow disk takes seconds.

The standalone server doesn't need this, because it loads once at startup. Embedders do: an app that loads a model
while a screen is open wants the load to stop when the user leaves that screen.

### Change

A settable hook on `server_context`, next to `set_state_callback`:

```cpp
using server_load_abort_callback_t = std::function<bool()>;
void set_load_abort_callback(server_load_abort_callback_t callback);
```

`load_progress_callback` asks the hook on every call, before the 200 ms throttle, and returns `false` when the hook
returns `true` (the same convention as `ggml_abort_callback`). The loader then stops within one tensor, and
`load_model()` returns `false` as it does for any failed load. Since the text model, the draft model and the image
encoder share this callback, one hook covers all three. With no hook set, nothing changes.

The hook is polled rather than tied to the state callback's reports, so a stop is noticed between throttled reports
too. Stages without a progress callback (creating the context, the warm-up decode) are not affected: an embedder that
asks for a stop during them sees `load_model()` succeed and frees the model itself. With a draft or an mmproj, the text
model's context and warm-up come before those stages (`server-context.cpp:1306-1395`), so the stop is caught at the next
stage's first progress call.

### Testing

We carry this patch in an Android SDK that runs the server core in-process. Its tests load Qwen3.5 4B (Q4_K_M GGUF)
with a hook that stops at the first progress report and with one that stops past 30%. In both cases the load comes
back cancelled, llama.cpp logs `cancelled model load` (so the weight read itself stopped), and a normal load of the
same file afterwards succeeds. A load whose hook never stops runs as before.

### Patch

```diff
diff --git a/tools/server/server-context.cpp b/tools/server/server-context.cpp
index a6059ab..76ca6d2 100644
--- a/tools/server/server-context.cpp
+++ b/tools/server/server-context.cpp
@@ -1051,6 +1051,7 @@ public:
     server_chat_params chat_params;
 
     server_state_callback_t callback_state = [](server_state, json) -> void {};
+    server_load_abort_callback_t callback_load_abort;
 
     server_context_impl() {
         mtmd_helper_log_set(common_log_default_callback, nullptr);
@@ -1179,6 +1180,10 @@ private:
     static bool load_progress_callback(float progress, void * user_data) {
         auto * d = static_cast<load_progress_data *>(user_data);
         GGML_ASSERT(d);
+        // checked on every call, before the throttle, so a stop lands within one tensor
+        if (d->ctx->callback_load_abort && d->ctx->callback_load_abort()) {
+            return false;
+        }
         // always emit the first and final sample; throttle the rest to one per 200ms
         {
             auto & t_last = d->t_last_load_progress_ms;
@@ -5332,6 +5337,10 @@ void server_context::set_state_callback(server_state_callback_t callback) {
     impl->callback_state = std::move(callback);
 }
 
+void server_context::set_load_abort_callback(server_load_abort_callback_t callback) {
+    impl->callback_load_abort = std::move(callback);
+}
+
 //
 // server_routes
 //
diff --git a/tools/server/server-context.h b/tools/server/server-context.h
index 4b145cb..5964ab6 100644
--- a/tools/server/server-context.h
+++ b/tools/server/server-context.h
@@ -60,6 +60,7 @@ struct server_context_meta {
 };
 
 using server_state_callback_t = std::function<void(server_state, json /* payload */)>;
+using server_load_abort_callback_t = std::function<bool()>;
 
 struct server_context {
     std::unique_ptr<server_context_impl> impl;
@@ -90,6 +91,11 @@ struct server_context {
 
     // note: must be set before load_model() is called
     void set_state_callback(server_state_callback_t callback);
+
+    // optional: polled while the text, draft and multimodal models load; returning true stops the load,
+    // and load_model() then returns false
+    // note: must be set before load_model() is called
+    void set_load_abort_callback(server_load_abort_callback_t callback);
 };
 
 
```
