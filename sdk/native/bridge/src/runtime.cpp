// lb_runtime_*: process-wide state. One runtime per process: the first lb_runtime_init
// wins; the same config again is a no-op, a different one is LB_ERR_ALREADY_INITIALIZED.
#include "llamabro.h"
#include "lb_common.h"
#include "config.h"

#include "common.h"
#include "ggml-backend.h"
#include "llama.h"
#include "log.h"
#include "server-stream.h"

#include <atomic>
#include <dlfcn.h>
#include <condition_variable>
#include <memory>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace {

struct log_entry {
    lb_log_cb cb;
    void * user;
    int calls = 0;  // threads inside cb now; guarded by runtime_state::log_mtx
};

struct runtime_state {
    std::mutex mtx;
    bool initialized = false;
    std::string config;  // canonical dump of the first config
    json backends = json::array();  // lb_runtime_backends: what loaded, from the first successful init

    std::mutex log_mtx;
    std::shared_ptr<log_entry> log;                   // guarded by log_mtx; calls hold their own copy
    std::condition_variable log_idle;                 // notified when a call of any entry returns
    ggml_log_level last_level = GGML_LOG_LEVEL_INFO;  // for GGML_LOG_LEVEL_CONT lines
};

thread_local const log_entry * t_log_entry = nullptr;  // the entry whose callback this thread is inside, if any
thread_local std::string * t_capture = nullptr;  // lb_error_capture on this thread, if any

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

