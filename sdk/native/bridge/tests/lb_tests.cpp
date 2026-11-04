// Host tests for the C API (P1 Tasks 2-4). One executable; argv[1] names the test. Each test runs in its
// own process (ctest), so process-wide runtime state starts fresh.
#include "llamabro.h"
#include "config.h"

#include "common.h"


#include <cstdio>
#include <cstdlib>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <vector>
#include <cstring>
#include <functional>
#include <map>
#include <string>


#define LB_CHECK(cond)                                                              \
    do {                                                                            \
        if (!(cond)) {                                                              \
            std::fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__, __LINE__, #cond); \
            std::exit(1);                                                           \
        }                                                                           \
    } while (0)

static std::string env_or(const char * name, const std::string & fallback) {
    const char * v = std::getenv(name);
    return v && *v ? v : fallback;
}

static std::string runtime_config(const char * log_level = "warn") {
    return json{{"backend_dir", env_or("LB_BACKEND_DIR", "")}, {"log_level", log_level}}.dump();
}

static void init_runtime() {
    char * err = nullptr;
    const lb_status st = lb_runtime_init(runtime_config().c_str(), &err);
    if (st != LB_OK) std::fprintf(stderr, "lb_runtime_init: %d %s\n", st, err ? err : "");
    LB_CHECK(st == LB_OK);
}

static std::string test_model() {
    std::string path = env_or("LB_TEST_MODEL", "");
    if (path.empty()) {
        const char * home = std::getenv("HOME");
        path = std::string(home ? home : "") + "/Downloads/Qwen3.5-4B-Q4_K_M.gguf";
    }
    if (FILE * f = std::fopen(path.c_str(), "rb")) {
        std::fclose(f);
    } else {
        std::fprintf(stderr, "test model not found at %s: set LB_TEST_MODEL to a GGUF file\n", path.c_str());
        std::exit(1);
    }
    return path;
}

// Loads the test model with `extra` merged into the config. Fails the test on error.
static lb_model * load_test_model(const json & extra = json::object()) {
    init_runtime();
    json cfg = {{"model_path", test_model()}};
    for (const auto & item : extra.items()) cfg[item.key()] = item.value();
    lb_model * model = nullptr;
    char * err = nullptr;
    const lb_status st = lb_model_load(cfg.dump().c_str(), nullptr, nullptr, &model, &err);
    if (st != LB_OK) std::fprintf(stderr, "lb_model_load: %d %s\n", st, err ? err : "");
    LB_CHECK(st == LB_OK);
    LB_CHECK(model != nullptr);
    return model;
}

static json model_info(lb_model * model) {
    char * s = lb_model_info(model);
    LB_CHECK(s != nullptr);
    json info = json::parse(s);
    lb_string_free(s);
    return info;
}

// ---------------------------------------------------------------------------------------------
// Task 2: runtime
// ---------------------------------------------------------------------------------------------

static void runtime_init_twice_same_config_ok() {
    char * err = nullptr;
    LB_CHECK(lb_runtime_init(runtime_config().c_str(), &err) == LB_OK);
    LB_CHECK(err == nullptr);
    LB_CHECK(lb_runtime_init(runtime_config().c_str(), &err) == LB_OK);
    LB_CHECK(err == nullptr);
}

static void runtime_init_different_config_fails() {
    char * err = nullptr;
    LB_CHECK(lb_runtime_init(runtime_config("warn").c_str(), &err) == LB_OK);
    LB_CHECK(lb_runtime_init(runtime_config("error").c_str(), &err) == LB_ERR_ALREADY_INITIALIZED);
    LB_CHECK(err != nullptr);
    LB_CHECK(json::parse(err).contains("error"));
    lb_string_free(err);
}

