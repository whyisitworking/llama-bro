#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    LB_OK = 0,
    LB_ERR_INVALID_ARG = 1,          // bad JSON, unknown config key, unknown endpoint
    LB_ERR_ALREADY_INITIALIZED = 2,  // lb_runtime_init called again with a different config
    LB_ERR_NOT_INITIALIZED = 3,
    LB_ERR_LOAD_FAILED = 4,
    LB_ERR_INTERNAL = 5,
    LB_ERR_CANCELLED = 6,            // lb_model_load stopped because its progress callback returned false
} lb_status;

typedef enum { LB_EVENT_CHUNK = 0, LB_EVENT_DONE = 1, LB_EVENT_CANCELLED = 2 } lb_event;
typedef enum { LB_LOG_DEBUG = 0, LB_LOG_INFO = 1, LB_LOG_WARN = 2, LB_LOG_ERROR = 3 } lb_log_level;

typedef struct lb_model lb_model;
typedef struct lb_request lb_request;

typedef void (*lb_log_cb)(void * user, lb_log_level level, const char * text);
// 0..1 while loading, on the loading thread; return false to stop the load (lb_model_load then returns
// LB_ERR_CANCELLED), true to go on. Once it has returned false it is not called again.
typedef bool (*lb_progress_cb)(void * user, float progress);
// CHUNK: http_status is the response status (200, or 4xx/5xx with an error body); data is the bytes as
// llama-server would send them (SSE events for streams, the whole body otherwise).
// Exactly one terminal event (DONE or CANCELLED) per request, always last, with data NULL.
// Called on the request's own thread.
typedef void (*lb_request_cb)(void * user, lb_event event, int http_status, const char * data, size_t len);

const char * lb_version(void);                                         // "llamabro <ver> llama.cpp b12479"
void lb_string_free(char * s);                                         // frees every char* this API returns

// config: {"backend_dir": "<dir>", "log_level": "warn", "backends": [...]}, all optional. The CPU backend is loaded by
// file name (libllamabro-ggml-cpu-*.so, the best variant for this CPU); without backend_dir the dynamic linker finds
// it, which on Android works straight from the APK. Fails if no CPU backend loads.
// backends: [{"name": "hexagon", "files": ["libllamabro-ggml-hexagon.so"], "env": {"GGML_HEXAGON_LIBHTP_DIR": "..."}}]:
// optional backends, loaded the same way after the CPU, each after its env is set (setenv, under the init lock).
// One that fails to load never fails init: lb_runtime_backends reports it with ggml's reason.
lb_status lb_runtime_init(const char * config_json, char ** error_json);
// NULL restores llama.cpp's default. The callback runs on llama's threads, outside the bridge's locks, and may
// call back into the API. On return, no call of the previous callback is running on another thread, so its
// `user` can be released (when called from inside the callback, after that callback returns).
void lb_runtime_set_log(lb_log_cb cb, void * user);
char * lb_runtime_devices(void);                                       // [{"name","description","type","memory_free","memory_total"}]
char * lb_runtime_backends(void);                                      // [{"name","loaded","error","devices"}], "cpu" first; [] before init
// Dry-run the same model config and device fit as lb_model_load, without loading tensors. Returns a JSON object with
// fits, n_ctx, n_ctx_min, used_min_mib, used_max_mib and budget_mib; NULL plus error_json on failure.
char * lb_fit_estimate(const char * config_json, char ** error_json);

// On LB_ERR_LOAD_FAILED, error_json is {"error": "<why, with llama.cpp's own reason>"}, plus "code": "file_not_found"
// when the model or mmproj file can't be opened.
// Model config "n_ctx": "auto" (or absent) picks the context: with "memory_budget_mib", the largest up to "n_ctx_cap" (or
// the training context) whose projected memory, over every device, fits the budget, at least 4096 tokens or
// LB_ERR_LOAD_FAILED with "code": "insufficient_memory"; without a budget, min(n_ctx_cap or 8192, training context).
// lb_model_info's "ctx_fit" has the numbers (null when not fitted).
// Model config "devices": ["HTP0"] puts the model (and its image encoder) on those devices, with "n_gpu_layers";
// absent or [] is CPU only. The device is never llama.cpp's default (every GPU-type device, F32). lb_model_info's
// "devices" names the ones used ("CPU" for CPU only). "mmproj_device" (upstream's -mmdev) moves the image encoder:
// "none" for the CPU or a device name such as "GPUOpenCL"; absent, it follows "devices".
// A progress callback that returns false stops the load (D67). A stop takes effect only from a progress call: while
// weights are read (the model, its draft and its image encoder), the read stops within one tensor. If the callback
// said stop on a call whose answer the load can't act on (MTP's own 0 and 1, the last tick), the load finishes and is
// then freed. Either way the result is LB_ERR_CANCELLED with nothing allocated: *out and *error_json stay NULL.
// Nothing calls cb after the last weight tick, so the stages without progress (creating the context, the warm-up
// decode, compiling GPU kernels, the NPU session) can't be stopped: a caller that cancels during them gets LB_OK and
// frees the returned model itself. With an image encoder or a draft, the text model's context and warm-up come before
// their stages, so a cancel during them is caught at the next stage's first tick. Bindings over this API (a future
// Flutter or KMP one) must free a model returned with LB_OK after their caller cancelled, as the Kotlin runtime does.
// cb and user are not used once lb_model_load returns.
lb_status lb_model_load(const char * config_json, lb_progress_cb cb, void * user, lb_model ** out, char ** error_json);
char * lb_model_info(lb_model * model);
// endpoint: every route tools/server/server.cpp registers for one model (server_register_routes), by name:
//   chat (/v1/chat/completions), count_tokens (/v1/chat/completions/input_tokens), control (…/control),
//   completion (/completion), completion_oai (/v1/completions), infill (/infill),
//   responses (/v1/responses), responses_input_tokens (/v1/responses/input_tokens),
//   messages (/v1/messages), messages_count_tokens (/v1/messages/count_tokens),
//   embeddings (/v1/embeddings), embeddings_native (/embeddings), rerank (/v1/rerank), tts (/tts),
//   speech (/v1/audio/speech), transcriptions (/v1/audio/transcriptions: the audio is attachment "file", F55),
//   systemone (/v1/systemone), tokenize, detokenize, apply_template (/apply-template),
//   props (GET /props), props_set (POST /props), models (/v1/models), health, slots (GET /slots),
//   slots_action (POST /slots/:id_slot), metrics, lora_adapters (GET), lora_adapters_set (POST /lora-adapters).
// Left out: /v1/realtime (a WebSocket), and the routes that are the HTTP layer's or the router's (/cors-proxy, /tools,
//   /models/load, /v1/stream and /v1/streams/lookup, the GCP compat routes).
// params_json: path/query params as a flat JSON object (e.g. {"id_slot":"0","action":"save"}), or NULL.
// lb_model_request_with below is the same call with attachments; this one sends none.
lb_status lb_model_request(lb_model * model, const char * endpoint, const char * params_json,
                           const char * body, size_t body_len, lb_request_cb cb, void * user,
                           lb_request ** out);