// Installed from lb_runtime_init on. Error lines go to this thread's lb_error_capture, if any (F51). Then, without
// an app callback, llama.cpp's default; with one, the same filter as common_log_default_callback (common/log.cpp)
// and the app's callback, called outside the lock: llama's threads don't queue behind a slow logger, and the
// callback may log or swap the logger itself.
void log_trampoline(ggml_log_level level, const char * text, void * /*user*/) {
    auto & s = state();
    const ggml_log_level raw = level;
    std::shared_ptr<log_entry> entry;
    bool call = false;
    {
        std::lock_guard<std::mutex> lk(s.log_mtx);
        if (level == GGML_LOG_LEVEL_CONT) {
            level = s.last_level;
        } else {
            s.last_level = level;
        }
        entry = s.log;
        call = entry && common_log_get_verbosity(level) <= common_log_get_verbosity_thold();
        if (call) entry->calls++;  // counted under the lock, so lb_runtime_set_log sees it before it swaps
    }
    if (t_capture && level == GGML_LOG_LEVEL_ERROR && text) t_capture->append(text);
    if (!entry) {
        common_log_default_callback(raw, text, nullptr);
        return;
    }
    if (!call) return;
    const log_entry * outer = t_log_entry;
    t_log_entry = entry.get();
    entry->cb(entry->user, to_lb_level(level), text);
    t_log_entry = outer;
    {
        std::lock_guard<std::mutex> lk(s.log_mtx);
        entry->calls--;
    }
    s.log_idle.notify_all();
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

// Port of ggml_backend_load_best (ggml/src/ggml-backend-reg.cpp, b12653; unchanged since b12052) that opens the CPU variants by file name
// instead of listing a directory: Android can dlopen a library by name straight from an uncompressed APK, so apps
// don't need extracted native libraries (findings F25). Each variant reports how well it fits this CPU through
// ggml_backend_score; the best one is loaded.
bool load_cpu_backend(const std::string & backend_dir, ggml_backend_reg_t & reg, std::string & err) {
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
    reg = ggml_backend_load(best_path.c_str());
    if (!reg) {
        err = "failed to load CPU backend " + best_path;
        return false;
    }
    return true;
}

json reg_device_names(ggml_backend_reg_t reg) {
    json names = json::array();
    for (size_t i = 0; i < ggml_backend_reg_dev_count(reg); i++) {
        names.push_back(ggml_backend_dev_name(ggml_backend_reg_dev_get(reg, i)));
    }
    return names;
}

// One optional backend from the config's "backends" (D24).
struct backend_request {
    std::string name;
    std::vector<std::string> files;
    std::vector<std::pair<std::string, std::string>> env;
};

bool parse_backends(const json & value, std::vector<backend_request> & out, std::string & err) {
    if (!value.is_array()) {
        err = "backends must be an array";
        return false;
    }
    for (const auto & entry : value) {
        if (!entry.is_object()) {
            err = "each backend must be an object {name, files, env}";
            return false;
        }
        backend_request b;
        for (const auto & item : entry.items()) {
            const std::string & key = item.key();
            const json & v = item.value();
            if (key == "name" && v.is_string() && !v.get<std::string>().empty()) {
                b.name = v.get<std::string>();
            } else if (key == "files" && v.is_array() && !v.empty()) {
                for (const auto & f : v) {
                    if (!f.is_string() || f.get<std::string>().empty()) {
                        err = "backend files must be non-empty strings";
                        return false;
                    }
                    b.files.push_back(f.get<std::string>());
                }
            } else if (key == "env" && v.is_object()) {
                for (const auto & kv : v.items()) {
                    if (!kv.value().is_string()) {
                        err = "backend env values must be strings: " + kv.key();
                        return false;
                    }
                    b.env.emplace_back(kv.key(), kv.value().get<std::string>());
                }
            } else {
                err = "unknown or mistyped backend key: " + key;
                return false;
            }
        }
        if (b.name.empty() || b.files.empty()) {
            err = "each backend needs a name and at least one file";
            return false;
        }
        if (b.name == "cpu") {
            err = "backend name cpu is reserved for the built-in CPU backend";
            return false;
        }
        for (const auto & other : out) {
            if (other.name == b.name) {
                err = "backend listed twice: " + b.name;
                return false;
            }
        }
        out.push_back(std::move(b));
    }
    return true;
}

// Loads one optional backend after its env is set. A failure is returned as the reason, never thrown: an optional
// backend can't stop the runtime. ggml says why only in its log, so the reason is its error
// line (as for model loads, F51). Runs under the runtime lock: setenv isn't safe against getenv on other threads,
// and this is before any of llama.cpp's own threads exist.
json load_optional_backend(const backend_request & b, const std::string & backend_dir) {
    const auto failed = [&](const std::string & reason) {
        return json{{"name", b.name}, {"loaded", false}, {"error", reason}, {"devices", json::array()}};
    };
    for (const auto & kv : b.env) setenv(kv.first.c_str(), kv.second.c_str(), 1);
    json devices = json::array();
    // a backend's init or device query may throw; past lb_runtime_init's extern "C" that would be std::terminate
    try {
        for (const auto & file : b.files) {
            const std::string path = backend_dir.empty() ? file : backend_dir + "/" + file;
            lb_error_capture errors;
            ggml_backend_reg_t reg = ggml_backend_load(path.c_str());
            if (!reg) {
                const std::string reason = errors.first_line();
                return failed(reason.empty() ? "failed to load " + path : reason);
            }
            for (const auto & d : reg_device_names(reg)) devices.push_back(d);
        }
    } catch (const std::exception & e) {
        return failed(std::string("threw: ") + e.what());
    } catch (...) {
        return failed("threw an unknown exception");
    }
    return {{"name", b.name}, {"loaded", true}, {"error", nullptr}, {"devices", devices}};
}

}  // namespace

char * lb_strdup(const std::string & s) {
    char * out = static_cast<char *>(std::malloc(s.size() + 1));
    if (!out) throw std::bad_alloc();
    std::memcpy(out, s.c_str(), s.size() + 1);
    return out;
}

char * lb_json_strdup(const json & j) { return lb_strdup(j.dump_safe()); }

void lb_set_error(char ** error_json, const std::string & message, const char * code) {
    if (!error_json) return;
    json e = {{"error", message}};
    if (code) e["code"] = code;
    *error_json = lb_json_strdup(e);
}

