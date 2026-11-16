// Host tests for the C API (P1 Tasks 2-4). One executable; argv[1] names the test. Each test runs in its
// own process (ctest), so process-wide runtime state starts fresh.
#include "llamabro.h"
#include "config.h"

#include "common.h"
#include "ggml-backend.h"
#include "gguf.h"


#include <cstdio>
#include <sys/resource.h>
#include <dlfcn.h>
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
#include <cmath>
#include <regex>
#include <string>
#include <filesystem>
#include <fstream>
#include <unistd.h>
#include <fcntl.h>
#include <iterator>

#include "base64.hpp"


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

static std::string test_dflash() {
    const char * home = std::getenv("HOME");
    const std::string path = env_or("LB_TEST_DFLASH", std::string(home ? home : "") +
                            "/llamabro-models/Qwen3.5-4B-DFlash-Q8_0.gguf");
    if (FILE * f = std::fopen(path.c_str(), "rb")) {
        std::fclose(f);
    } else {
        std::fprintf(stderr, "DFlash draft not found at %s: set LB_TEST_DFLASH to its GGUF file\n", path.c_str());
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

// A file that exists but isn't a GGUF; the caller removes it.
static std::string junk_file() {
    char path[] = "/tmp/lb-junk-XXXXXX";
    const int fd = mkstemp(path);
    LB_CHECK(fd >= 0);
    FILE * f = fdopen(fd, "w");
    std::fputs("not a model", f);
    std::fclose(f);
    return path;
}

static void log_callback_can_reenter() {
    init_runtime();
    lb_runtime_set_log(swap_logger_inside_callback, nullptr);
    lb_model * model = nullptr;
    char * err = nullptr;
    // n_ctx fixed: skips the GGUF header read, so llama itself logs the failed load
    const std::string junk = junk_file();
    const json cfg = {{"model_path", junk}, {"n_ctx", 512}};
    LB_CHECK(lb_model_load(cfg.dump().c_str(), nullptr, nullptr, &model, &err) == LB_ERR_LOAD_FAILED);
    lb_string_free(err);
    std::remove(junk.c_str());
    LB_CHECK(g_log_calls.load() >= 1);
}

static void version_names_build() {
    LB_CHECK(std::strstr(lb_version(), "b12352") != nullptr);
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

// ContextSize's cap is per slot (as upstream's fit): two slots get twice the context, each slot the cap
static void auto_ctx_cap_is_per_slot() {
    lb_model * model = load_test_model(json{{"n_parallel", 2}, {"n_ctx_cap", 2048}});
    const json info = model_info(model);
    LB_CHECK(info.at("n_ctx").get<int>() == 2 * std::min(2048, info.at("n_ctx_train").get<int>()));
    LB_CHECK(info.at("props").at("total_slots").get<int>() == 2);
    lb_model_free(model);
}

// build-info.cmake must describe the llama.cpp submodule, not the repo the copy sits in (F24)
static void props_build_info_names_llama_build() {
    lb_model * model = load_test_model({{"n_ctx", 512}});
    const std::string build_info = model_info(model).at("props").value("build_info", "");
    std::fprintf(stderr, "build_info: %s\n", build_info.c_str());
    LB_CHECK(build_info.find("b12352") != std::string::npos);
    LB_CHECK(build_info.find("5676dfb") != std::string::npos);
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
    LB_CHECK(p.load_mode == LLAMA_LOAD_MODE_NONE);  // F58, D30
    // no device given is CPU only, never llama.cpp's "every GPU" default (P2 T6, F32)
    LB_CHECK(p.devices.size() == 1 && p.devices[0] == nullptr);
    LB_CHECK(p.mmproj_use_gpu == false);
    LB_CHECK(p.fit_params == false);  // context and placement are explicit (D33)
}

static void mmproj_device_overrides_the_model_device() {
    // upstream's -mmdev: absent follows the model's device; "none" is the CPU; a CPU device name is refused
    init_runtime();  // the CPU backend registered, so "CPU" is refused as a CPU device, not as an unknown one
    common_params p;
    std::string err;
    LB_CHECK(lb_parse_model_config(json{{"model_path", "x"}, {"mmproj_device", "none"}}, p, err));
    LB_CHECK(p.mmproj_use_gpu == false && p.mmproj_device == nullptr);
    common_params q;
    LB_CHECK(!lb_parse_model_config(json{{"model_path", "x"}, {"mmproj_device", "CPU"}}, q, err));
    LB_CHECK(err.find("invalid mmproj_device") != std::string::npos);
    common_params r;
    LB_CHECK(!lb_parse_model_config(json{{"model_path", "x"}, {"mmproj_device", "NoSuchDevice"}}, r, err));
    common_params e;
    LB_CHECK(!lb_parse_model_config(json{{"model_path", "x"}, {"mmproj_device", ""}}, e, err));
}

static void model_load_missing_file_fails() {
    init_runtime();
    lb_model * model = nullptr;
    char * err = nullptr;
    const lb_status st = lb_model_load(json{{"model_path", "/nonexistent.gguf"}}.dump().c_str(), nullptr, nullptr, &model, &err);
    LB_CHECK(st == LB_ERR_LOAD_FAILED);
    LB_CHECK(model == nullptr);
    LB_CHECK(err != nullptr && std::strstr(err, "\"error\"") != nullptr);
    LB_CHECK(json::parse(err).value("code", "") == "file_not_found");
    lb_string_free(err);
}

// llama.cpp only logs why; the error carries its line (F51), with or without an app logger, and with no code
static void model_load_bad_file_has_reason() {
    init_runtime();
    const std::string junk = junk_file();
    for (const char * ctx : {"auto", "fixed"}) {
        lb_model * model = nullptr;
        char * err = nullptr;
        json cfg = {{"model_path", junk}};
        if (std::string(ctx) == "fixed") cfg["n_ctx"] = 512;
        LB_CHECK(lb_model_load(cfg.dump().c_str(), nullptr, nullptr, &model, &err) == LB_ERR_LOAD_FAILED);
        const json e = json::parse(err);
        std::fprintf(stderr, "%s: %s\n", ctx, e.dump().c_str());
        LB_CHECK(e.at("error").get<std::string>().find("invalid magic") != std::string::npos);
        LB_CHECK(!e.contains("code"));
        lb_string_free(err);
    }
    std::remove(junk.c_str());
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

// A load stops when its progress callback returns false (D67, F145): LB_ERR_CANCELLED, nothing returned, and the
// callback is not called again. The same file then loads normally, so the stopped load left nothing behind.
struct load_stopper {
    float stop_past;  // stop at the first call whose progress is past this
    std::vector<float> seen;
};

static constexpr float kNeverStop = 2.0f;  // progress never passes 1

static bool load_stopper_cb(void * user, float progress) {
    auto * s = static_cast<load_stopper *>(user);
    s->seen.push_back(progress);
    return progress <= s->stop_past;
}

static lb_status load_with_stopper(load_stopper & s, lb_model ** model, char ** err, const json & extra = json::object()) {
    json cfg = {{"model_path", test_model()}, {"n_ctx", 512}};
    for (const auto & item : extra.items()) cfg[item.key()] = item.value();
    return lb_model_load(cfg.dump().c_str(), load_stopper_cb, &s, model, err);
}

// llama.cpp logs "cancelled model load" (src/llama.cpp, llama_model_load_from_file) only when the progress callback
// made it stop reading weights, which only patch 0003's hook can do; the bridge's own check after the load can't
static std::atomic<bool> g_weight_read_aborted{false};
static void watch_for_aborted_read(void *, lb_log_level, const char * text) {
    if (text && std::strstr(text, "cancelled model load")) g_weight_read_aborted = true;
}

// read_aborted: whether the stop must end the weight read itself, or arrives after it (and the bridge frees the model)
static void check_load_stops(float stop_past, bool read_aborted, const json & extra = json::object()) {
    char * init_err = nullptr;
    // llama logs it at info, which common/log.cpp counts as trace: only "debug" lets it through
    LB_CHECK(lb_runtime_init(runtime_config("debug").c_str(), &init_err) == LB_OK);
    lb_runtime_set_log(watch_for_aborted_read, nullptr);
    load_stopper s{stop_past, {}};
    lb_model * model = nullptr;
    char * err = nullptr;
    const lb_status st = load_with_stopper(s, &model, &err, extra);
    std::fprintf(stderr, "status %d after %zu progress calls, last %.3f\n", st, s.seen.size(),
                 s.seen.empty() ? -1.0f : s.seen.back());
    LB_CHECK(st == LB_ERR_CANCELLED);
    LB_CHECK(model == nullptr);
    LB_CHECK(err == nullptr);
    LB_CHECK(!s.seen.empty());
    LB_CHECK(s.seen.back() > stop_past);  // the call that said stop was the last one
    for (size_t i = 0; i + 1 < s.seen.size(); i++) LB_CHECK(s.seen[i] <= stop_past);
    LB_CHECK(g_weight_read_aborted.load() == read_aborted);

    load_stopper keep{kNeverStop, {}};
    LB_CHECK(load_with_stopper(keep, &model, &err) == LB_OK);
    LB_CHECK(model != nullptr);
    lb_model_free(model);
}

static void load_stops_when_progress_says_false() {
    check_load_stops(-0.5f, true);  // every progress is past -0.5: the first call stops
}

static void load_stops_midway() {
    check_load_stops(0.3f, true);
}

// MTP loads no file: upstream reports its stage's 0 and 1 itself and ignores the answer, so a stop on the last tick
// arrives after every weight is read. The load finishes, then the bridge frees the model (D67)
static void load_stops_after_the_weights() {
    check_load_stops(0.99f, false, {{"speculative", {{"type", "mtp"}}}});
}

static void load_runs_when_progress_says_true() {
    init_runtime();
    load_stopper s{kNeverStop, {}};
    lb_model * model = nullptr;
    char * err = nullptr;
    const lb_status st = load_with_stopper(s, &model, &err);
    if (st != LB_OK) std::fprintf(stderr, "lb_model_load: %d %s\n", st, err ? err : "");
    LB_CHECK(st == LB_OK);
    LB_CHECK(model != nullptr);
    LB_CHECK(!s.seen.empty());
    LB_CHECK(s.seen.back() == 1.0f);
    lb_model_free(model);
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

// ---------------------------------------------------------------------------------------------
// P2 Task 2: optional backends, loaded by name, never failing the runtime
// ---------------------------------------------------------------------------------------------

static lb_status init_with_backends(const json & backends, char ** err) {
    json cfg = json::parse(runtime_config());
    cfg["backends"] = backends;
    return lb_runtime_init(cfg.dump().c_str(), err);
}

// one entry of the runtime config's "backends"
static json backend_entry(const std::string & name, const std::string & file, const std::map<std::string, std::string> & env = {}) {
    json b = json::object();
    b["name"] = name;
    b["files"] = json::array({file});
    if (!env.empty()) {
        json e = json::object();
        for (const auto & kv : env) e[kv.first] = kv.second;
        b["env"] = e;
    }
    return b;
}

static json one(const json & entry) {
    json list = json::array();
    list.push_back(entry);
    return list;
}

static json runtime_backends() {
    char * s = lb_runtime_backends();
    LB_CHECK(s != nullptr);
    json out = json::parse(s);
    lb_string_free(s);
    return out;
}

static json backend_named(const json & list, const std::string & name) {
    for (const auto & b : list) {
        if (b.value("name", "") == name) return b;
    }
    std::fprintf(stderr, "no backend %s in %s\n", name.c_str(), list.dump().c_str());
    std::exit(1);
}

static void backends_empty_before_init() {
    LB_CHECK(runtime_backends() == json::array());
}

static void optional_backend_failure_is_reported() {
    char * err = nullptr;
    LB_CHECK(init_with_backends(one(backend_entry("missing", "libllamabro-ggml-nonexistent.so")), &err) == LB_OK);
    LB_CHECK(err == nullptr);
    const json list = runtime_backends();
    LB_CHECK(list.size() == 2);
    LB_CHECK(list[0].value("name", "") == "cpu");
    const json cpu = backend_named(list, "cpu");
    LB_CHECK(cpu.at("loaded") == true);
    LB_CHECK(cpu.at("error").is_null());
    LB_CHECK(cpu.at("devices") == json::array({"CPU"}));
    const json missing = backend_named(list, "missing");
    LB_CHECK(missing.at("loaded") == false);
    LB_CHECK(missing.at("error").is_string());
    const std::string reason = missing.at("error").get<std::string>();
    LB_CHECK(reason.find("libllamabro-ggml-nonexistent.so") != std::string::npos);
    LB_CHECK(reason.find("dlopen") != std::string::npos);  // the linker's own reason, from ggml's log line
    LB_CHECK(missing.at("devices") == json::array());
    // CPU still loads and serves a model
    lb_model * model = nullptr;
    LB_CHECK(lb_model_load(json{{"model_path", test_model()}}.dump().c_str(), nullptr, nullptr, &model, &err) == LB_OK);
    lb_model_free(model);
}

static void backend_init_failure_is_reported() {
    char * err = nullptr;
    LB_CHECK(init_with_backends(one(backend_entry("test", LB_TEST_BACKEND_FILE, {{"LB_TEST_BACKEND_FAIL", "1"}})), &err) == LB_OK);
    const json test = backend_named(runtime_backends(), "test");
    LB_CHECK(test.at("loaded") == false);
    LB_CHECK(test.at("error").get<std::string>().find("returned NULL") != std::string::npos);
    LB_CHECK(count_devices_of_type("cpu") == 1);
}

static void backend_env_set_before_load() {
    char * err = nullptr;
    LB_CHECK(init_with_backends(one(backend_entry("test", LB_TEST_BACKEND_FILE, {{"LB_TEST_BACKEND_VALUE", "seen-by-init"}})), &err) == LB_OK);
    const json test = backend_named(runtime_backends(), "test");
    LB_CHECK(test.at("loaded") == true);
    LB_CHECK(test.at("error").is_null());
    LB_CHECK(test.at("devices") == json::array());
    // the library stays loaded (ggml's registry holds it): ask it what its ggml_backend_init saw
    const std::string path = env_or("LB_BACKEND_DIR", "") + "/" + LB_TEST_BACKEND_FILE;
    void * h = dlopen(path.c_str(), RTLD_NOW | RTLD_NOLOAD);
    LB_CHECK(h != nullptr);
    auto seen = reinterpret_cast<const char * (*)(void)>(dlsym(h, "lb_test_backend_seen"));
    LB_CHECK(seen != nullptr);
    LB_CHECK(std::string(seen()) == "seen-by-init");
    dlclose(h);
}

static void runtime_init_twice_same_config_with_backends_ok() {
    char * err = nullptr;
    const json backends = one(backend_entry("test", LB_TEST_BACKEND_FILE));
    LB_CHECK(init_with_backends(backends, &err) == LB_OK);
    LB_CHECK(init_with_backends(backends, &err) == LB_OK);
    LB_CHECK(err == nullptr);
    LB_CHECK(runtime_backends().size() == 2);  // cpu and test, not loaded twice
    LB_CHECK(init_with_backends(json::array(), &err) == LB_ERR_ALREADY_INITIALIZED);
    lb_string_free(err);
}

static void backends_config_invalid() {
    std::vector<json> bad;
    bad.push_back(json("x"));                       // not an array
    bad.push_back(json::array({"x"}));              // an entry that isn't an object
    json no_name = backend_entry("a", "a.so");
    no_name.erase("name");
    bad.push_back(one(no_name));
    json files_string = backend_entry("a", "a.so");
    files_string["files"] = "a.so";
    bad.push_back(one(files_string));
    json empty_files = backend_entry("a", "a.so");
    empty_files["files"] = json::array();
    bad.push_back(one(empty_files));
    json env_number = backend_entry("a", "a.so");
    json e = json::object();
    e["K"] = 1;
    env_number["env"] = e;
    bad.push_back(one(env_number));
    json extra = backend_entry("a", "a.so");
    extra["extra"] = 1;
    bad.push_back(one(extra));
    bad.push_back(one(backend_entry("cpu", "a.so")));  // "cpu" is the built-in backend
    json dup = json::array();
    dup.push_back(backend_entry("a", "a.so"));
    dup.push_back(backend_entry("a", "b.so"));
    bad.push_back(dup);
    for (const json & b : bad) {
        char * err = nullptr;
        const lb_status st = init_with_backends(b, &err);
        if (st != LB_ERR_INVALID_ARG) std::fprintf(stderr, "accepted: %s\n", b.dump().c_str());
        LB_CHECK(st == LB_ERR_INVALID_ARG);
        LB_CHECK(err != nullptr);
        lb_string_free(err);
    }
    LB_CHECK(runtime_backends() == json::array());  // nothing initialised
}

// ---------------------------------------------------------------------------------------------
// P2 Task 6: explicit devices
// ---------------------------------------------------------------------------------------------

static void model_devices_default_is_cpu() {
    lb_model * model = load_test_model();
    LB_CHECK(model_info(model).at("devices") == json::array({"CPU"}));
    lb_model_free(model);
}

static void model_devices_empty_is_cpu_and_no_scan() {
    // a backend named by GGML_BACKEND_PATH would be loaded by ggml's scan; with devices set, no load may scan (F35)
    const std::string dir = env_or("LB_BACKEND_DIR", "");
    setenv("GGML_BACKEND_PATH", (dir + "/" + LB_TEST_BACKEND_FILE).c_str(), 1);
    json extra = json::object();
    extra["devices"] = json::array();
    extra["n_gpu_layers"] = 0;
    lb_model * model = load_test_model(extra);
    LB_CHECK(model_info(model).at("devices") == json::array({"CPU"}));
    lb_model_free(model);
    LB_CHECK(ggml_backend_reg_count() == 1);  // only our CPU backend: no scan loaded the test backend behind our back
}

static void model_devices_invalid_names_fail() {
    init_runtime();
    for (const char * name : {"NOPE0", "CPU"}) {  // unknown, and the CPU itself (as parse_device_list)
        json cfg = {{"model_path", test_model()}};
        cfg["devices"] = json::array({name});
        lb_model * model = nullptr;
        char * err = nullptr;
        const lb_status st = lb_model_load(cfg.dump().c_str(), nullptr, nullptr, &model, &err);
        LB_CHECK(st == LB_ERR_INVALID_ARG);
        LB_CHECK(model == nullptr);
        LB_CHECK(err != nullptr && std::strstr(err, name) != nullptr);
        lb_string_free(err);
    }
}

// ---------------------------------------------------------------------------------------------
// P2 Task 7: automatic context fitted to a memory budget (D33)
// ---------------------------------------------------------------------------------------------

static common_params parsed_test_config() {
    common_params p;
    std::string err;
    LB_CHECK(lb_parse_model_config(json{{"model_path", test_model()}}, p, err));
    return p;
}

static void auto_ctx_fits_budget() {
    init_runtime();
    constexpr int64_t MiB = 1024 * 1024;
    // huge budget: the training context
    common_params p = parsed_test_config();
    json report;
    bool insufficient = false;
    std::string err;
    LB_CHECK(lb_fit_auto_ctx(p, 1024 * 1024 * MiB, report, insufficient, err));
    const uint32_t n_train = report.at("n_ctx_train").get<uint32_t>();
    LB_CHECK(p.n_ctx == (int32_t) n_train);
    const int64_t used_min = report.at("used_min_mib").get<int64_t>(), used_max = report.at("used_max_mib").get<int64_t>();
    LB_CHECK(used_min > 0 && used_max > used_min);
    // half way between the two measurements: in between, aligned to 256
    common_params q = parsed_test_config();
    LB_CHECK(lb_fit_auto_ctx(q, (used_min + (used_max - used_min) / 2) * MiB, report, insufficient, err));
    LB_CHECK(q.n_ctx > 4096 && q.n_ctx < (int32_t) n_train && q.n_ctx % 256 == 0);
    // a ceiling (ContextSize.Auto's 16384, D34) bounds it however much memory there is
    common_params c = parsed_test_config();
    LB_CHECK(lb_fit_auto_ctx(c, 1024 * 1024 * MiB, report, insufficient, err, 16384));
    LB_CHECK(c.n_ctx == 16384);
    LB_CHECK(report.at("n_ctx_cap").get<uint32_t>() == 16384);
    // less than the minimum context needs: a typed failure, not a smaller context
    common_params r = parsed_test_config();
    LB_CHECK(!lb_fit_auto_ctx(r, (used_min - 1) * MiB, report, insufficient, err));
    LB_CHECK(insufficient);
    LB_CHECK(err.find("not enough memory") != std::string::npos);
}

static void auto_ctx_budget_load_and_insufficient_error() {
    init_runtime();
    // a budget just above what the minimum context needs gives about that context, and the model loads with it
    common_params p = parsed_test_config();
    json report;
    bool insufficient = false;
    std::string err;
    LB_CHECK(lb_fit_auto_ctx(p, 1024LL * 1024 * 1024 * 1024, report, insufficient, err));
    const int64_t used_min = report.at("used_min_mib").get<int64_t>();
    json extra = json::object();
    extra["memory_budget_mib"] = used_min + 16;
    lb_model * model = load_test_model(extra);
    const json info = model_info(model);
    LB_CHECK(info.at("ctx_fit").at("n_ctx").get<int>() == info.at("n_ctx").get<int>());
    LB_CHECK(info.at("n_ctx").get<int>() >= 4096 && info.at("n_ctx").get<int>() < 8192);
    lb_model_free(model);
    // a budget below it: LB_ERR_LOAD_FAILED with code insufficient_memory, nothing allocated
    json cfg = {{"model_path", test_model()}};
    cfg["memory_budget_mib"] = used_min / 2;
    lb_model * none = nullptr;
    char * e = nullptr;
    LB_CHECK(lb_model_load(cfg.dump().c_str(), nullptr, nullptr, &none, &e) == LB_ERR_LOAD_FAILED);
    LB_CHECK(none == nullptr);
    LB_CHECK(json::parse(e).value("code", "") == "insufficient_memory");
    lb_string_free(e);
}

// ---------------------------------------------------------------------------------------------
// P2 Task 8: MTP only where the model has a head
// ---------------------------------------------------------------------------------------------

static bool has_mtp(const common_params & p) {
    return std::find(p.speculative.types.begin(), p.speculative.types.end(), COMMON_SPECULATIVE_TYPE_DRAFT_MTP) !=
           p.speculative.types.end();
}

// ---------------------------------------------------------------------------------------------
// D35: n-gram speculation (ngram-mod with upstream's --spec-default values), alone or before MTP (F72)
// ---------------------------------------------------------------------------------------------

static void ngram_config_maps_to_ngram_mod() {
    std::string err;
    common_params alone;
    LB_CHECK(lb_parse_model_config(json{{"model_path", test_model()}, {"speculative", {{"type", "ngram"}}}}, alone, err));
    // --spec-type appends to upstream's default list ({none}), as llama-server does with the same flag
    LB_CHECK(std::find(alone.speculative.types.begin(), alone.speculative.types.end(), COMMON_SPECULATIVE_TYPE_NGRAM_MOD) !=
             alone.speculative.types.end());
    LB_CHECK(!has_mtp(alone));
    LB_CHECK(alone.speculative.ngram_mod.n_match == 24);
    LB_CHECK(alone.speculative.ngram_mod.n_min == 48);
    LB_CHECK(alone.speculative.ngram_mod.n_max == 64);

    common_params with_mtp;
    LB_CHECK(lb_parse_model_config(json{{"model_path", test_model()}, {"speculative", {{"type", "ngram"}, {"mtp_n_max", 4}}}}, with_mtp, err));
    LB_CHECK(std::find(with_mtp.speculative.types.begin(), with_mtp.speculative.types.end(), COMMON_SPECULATIVE_TYPE_NGRAM_MOD) !=
             with_mtp.speculative.types.end());
    LB_CHECK(has_mtp(with_mtp));
    LB_CHECK(with_mtp.speculative.draft.n_max == 4);
    LB_CHECK(with_mtp.speculative.ngram_mod.n_max == 64);  // MTP's limit doesn't cap the n-gram drafts

    common_params bad;
    LB_CHECK(!lb_parse_model_config(json{{"model_path", test_model()}, {"speculative", {{"type", "ngram"}, {"n_max", 4}}}}, bad, err));
    LB_CHECK(err.find("speculative.n_max") != std::string::npos);
}

// a reply that copies its prompt: the case n-grams are for. Greedy output must not change.
static std::pair<std::string, int> ngram_copy_reply(lb_model * m) {
    std::string text;
    for (int i = 1; i <= 12; i++) text += "Line " + std::to_string(i) + ": the build copies libllama.so into the jniLibs folder for arm64-v8a. ";
    collector c;
    lb_request * req = start(m, "chat", chat_body("Repeat the following text exactly, and nothing else:\n\n" + text, 160, false), c);
    c.wait_terminal();
    LB_CHECK(c.terminal == LB_EVENT_DONE && c.chunks.size() == 1 && c.statuses[0] == 200);
    const json body = json::parse(c.chunks[0]);
    lb_request_free(req);
    return { body.at("choices").at((size_t) 0).at("message").value("content", ""),
             body.at("timings").value("draft_n_accepted", 0) };
}

static void ngram_drafts_and_keeps_the_reply() {
    lb_model * plain = load_test_model();
    const auto off = ngram_copy_reply(plain);
    lb_model_free(plain);
    lb_model * ngram = load_test_model(json{{"speculative", {{"type", "ngram"}}}});
    const auto on = ngram_copy_reply(ngram);
    LB_CHECK(model_info(ngram).at("speculative") == "none");  // "speculative" names MTP only
    lb_model_free(ngram);
    LB_CHECK(off.second == 0);
    LB_CHECK(on.second > 0);
    LB_CHECK(on.first == off.first);
}

// ---------------------------------------------------------------------------------------------
// P3 Task 6: a separate DFlash draft model and its memory projection (D41)
// ---------------------------------------------------------------------------------------------

static json dflash_config() {
    return {{"model_path", test_model()}, {"draft_path", test_dflash()},
            {"speculative", {{"type", "dflash"}}}};
}

static void dflash_config_maps_to_draft_dflash() {
    common_params p;
    std::string err;
    LB_CHECK(lb_parse_model_config(dflash_config(), p, err));
    LB_CHECK(std::find(p.speculative.types.begin(), p.speculative.types.end(),
                       COMMON_SPECULATIVE_TYPE_DRAFT_DFLASH) != p.speculative.types.end());
    LB_CHECK(p.speculative.draft.mparams.path == test_dflash());
    LB_CHECK(p.speculative.draft.n_max == 15);
    LB_CHECK(p.flash_attn_type == LLAMA_FLASH_ATTN_TYPE_ENABLED);
}

static void dflash_without_draft_fails() {
    common_params p;
    std::string err;
    LB_CHECK(!lb_parse_model_config(json{{"model_path", test_model()},
                                          {"speculative", {{"type", "dflash"}}}}, p, err));
    LB_CHECK(err == "speculative.type dflash needs draft_path");
}

static void draft_path_without_dflash_fails() {
    common_params p;
    std::string err;
    LB_CHECK(!lb_parse_model_config(json{{"model_path", test_model()}, {"draft_path", test_dflash()}}, p, err));
    LB_CHECK(err == "draft_path needs speculative.type dflash");
    LB_CHECK(!lb_parse_model_config(json{{"model_path", test_model()}, {"draft_path", test_dflash()},
                                          {"speculative", {{"type", "mtp"}}}}, p, err));
    LB_CHECK(err == "draft_path needs speculative.type dflash");
}

static void dflash_missing_file_is_file_not_found() {
    init_runtime();
    json cfg = dflash_config();
    cfg["draft_path"] = "/nonexistent-dflash.gguf";
    lb_model * model = nullptr;
    char * err = nullptr;
    LB_CHECK(lb_model_load(cfg.dump().c_str(), nullptr, nullptr, &model, &err) == LB_ERR_LOAD_FAILED);
    LB_CHECK(model == nullptr);
    LB_CHECK(json::parse(err).value("code", "") == "file_not_found");
    lb_string_free(err);
}

static void dflash_loads_and_drafts() {
    json config = dflash_config();
    config["n_ctx"] = 4096;
    lb_model * model = load_test_model(config);
    LB_CHECK(model_info(model).at("speculative") == "dflash");
    collector c;
    const std::string document = "The document lists backends CUDA for NVIDIA GPUs, Metal for Apple GPUs, and ";
    const std::string instruction = "List every backend the document above describes as a JSON array of objects "
        "with the keys \"name\", \"cmake_flag\" and \"platforms\" (an array of strings). Output only the JSON.";
    json body = chat_body(document + "\n\n" + instruction, 64, false);
    body["seed"] = 42;
    lb_request * req = start(model, "chat", body, c);
    c.wait_terminal();
    LB_CHECK(c.terminal == LB_EVENT_DONE && c.chunks.size() == 1 && c.statuses[0] == 200);
    const json response = json::parse(c.chunks[0]);
    LB_CHECK(!response.at("choices").at((size_t) 0).at("message").value("content", "").empty());
    LB_CHECK(response.at("timings").value("draft_n", 0) > 0);
    lb_request_free(req);
    lb_model_free(model);
}

static void fit_counts_the_draft() {
    init_runtime();
    constexpr int64_t MiB = 1024 * 1024;
    const int64_t huge_budget = 1024LL * 1024 * 1024 * 1024;
    std::string err;
    json plain_report, draft_report;
    bool insufficient = false;
    common_params plain = parsed_test_config();
    LB_CHECK(lb_fit_auto_ctx(plain, huge_budget, plain_report, insufficient, err));
    common_params draft;
    LB_CHECK(lb_parse_model_config(dflash_config(), draft, err));
    LB_CHECK(lb_fit_auto_ctx(draft, huge_budget, draft_report, insufficient, err));
    FILE * f = std::fopen(test_dflash().c_str(), "rb");
    LB_CHECK(f != nullptr);
    LB_CHECK(std::fseek(f, 0, SEEK_END) == 0);
    const int64_t draft_size_mib = std::ftell(f) / MiB;
    std::fclose(f);
    LB_CHECK(draft_report.at("used_min_mib").get<int64_t>() >=
             plain_report.at("used_min_mib").get<int64_t>() + (draft_size_mib * 9 / 10));
    LB_CHECK(draft_report.at("draft_mib_min").get<int64_t>() > 0);
    std::fprintf(stderr, "fit min: plain=%lld MiB, with draft=%lld MiB, draft=%lld MiB\n",
                 (long long) plain_report.at("used_min_mib").get<int64_t>(),
                 (long long) draft_report.at("used_min_mib").get<int64_t>(),
                 (long long) draft_report.at("draft_mib_min").get<int64_t>());
}

// The projection against a real load (review of P3, F90): the process's peak resident set after loading Qwen3.5 and
// the DFlash draft at 4096 tokens and answering once. Measured 5,793 projected vs 5,208 MiB peak (1.11; F90's lb-run
// run peaked at 5,576); the target's recurrent state counted twice would give about 1.26. Runs alone in its process.
static void dflash_fit_matches_load_rss() {
    init_runtime();
    std::string err;
    json report;
    bool insufficient = false;
    common_params draft;
    LB_CHECK(lb_parse_model_config(dflash_config(), draft, err));
    LB_CHECK(lb_fit_auto_ctx(draft, 1024LL * 1024 * 1024 * 1024, report, insufficient, err));
    const double projected = (double) report.at("used_min_mib").get<int64_t>();
    json config = dflash_config();
    config["n_ctx"] = 4096;
    lb_model * model = load_test_model(config);
    collector c;
    lb_request * req = start(model, "chat", chat_body("Say hello in five words.", 16, false), c);
    c.wait_terminal();
    LB_CHECK(c.terminal == LB_EVENT_DONE);
    rusage usage{};
    LB_CHECK(getrusage(RUSAGE_SELF, &usage) == 0);
#ifdef __APPLE__
    const double peak = (double) usage.ru_maxrss / (1024.0 * 1024.0);  // bytes on macOS
#else
    const double peak = (double) usage.ru_maxrss / 1024.0;             // KiB on Linux
#endif
    std::fprintf(stderr, "dflash fit: projected %.0f MiB, peak RSS %.0f MiB, ratio %.3f\n", projected, peak, projected / peak);
    LB_CHECK(projected >= peak * 0.90 && projected <= peak * 1.18);
    lb_request_free(req);
    lb_model_free(model);
}

// ---------------------------------------------------------------------------------------------
// Load-time features (F69): KV-cache type, embeddings and rerank, LoRA. Same models and checks as upstream's
// tools/server/tests/unit/test_embedding.py, test_rerank.py and test_lora.py (sdk/scripts/fetch-test-models.sh).
// ---------------------------------------------------------------------------------------------

static std::string upstream_test_model(const char * file) {
    const char * home = std::getenv("HOME");
    const std::string path = env_or("LB_TEST_MODELS_DIR", std::string(home ? home : "") + "/Downloads/llamabro-test") + "/" + file;
    if (FILE * f = std::fopen(path.c_str(), "rb")) {
        std::fclose(f);
    } else {
        std::fprintf(stderr, "%s not found: run sdk/scripts/fetch-test-models.sh (or set LB_TEST_MODELS_DIR)\n", path.c_str());
        std::exit(1);
    }
    return path;
}

static lb_model * load_at(const std::string & path, const json & extra) {
    init_runtime();
    json cfg = extra;
    cfg["model_path"] = path;
    lb_model * model = nullptr;
    char * err = nullptr;
    const lb_status st = lb_model_load(cfg.dump().c_str(), nullptr, nullptr, &model, &err);
    if (st != LB_OK) std::fprintf(stderr, "lb_model_load: %d %s\n", st, err ? err : "");
    LB_CHECK(st == LB_OK);
    return model;
}

// one non-streaming request: the HTTP status and the parsed body
static std::pair<int, json> call(lb_model * m, const char * endpoint, const json & body) {
    collector c;
    lb_request * req = start(m, endpoint, body, c);
    c.wait_terminal();
    lb_request_free(req);
    std::string all;
    for (const auto & ch : c.chunks) all += ch;
    return { c.statuses.empty() ? 0 : c.statuses.front(), json::parse(all) };
}

static void load_features_parsed() {
    std::string err;
    common_params p;
    json cfg = {{"model_path", "x"}, {"kv_cache_type", "q8_0"}, {"embeddings", true}, {"pooling", "mean"}};
    cfg["lora"] = json::array({ json{{"path", "/a,b:c.gguf"}, {"scale", 0.5}}, json{{"path", "/d.gguf"}} });
    LB_CHECK(lb_parse_model_config(cfg, p, err));
    LB_CHECK(p.cache_type_k == GGML_TYPE_Q8_0 && p.cache_type_v == GGML_TYPE_Q8_0);
    LB_CHECK(p.embedding && p.pooling_type == LLAMA_POOLING_TYPE_MEAN);
    LB_CHECK(p.lora_adapters.size() == 2);
    LB_CHECK(p.lora_adapters[0].path == "/a,b:c.gguf" && p.lora_adapters[0].scale == 0.5f);  // no csv or colon parsing
    LB_CHECK(p.lora_adapters[1].scale == 1.0f);

    common_params r;
    LB_CHECK(lb_parse_model_config(json{{"model_path", "x"}, {"reranking", true}}, r, err));
    LB_CHECK(r.embedding && r.pooling_type == LLAMA_POOLING_TYPE_RANK);  // as --reranking

    common_params d;  // defaults: f16 KV, generation, no adapters (upstream's)
    LB_CHECK(lb_parse_model_config(json{{"model_path", "x"}}, d, err));
    LB_CHECK(d.cache_type_k == GGML_TYPE_F16 && d.cache_type_v == GGML_TYPE_F16 && !d.embedding && d.lora_adapters.empty());

    const json bad[] = {
        {{"model_path", "x"}, {"kv_cache_type", "q3_k"}},
        {{"model_path", "x"}, {"pooling", "max"}},
        {{"model_path", "x"}, {"embeddings", "yes"}},
        {{"model_path", "x"}, {"lora", json{{"path", "/a"}}}},
        {{"model_path", "x"}, {"lora", json::array({json{{"path", "/a"}, {"alpha", 1}}})}},
        {{"model_path", "x"}, {"lora", json::array({json{{"scale", 1}}})}},
    };
    for (const auto & b : bad) {
        common_params q;
        LB_CHECK(!lb_parse_model_config(b, q, err));
    }
}

static void embeddings_mean_pooling() {  // test_embedding_pooling_mean
    lb_model * m = load_at(upstream_test_model("bge-small-f16.gguf"), json{{"embeddings", true}, {"pooling", "mean"}, {"n_ctx", 512}});
    auto [status, body] = call(m, "embeddings", json{{"input", "I believe the meaning of life is"}});
    LB_CHECK(status == 200);
    LB_CHECK(body.at("data").size() == 1);
    const auto & e = body.at("data")[0].at("embedding");
    LB_CHECK(e.size() > 1);
    double sq = 0;
    for (const auto & x : e) sq += x.get<double>() * x.get<double>();
    LB_CHECK(std::abs(sq - 1) < 1e-3);  // normalized
    lb_model_free(m);
}

static void embeddings_off_by_default() {  // the gate F69 found: a chat model loaded as before refuses
    lb_model * m = load_at(upstream_test_model("stories15M_MOE-F16.gguf"), json{{"n_ctx", 512}});
    auto [status, body] = call(m, "embeddings", json{{"input", "hello"}});
    LB_CHECK(status == 501);
    lb_model_free(m);
}

static void rerank_orders_documents() {  // test_rerank
    lb_model * m = load_at(upstream_test_model("jina-reranker-tiny-f16.gguf"), json{{"reranking", true}, {"n_ctx", 512}});
    const json docs = json::array({
        "A machine is a physical system that uses power to apply forces and control movement to perform an action. The term is commonly applied to artificial devices, such as those employing engines or motors, but also to natural biological macromolecules, such as molecular machines.",
        "Learning is the process of acquiring new understanding, knowledge, behaviors, skills, values, attitudes, and preferences. The ability to learn is possessed by humans, non-human animals, and some machines; there is also evidence for some kind of learning in certain plants.",
        "Machine learning is a field of study in artificial intelligence concerned with the development and study of statistical algorithms that can learn from data and generalize to unseen data, and thus perform tasks without explicit instructions.",
        "Paris, capitale de la France, est une grande ville européenne et un centre mondial de l'art, de la mode, de la gastronomie et de la culture. Son paysage urbain du XIXe siècle est traversé par de larges boulevards et la Seine.",
    });
    auto [status, body] = call(m, "rerank", json{{"query", "Machine learning is"}, {"documents", docs}});
    LB_CHECK(status == 200);
    const auto & results = body.at("results");
    LB_CHECK(results.size() == 4);
    json most = results[0], least = results[0];
    for (const auto & d : results) {
        if (d.at("relevance_score").get<double>() > most.at("relevance_score").get<double>()) most = d;
        if (d.at("relevance_score").get<double>() < least.at("relevance_score").get<double>()) least = d;
    }
    LB_CHECK(most.at("index") == 2);
    LB_CHECK(least.at("index") == 3);
    lb_model_free(m);
}

static bool matches(const std::string & s, const char * re) { return std::regex_search(s, std::regex(re)); }

static void lora_scale_changes_output() {  // test_lora_per_request
    json lora = json::array({ json{{"path", upstream_test_model("moe_shakespeare15M.gguf")}} });
    lb_model * m = load_at(upstream_test_model("stories15M_MOE-F16.gguf"), json{{"lora", lora}, {"n_ctx", 512}});
    const std::pair<double, const char *> cases[] = {
        {0.0, "(bright|day|many|happy)+"}, {0.3, "(special|thing|gifted)+"}, {0.7, "(far|from|home|away)+"}, {1.0, "(eye|love|glass|sun)+"},
    };
    for (const auto & [scale, re] : cases) {
        json body = {{"prompt", "Look in thy glass"}, {"seed", 42}, {"temperature", 0.0}, {"cache_prompt", false}};
        body["lora"] = json::array({ json{{"id", 0}, {"scale", scale}} });
        auto [status, res] = call(m, "completion", body);
        LB_CHECK(status == 200);
        const std::string content = res.at("content");
        if (!matches(content, re)) std::fprintf(stderr, "scale %.1f: %s\n", scale, content.c_str());
        LB_CHECK(matches(content, re));
    }
    auto [st, adapters] = call(m, "lora_adapters", json::object());
    LB_CHECK(st == 200 && adapters.size() == 1);
    lb_model_free(m);
}

static void kv_cache_q8_0_loads_and_generates() {
    lb_model * m = load_test_model(json{{"kv_cache_type", "q8_0"}, {"n_ctx", 4096}});
    auto [status, res] = call(m, "chat", chat_body("Say hello.", 8, false));
    LB_CHECK(status == 200);
    LB_CHECK(!res.at("choices")[0].at("message").at("content").get<std::string>().empty());
    lb_model_free(m);
    // a model whose head size q8_0's 32-value blocks don't divide fails with upstream's reason, not a crash
    init_runtime();
    json cfg = {{"model_path", upstream_test_model("stories15M_MOE-F16.gguf")}, {"kv_cache_type", "q8_0"}, {"n_ctx", 512}};
    lb_model * bad = nullptr;
    char * err = nullptr;
    LB_CHECK(lb_model_load(cfg.dump().c_str(), nullptr, nullptr, &bad, &err) != LB_OK);
    LB_CHECK(err && std::strstr(err, "does not divide n_embd_head_k"));
    lb_string_free(err);
}

static json estimate(const json & cfg) {
    char * err = nullptr;
    char * raw = lb_fit_estimate(cfg.dump().c_str(), &err);
    if (!raw) std::fprintf(stderr, "lb_fit_estimate: %s\n", err ? err : "unknown error");
    LB_CHECK(raw != nullptr);
    json result = json::parse(raw);
    lb_string_free(raw);
    if (err) lb_string_free(err);
    return result;
}

static void fit_estimate_matches_load() {
    init_runtime();
    const json cfg = {{"model_path", test_model()}, {"n_ctx", "auto"},
                      {"n_ctx_cap", 8192}, {"memory_budget_mib", 4300}};
    const json projected = estimate(cfg);
    LB_CHECK(projected.at("fits") == true);
    lb_model * model = nullptr;
    char * err = nullptr;
    const lb_status status = lb_model_load(cfg.dump().c_str(), nullptr, nullptr, &model, &err);
    if (status != LB_OK) std::fprintf(stderr, "lb_model_load: %s\n", err ? err : "unknown error");
    LB_CHECK(status == LB_OK);
    const json loaded = model_info(model);
    LB_CHECK(projected.at("n_ctx") == loaded.at("n_ctx"));
    LB_CHECK(projected.at("used_min_mib") == loaded.at("ctx_fit").at("used_min_mib"));
    lb_model_free(model);
    if (err) lb_string_free(err);
}

static void fit_estimate_on_stub_equals_real() {
    init_runtime();
    const char * home = std::getenv("HOME");
    const std::string downloads = std::string(home ? home : "") + "/Downloads/";
    const std::vector<std::string> files = {
        "Qwen3.5-4B-Q4_K_M.gguf",
        "gemma-3-4b-it-qat-UD-Q4_K_XL.gguf",
        "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
    };
    const auto dir = std::filesystem::temp_directory_path() /
        ("llamabro-fit-estimate-" + std::to_string(getpid()));
    std::filesystem::create_directories(dir);
    for (const auto & name : files) {
        const auto source = downloads + name;
        LB_CHECK(std::filesystem::is_regular_file(source));
        gguf_init_params options = {true, nullptr};
        gguf_context * header = gguf_init_from_file(source.c_str(), options);
        LB_CHECK(header != nullptr);
        const size_t offset = gguf_get_data_offset(header);
        gguf_free(header);
        const auto stub = dir / name;
        std::ifstream input(source, std::ios::binary);
        std::ofstream output(stub, std::ios::binary);
        LB_CHECK(input.good() && output.good());
        std::vector<char> bytes(offset);
        input.read(bytes.data(), (std::streamsize) bytes.size());
        LB_CHECK(input.gcount() == (std::streamsize) bytes.size());
        output.write(bytes.data(), (std::streamsize) bytes.size());
        output.close();
        std::filesystem::resize_file(stub, std::filesystem::file_size(source));
        const json base = {{"n_ctx", "auto"}, {"n_ctx_cap", 8192}, {"memory_budget_mib", 100000}};
        json real_cfg = base;
        real_cfg["model_path"] = source;
        json stub_cfg = base;
        stub_cfg["model_path"] = stub.string();
        const json real = estimate(real_cfg);
        const json sparse = estimate(stub_cfg);
        LB_CHECK(real.at("used_min_mib") == sparse.at("used_min_mib"));
        LB_CHECK(real.at("used_max_mib") == sparse.at("used_max_mib"));
    }
    std::filesystem::remove_all(dir);
}

// ---------------------------------------------------------------------------------------------
// Attachments (D84) and transcription (F55): upstream's own audio test model (tools/server/tests/utils.py
// ServerPreset.tinylfm3omni, fetch-test-models.sh) and a clip generated at check time (make-test-clip.sh)
// ---------------------------------------------------------------------------------------------

int lb_attachments_live();  // request.cpp, declared in model.h

static std::string test_clip() {
    const std::string path = env_or("LB_TEST_CLIP", "");
    if (path.empty() || !std::filesystem::exists(path)) {
        std::fprintf(stderr, "test clip not found at '%s': run sdk/scripts/make-test-clip.sh <out.wav> and set LB_TEST_CLIP\n",
                     path.c_str());
        std::exit(1);
    }
    return path;
}

static std::vector<uint8_t> file_bytes(const std::string & path) {
    std::ifstream in(path, std::ios::binary);
    LB_CHECK(in.good());
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}

static lb_model * load_omni() {
    return load_at(upstream_test_model("tinyLFM3-Omni-for-testing-Q2_K.gguf"),
                   {{"mmproj_path", upstream_test_model("mmproj-tinyLFM3-Omni-for-testing-Q2_K.gguf")}, {"n_ctx", 2048}});
}

static lb_attachment att_path(const char * name, const char * mime, const std::string & path) {
    return lb_attachment{ name, mime, path.c_str(), -1, nullptr, 0 };
}
static lb_attachment att_fd(const char * name, const char * mime, int fd) {
    return lb_attachment{ name, mime, nullptr, fd, nullptr, 0 };
}
static lb_attachment att_bytes(const char * name, const char * mime, const std::vector<uint8_t> & data) {
    return lb_attachment{ name, mime, nullptr, -1, data.data(), data.size() };
}

static lb_status start_with(lb_model * m, const char * endpoint, const json & body, const std::vector<lb_attachment> & atts,
                            collector & c, lb_request ** req) {
    const std::string b = body.dump();
    return lb_model_request_with(m, endpoint, nullptr, b.data(), b.size(), atts.data(), atts.size(), collector::cb, &c, req);
}

// one non-streaming request with attachments: the HTTP status and the parsed body
static std::pair<int, json> call_with(lb_model * m, const char * endpoint, const json & body,
                                      const std::vector<lb_attachment> & atts) {
    collector c;
    lb_request * req = nullptr;
    LB_CHECK(start_with(m, endpoint, body, atts, c, &req) == LB_OK);
    LB_CHECK(req != nullptr);
    c.wait_terminal();
    LB_CHECK(c.terminal == LB_EVENT_DONE);
    lb_request_free(req);
    std::string all;
    for (const auto & ch : c.chunks) all += ch;
    return { c.statuses.empty() ? 0 : c.statuses.front(), json::parse(all) };
}

// form fields as upstream's form-data parsing gives them: strings (server-http.cpp, convert_transcriptions_to_chatcmpl);
// reasoning left in the text, where the test model's output goes
static json transcription_form() {
    return json{{"temperature", "0"}, {"max_tokens", "16"}, {"reasoning_format", "none"}};
}

static json audio_chat(const std::string & data) {
    json parts = json::array();
    parts.push_back(json{{"type", "text"}, {"text", "What is said here?"}});
    parts.push_back(json{{"type", "input_audio"}, {"input_audio", json{{"data", data}, {"format", "wav"}}}});
    json body = chat_body("", 8, false);
    body["messages"][0]["content"] = parts;
    body["cache_prompt"] = false;
    return body;
}

static void check_400(const std::pair<int, json> & r, const std::string & needle) {
    if (r.first != 400 || r.second.dump().find(needle) == std::string::npos) {
        std::fprintf(stderr, "expected 400 with '%s', got %d %s\n", needle.c_str(), r.first, r.second.dump().c_str());
    }
    LB_CHECK(r.first == 400);
    LB_CHECK(r.second.at("error").at("type") == "invalid_request_error");
    LB_CHECK(r.second.at("error").at("message").get<std::string>().find(needle) != std::string::npos);
}

static std::string chat_text(const json & r) {
    return r.at("choices").at(0).at("message").value("content", "");
}

// the bridge's data URL is the one an app would have sent: same reply, same prompt size
static void attachment_placeholder_replaced() {
    lb_model * m = load_omni();
    const std::string clip = test_clip();
    const auto viaPlaceholder = call_with(m, "chat", audio_chat("lb-attachment:0"), {att_path("0", "audio/wav", clip)});
    const std::vector<uint8_t> bytes = file_bytes(clip);
    const std::string url = "data:audio/wav;base64," + base64::encode(reinterpret_cast<const char *>(bytes.data()), bytes.size());
    const auto direct = call(m, "chat", audio_chat(url));
    std::fprintf(stderr, "placeholder: %d %s\ndirect: %d %s\n", viaPlaceholder.first, viaPlaceholder.second.dump().c_str(),
                 direct.first, direct.second.dump().c_str());
    LB_CHECK(viaPlaceholder.first == 200);
    LB_CHECK(direct.first == 200);
    LB_CHECK(chat_text(viaPlaceholder.second) == chat_text(direct.second));
    LB_CHECK(viaPlaceholder.second.at("usage") == direct.second.at("usage"));
    LB_CHECK(viaPlaceholder.second.at("usage").at("prompt_tokens").get<int>() > 20);  // the audio's tokens are in
    lb_model_free(m);
}

// only a whole url/data value inside content is a placeholder; the same text in a message is the user's
static void attachment_user_text_untouched() {
    lb_model * m = load_omni();
    json body = audio_chat("lb-attachment:0");
    body["messages"][0]["content"][0]["text"] = "say lb-attachment:0 please";
    body["messages"][0]["content"].push_back(json{{"type", "text"}, {"text", "lb-attachment:0"}});
    json second = json::object();
    second["role"] = "assistant";
    second["content"] = "lb-attachment:0";
    body["messages"].push_back(second);
    json third = json::object();
    third["role"] = "user";
    third["content"] = "and lb-attachment:0 again";
    body["messages"].push_back(third);
    const auto r = call_with(m, "apply_template", body, {att_path("0", "audio/wav", test_clip())});
    std::fprintf(stderr, "%d %s\n", r.first, r.second.dump().c_str());
    LB_CHECK(r.first == 200);
    const std::string prompt = r.second.at("prompt").get<std::string>();
    size_t n = 0;
    for (size_t pos = 0; (pos = prompt.find("lb-attachment:0", pos)) != std::string::npos; pos++) n++;
    LB_CHECK(n == 4);
    LB_CHECK(prompt.find("say lb-attachment:0 please") != std::string::npos);
    lb_model_free(m);
}

static void attachment_placeholder_without_attachment_refused() {
    lb_model * m = load_omni();
    const std::string clip = test_clip();
    check_400(call_with(m, "chat", audio_chat("lb-attachment:1"), {att_path("0", "audio/wav", clip)}), "lb-attachment:1");
    check_400(call_with(m, "chat", audio_chat("lb-attachment:0"), {}), "lb-attachment:0");
    check_400(call(m, "chat", audio_chat("lb-attachment:0")), "lb-attachment:0");

    // the caller's mistakes are the call's: no name, two sources or none, a name used twice
    collector c;
    lb_request * req = nullptr;
    const json body = audio_chat("lb-attachment:0");
    lb_attachment two = att_path("0", "audio/wav", clip);
    two.fd = 0;
    lb_attachment none = att_path("0", "audio/wav", clip);
    none.path = nullptr;
    lb_attachment unnamed = att_path(nullptr, "audio/wav", clip);
    for (const auto & atts : std::vector<std::vector<lb_attachment>>{
             {two}, {none}, {unnamed}, {att_path("0", "audio/wav", clip), att_path("0", "audio/wav", clip)}}) {
        LB_CHECK(start_with(m, "chat", body, atts, c, &req) == LB_ERR_INVALID_ARG);
        LB_CHECK(req == nullptr);
    }
    LB_CHECK(c.chunks.empty() && c.n_terminal == 0);
    lb_model_free(m);
}

// An ADTS AAC header (MPEG-4, layer bits 00) has MP3's sync word: upstream's check passes it and its decoder then
// fails with a 500, so the bridge refuses it first, as a bad request.
static void attachment_adts_aac_refused() {
    lb_model * m = load_omni();
    const std::vector<uint8_t> adts = {0xFF, 0xF1, 0x50, 0x80, 0x02, 0x1F, 0xFC, 0x21, 0x00, 0x49, 0x90, 0x02, 0x19, 0x00};
    check_400(call_with(m, "chat", audio_chat("lb-attachment:0"), {att_bytes("0", "audio/aac", adts)}), "WAV, MP3 or FLAC");
    check_400(call_with(m, "transcriptions", transcription_form(), {att_bytes("file", "audio/wav", adts)}), "WAV, MP3 or FLAC");
    lb_model_free(m);
}

static void attachment_read_errors_are_400() {
    lb_model * m = load_omni();
    const auto dir = std::filesystem::temp_directory_path() / ("lb-att-" + std::to_string(getpid()));
    std::filesystem::create_directories(dir);
    const std::string text = (dir / "notes.wav").string();
    { std::ofstream(text) << "these are notes, not audio at all"; }

    check_400(call_with(m, "transcriptions", transcription_form(), {att_path("file", "audio/wav", (dir / "missing.wav").string())}),
              "missing.wav");
    check_400(call_with(m, "transcriptions", transcription_form(), {att_fd("file", "audio/wav", 4000)}), "descriptor 4000");
    check_400(call_with(m, "transcriptions", transcription_form(), {att_path("file", "audio/wav", text)}), "WAV, MP3 or FLAC");
    const std::vector<uint8_t> notes = file_bytes(text);
    check_400(call_with(m, "chat", audio_chat("lb-attachment:0"), {att_bytes("0", "audio/wav", notes)}), "WAV, MP3 or FLAC");
    // upstream's own refusal without the file (convert_transcriptions_to_chatcmpl)
    check_400(call(m, "transcriptions", transcription_form()), "No input file found");
    std::filesystem::remove_all(dir);
    lb_model_free(m);
}

// the whole response (text and token counts; it has no id or time), checked to have read the audio. The model is a
// random-weights test model (upstream's), so the text itself means nothing: equal answers are the check
static std::string transcribed(const std::pair<int, json> & r) {
    std::fprintf(stderr, "transcription: %d %s\n", r.first, r.second.dump().c_str());
    LB_CHECK(r.first == 200);
    LB_CHECK(!r.second.at("text").get<std::string>().empty());
    LB_CHECK(r.second.at("usage").at("input_tokens").get<int>() > 30);  // the prompt alone is far fewer
    // a repeat of the same request reuses the cache (F153), so the cached count is the one field that may differ
    json same = r.second;
    same["usage"].erase("input_tokens_details");
    return same.dump();
}

static void attachment_fd_read_and_left_open() {
    lb_model * m = load_omni();
    const std::string clip = test_clip();
    const std::string by_path = transcribed(call_with(m, "transcriptions", transcription_form(), {att_path("file", "audio/wav", clip)}));

    // a seekable file: read from offset 0 with pread; the caller's offset and descriptor stay as they were
    const int fd = open(clip.c_str(), O_RDONLY);
    LB_CHECK(fd >= 0);
    LB_CHECK(lseek(fd, 100, SEEK_SET) == 100);
    LB_CHECK(transcribed(call_with(m, "transcriptions", transcription_form(), {att_fd("file", "audio/wav", fd)})) == by_path);
    LB_CHECK(fcntl(fd, F_GETFD) != -1);
    LB_CHECK(lseek(fd, 0, SEEK_CUR) == 100);
    close(fd);

    // a pipe can't seek: read with read() until the writer closes it
    int p[2];
    LB_CHECK(pipe(p) == 0);
    const std::vector<uint8_t> bytes = file_bytes(clip);
    std::thread writer([&] {
        size_t off = 0;
        while (off < bytes.size()) {
            const ssize_t n = write(p[1], bytes.data() + off, bytes.size() - off);
            if (n <= 0) break;
            off += (size_t) n;
        }
        close(p[1]);
    });
    LB_CHECK(transcribed(call_with(m, "transcriptions", transcription_form(), {att_fd("file", "audio/wav", p[0])})) == by_path);
    writer.join();
    LB_CHECK(fcntl(p[0], F_GETFD) != -1);
    close(p[0]);
    lb_model_free(m);
}

static void transcription_path_fd_bytes_same_text() {
    lb_model * m = load_omni();
    const std::string clip = test_clip();
    const std::string by_path = transcribed(call_with(m, "transcriptions", transcription_form(), {att_path("file", "audio/wav", clip)}));
    const int fd = open(clip.c_str(), O_RDONLY);
    LB_CHECK(fd >= 0);
    const std::string by_fd = transcribed(call_with(m, "transcriptions", transcription_form(), {att_fd("file", "audio/wav", fd)}));
    close(fd);

    // the caller's bytes are copied before the call returns: scribbling over them right after changes nothing
    collector c;
    lb_request * req = nullptr;
    {
        std::vector<uint8_t> bytes = file_bytes(clip);
        LB_CHECK(start_with(m, "transcriptions", transcription_form(), {att_bytes("file", nullptr, bytes)}, c, &req) == LB_OK);
        std::fill(bytes.begin(), bytes.end(), 0);
    }
    c.wait_terminal();
    lb_request_free(req);
    std::string all;
    for (const auto & ch : c.chunks) all += ch;
    const std::string by_bytes = transcribed({ c.statuses.front(), json::parse(all) });

    std::fprintf(stderr, "path '%s', fd '%s', bytes '%s'\n", by_path.c_str(), by_fd.c_str(), by_bytes.c_str());
    LB_CHECK(by_path == by_fd);
    LB_CHECK(by_path == by_bytes);
    lb_model_free(m);
}

// a request's attachment buffers are freed by its end, whether it ran, was cancelled or its model was freed
static void attachment_buffers_freed_on_cancel() {
    lb_model * m = load_omni();
    const std::string clip = test_clip();
    const std::vector<uint8_t> bytes = file_bytes(clip);
    LB_CHECK(lb_attachments_live() == 0);
    for (const char * endpoint : {"transcriptions", "chat"}) {
        const bool chat = std::strcmp(endpoint, "chat") == 0;
        collector c;
        lb_request * req = nullptr;
        const json body = chat ? audio_chat("lb-attachment:0") : transcription_form();
        LB_CHECK(start_with(m, endpoint, body, {att_bytes(chat ? "0" : "file", "audio/wav", bytes)}, c, &req) == LB_OK);
        // read and held by the request; a chat's may already be in the body, freed
        if (chat) {
            LB_CHECK(lb_attachments_live() <= 1);
        } else {
            LB_CHECK(lb_attachments_live() == 1);
        }
        lb_request_cancel(req);
        c.wait_terminal();
        LB_CHECK(lb_attachments_live() == 0);
        lb_request_free(req);
    }
    // freeing the model cancels the request; it ends with its buffers freed
    collector c;
    lb_request * req = nullptr;
    LB_CHECK(start_with(m, "transcriptions", transcription_form(), {att_path("file", "audio/wav", clip)}, c, &req) == LB_OK);
    lb_model_free(m);
    LB_CHECK(c.n_terminal == 1);
    LB_CHECK(lb_attachments_live() == 0);
    lb_request_free(req);
}

static void read_fd_reads_whole_file_and_leaves_it_open() {
    init_runtime();
    const std::string clip = test_clip();
    const std::vector<uint8_t> want = file_bytes(clip);
    const int fd = open(clip.c_str(), O_RDONLY);
    LB_CHECK(fd >= 0);
    LB_CHECK(lseek(fd, 50, SEEK_SET) == 50);
    uint8_t * buf = nullptr;
    size_t len = 0;
    char * err = nullptr;
    LB_CHECK(lb_read_fd(fd, &buf, &len, &err) == LB_OK);
    LB_CHECK(err == nullptr);
    LB_CHECK(len == want.size() && std::memcmp(buf, want.data(), len) == 0);
    lb_buffer_free(buf);
    LB_CHECK(fcntl(fd, F_GETFD) != -1);
    LB_CHECK(lseek(fd, 0, SEEK_CUR) == 50);
    close(fd);

    LB_CHECK(lb_read_fd(4000, &buf, &len, &err) == LB_ERR_INVALID_ARG);
    LB_CHECK(buf == nullptr && err != nullptr);
    LB_CHECK(json::parse(err).at("error").get<std::string>().find("descriptor 4000") != std::string::npos);
    lb_string_free(err);
}

// only chat-completions routes get placeholders filled: Anthropic's source.data on /v1/messages is the caller's own,
// so the request answers exactly as without the attachment (no double data URL)
static void placeholder_untouched_on_other_routes() {
    lb_model * m = load_omni();
    const std::vector<uint8_t> bytes = file_bytes(test_clip());
    json block = json::object();
    block["type"] = "image";
    block["source"] = json{{"type", "base64"}, {"media_type", "image/png"}, {"data", "lb-attachment:0"}};
    json msg = json::object();
    msg["role"] = "user";
    msg["content"] = json::array();
    msg["content"].push_back(block);
    msg["content"].push_back(json{{"type", "text"}, {"text", "What is this?"}});
    json body = json::object();
    body["model"] = "x";
    body["max_tokens"] = 4;
    body["messages"] = json::array();
    body["messages"].push_back(msg);
    for (const char * endpoint : {"messages", "messages_count_tokens"}) {
        const auto with = call_with(m, endpoint, body, {att_bytes("0", "image/png", bytes)});
        const auto without = call(m, endpoint, body);
        std::fprintf(stderr, "%s with: %d %s\nwithout: %d %s\n", endpoint, with.first, with.second.dump().c_str(),
                     without.first, without.second.dump().c_str());
        LB_CHECK(with.first == without.first);
        if (with.first != 200) LB_CHECK(with.second == without.second);
        LB_CHECK(with.second.dump().find("Invalid uri") == std::string::npos);
    }
    lb_model_free(m);
}

// a placeholder-looking string outside messages[].content[] media fields (here a response_format schema's const, under
// a property named "content") is the caller's: the grammar makes the model write it back verbatim
static void placeholder_untouched_in_response_format() {
    lb_model * m = load_omni();
    const std::string clip = test_clip();
    json schema = json::object();
    schema["type"] = "object";
    schema["properties"] = json::object();
    schema["properties"]["content"] = json{{"const", json{{"url", "lb-attachment:0"}, {"data", "lb-attachment:0"}}}};
    schema["required"] = json::array({"content"});
    auto with_format = [&](json body) {
        json js = json::object();
        js["name"] = "r";
        js["schema"] = schema;
        body["response_format"] = json{{"type", "json_schema"}, {"json_schema", js}};
        body["max_tokens"] = 96;
        return body;
    };
    const auto via = call_with(m, "chat", with_format(audio_chat("lb-attachment:0")), {att_path("0", "audio/wav", clip)});
    const std::vector<uint8_t> bytes = file_bytes(clip);
    const std::string url = "data:audio/wav;base64," + base64::encode(reinterpret_cast<const char *>(bytes.data()), bytes.size());
    const auto direct = call(m, "chat", with_format(audio_chat(url)));
    std::fprintf(stderr, "via: %d %s\ndirect: %d %s\n", via.first, via.second.dump().c_str(), direct.first,
                 direct.second.dump().c_str());
    LB_CHECK(via.first == 200 && direct.first == 200);
    LB_CHECK(chat_text(via.second) == chat_text(direct.second));
    LB_CHECK(via.second.at("usage") == direct.second.at("usage"));
    const json reply = json::parse(chat_text(via.second));
    LB_CHECK(reply.at("content").at("url") == "lb-attachment:0");
    LB_CHECK(reply.at("content").at("data") == "lb-attachment:0");
    lb_model_free(m);
}

// embeddings read OAI content parts too (handle_embeddings_impl, tokenize_oai_content_array): a placeholder in a part's
// input_audio.data becomes the file's data URL, on both routes and in an array of entries; a plain text input that
// reads like a placeholder is the caller's
static void embeddings_placeholder_replaced() {
    lb_model * m = load_at(upstream_test_model("tinyLFM3-Omni-for-testing-Q2_K.gguf"),
                           {{"mmproj_path", upstream_test_model("mmproj-tinyLFM3-Omni-for-testing-Q2_K.gguf")},
                            {"n_ctx", 2048}, {"embeddings", true}, {"pooling", "mean"}});
    const std::string clip = test_clip();
    const std::vector<uint8_t> bytes = file_bytes(clip);
    const std::string url = "data:audio/wav;base64," + base64::encode(reinterpret_cast<const char *>(bytes.data()), bytes.size());
    auto entry = [](const std::string & data) {
        json parts = json::array();
        parts.push_back(json{{"type", "text"}, {"text", "a clip"}});
        parts.push_back(json{{"type", "input_audio"}, {"input_audio", json{{"data", data}, {"format", "wav"}}}});
        return json{{"content", parts}};
    };
    const auto via = call_with(m, "embeddings", json{{"input", entry("lb-attachment:0")}}, {att_path("0", "audio/wav", clip)});
    const auto direct = call(m, "embeddings", json{{"input", entry(url)}});
    std::fprintf(stderr, "via: %d %.200s\ndirect: %d %.200s\n", via.first, via.second.dump().c_str(), direct.first,
                 direct.second.dump().c_str());
    LB_CHECK(via.first == 200 && direct.first == 200);
    LB_CHECK(via.second.at("data") == direct.second.at("data"));
    LB_CHECK(via.second.at("usage") == direct.second.at("usage"));
    LB_CHECK(via.second.at("usage").at("prompt_tokens").get<int>() > 20);  // the audio's tokens are in

    // the native route, "content" holding an array of entries, the second one with the placeholder
    const json texts = json{{"content", json::array({json{{"content", json::array({json{{"type", "text"}, {"text", "x"}}})}},
                                                       entry("lb-attachment:0")})}};
    const auto native = call_with(m, "embeddings_native", texts, {att_bytes("0", "audio/wav", bytes)});
    json direct_texts = texts;
    direct_texts["content"][1] = entry(url);
    const auto native_direct = call(m, "embeddings_native", direct_texts);
    LB_CHECK(native.first == 200 && native_direct.first == 200);
    LB_CHECK(native.second == native_direct.second);

    // a text input is not a media field: answered as without the attachment
    const auto text_via = call_with(m, "embeddings", json{{"input", "lb-attachment:0"}}, {att_path("0", "audio/wav", clip)});
    const auto text_direct = call(m, "embeddings", json{{"input", "lb-attachment:0"}});
    LB_CHECK(text_via.first == 200);
    LB_CHECK(text_via.second.at("data") == text_direct.second.at("data"));
    lb_model_free(m);
}

// ---------------------------------------------------------------------------------------------
// Prompt cache on hybrid memory with SWA layers (F153, patch 0004). tinyLFM3 mixes full-attention, sliding-window and
// recurrent layers, as LFM3 does. Without the patch, llama-server restores only the checkpoint before the last user
// message, so each turn processes that whole message again (its audio too)
// ---------------------------------------------------------------------------------------------

// a user message of some 150 tokens, different every turn
static std::string user_note(int turn) {
    std::string s = "Note " + std::to_string(turn) + ", please keep it:";
    for (int i = 0; i < 40; i++) s += " item " + std::to_string(turn * 100 + i) + ",";
    return s;
}

static json conversation_body(const json & messages, int max_tokens, bool cache_prompt) {
    json body = chat_body("", max_tokens, false);
    body["messages"] = messages;
    body["cache_prompt"] = cache_prompt;
    return body;
}

// sends turns 1..n, each with a fixed reply in the history, and checks that every turn after the first reuses the
// previous prompt up to the checkpoint llama-server makes 4 tokens before its end, not just up to its last user message
static void check_cache_covers_previous_prompt(lb_model * m, const std::function<json(int)> & user_content, int n) {
    json messages = json::array();
    int prev_prompt = 0;
    for (int turn = 1; turn <= n; turn++) {
        messages.push_back(json{{"role", "user"}, {"content", user_content(turn)}});
        const auto r = call(m, "chat", conversation_body(messages, 4, true));
        LB_CHECK(r.first == 200);
        const int prompt = r.second.at("usage").at("prompt_tokens").get<int>();
        const int cached = r.second.at("timings").at("cache_n").get<int>();
        std::fprintf(stderr, "turn %d: prompt %d, cached %d (previous prompt %d)\n", turn, prompt, cached, prev_prompt);
        if (turn > 1) {
            LB_CHECK(cached >= prev_prompt - 8);
            LB_CHECK(cached < prompt);
        }
        prev_prompt = prompt;
        messages.push_back(json{{"role", "assistant"}, {"content", "Noted."}});
    }
}

// LB_TEST_MODEL (a real LFM3 GGUF, with LB_TEST_MMPROJ if it has an encoder) replaces the tiny model, for attended runs
static lb_model * load_omni_long() {
    const std::string own = env_or("LB_TEST_MODEL", "");
    if (!own.empty()) {
        json cfg = {{"n_ctx", 8192}};
        const std::string proj = env_or("LB_TEST_MMPROJ", "");
        if (!proj.empty()) {
            cfg["mmproj_path"] = proj;
        }
        return load_at(own, cfg);
    }
    return load_at(upstream_test_model("tinyLFM3-Omni-for-testing-Q2_K.gguf"),
                   {{"mmproj_path", upstream_test_model("mmproj-tinyLFM3-Omni-for-testing-Q2_K.gguf")}, {"n_ctx", 8192}});
}

static void hybrid_swa_cache_covers_last_message() {
    lb_model * m = load_omni_long();
    check_cache_covers_previous_prompt(m, [](int turn) { return json(user_note(turn)); }, 5);
    lb_model_free(m);
}

// a chat of short turns, under 500 tokens in all: without the patch nothing but the first token is reused, since every
// checkpoint and the live state lie within n_swa of the prompt's end, so each turn processes the whole chat again
static void hybrid_swa_cache_covers_short_chat() {
    lb_model * m = load_omni_long();
    check_cache_covers_previous_prompt(m, [](int turn) { return json("Short note " + std::to_string(turn) + "."); }, 12);
    lb_model_free(m);
}

// the live state: when the previous prompt and its reply are both a prefix of the new prompt, llama-server goes on from
// the slot's own memory, with no checkpoint. Without the patch that is refused by the same SWA threshold. Raw completions
// of token arrays make the reply part of the prefix exactly
static void hybrid_swa_cache_reuses_live_state() {
    lb_model * m = load_omni_long();
    auto tokenize = [&](const std::string & text) {
        const auto r = call(m, "tokenize", json{{"content", text}});
        LB_CHECK(r.first == 200);
        return r.second.at("tokens");
    };
    json prompt = tokenize("Short note 1.");
    size_t prev_reused = 0;  // the previous prompt and its reply but the last token, which was sampled, never decoded
    for (int turn = 1; turn <= 8; turn++) {
        const auto r = call(m, "completion", json{{"prompt", prompt}, {"n_predict", 8}, {"temperature", 0}, {"seed", 1},
                                                  {"return_tokens", true}, {"cache_prompt", true}});
        LB_CHECK(r.first == 200);
        const int cached = r.second.at("timings").at("cache_n").get<int>();
        const json gen = r.second.at("tokens");
        std::fprintf(stderr, "turn %d: prompt %zu, cached %d, generated %zu\n", turn, prompt.size(), cached, gen.size());
        // beyond every checkpoint, which lie 4 or more tokens before the previous prompt's end
        if (turn > 1) LB_CHECK(cached == (int) prev_reused);
        LB_CHECK(!gen.empty());
        prev_reused = prompt.size() + gen.size() - 1;
        for (const auto & t : gen) prompt.push_back(t);
        for (const auto & t : tokenize(" Short note " + std::to_string(turn + 1) + ".")) prompt.push_back(t);
    }
    lb_model_free(m);
}

// voice notes: without the patch each turn encodes the previous turn's clip again
static void hybrid_swa_cache_covers_last_voice_note() {
    lb_model * m = load_omni_long();
    const std::vector<uint8_t> bytes = file_bytes(test_clip());
    const std::string url = "data:audio/wav;base64," + base64::encode(reinterpret_cast<const char *>(bytes.data()), bytes.size());
    check_cache_covers_previous_prompt(m, [&](int turn) {
        json parts = json::array();
        parts.push_back(json{{"type", "text"}, {"text", "Here is voice note " + std::to_string(turn) + "."}});
        if (turn < 4) {  // the last turn is text only, as a reply after voice notes
            parts.push_back(json{{"type", "input_audio"}, {"input_audio", json{{"data", url}, {"format", "wav"}}}});
        }
        return parts;
    }, 4);
    lb_model_free(m);
}

// the restored checkpoints change nothing in the output: over 8 greedy turns with the model's own replies, every reply
// equals the reply of the same conversation without the prompt cache, and so do the top-5 logprobs of each token, up to
// the noise of different batch splits (0.024 nat at most when measured, F153). The tiny model's output barely depends on
// the history (another first message moves them by 0.034), so this catches a broken state, not a subtle one
static void hybrid_swa_cache_replies_equal_uncached() {
    lb_model * m = load_omni_long();
    auto run = [&](bool cache_prompt) {
        std::vector<json> replies;
        json messages = json::array();
        int prev_prompt = 0;
        for (int turn = 1; turn <= 8; turn++) {
            messages.push_back(json{{"role", "user"}, {"content", user_note(turn)}});
            json body = conversation_body(messages, 16, cache_prompt);
            body["logprobs"] = true;
            body["top_logprobs"] = 5;
            const auto r = call(m, "chat", body);
            LB_CHECK(r.first == 200);
            const int cached = r.second.at("timings").at("cache_n").get<int>();
            const int prompt = r.second.at("usage").at("prompt_tokens").get<int>();
            // the previous prompt but a few tokens: the new resume points are used, not a fallback to an old checkpoint
            LB_CHECK(cache_prompt ? (turn == 1 || cached >= prev_prompt - 8) : cached == 0);
            prev_prompt = prompt;
            const json & choice = r.second.at("choices").at(0);
            std::fprintf(stderr, "cache_prompt %d, turn %d: cached %d of %d\n", cache_prompt, turn, cached,
                         r.second.at("usage").at("prompt_tokens").get<int>());
            replies.push_back(json{{"message", choice.at("message")}, {"logprobs", choice.at("logprobs").at("content")}});
            messages.push_back(json{{"role", "assistant"}, {"content", choice.at("message").value("content", "")}});
        }
        return replies;
    };
    const auto cached = run(true);
    const auto uncached = run(false);
    double max_diff = 0;
    for (size_t i = 0; i < cached.size(); i++) {
        if (cached[i].at("message") != uncached[i].at("message")) {
            std::fprintf(stderr, "turn %zu differs:\n  cached:   %s\n  uncached: %s\n", i + 1,
                         cached[i].at("message").dump().c_str(), uncached[i].at("message").dump().c_str());
        }
        LB_CHECK(cached[i].at("message") == uncached[i].at("message"));
        const json & a = cached[i].at("logprobs");
        const json & b = uncached[i].at("logprobs");
        LB_CHECK(a.size() == b.size() && !a.empty());
        for (size_t k = 0; k < a.size(); k++) {
            LB_CHECK(a[k].at("id") == b[k].at("id"));
            for (const auto & x : a[k].at("top_logprobs")) {
                for (const auto & y : b[k].at("top_logprobs")) {
                    if (x.at("id") == y.at("id")) {
                        max_diff = std::max(max_diff, std::abs(x.at("logprob").get<double>() - y.at("logprob").get<double>()));
                    }
                }
            }
        }
    }
    std::fprintf(stderr, "largest top-5 logprob difference: %.4f\n", max_diff);
    LB_CHECK(max_diff < 0.1);
    lb_model_free(m);
}

int main(int argc, char ** argv) {
    const std::map<std::string, std::function<void()>> tests = {
        {"runtime_init_twice_same_config_ok", runtime_init_twice_same_config_ok},
        {"backends_empty_before_init", backends_empty_before_init},
        {"optional_backend_failure_is_reported", optional_backend_failure_is_reported},
        {"backend_init_failure_is_reported", backend_init_failure_is_reported},
        {"backend_env_set_before_load", backend_env_set_before_load},
        {"runtime_init_twice_same_config_with_backends_ok", runtime_init_twice_same_config_with_backends_ok},
        {"backends_config_invalid", backends_config_invalid},
        {"model_devices_default_is_cpu", model_devices_default_is_cpu},
        {"model_devices_empty_is_cpu_and_no_scan", model_devices_empty_is_cpu_and_no_scan},
        {"model_devices_invalid_names_fail", model_devices_invalid_names_fail},
        {"auto_ctx_fits_budget", auto_ctx_fits_budget},
        {"auto_ctx_budget_load_and_insufficient_error", auto_ctx_budget_load_and_insufficient_error},
        {"ngram_config_maps_to_ngram_mod", ngram_config_maps_to_ngram_mod},
        {"ngram_drafts_and_keeps_the_reply", ngram_drafts_and_keeps_the_reply},
        {"fit_estimate_matches_load", fit_estimate_matches_load},
        {"fit_estimate_on_stub_equals_real", fit_estimate_on_stub_equals_real},
        {"dflash_config_maps_to_draft_dflash", dflash_config_maps_to_draft_dflash},
        {"dflash_without_draft_fails", dflash_without_draft_fails},
        {"draft_path_without_dflash_fails", draft_path_without_dflash_fails},
        {"dflash_missing_file_is_file_not_found", dflash_missing_file_is_file_not_found},
        {"dflash_loads_and_drafts", dflash_loads_and_drafts},
        {"fit_counts_the_draft", fit_counts_the_draft},
        {"dflash_fit_matches_load_rss", dflash_fit_matches_load_rss},
        {"runtime_init_different_config_fails", runtime_init_different_config_fails},
        {"devices_lists_cpu", devices_lists_cpu},
        {"runtime_init_retry_after_failure", runtime_init_retry_after_failure},
        {"backend_path_env_ignored", backend_path_env_ignored},
        {"version_names_build", version_names_build},
        {"log_callback_can_reenter", log_callback_can_reenter},
        {"model_load_auto_ctx", model_load_auto_ctx},
        {"auto_ctx_cap_is_per_slot", auto_ctx_cap_is_per_slot},
        {"props_build_info_names_llama_build", props_build_info_names_llama_build},
        {"config_defaults_applied", config_defaults_applied},
        {"mmproj_device_overrides_the_model_device", mmproj_device_overrides_the_model_device},
        {"model_load_missing_file_fails", model_load_missing_file_fails},
        {"model_load_bad_file_has_reason", model_load_bad_file_has_reason},
        {"model_load_unknown_key_fails", model_load_unknown_key_fails},
        {"model_free_right_after_load", model_free_right_after_load},
        {"load_stops_when_progress_says_false", load_stops_when_progress_says_false},
        {"load_stops_midway", load_stops_midway},
        {"load_stops_after_the_weights", load_stops_after_the_weights},
        {"load_runs_when_progress_says_true", load_runs_when_progress_says_true},
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
        {"load_features_parsed", load_features_parsed},
        {"embeddings_mean_pooling", embeddings_mean_pooling},
        {"embeddings_off_by_default", embeddings_off_by_default},
        {"rerank_orders_documents", rerank_orders_documents},
        {"lora_scale_changes_output", lora_scale_changes_output},
        {"kv_cache_q8_0_loads_and_generates", kv_cache_q8_0_loads_and_generates},
        {"attachment_placeholder_replaced", attachment_placeholder_replaced},
        {"attachment_user_text_untouched", attachment_user_text_untouched},
        {"attachment_placeholder_without_attachment_refused", attachment_placeholder_without_attachment_refused},
        {"attachment_read_errors_are_400", attachment_read_errors_are_400},
        {"attachment_adts_aac_refused", attachment_adts_aac_refused},
        {"attachment_fd_read_and_left_open", attachment_fd_read_and_left_open},
        {"transcription_path_fd_bytes_same_text", transcription_path_fd_bytes_same_text},
        {"attachment_buffers_freed_on_cancel", attachment_buffers_freed_on_cancel},
        {"read_fd_reads_whole_file_and_leaves_it_open", read_fd_reads_whole_file_and_leaves_it_open},
        {"placeholder_untouched_on_other_routes", placeholder_untouched_on_other_routes},
        {"placeholder_untouched_in_response_format", placeholder_untouched_in_response_format},
        {"embeddings_placeholder_replaced", embeddings_placeholder_replaced},
        {"hybrid_swa_cache_covers_last_message", hybrid_swa_cache_covers_last_message},
        {"hybrid_swa_cache_covers_last_voice_note", hybrid_swa_cache_covers_last_voice_note},
        {"hybrid_swa_cache_covers_short_chat", hybrid_swa_cache_covers_short_chat},
        {"hybrid_swa_cache_reuses_live_state", hybrid_swa_cache_reuses_live_state},
        {"hybrid_swa_cache_replies_equal_uncached", hybrid_swa_cache_replies_equal_uncached},
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
