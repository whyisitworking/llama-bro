#pragma once
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
} lb_status;

typedef enum { LB_EVENT_CHUNK = 0, LB_EVENT_DONE = 1, LB_EVENT_CANCELLED = 2 } lb_event;
typedef enum { LB_LOG_DEBUG = 0, LB_LOG_INFO = 1, LB_LOG_WARN = 2, LB_LOG_ERROR = 3 } lb_log_level;

typedef struct lb_model lb_model;
typedef struct lb_request lb_request;

typedef void (*lb_log_cb)(void * user, lb_log_level level, const char * text);
typedef void (*lb_progress_cb)(void * user, float progress);            // 0..1 while loading
// CHUNK: http_status is the response status (200, or 4xx/5xx with an error body); data is the bytes as
// llama-server would send them (SSE events for streams, the whole body otherwise).
// Exactly one terminal event (DONE or CANCELLED) per request, always last, with data NULL.
// Called on the request's own thread.
typedef void (*lb_request_cb)(void * user, lb_event event, int http_status, const char * data, size_t len);

const char * lb_version(void);                                         // "llamabro <ver> llama.cpp b12052"
void lb_string_free(char * s);                                         // frees every char* this API returns

// config: {"backend_dir": "<dir>", "log_level": "warn"}, both optional. The CPU backend is loaded by file name
// (libllamabro-ggml-cpu-*.so, the best variant for this CPU); without backend_dir the dynamic linker finds it,
// which on Android works straight from the APK. Fails if no CPU backend loads.
lb_status lb_runtime_init(const char * config_json, char ** error_json);
// NULL restores llama.cpp's default. The callback runs on llama's threads, outside the bridge's locks, and may
// call back into the API. On return, no call of the previous callback is running on another thread, so its
// `user` can be released (when called from inside the callback, after that callback returns).
void lb_runtime_set_log(lb_log_cb cb, void * user);
char * lb_runtime_devices(void);                                       // [{"name","description","type","memory_free","memory_total"}]

// On LB_ERR_LOAD_FAILED, error_json is {"error": "<why, with llama.cpp's own reason>"}, plus "code": "file_not_found"
// when the model or mmproj file can't be opened.
lb_status lb_model_load(const char * config_json, lb_progress_cb cb, void * user, lb_model ** out, char ** error_json);
char * lb_model_info(lb_model * model);
// endpoint: every route tools/server/server.cpp registers for one model (server_register_routes), by name:
//   chat (/v1/chat/completions), count_tokens (/v1/chat/completions/input_tokens), control (…/control),
//   completion (/completion), completion_oai (/v1/completions), infill (/infill),
//   responses (/v1/responses), responses_input_tokens (/v1/responses/input_tokens),
//   messages (/v1/messages), messages_count_tokens (/v1/messages/count_tokens),
//   embeddings (/v1/embeddings), embeddings_native (/embeddings), rerank (/v1/rerank), tts (/tts),
//   systemone (/v1/systemone), tokenize, detokenize, apply_template (/apply-template),
//   props (GET /props), props_set (POST /props), models (/v1/models), health, slots (GET /slots),
//   slots_action (POST /slots/:id_slot), metrics, lora_adapters (GET), lora_adapters_set (POST /lora-adapters).
// Left out: /v1/audio/transcriptions (its audio arrives as a multipart file; this call takes a JSON body),
//   /v1/realtime (a WebSocket), and the routes that are the HTTP layer's or the router's (/cors-proxy, /tools,
//   /models/load, /v1/stream and /v1/streams/lookup, the GCP compat routes).
// params_json: path/query params as a flat JSON object (e.g. {"id_slot":"0","action":"save"}), or NULL.
lb_status lb_model_request(lb_model * model, const char * endpoint, const char * params_json,
                           const char * body, size_t body_len, lb_request_cb cb, void * user,
                           lb_request ** out);
void lb_request_cancel(lb_request * req);      // idempotent; the terminal event follows soon after
void lb_request_free(lb_request * req);        // never blocks; may be called from inside the callback
void lb_model_free(lb_model * model);          // cancels in-flight requests, waits for their terminal
                                               // events, stops the loop, joins the worker thread
#ifdef __cplusplus
}
#endif