// A file sent beside a request (D84): exactly one of path, fd (>= 0) or data (len bytes) is set; set path NULL,
// fd -1 and data NULL for the others. Every attachment is read before lb_model_request_with returns, so the caller
// may release its data and close its fd as soon as the call returns; the bridge never closes fd. One exception: with a
// media_dir in the model config, a path attachment on the chat-like and embeddings routes is checked (its first bytes)
// but not read; the bridge links it into media_dir under a random name and writes file://<name>, and llama-server reads
// it while the request runs (F157), so the file must stay until the terminal event, when the link is removed. A descriptor is
// read whole from offset 0 with pread (its file offset unchanged), or with read when it can't seek (a pipe).
//   transcriptions: name is the form field, "file" for the audio (F55); body is the form's other fields as a JSON
//     object of strings ({"language": "en", "temperature": "0"}), as upstream reads form data.
//   chat, count_tokens, apply_template (the routes whose body is a chat completion's): in messages[].content[], a
//     part's image_url.url or input_audio.data that is exactly "lb-attachment:<name>" becomes
//     "data:<mime>;base64,<the file>", written here, so llama-server reads it as if the app had sent it. The same text
//     anywhere else (a message's text, response_format, tools) is left as is, and a body with nothing to replace is
//     passed on byte for byte. A placeholder there with no attachment of its name fails the request.
//   embeddings, embeddings_native: the same, in the content parts of the {"content": [...]} entries of "input" (or
//     "content"), one entry or an array of them, as llama-server reads multimodal embedding input. A text input or
//     anything else is left as is.
//   other routes: attachments are read and checked, then unused; the body is passed on as written.
// Audio (transcriptions, or a mime starting with "audio/") must be WAV, MP3 or FLAC, as llama.cpp decodes
// (mtmd-helper.cpp is_audio_file): the bridge checks the first bytes.
typedef struct {
    const char * name;      // the placeholder's name ("0" for "lb-attachment:0") or the form field ("file")
    const char * mime;      // e.g. "audio/wav", "image/png"; needed for a placeholder, optional for "file"
    const char * path;
    int fd;
    const uint8_t * data;
    size_t len;
} lb_attachment;

// lb_model_request with attachments. LB_ERR_INVALID_ARG, as for a bad endpoint or params_json, when an attachment
// has no name, a name used twice, not exactly one source, or (on a route other than transcriptions) no mime. A file
// that can't be read, audio that isn't WAV, MP3 or FLAC, or a placeholder with no attachment of its name is the
// request's error, as llama-server answers a bad body: one CHUNK with status 400 and
// {"error": {"code": 400, "message": "...", "type": "invalid_request_error"}}, then DONE.
lb_status lb_model_request_with(lb_model * model, const char * endpoint, const char * params_json,
                                const char * body, size_t body_len, const lb_attachment * atts, size_t n_atts,
                                lb_request_cb cb, void * user, lb_request ** out);
void lb_request_cancel(lb_request * req);      // idempotent; the terminal event follows soon after
void lb_request_free(lb_request * req);        // never blocks; may be called from inside the callback
void lb_model_free(lb_model * model);          // cancels in-flight requests, waits for their terminal
                                               // events, stops the loop, joins the worker thread

// Bridge-only (upstream has no such call): reads the whole file behind fd, from offset 0 with pread or with read when
// it can't seek, and leaves fd open. For an app that keeps a descriptor's media as bytes (D84). On success *out
// (free with lb_buffer_free) and *out_len are set; else LB_ERR_INVALID_ARG and error_json {"error": "..."}.
lb_status lb_read_fd(int fd, uint8_t ** out, size_t * out_len, char ** error_json);
void lb_buffer_free(uint8_t * buf);
#ifdef __cplusplus
}
#endif