static void devices_lists_cpu() {
    init_runtime();
    char * s = lb_runtime_devices();
    LB_CHECK(s != nullptr);
    const json devices = json::parse(s);
    lb_string_free(s);
    LB_CHECK(devices.is_array());
    bool has_cpu = false;
    for (const auto & d : devices) has_cpu = has_cpu || d.value("type", "") == "cpu";
    LB_CHECK(has_cpu);
}

static int count_devices_of_type(const char * type) {
    char * s = lb_runtime_devices();
    LB_CHECK(s != nullptr);
    const json devices = json::parse(s);
    lb_string_free(s);
    int n = 0;
    for (const auto & d : devices) n += d.value("type", "") == type ? 1 : 0;
    return n;
}

// A failed init (no CPU backend in backend_dir) leaves the runtime retryable: a later init with a good config
// succeeds and registers exactly one CPU backend (P1 follow-up).
static void runtime_init_retry_after_failure() {
    char * err = nullptr;
    const std::string bad = json{{"backend_dir", "/nonexistent-llamabro-backends"}, {"log_level", "warn"}}.dump();
    const lb_status st = lb_runtime_init(bad.c_str(), &err);
    std::fprintf(stderr, "bad init: %d %s\n", st, err ? err : "");
    LB_CHECK(st == LB_ERR_INTERNAL);
    LB_CHECK(err != nullptr);
    lb_string_free(err);
    init_runtime();
    LB_CHECK(count_devices_of_type("cpu") == 1);
    lb_model_free(load_test_model());
}

// Backends come only from the bridge's own list: ggml's directory scan and GGML_BACKEND_PATH must not add
// any (an app's process must not be steered by environment variables, as in config.cpp).
static void backend_path_env_ignored() {
    const std::string dir = env_or("LB_BACKEND_DIR", "");
    LB_CHECK(!dir.empty());
    // a CPU variant other than the one the score picks, so a load would add a second CPU device
    const std::string other = dir + "/libggml-cpu-apple_m1.so";
    setenv("GGML_BACKEND_PATH", other.c_str(), 1);
    init_runtime();
    LB_CHECK(count_devices_of_type("cpu") == 1);
}

// The app's logger runs outside the bridge's lock: it may log or swap the logger itself without deadlocking,
// and llama's inference threads don't queue behind it (P1 review).
static std::atomic<int> g_log_calls{0};
static void swap_logger_inside_callback(void *, lb_log_level, const char *) {
    g_log_calls++;
    lb_runtime_set_log(nullptr, nullptr);  // re-enters the bridge from inside the callback
}

static void log_callback_can_reenter() {
    init_runtime();
    lb_runtime_set_log(swap_logger_inside_callback, nullptr);
    lb_model * model = nullptr;
    char * err = nullptr;
    // n_ctx fixed: skips the GGUF header read, so llama itself logs the failed load
    const json cfg = {{"model_path", "/nonexistent.gguf"}, {"n_ctx", 512}};
    LB_CHECK(lb_model_load(cfg.dump().c_str(), nullptr, nullptr, &model, &err) == LB_ERR_LOAD_FAILED);
    lb_string_free(err);
    LB_CHECK(g_log_calls.load() >= 1);
}

static void version_names_build() {
    LB_CHECK(std::strstr(lb_version(), "b12052") != nullptr);
}

// ---------------------------------------------------------------------------------------------
// Task 3: model load, info, free
// ---------------------------------------------------------------------------------------------

static void model_load_auto_ctx() {
    lb_model * model = load_test_model();
    const json info = model_info(model);
    const int n_ctx = info.at("n_ctx").get<int>();
    const int n_ctx_train = info.at("n_ctx_train").get<int>();
    LB_CHECK(n_ctx == std::min(8192, n_ctx_train));
    LB_CHECK(!info.at("metadata").value("general.architecture", "").empty());
    LB_CHECK(info.at("props").at("total_slots").get<int>() == 1);
    lb_model_free(model);
}

