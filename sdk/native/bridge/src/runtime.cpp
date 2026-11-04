// lb_runtime_*: process-wide state (P1 T2, design §5). One runtime per process: the first lb_runtime_init
// wins; the same config again is a no-op, a different one is LB_ERR_ALREADY_INITIALIZED.
#include "llamabro.h"
#include "lb_common.h"

#include "common.h"
#include "ggml-backend.h"
#include "llama.h"
#include "log.h"
#include "server-stream.h"

#include <atomic>
#include <dlfcn.h>
#include <chrono>
#include <memory>
#include <thread>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

namespace {

struct log_entry {
    lb_log_cb cb;
    void * user;
};

struct runtime_state {
    std::mutex mtx;
    bool initialized = false;
    std::string config;  // canonical dump of the first config

    std::mutex log_mtx;
    std::shared_ptr<log_entry> log;                   // guarded by log_mtx; calls hold their own copy
    ggml_log_level last_level = GGML_LOG_LEVEL_INFO;  // for GGML_LOG_LEVEL_CONT lines
};

thread_local int t_log_depth = 0;  // > 0 while this thread is inside the app's log callback

runtime_state & state() {
    static runtime_state s;  // function-local static: initialised once, thread-safely
    return s;
}

bool parse_log_level(const std::string & s, int & verbosity) {
    // common/log.h verbosity thresholds
    if (s == "debug") { verbosity = LOG_LEVEL_DEBUG; return true; }
    if (s == "info")  { verbosity = LOG_LEVEL_INFO;  return true; }
    if (s == "warn")  { verbosity = LOG_LEVEL_WARN;  return true; }
    if (s == "error") { verbosity = LOG_LEVEL_ERROR; return true; }
    return false;
}

lb_log_level to_lb_level(ggml_log_level level) {
    switch (level) {
        case GGML_LOG_LEVEL_DEBUG: return LB_LOG_DEBUG;
        case GGML_LOG_LEVEL_WARN:  return LB_LOG_WARN;
        case GGML_LOG_LEVEL_ERROR: return LB_LOG_ERROR;
        default:                   return LB_LOG_INFO;
    }
}

// Same filter as common_log_default_callback (common/log.cpp), then the app's callback.
// Same filter as common_log_default_callback (common/log.cpp), then the app's callback, called outside the lock:
// llama's threads don't queue behind a slow logger, and the callback may log or swap the logger itself.
void log_trampoline(ggml_log_level level, const char * text, void * /*user*/) {
    auto & s = state();
    std::shared_ptr<log_entry> entry;
    {
        std::lock_guard<std::mutex> lk(s.log_mtx);
        if (level == GGML_LOG_LEVEL_CONT) {
            level = s.last_level;
        } else {
            s.last_level = level;
        }
        if (common_log_get_verbosity(level) > common_log_get_verbosity_thold()) return;
        entry = s.log;
    }
    if (!entry || !entry->cb) return;
    t_log_depth++;
    entry->cb(entry->user, to_lb_level(level), text);
    t_log_depth--;
}

const char * device_type_name(enum ggml_backend_dev_type t) {
    switch (t) {
        case GGML_BACKEND_DEVICE_TYPE_CPU:   return "cpu";
        case GGML_BACKEND_DEVICE_TYPE_GPU:   return "gpu";
        case GGML_BACKEND_DEVICE_TYPE_IGPU:  return "igpu";
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: return "accel";
        case GGML_BACKEND_DEVICE_TYPE_META:  return "meta";
    }
    return "unknown";
}

// Port of ggml_backend_load_best (ggml/src/ggml-backend-reg.cpp, b12052) that opens the CPU variants by file name
// instead of listing a directory: Android can dlopen a library by name straight from an uncompressed APK, so apps
// don't need extracted native libraries (findings F25). Each variant reports how well it fits this CPU through
// ggml_backend_score; the best one is loaded.
bool load_cpu_backend(const std::string & backend_dir, std::string & err) {
    std::string best_path;
    int best_score = 0;
    std::string files = LB_CPU_BACKEND_FILES;
    size_t start = 0;
    while (start <= files.size()) {
        const size_t comma = files.find(',', start);
        const std::string name = files.substr(start, comma == std::string::npos ? std::string::npos : comma - start);
        start = comma == std::string::npos ? files.size() + 1 : comma + 1;
        if (name.empty()) continue;
        const std::string path = backend_dir.empty() ? name : backend_dir + "/" + name;
        void * handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!handle) continue;
        using score_fn_t = int (*)(void);
        auto score_fn = reinterpret_cast<score_fn_t>(dlsym(handle, "ggml_backend_score"));
        const int score = score_fn ? score_fn() : 0;
        dlclose(handle);
        if (score > best_score) {
            best_score = score;
            best_path = path;
        }
    }
    if (best_path.empty()) {
        err = "no CPU backend could be loaded (" + std::string(LB_CPU_BACKEND_FILES) + ")" +
              (backend_dir.empty() ? std::string() : " from " + backend_dir);
        return false;
    }
    if (!ggml_backend_load(best_path.c_str())) {
        err = "failed to load CPU backend " + best_path;
        return false;
    }
    return true;
}

}  // namespace

