// lb-spike: llama-server's core (server-context) driven in-process, with no HTTP.
//
// P0 spike for the Llama-Bro SDK v2 (docs/sdk-v2/plans/p0-spike.md §3.1). Startup mirrors
// tools/server/server.cpp main() of llama.cpp b12052, so that the only difference from
// llama-server is the transport. Takes llama-server's flags plus:
//   --spike-requests FILE   JSONL, one request per line:
//                           {"id": "...", "endpoint": "chat", "body": {...},
//                            "cancel_after_ms": 0, "cancel_after_chunks": 0}
//   --spike-out DIR         writes DIR/<id>.jsonl (timestamped chunks) and DIR/summary.jsonl

#include "server-common.h"
#include "server-context.h"
#include "server-http.h"
#include "server-stream.h"

#include "arg.h"
#include "common.h"
#include "llama.h"
#include "log.h"

#include <atomic>
#include <chrono>
#include <clocale>
#include <condition_variable>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <map>
#include <mutex>
#include <signal.h>
#include <string>
#include <thread>
#include <vector>


// ---------------------------------------------------------------------------------------------
// Copied word for word from tools/server/server.cpp:61 (b12052). Both functions are static there.
// ---------------------------------------------------------------------------------------------

static server_http_context::handler_t ex_wrapper(server_http_context::handler_t func) {
    return [func = std::move(func)](const server_http_req & req) -> server_http_res_ptr {
        std::string message;
        error_type error;
        try {
            return func(req);
        } catch (const std::invalid_argument & e) {
            // treat invalid_argument as invalid request (400)
            error = ERROR_TYPE_INVALID_REQUEST;
            message = e.what();
        } catch (const common_json_error & e) {
            // JSON parse and type errors are invalid requests (400)
            error = ERROR_TYPE_INVALID_REQUEST;
            message = e.what();
        } catch (const std::exception & e) {
            // treat other exceptions as server error (500)
            error = ERROR_TYPE_SERVER;
            message = e.what();
        } catch (...) {
            error = ERROR_TYPE_SERVER;
            message = "unknown error";
        }

        auto res = std::make_unique<server_http_res>();
        res->status = 500;
        try {
            json error_data = format_error_response(message, error);
            res->status = json_value(error_data, "code", 500);
            res->data = safe_json_to_str({{ "error", error_data }});
            SRV_WRN("got exception: %s\n", res->data.c_str());
        } catch (const std::exception & e) {
            SRV_ERR("got another exception: %s | while handling exception: %s\n", e.what(), message.c_str());
            res->data = "Internal Server Error";
        }
        return res;
    };
}

// From tools/server/server.cpp:115 (b12052), for role SERVER_ROLE_INSTANCE. The --connect
// branch is replaced by a refusal: server_connect lives in llama-server-impl, which the
// harness doesn't link, and the spike never uses it.
static bool server_prepare_params(common_params & params) {
    {
        // validate batch size for embeddings
        // embeddings require all tokens to be processed in a single ubatch
        // see https://github.com/ggml-org/llama.cpp/issues/12836
        if (params.embedding && params.n_batch > params.n_ubatch) {
            SRV_WRN("embeddings enabled with n_batch (%d) > n_ubatch (%d)\n", params.n_batch, params.n_ubatch);
            SRV_WRN("setting n_batch = n_ubatch = %d to avoid assertion failure\n", params.n_ubatch);
            params.n_batch = params.n_ubatch;
        }

        if (params.n_parallel < 0) {
            SRV_TRC("%s", "n_parallel is set to auto, using n_parallel = 4 and kv_unified = true\n");

            params.n_parallel = 4;
            params.kv_unified = true;
        }
    }

    // size the KV pool from --kv-unified-per-slot, unless the user pinned it with -c
    // or with -c 0 for max context
    const bool ctx_pool_auto_sized = params.kv_unified_per_slot > 0 &&
                                     params.n_ctx == 0 &&
                                     (uint32_t) params.fit_params_min_ctx != UINT32_MAX;

    if (ctx_pool_auto_sized) {
        params.n_ctx = params.n_parallel * params.kv_unified_per_slot;
        SRV_INF("--kv-unified-per-slot: sizing KV pool to n_parallel * kv_unified_per_slot = %d * %d = %d\n", params.n_parallel,
                params.kv_unified_per_slot, params.n_ctx);
    }

    // for consistency between server router mode and single-model mode, we set the same model name as alias
    auto model_name = params.model.get_name();
    if (params.model_alias.empty() && !model_name.empty()) {
        params.model_alias.insert(model_name);
    }

    if (params.server_connect) {
        SRV_ERR("%s", "lb-spike: --connect is not supported\n");
        return false;
    } else if (!params.server_connect_code.empty()) {
        // the code is only meaningful while the tunnel runs, and /props keys off it being set
        SRV_WRN("%s", "--connect-code is ignored without --connect\n");
        params.server_connect_code.clear();
    }

    return true;
}