// build-info.cmake must describe the llama.cpp submodule, not the repo the copy sits in (F24)
static void props_build_info_names_llama_build() {
    lb_model * model = load_test_model({{"n_ctx", 512}});
    const std::string build_info = model_info(model).at("props").value("build_info", "");
    std::fprintf(stderr, "build_info: %s\n", build_info.c_str());
    LB_CHECK(build_info.find("b12052") != std::string::npos);
    LB_CHECK(build_info.find("84d9ce0") != std::string::npos);
    lb_model_free(model);
}

static void config_defaults_applied() {
    common_params p;
    std::string err;
    LB_CHECK(lb_parse_model_config(json{{"model_path", "x"}}, p, err));
    LB_CHECK(p.model.path == "x");
    LB_CHECK(p.n_parallel == 1);
    LB_CHECK(p.cache_ram_mib == 1024);
    LB_CHECK(p.n_ctx_checkpoints == 8);
    LB_CHECK(p.n_batch == 512);
    LB_CHECK(p.n_ubatch == 512);
    LB_CHECK(p.ctx_shift == false);
    LB_CHECK(p.sleep_idle_seconds == -1);
}

static void model_load_missing_file_fails() {
    init_runtime();
    lb_model * model = nullptr;
    char * err = nullptr;
    const lb_status st = lb_model_load(json{{"model_path", "/nonexistent.gguf"}}.dump().c_str(), nullptr, nullptr, &model, &err);
    LB_CHECK(st == LB_ERR_LOAD_FAILED);
    LB_CHECK(model == nullptr);
    LB_CHECK(err != nullptr && std::strstr(err, "\"error\"") != nullptr);
    lb_string_free(err);
}

static void model_load_unknown_key_fails() {
    init_runtime();
    lb_model * model = nullptr;
    char * err = nullptr;
    const json cfg = {{"model_path", test_model()}, {"n_ctxx", 1}};
    LB_CHECK(lb_model_load(cfg.dump().c_str(), nullptr, nullptr, &model, &err) == LB_ERR_INVALID_ARG);
    LB_CHECK(model == nullptr);
    LB_CHECK(err != nullptr && std::strstr(err, "n_ctxx") != nullptr);
    lb_string_free(err);
}

static void model_free_right_after_load() {
    for (int i = 0; i < 3; i++) {
        lb_model * model = load_test_model({{"n_ctx", 512}});
        lb_model_free(model);
    }
}

// ---------------------------------------------------------------------------------------------
// Task 4: requests, streaming, cancel
// ---------------------------------------------------------------------------------------------

using clk = std::chrono::steady_clock;

static int64_t ms_since(clk::time_point t) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(clk::now() - t).count();
}

// Records every callback. Thread-safe; tests wait on it.
struct collector {
    std::mutex mtx;
    std::condition_variable cv;
    std::vector<std::string> chunks;
    std::vector<int> statuses;
    int n_terminal = 0;
    int terminal = -1;              // lb_event of the terminal event
    bool chunk_after_terminal = false;
    clk::time_point t_terminal;
    lb_request * free_in_callback = nullptr;  // request_free_inside_callback

    static void cb(void * user, lb_event ev, int status, const char * data, size_t len) {
        auto * c = static_cast<collector *>(user);
        lb_request * to_free = nullptr;
        {
            std::lock_guard<std::mutex> lk(c->mtx);
            if (ev == LB_EVENT_CHUNK) {
                if (c->n_terminal > 0) c->chunk_after_terminal = true;
                c->chunks.emplace_back(data, len);
                c->statuses.push_back(status);
            } else {
                LB_CHECK(data == nullptr);
                c->n_terminal++;
                c->terminal = ev;
                c->t_terminal = clk::now();
                to_free = c->free_in_callback;
            }
        }
        if (to_free) lb_request_free(to_free);
        c->cv.notify_all();
    }