lb_error_capture::lb_error_capture() : prev_(t_capture) { t_capture = &text_; }
lb_error_capture::~lb_error_capture() { t_capture = prev_; }

std::string lb_error_capture::first_line() const {
    std::string line = text_.substr(0, text_.find('\n'));
    while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) line.pop_back();
    return line;
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

lb_status lb_fit_estimate(const char * config_json, char ** out_json, char ** error_json) {
    return lb_guard(LB_ERR_INTERNAL, error_json, [&]() -> lb_status {
        if (error_json) *error_json = nullptr;
        if (!out_json) {
            lb_set_error(error_json, "out_json is NULL");
            return LB_ERR_INVALID_ARG;
        }
        *out_json = nullptr;
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
        if (!cfg.is_object()) {
            lb_set_error(error_json, "config must be a JSON object");
            return LB_ERR_INVALID_ARG;
        }
        try {
            common_params params;
            std::string err;
            if (!lb_parse_model_config(cfg, params, err)) {
                lb_set_error(error_json, err);
                return LB_ERR_INVALID_ARG;
            }
            for (const std::string * path : {&params.model.path, &params.mmproj.path,
                                             &params.speculative.draft.mparams.path}) {
                if (!path->empty() && !std::ifstream(*path).good()) {
                    lb_set_error(error_json, "failed to open model file: " + *path, "file_not_found");
                    return LB_ERR_LOAD_FAILED;
                }
            }

            constexpr int64_t MiB = 1024 * 1024;
            const int64_t budget_mib = lb_config_memory_budget_mib(cfg);
            const bool has_budget = budget_mib > 0;
            const uint32_t cap = lb_config_ctx_cap(cfg);
            json result = {{"fits", nullptr}, {"n_ctx", nullptr}, {"n_ctx_min", nullptr},
                           {"used_min_mib", nullptr}, {"used_max_mib", nullptr}, {"budget_mib", nullptr}};
            if (has_budget) result["budget_mib"] = budget_mib;
            if (lb_config_wants_auto_ctx(cfg) && has_budget) {
                json report;
                bool insufficient = false;
                if (!lb_fit_auto_ctx(params, budget_mib * MiB, report, insufficient, err, cap) && !insufficient) {
                    lb_set_error(error_json, err);
                    return LB_ERR_LOAD_FAILED;
                }
                result["fits"] = !insufficient;
                result["n_ctx_min"] = report.at("n_ctx_min");
                result["used_min_mib"] = report.at("used_min_mib");
                if (report.contains("used_max_mib")) result["used_max_mib"] = report.at("used_max_mib");
                if (!insufficient) result["n_ctx"] = report.at("n_ctx");
            } else {
                common_params minimum = params;
                if (!lb_resolve_auto_ctx(minimum, err, (uint32_t) params.fit_params_min_ctx)) {
                    lb_set_error(error_json, err);
                    return LB_ERR_LOAD_FAILED;
                }
                const uint32_t min_ctx = (uint32_t) minimum.n_ctx;
                if (lb_config_wants_auto_ctx(cfg) && !lb_resolve_auto_ctx(params, err, cap)) {
                    lb_set_error(error_json, err);
                    return LB_ERR_LOAD_FAILED;
                }
                const int64_t used_min = lb_measure_ctx(params, min_ctx);
                const int64_t used = params.n_ctx == (int32_t) min_ctx ? used_min
                    : lb_measure_ctx(params, (uint32_t) params.n_ctx);
                result["n_ctx_min"] = min_ctx;
                result["used_min_mib"] = used_min / MiB;
                result["used_max_mib"] = used / MiB;
                if (has_budget && used > budget_mib * MiB) {
                    result["fits"] = false;
                } else {
                    result["n_ctx"] = params.n_ctx;
                    if (has_budget) result["fits"] = true;
                }
            }
            *out_json = lb_json_strdup(result);
            return LB_OK;
        } catch (const std::runtime_error & e) {
            // the measurement's own failures (config.cpp throws runtime_error); bad_alloc and anything else reach
            // lb_guard as LB_ERR_INTERNAL
            lb_set_error(error_json, std::string("fit estimate failed: ") + e.what());
            return LB_ERR_LOAD_FAILED;
        }
    });
}