// ---------------------------------------------------------------------------------------------
// Spike driver
// ---------------------------------------------------------------------------------------------

static int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

static long read_vm_hwm_kb() {
    std::ifstream f("/proc/self/status");
    std::string line;
    while (std::getline(f, line)) {
        if (line.rfind("VmHWM:", 0) == 0) {
            return std::atol(line.c_str() + 6);
        }
    }
    return -1; // not Linux/Android
}

static std::map<std::string, server_http_context::handler_t> make_endpoints(server_routes & routes) {
    // same handlers, each wrapped in ex_wrapper, as server_register_routes() in server.cpp
    return {
        { "chat",           ex_wrapper(routes.post_chat_completions) },
        { "completion",     ex_wrapper(routes.post_completions) },
        { "completion_oai", ex_wrapper(routes.post_completions_oai) },
        { "control",        ex_wrapper(routes.post_control) },
        { "embeddings",     ex_wrapper(routes.post_embeddings_oai) },
        { "rerank",         ex_wrapper(routes.post_rerank) },
        { "tokenize",       ex_wrapper(routes.post_tokenize) },
        { "detokenize",     ex_wrapper(routes.post_detokenize) },
        { "count_tokens",   ex_wrapper(routes.post_chat_completions_tok) },
        { "apply_template", ex_wrapper(routes.post_apply_template) },
        { "props",          ex_wrapper(routes.get_props) },
        { "slots",          ex_wrapper(routes.get_slots) },
        { "metrics",        ex_wrapper(routes.get_metrics) },
    };
}

// true while any slot is still processing (used to time how long a cancel takes to free the slot)
static bool any_slot_busy(const server_http_context::handler_t & get_slots) {
    const std::function<bool()> never = []() { return false; };
    server_http_req req{ {}, {}, "/slots", "", "", {}, never };
    auto res = get_slots(req);
    bool busy = false;
    try {
        for (const auto & s : json::parse(res->data)) {
            busy = busy || s.value("is_processing", false);
        }
    } catch (...) {
        busy = false;
    }
    res->on_complete();
    return busy;
}

struct cancel_timer {
    std::thread th;
    std::mutex mtx;
    std::condition_variable cv;
    bool done = false;

    void start(int ms, std::atomic<bool> & stop, std::atomic<int64_t> & t_cancel) {
        th = std::thread([this, ms, &stop, &t_cancel]() {
            std::unique_lock<std::mutex> lk(mtx);
            if (!cv.wait_for(lk, std::chrono::milliseconds(ms), [this] { return done; })) {
                t_cancel.store(now_us());
                stop.store(true);
            }
        });
    }
    void finish() {
        if (!th.joinable()) return;
        { std::lock_guard<std::mutex> lk(mtx); done = true; }
        cv.notify_all();
        th.join();
    }
};