    void wait_chunks(size_t n) {
        std::unique_lock<std::mutex> lk(mtx);
        LB_CHECK(cv.wait_for(lk, std::chrono::seconds(120), [&] { return chunks.size() >= n || n_terminal > 0; }));
        LB_CHECK(chunks.size() >= n);
    }
    void wait_terminal() {
        std::unique_lock<std::mutex> lk(mtx);
        LB_CHECK(cv.wait_for(lk, std::chrono::seconds(300), [&] { return n_terminal > 0; }));
    }
};

// deterministic chat body: temperature 0, seed 1, no thinking
static json chat_body(const std::string & user, int max_tokens, bool stream) {
    json msg = json::object();
    msg["role"] = "user";
    msg["content"] = user;
    json messages = json::array();
    messages.push_back(msg);
    json kwargs = json::object();
    kwargs["enable_thinking"] = false;
    json body = json::object();
    body["messages"] = messages;
    body["max_tokens"] = max_tokens;
    body["temperature"] = 0;
    body["seed"] = 1;
    body["stream"] = stream;
    body["chat_template_kwargs"] = kwargs;
    return body;
}

static std::string long_prompt(int approx_tokens) {
    std::string s = "Repeat nothing. ";
    for (int i = 0; i < approx_tokens / 10; i++) s += "The quick brown fox jumps over the lazy dog. ";
    return s;
}

static lb_request * start(lb_model * m, const char * endpoint, const json & body, collector & c) {
    const std::string b = body.dump();
    lb_request * req = nullptr;
    const lb_status st = lb_model_request(m, endpoint, nullptr, b.data(), b.size(), collector::cb, &c, &req);
    LB_CHECK(st == LB_OK);
    LB_CHECK(req != nullptr);
    return req;
}

// concatenated choices[0].delta.content of an SSE stream
static std::string stream_content(const std::vector<std::string> & chunks) {
    std::string out;
    for (const auto & ch : chunks) {
        size_t pos = 0;
        while ((pos = ch.find("data: ", pos)) != std::string::npos) {
            const size_t end = ch.find("\n\n", pos);
            const std::string payload = ch.substr(pos + 6, end == std::string::npos ? std::string::npos : end - pos - 6);
            pos = end == std::string::npos ? ch.size() : end;
            if (payload == "[DONE]") continue;
            const json j = json::parse(payload);
            if (!j.contains("choices") || j.at("choices").empty()) continue;
            const json & delta = j.at("choices").at((size_t) 0).value("delta", json::object());
            if (delta.contains("content") && delta.at("content").is_string()) out += delta.at("content").get<std::string>();
        }
    }
    return out;
}

static void chat_nonstream_ok() {
    lb_model * m = load_test_model();
    collector c;
    lb_request * req = start(m, "chat", chat_body("Say hi", 8, false), c);
    c.wait_terminal();
    LB_CHECK(c.terminal == LB_EVENT_DONE && c.n_terminal == 1);
    LB_CHECK(c.chunks.size() == 1 && c.statuses[0] == 200);
    const json body = json::parse(c.chunks[0]);
    LB_CHECK(!body.at("choices").at((size_t) 0).at("message").value("content", "").empty());
    lb_request_free(req);
    lb_model_free(m);
}

static void chat_stream_ends_with_done_marker() {
    lb_model * m = load_test_model();
    collector c;
    lb_request * req = start(m, "chat", chat_body("Say hi", 8, true), c);
    c.wait_terminal();
    LB_CHECK(c.terminal == LB_EVENT_DONE && c.n_terminal == 1);
    LB_CHECK(!c.chunks.empty());
    for (const auto & ch : c.chunks) LB_CHECK(ch.rfind("data: ", 0) == 0);
    LB_CHECK(c.chunks.back() == "data: [DONE]\n\n");
    LB_CHECK(!c.chunk_after_terminal);
    lb_request_free(req);
    lb_model_free(m);
}

