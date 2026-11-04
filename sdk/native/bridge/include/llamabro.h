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

lb_status lb_model_load(const char * config_json, lb_progress_cb cb, void * user, lb_model ** out, char ** error_json);
char * lb_model_info(lb_model * model);
// endpoint: chat, completion, completion_oai, control, embeddings, rerank, tokenize, detokenize,
//           count_tokens, apply_template, props, slots, slots_action, metrics
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