lb_status lb_runtime_init(const char * config_json, char ** error_json) {
    return lb_guard(LB_ERR_INTERNAL, error_json, [&]() -> lb_status {
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
        std::vector<backend_request> backends;
        int verbosity = LOG_LEVEL_WARN;
        for (const auto & item : cfg.items()) {
            const std::string & key = item.key();
            const json & value = item.value();
            if (key == "backend_dir" && value.is_string()) {
                backend_dir = value.get<std::string>();
            } else if (key == "backends") {
                std::string err;
                if (!parse_backends(value, backends, err)) {
                    lb_set_error(error_json, err);
                    return LB_ERR_INVALID_ARG;
                }
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
        const std::string canonical = cfg.dump_safe();
        if (s.initialized) {
            if (canonical == s.config) return LB_OK;
            lb_set_error(error_json, "runtime already initialized with a different config: " + s.config);
            return LB_ERR_ALREADY_INITIALIZED;
        }

        // same order as llama_server() in tools/server/server.cpp: common_init, then the backend. Each step is safe
        // to repeat if an earlier init failed (start_gc returns when already running).
        common_init();
        common_log_set_verbosity_thold(verbosity);
        llama_log_set(log_trampoline, nullptr);  // replaces common_init's default, which it calls when the app has none
        server_stream_session_manager_start();  // once per process, as llama_server() does
        // Our backend goes in before llama_backend_init: with an empty registry it calls ggml_backend_load_all, which
        // scans the executable's and the current directory and loads GGML_BACKEND_PATH. Backends come only from
        // our list (findings F35). llama-server too loads backends before llama_backend_init when given --device.
        std::string load_error;
        ggml_backend_reg_t cpu = nullptr;
        if (!load_cpu_backend(backend_dir, cpu, load_error)) {
            lb_set_error(error_json, load_error);
            return LB_ERR_INTERNAL;
        }
        json loaded = json::array();
        loaded.push_back({{"name", "cpu"}, {"loaded", true}, {"error", nullptr}, {"devices", reg_device_names(cpu)}});
        // the optional backends come only from this list too, so their devices are known before any model loads
        for (const auto & b : backends) loaded.push_back(load_optional_backend(b, backend_dir));
        llama_backend_init();

        s.backends = std::move(loaded);
        s.config = canonical;
        s.initialized = true;
        return LB_OK;
    });
}

void lb_runtime_set_log(lb_log_cb cb, void * user) {
    auto & s = state();
    {
        std::unique_lock<std::mutex> lk(s.log_mtx);
        std::shared_ptr<log_entry> old = std::move(s.log);
        if (cb) s.log = std::make_shared<log_entry>(log_entry{cb, user});
        // Wait for calls of the old callback on other threads, so the caller may release the old `user` on return:
        // the count drops under the same mutex, which orders those calls before this return. A call on this thread
        // (set_log from inside the old callback) is still running and finishes after we return.
        if (old) {
            const int own = t_log_entry == old.get() ? 1 : 0;
            s.log_idle.wait(lk, [&] { return old->calls <= own; });
        }
    }
    llama_log_set(log_trampoline, nullptr);  // without a callback it forwards to llama.cpp's default
}

char * lb_runtime_backends(void) {
    return lb_guard(static_cast<char *>(nullptr), nullptr, [&]() -> char * {
        auto & s = state();
        std::lock_guard<std::mutex> lk(s.mtx);
        return s.initialized ? lb_json_strdup(s.backends) : lb_strdup("[]");
    });
}

char * lb_runtime_devices(void) {
    return lb_guard(static_cast<char *>(nullptr), nullptr, [&]() -> char * {
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
        return lb_json_strdup(out);
    });
}

}  // extern "C"