static void cancel_during_decode() {
    lb_model * m = load_test_model();
    collector c;
    lb_request * req = start(m, "chat", chat_body("Write a long story about a dragon.", 512, true), c);
    c.wait_chunks(5);
    const auto t_cancel = clk::now();
    lb_request_cancel(req);
    lb_request_cancel(req);  // idempotent
    c.wait_terminal();
    const auto dt = std::chrono::duration_cast<std::chrono::milliseconds>(c.t_terminal - t_cancel).count();
    std::fprintf(stderr, "cancel -> terminal: %lld ms\n", (long long) dt);
    LB_CHECK(c.terminal == LB_EVENT_CANCELLED && c.n_terminal == 1);
    LB_CHECK(dt <= 500);
    lb_request_free(req);
    lb_model_free(m);
}

static void cancel_during_prefill() {
    lb_model * m = load_test_model();
    collector c;
    lb_request * req = start(m, "chat", chat_body(long_prompt(3000), 16, true), c);
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    const auto t_cancel = clk::now();
    lb_request_cancel(req);
    c.wait_terminal();
    const auto dt = std::chrono::duration_cast<std::chrono::milliseconds>(c.t_terminal - t_cancel).count();
    std::fprintf(stderr, "cancel -> terminal: %lld ms\n", (long long) dt);
    LB_CHECK(c.terminal == LB_EVENT_CANCELLED);
    LB_CHECK(dt <= 2000);
    lb_request_free(req);
    lb_model_free(m);
}

// Typed chat always sends return_progress: each n_batch pass then yields a result, so the stream sees the
// cancel after one pass instead of at the 1 s polling tick (F23).
static void cancel_during_prefill_with_progress() {
    lb_model * m = load_test_model();
    collector c;
    json body = chat_body(long_prompt(3000), 16, true);
    body["return_progress"] = true;
    lb_request * req = start(m, "chat", body, c);
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    const auto t_cancel = clk::now();
    lb_request_cancel(req);
    c.wait_terminal();
    const auto dt = std::chrono::duration_cast<std::chrono::milliseconds>(c.t_terminal - t_cancel).count();
    std::fprintf(stderr, "cancel -> terminal: %lld ms\n", (long long) dt);
    LB_CHECK(c.terminal == LB_EVENT_CANCELLED);
    LB_CHECK(dt <= 1000);
    lb_request_free(req);
    lb_model_free(m);
}

static void context_exceeded_returns_counts() {
    lb_model * m = load_test_model({{"n_ctx", 256}});
    collector c;
    lb_request * req = start(m, "chat", chat_body(long_prompt(400), 8, false), c);
    c.wait_terminal();
    LB_CHECK(c.terminal == LB_EVENT_DONE);
    LB_CHECK(c.chunks.size() == 1 && c.statuses[0] == 400);
    const json err = json::parse(c.chunks[0]).at("error");
    LB_CHECK(err.value("type", "") == "exceed_context_size_error");
    LB_CHECK(err.at("n_prompt_tokens").get<int>() > err.at("n_ctx").get<int>());
    lb_request_free(req);
    lb_model_free(m);
}

static void unknown_endpoint_invalid_arg() {
    lb_model * m = load_test_model();
    collector c;
    lb_request * req = nullptr;
    LB_CHECK(lb_model_request(m, "nope", nullptr, "{}", 2, collector::cb, &c, &req) == LB_ERR_INVALID_ARG);
    LB_CHECK(req == nullptr);
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    LB_CHECK(c.chunks.empty() && c.n_terminal == 0);
    lb_model_free(m);
}

