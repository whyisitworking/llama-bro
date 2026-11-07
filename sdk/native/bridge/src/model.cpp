// lb_model_*: one server_context per model on its own worker thread (P1 T3, design §3.2, F3).
// The startup order mirrors server.cpp main() as the P0 harness (spike/p0/harness/lb-spike.cpp) does:
// server_prepare_params, server_context, server_routes, load_model, update_meta, then start_loop on
// the same thread.
#include "model.h"
#include "ggml-backend.h"

#include "config.h"
#include "upstream.h"

#include "server-common.h"

#include "arg.h"

#include "llama.h"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#if defined(__ANDROID__)
#include <malloc.h>
#endif
#include <vector>

namespace {

std::map<std::string, server_http_context::handler_t> make_endpoints(server_routes & r) {
    // the same handlers, each wrapped in ex_wrapper, as server_register_routes() in tools/server/server.cpp
    return {
        { "chat",           ex_wrapper(r.post_chat_completions) },
        { "completion",     ex_wrapper(r.post_completions) },
        { "completion_oai", ex_wrapper(r.post_completions_oai) },
        { "control",        ex_wrapper(r.post_control) },
        { "embeddings",     ex_wrapper(r.post_embeddings_oai) },
        { "rerank",         ex_wrapper(r.post_rerank) },
        { "tokenize",       ex_wrapper(r.post_tokenize) },
        { "detokenize",     ex_wrapper(r.post_detokenize) },
        { "count_tokens",   ex_wrapper(r.post_chat_completions_tok) },
        { "apply_template", ex_wrapper(r.post_apply_template) },
        { "props",          ex_wrapper(r.get_props) },
        { "slots",          ex_wrapper(r.get_slots) },
        { "slots_action",   ex_wrapper(r.post_slots) },
        { "metrics",        ex_wrapper(r.get_metrics) },
        { "infill",                 ex_wrapper(r.post_infill) },
        { "responses",              ex_wrapper(r.post_responses_oai) },
        { "responses_input_tokens", ex_wrapper(r.post_responses_tok_oai) },
        { "messages",               ex_wrapper(r.post_anthropic_messages) },
        { "messages_count_tokens",  ex_wrapper(r.post_anthropic_count_tokens) },
        { "embeddings_native",      ex_wrapper(r.post_embeddings) },
        { "tts",                    ex_wrapper(r.post_tts) },
        { "systemone",              ex_wrapper(r.post_systemone) },
        { "props_set",              ex_wrapper(r.post_props) },
        { "models",                 ex_wrapper(r.get_models) },
        { "health",                 ex_wrapper(r.get_health) },
        { "lora_adapters",          ex_wrapper(r.get_lora_adapters) },
        { "lora_adapters_set",      ex_wrapper(r.post_lora_adapters) },
    };
}

void finish_load(lb_model * m, lb_status st, const std::string & error) {
    std::lock_guard<std::mutex> lk(m->mtx);
    m->load_status = st;
    m->load_error = error;
    m->state = st == LB_OK ? lb_model::READY : lb_model::FAILED;
    m->cv.notify_all();
}

void worker_main(lb_model * m) {
    lb_error_capture errors;  // llama.cpp logs why a load failed, and only there (F51)
    if (!server_prepare_params(m->params)) {
        finish_load(m, LB_ERR_INVALID_ARG, "invalid model parameters");
        return;
    }
    try {
        common_models_handler models_handler;
        common_models_handler_apply(models_handler, m->params);
    } catch (const std::exception & e) {
        finish_load(m, LB_ERR_LOAD_FAILED, std::string("failed to resolve model: ") + e.what());
        return;
    }

    // load progress: server_context replaces common_params.load_progress_callback with its own and reports
    // through the state callback ({"stages", "current", "value"}, server-context.cpp load_progress_callback)
    m->ctx_server.set_state_callback([m](server_state state, json payload) {
        if (state != SERVER_STATE_LOADING || !m->loading.load() || !m->progress_cb) return;
        if (!payload.contains("value") || !payload.contains("stages")) return;
        const auto stages = payload.at("stages").get<std::vector<std::string>>();
        const std::string current = payload.value("current", "");
        size_t idx = 0;
        while (idx < stages.size() && stages[idx] != current) idx++;
        const float n = stages.empty() ? 1.0f : (float) stages.size();
        const float overall = ((idx < stages.size() ? (float) idx : 0.0f) + payload.at("value").get<float>()) / n;
        m->progress_cb(m->progress_user, std::min(1.0f, overall));
    });

    m->routes = std::make_unique<server_routes>(m->params, m->ctx_server);

    bool loaded = false;
    std::string error = "failed to load model: " + m->params.model.path;
    try {
        loaded = m->ctx_server.load_model(m->params);
    } catch (const std::exception & e) {
        error += std::string(": ") + e.what();
    }
    if (!loaded && !errors.first_line().empty()) error += ": " + errors.first_line();
    if (!loaded) {
        m->ctx_server.terminate();
        finish_load(m, LB_ERR_LOAD_FAILED, error);
        return;
    }
    m->routes->update_meta(m->ctx_server);
    m->endpoints = make_endpoints(*m->routes);
    m->loading.store(false);
    finish_load(m, LB_OK, "");

    m->ctx_server.start_loop();  // returns after terminate()

    std::lock_guard<std::mutex> lk(m->mtx);
    m->loop_exited = true;
    m->cv.notify_all();
}

// A file that can't be opened gets its own code, so apps can tell it apart (F51). llama.cpp would fail on it too,
// but says why only in its log.
bool can_open(const std::string & path, std::string & err) {
    if (path.empty()) return true;
    FILE * f = std::fopen(path.c_str(), "rb");
    if (!f) {
        err = "cannot open " + path + ": " + std::strerror(errno);
        return false;
    }
    std::fclose(f);
    return true;
}

// GGUF metadata value, any length (llama_model_meta_val_str_by_index returns the full length, like snprintf)
std::string meta_value(const llama_model * model, int32_t i) {
    std::vector<char> buf(256);
    int32_t n = llama_model_meta_val_str_by_index(model, i, buf.data(), buf.size());
    if (n >= (int32_t) buf.size()) {
        buf.resize(n + 1);
        n = llama_model_meta_val_str_by_index(model, i, buf.data(), buf.size());
    }
    return n < 0 ? std::string() : std::string(buf.data(), std::min<size_t>(n, buf.size() - 1));
}

std::string meta_key(const llama_model * model, int32_t i) {
    std::vector<char> buf(256);
    int32_t n = llama_model_meta_key_by_index(model, i, buf.data(), buf.size());
    if (n >= (int32_t) buf.size()) {
        buf.resize(n + 1);
        n = llama_model_meta_key_by_index(model, i, buf.data(), buf.size());
    }
    return n < 0 ? std::string() : std::string(buf.data(), std::min<size_t>(n, buf.size() - 1));
}

}  // namespace