static void run_requests(const std::string & requests_path, const std::string & out_dir,
                         const std::map<std::string, server_http_context::handler_t> & endpoints) {
    std::ifstream in(requests_path);
    std::ofstream summary(out_dir + "/summary.jsonl");
    const auto & get_slots = endpoints.at("slots");

    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        const json spec = json::parse(line);
        const std::string id       = spec.at("id");
        const std::string endpoint = spec.at("endpoint");
        const int cancel_ms        = spec.value("cancel_after_ms", 0);
        const int cancel_chunks    = spec.value("cancel_after_chunks", 0);

        const auto it = endpoints.find(endpoint);
        if (it == endpoints.end()) {
            summary << json::object({{"id", id}, {"error", "unknown endpoint " + endpoint}}).dump() << "\n";
            continue;
        }

        // req holds a reference to should_stop, and a stream keeps a pointer to req
        // (server-stream.cpp), so both live until after on_complete()
        std::atomic<bool> stop{false};
        std::atomic<int64_t> t_cancel{0};
        const std::function<bool()> should_stop = [&stop]() { return stop.load(); };
        server_http_req req{ {}, {}, "/" + endpoint, "", spec.at("body").dump(), {}, should_stop };

        std::ofstream out(out_dir + "/" + id + ".jsonl");
        cancel_timer timer;
        if (cancel_ms > 0) timer.start(cancel_ms, stop, t_cancel);

        const int64_t t0 = now_us();
        server_http_res_ptr res = it->second(req);
        int64_t t_first = 0;
        int n_chunks = 0;

        if (res->is_stream()) {
            bool has_next = true;
            while (has_next) {
                std::string chunk;
                has_next = res->next(chunk);
                const int64_t t = now_us();
                if (!chunk.empty()) {
                    if (n_chunks == 0) t_first = t;
                    n_chunks++;
                    out << json::object({{"t_us", t - t0}, {"data", chunk}}).dump() << "\n";
                }
                if (cancel_chunks > 0 && n_chunks >= cancel_chunks && !stop.load()) {
                    t_cancel.store(now_us());
                    stop.store(true);
                }
            }
        } else {
            t_first = now_us();
            out << json::object({{"t_us", t_first - t0}, {"data", res->data}}).dump() << "\n";
        }
        const int64_t t_returned = now_us();
        const int status = res->status;

        // destroying the response is what posts the cancel to the server loop (server-queue.cpp)
        res->on_complete();
        res.reset();
        timer.finish();

        int64_t t_idle = 0;
        if (t_cancel.load() != 0) {
            while (any_slot_busy(get_slots)) {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
            }
            t_idle = now_us();
        }

        const int64_t tc = t_cancel.load();
        summary << json::object({
            {"id", id},
            {"endpoint", endpoint},
            {"status", status},
            {"n_chunks", n_chunks},
            {"t_first_us", t_first ? t_first - t0 : -1},
            {"t_returned_us", t_returned - t0},
            {"t_cancel_us", tc ? tc - t0 : -1},
            {"cancel_to_return_us", tc ? t_returned - tc : -1},
            {"cancel_to_idle_us", tc ? t_idle - tc : -1},
        }).dump() << "\n";
        summary.flush();
    }
}

int main(int argc, char ** argv) {
    // split our own flags from llama-server's
    std::string requests_path, out_dir;
    std::vector<char *> args = { argv[0] };
    for (int i = 1; i < argc; i++) {
        const std::string a = argv[i];
        if (a == "--spike-requests" && i + 1 < argc) { requests_path = argv[++i]; continue; }
        if (a == "--spike-out"      && i + 1 < argc) { out_dir       = argv[++i]; continue; }
        args.push_back(argv[i]);
    }
    if (requests_path.empty() || out_dir.empty()) {
        fprintf(stderr, "usage: %s <llama-server flags> --spike-requests FILE --spike-out DIR\n", argv[0]);
        return 1;
    }

    // --- same order as llama_server(argc, argv) in server.cpp ---
    std::setlocale(LC_NUMERIC, "C");
    signal(SIGPIPE, SIG_IGN);

    common_params params;
    common_init();
    server_stream_session_manager_start();

    if (!common_params_parse((int) args.size(), args.data(), params, LLAMA_EXAMPLE_SERVER)) {
        return 1;
    }

    SRV_INF("%s", "initializing ...\n");
    llama_backend_init();
    llama_numa_init(params.numa);

    // --- server_run(): role is always INSTANCE (a model is given; no router) ---
    common_models_handler models_handler;
    common_params_print_info(params, true);
    if (!server_prepare_params(params)) {
        return 1;
    }

    // --- server_run_instance() ---
    try {
        common_models_handler_apply(models_handler, params);
    } catch (const std::exception & e) {
        SRV_ERR("failed to resolve model: %s\n", e.what());
        return 1;
    }

    server_context ctx_server;
    server_routes  routes(params, ctx_server); // the constructor calls init_routes()

    auto clean_up = [&ctx_server]() {
        server_stream_session_manager_stop();
        ctx_server.terminate();
        llama_backend_free();
    };

    if (!ctx_server.load_model(params)) {
        clean_up();
        SRV_ERR("%s", "exiting due to model loading error\n");
        return 1;
    }
    routes.update_meta(ctx_server);
    SRV_INF("lb-spike: model loaded, n_parallel = %d, kv_unified = %d\n", params.n_parallel, params.kv_unified);

    const auto endpoints = make_endpoints(routes);

    // requests come from another thread, as HTTP worker threads do in llama-server
    std::thread driver([&]() {
        run_requests(requests_path, out_dir, endpoints);
        ctx_server.terminate(); // unblocks start_loop()
    });

    // like server.cpp, start_loop() runs on the thread that loaded the model
    ctx_server.start_loop();
    driver.join();

    const long hwm = read_vm_hwm_kb();
    std::string rollup;
    {
        std::ifstream f("/proc/self/smaps_rollup");
        rollup.assign(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
    }
    std::ofstream(out_dir + "/memory.json") << json::object({{"vm_hwm_kb", hwm}, {"smaps_rollup", rollup}}).dump() << "\n";

    clean_up();
    return 0;
}