static void two_requests_one_slot_both_complete() {
    lb_model * m = load_test_model();
    const json a = chat_body("Name three colors.", 24, true);
    const json b = chat_body("Name three animals.", 24, true);
    std::string alone_a, alone_b;
    for (int i = 0; i < 2; i++) {
        collector c;
        lb_request * req = start(m, "chat", i == 0 ? a : b, c);
        c.wait_terminal();
        LB_CHECK(c.terminal == LB_EVENT_DONE);
        (i == 0 ? alone_a : alone_b) = stream_content(c.chunks);
        lb_request_free(req);
    }
    collector ca, cb;
    lb_request * ra = start(m, "chat", a, ca);
    lb_request * rb = start(m, "chat", b, cb);
    ca.wait_terminal();
    cb.wait_terminal();
    LB_CHECK(ca.terminal == LB_EVENT_DONE && cb.terminal == LB_EVENT_DONE);
    LB_CHECK(!alone_a.empty() && !alone_b.empty());
    LB_CHECK(stream_content(ca.chunks) == alone_a);
    LB_CHECK(stream_content(cb.chunks) == alone_b);
    lb_request_free(ra);
    lb_request_free(rb);
    lb_model_free(m);
}

static void model_free_with_inflight_request() {
    lb_model * m = load_test_model();
    collector c;
    lb_request * req = start(m, "chat", chat_body("Write a long story about a dragon.", 512, true), c);
    c.wait_chunks(3);
    lb_model_free(m);
    {
        std::lock_guard<std::mutex> lk(c.mtx);
        LB_CHECK(c.n_terminal == 1 && c.terminal == LB_EVENT_CANCELLED);
    }
    lb_request_cancel(req);  // safe after the model is gone
    lb_request_free(req);
}

static void request_free_inside_callback() {
    lb_model * m = load_test_model();
    collector c;
    {
        std::lock_guard<std::mutex> lk(c.mtx);  // set before the request can finish
        lb_request * req = start(m, "chat", chat_body("Say hi", 4, true), c);
        c.free_in_callback = req;
    }
    c.wait_terminal();
    LB_CHECK(c.terminal == LB_EVENT_DONE);
    lb_model_free(m);
}

int main(int argc, char ** argv) {
    const std::map<std::string, std::function<void()>> tests = {
        {"runtime_init_twice_same_config_ok", runtime_init_twice_same_config_ok},
        {"runtime_init_different_config_fails", runtime_init_different_config_fails},
        {"devices_lists_cpu", devices_lists_cpu},
        {"runtime_init_retry_after_failure", runtime_init_retry_after_failure},
        {"backend_path_env_ignored", backend_path_env_ignored},
        {"version_names_build", version_names_build},
        {"log_callback_can_reenter", log_callback_can_reenter},
        {"model_load_auto_ctx", model_load_auto_ctx},
        {"props_build_info_names_llama_build", props_build_info_names_llama_build},
        {"config_defaults_applied", config_defaults_applied},
        {"model_load_missing_file_fails", model_load_missing_file_fails},
        {"model_load_unknown_key_fails", model_load_unknown_key_fails},
        {"model_free_right_after_load", model_free_right_after_load},
        {"chat_nonstream_ok", chat_nonstream_ok},
        {"chat_stream_ends_with_done_marker", chat_stream_ends_with_done_marker},
        {"cancel_during_decode", cancel_during_decode},
        {"cancel_during_prefill", cancel_during_prefill},
        {"cancel_during_prefill_with_progress", cancel_during_prefill_with_progress},
        {"context_exceeded_returns_counts", context_exceeded_returns_counts},
        {"unknown_endpoint_invalid_arg", unknown_endpoint_invalid_arg},
        {"two_requests_one_slot_both_complete", two_requests_one_slot_both_complete},
        {"model_free_with_inflight_request", model_free_with_inflight_request},
        {"request_free_inside_callback", request_free_inside_callback},
    };
    if (argc < 2 || !tests.count(argv[1])) {
        std::fprintf(stderr, "usage: lb-tests <test>\n");
        for (const auto & t : tests) std::fprintf(stderr, "  %s\n", t.first.c_str());
        return 2;
    }
    tests.at(argv[1])();
    std::printf("PASS %s\n", argv[1]);
    return 0;
}