extern "C" {

lb_status lb_model_load(const char * config_json, lb_progress_cb cb, void * user, lb_model ** out, char ** error_json) {
    if (error_json) *error_json = nullptr;
    if (!out) {
        lb_set_error(error_json, "out is NULL");
        return LB_ERR_INVALID_ARG;
    }
    *out = nullptr;
    if (!lb_runtime_is_initialized()) {
        lb_set_error(error_json, "lb_runtime_init has not been called");
        return LB_ERR_NOT_INITIALIZED;
    }

    json cfg;
    try {
        cfg = json::parse(config_json ? config_json : "");
    } catch (const std::exception & e) {
        lb_set_error(error_json, std::string("invalid config JSON: ") + e.what());
        return LB_ERR_INVALID_ARG;
    }

    auto m = std::make_unique<lb_model>();
    std::string err;
    if (!lb_parse_model_config(cfg, m->params, err)) {
        lb_set_error(error_json, err);
        return LB_ERR_INVALID_ARG;
    }
    if (!can_open(m->params.model.path, err) || !can_open(m->params.mmproj.path, err)) {
        lb_set_error(error_json, "failed to load model: " + err, "file_not_found");
        return LB_ERR_LOAD_FAILED;
    }
    lb_error_capture errors;
    if (lb_config_wants_auto_ctx(cfg)) {
        const int64_t budget_mib = lb_config_memory_budget_mib(cfg);
        bool insufficient = false;
        const uint32_t cap = lb_config_ctx_cap(cfg);
        const bool ok = budget_mib > 0
            ? lb_fit_auto_ctx(m->params, budget_mib * 1024 * 1024, m->ctx_fit, insufficient, err, cap)
            : lb_resolve_auto_ctx(m->params, err, cap);
        if (!ok) {
            if (insufficient) {
                lb_set_error(error_json, "failed to load model: " + err, "insufficient_memory");
            } else {
                if (!errors.first_line().empty()) err += ": " + errors.first_line();
                lb_set_error(error_json, "failed to load model: " + err);
            }
            return LB_ERR_LOAD_FAILED;
        }
    }
    m->progress_cb = cb;
    m->progress_user = user;

    lb_model * raw = m.get();
    m->worker = std::thread(worker_main, raw);
    {
        std::unique_lock<std::mutex> lk(m->mtx);
        m->cv.wait(lk, [raw] { return raw->state != lb_model::LOADING; });
    }
    m->loading.store(false);  // no progress callbacks once this returns: `user` may be gone
    if (m->state == lb_model::FAILED) {
        m->worker.join();
        lb_set_error(error_json, m->load_error);
        return m->load_status;
    }
    *out = m.release();
    return LB_OK;
}

char * lb_model_info(lb_model * m) {
    if (!m) return nullptr;
    const llama_context * lctx = m->ctx_server.get_llama_context();
    const llama_model * model = llama_get_model(lctx);

    char desc[256] = {0};
    llama_model_desc(model, desc, sizeof(desc));

    json metadata = json::object();
    const int32_t n_meta = llama_model_meta_count(model);
    for (int32_t i = 0; i < n_meta; i++) {
        metadata[meta_key(model, i)] = meta_value(model, i);
    }

    json props = json::object();
    {
        const std::function<bool()> never = []() { return false; };
        server_http_req req{ {}, {}, "/props", "", "", {}, never };
        auto res = m->endpoints.at("props")(req);
        try {
            props = json::parse(res->data);
        } catch (...) {
            props = json::object();
        }
        res->on_complete();
    }

    json devices = json::array();  // the devices the model was loaded on (P2 T6)
    for (ggml_backend_dev_t dev : m->params.devices) {
        if (dev) devices.push_back(ggml_backend_dev_name(dev));
    }
    if (devices.empty()) devices.push_back("CPU");

    return lb_strdup(json{
        {"devices", devices},
        {"ctx_fit", m->ctx_fit},
        // what decodes: "mtp" when the model's MTP head drafts tokens (P2 T8)
        {"speculative", std::find(m->params.speculative.types.begin(), m->params.speculative.types.end(),
                                  COMMON_SPECULATIVE_TYPE_DRAFT_MTP) != m->params.speculative.types.end() ? "mtp" : "none"},
        {"n_ctx", llama_n_ctx(lctx)},
        {"n_ctx_train", llama_model_n_ctx_train(model)},
        {"n_params", llama_model_n_params(model)},
        {"size", llama_model_size(model)},
        {"desc", desc},
        {"metadata", metadata},
        {"props", props},
    }.dump());
}

void lb_model_free(lb_model * m) {
    if (!m) return;
    lb_model_cancel_all_requests(m);

    // start_loop() sets its running flag on entry, so a terminate() that lands before it would be lost
    // (server-queue.cpp start_loop/terminate): repeat until the loop has exited
    std::unique_lock<std::mutex> lk(m->mtx);
    while (!m->loop_exited) {
        lk.unlock();
        m->ctx_server.terminate();
        lk.lock();
        m->cv.wait_for(lk, std::chrono::milliseconds(20), [m] { return m->loop_exited; });
    }
    lk.unlock();
    m->worker.join();
    delete m;
#if defined(__ANDROID__)
    // bionic's allocator keeps the freed weights and KV cache (about the model's size) for reuse instead of
    // returning them; an app that closes a model expects that memory back (findings F49)
    mallopt(M_PURGE, 0);
#endif
}

}  // extern "C"