char * lb_strdup(const std::string & s) {
    char * out = static_cast<char *>(std::malloc(s.size() + 1));
    std::memcpy(out, s.c_str(), s.size() + 1);
    return out;
}

void lb_set_error(char ** error_json, const std::string & message) {
    if (error_json) *error_json = lb_strdup(json{{"error", message}}.dump());
}

bool lb_runtime_is_initialized() {
    auto & s = state();
    std::lock_guard<std::mutex> lk(s.mtx);
    return s.initialized;
}

extern "C" {

const char * lb_version(void) {
    return "llamabro " LB_VERSION " llama.cpp " LB_LLAMA_BUILD;
}

void lb_string_free(char * s) { std::free(s); }

lb_status lb_runtime_init(const char * config_json, char ** error_json) {
    if (error_json) *error_json = nullptr;

    json cfg;
    try {
        cfg = json::parse(config_json ? config_json : "{}");
    } catch (const std::exception & e) {
        lb_set_error(error_json, std::string("invalid config JSON: ") + e.what());
        return LB_ERR_INVALID_ARG;
    }
    if (!cfg.is_object()) {
        lb_set_error(error_json, "config must be a JSON object");
        return LB_ERR_INVALID_ARG;
    }

    std::string backend_dir;
    int verbosity = LOG_LEVEL_WARN;
    for (const auto & item : cfg.items()) {
        const std::string & key = item.key();
        const json & value = item.value();
        if (key == "backend_dir" && value.is_string()) {
            backend_dir = value.get<std::string>();
        } else if (key == "log_level" && value.is_string()) {
            if (!parse_log_level(value.get<std::string>(), verbosity)) {
                lb_set_error(error_json, "log_level must be debug, info, warn or error");
                return LB_ERR_INVALID_ARG;
            }
        } else {
            lb_set_error(error_json, "unknown or mistyped config key: " + key);
            return LB_ERR_INVALID_ARG;
        }
    }

    auto & s = state();
    std::lock_guard<std::mutex> lk(s.mtx);
    const std::string canonical = cfg.dump();
    if (s.initialized) {
        if (canonical == s.config) return LB_OK;
        lb_set_error(error_json, "runtime already initialized with a different config: " + s.config);
        return LB_ERR_ALREADY_INITIALIZED;
    }

    // same order as llama_server() in tools/server/server.cpp: common_init, then the backend. Each step is safe
    // to repeat if an earlier init failed (start_gc returns when already running).
    common_init();
    common_log_set_verbosity_thold(verbosity);
    server_stream_session_manager_start();  // once per process, as llama_server() does
    // Our backend goes in before llama_backend_init: with an empty registry it calls ggml_backend_load_all, which
    // scans the executable's and the current directory and loads GGML_BACKEND_PATH. Backends come only from
    // our list (findings F35). llama-server too loads backends before llama_backend_init when given --device.
    std::string load_error;
    if (!load_cpu_backend(backend_dir, load_error)) {
        lb_set_error(error_json, load_error);
        return LB_ERR_INTERNAL;
    }
    llama_backend_init();

    s.config = canonical;
    s.initialized = true;
    return LB_OK;
}

void lb_runtime_set_log(lb_log_cb cb, void * user) {
    auto & s = state();
    std::shared_ptr<log_entry> old;
    {
        std::lock_guard<std::mutex> lk(s.log_mtx);
        old = std::move(s.log);
        if (cb) s.log = std::make_shared<log_entry>(log_entry{cb, user});
    }
    // Wait for calls of the old callback on other threads, so the caller may release the old `user` on return.
    // A call on this thread (set_log from inside the callback) holds one more copy and finishes after we return.
    if (old) {
        const long own = t_log_depth > 0 ? 1 : 0;
        while (old.use_count() > 1 + own) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    if (cb) {
        llama_log_set(log_trampoline, nullptr);
    } else {
        llama_log_set(common_log_default_callback, nullptr);  // what common_init() installs
    }
}

char * lb_runtime_devices(void) {
    json out = json::array();
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        size_t free = 0, total = 0;
        ggml_backend_dev_memory(dev, &free, &total);
        out.push_back({
            {"name", ggml_backend_dev_name(dev)},
            {"description", ggml_backend_dev_description(dev)},
            {"type", device_type_name(ggml_backend_dev_type(dev))},
            {"memory_free", free},
            {"memory_total", total},
        });
    }
    return lb_strdup(out.dump());
}

}  // extern "C"
