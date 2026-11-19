// Model config JSON -> common_params. Every key is applied through the same common/arg.cpp option
// handler as the equivalent llama-server flag, so values get llama-server's validation and side effects
// (for example --slot-save-path checks the directory and appends a separator). Unlike common_params_parse,
// no environment variables or config.ini files are read: an app's process must not be steered by them.
#include "config.h"

#include "arg.h"
#include "gguf.h"
#include "fit.h"
#include "speculative.h"
#include "ggml-backend.h"
#include "llama-cpp.h"

#include <algorithm>
#include <cstring>
#include <mutex>
#include <stdexcept>

namespace {

common_arg * find_option(common_params_context & ctx, const char * flag) {
    for (auto & opt : ctx.options) {
        for (const char * a : opt.args)     if (std::strcmp(a, flag) == 0) return &opt;
        for (const char * a : opt.args_neg) if (std::strcmp(a, flag) == 0) return &opt;
    }
    throw std::logic_error(std::string("llama.cpp has no option ") + flag);
}

// the same dispatch as common_params_parse_ex (common/arg.cpp) for one "flag value" pair
void apply_flag(common_params_context & ctx, const char * flag, const std::string & value) {
    common_arg * opt = find_option(ctx, flag);
    if (opt->handler_int) {
        opt->handler_int(ctx.params, std::stoi(value));
    } else if (opt->handler_string) {
        opt->handler_string(ctx.params, value);
    } else {
        throw std::logic_error(std::string("option takes no single value: ") + flag);
    }
}

// a flag without a value: --foo / --no-foo
void apply_switch(common_params_context & ctx, const char * flag) {
    common_arg * opt = find_option(ctx, flag);
    const bool negative = std::any_of(opt->args_neg.begin(), opt->args_neg.end(),
                                      [&](const char * a) { return std::strcmp(a, flag) == 0; });
    if (opt->handler_bool) {
        opt->handler_bool(ctx.params, !negative);
    } else if (opt->handler_void && !negative) {
        opt->handler_void(ctx.params);
    } else {
        throw std::logic_error(std::string("option is not a switch: ") + flag);
    }
}

}  // namespace

bool lb_config_wants_auto_ctx(const json & cfg) {
    return !cfg.contains("n_ctx") || (cfg.at("n_ctx").is_string() && cfg.at("n_ctx") == "auto");
}

static bool gguf_arch_u64(const std::string & path, const char * suffix, uint64_t & value, std::string & err);

bool lb_parse_model_config(const json & cfg, common_params & out, std::string & err) {
    if (!cfg.is_object()) {
        err = "model config must be a JSON object";
        return false;
    }
    out = common_params();
    // per-example defaults, as llama-server gets them (sets n_parallel = -1 among others)
    common_params_context ctx = common_params_parser_init(out, LLAMA_EXAMPLE_SERVER, nullptr);

    auto int_of = [&](const std::string & key, const json & v) {
        if (!v.is_number_integer()) throw std::invalid_argument(key + " must be an integer");
        return std::to_string(v.get<long long>());
    };
    auto str_of = [&](const std::string & key, const json & v) {
        if (!v.is_string()) throw std::invalid_argument(key + " must be a string");
        return v.get<std::string>();
    };

    try {
        bool has_draft_path = false;
        bool is_dflash = false;
        // phone defaults (D17); keys below override them
        apply_flag(ctx, "--parallel", "1");
        apply_flag(ctx, "--cache-ram", "1024");
        apply_flag(ctx, "--ctx-checkpoints", "8");
        apply_flag(ctx, "--batch-size", "512");
        apply_flag(ctx, "--ubatch-size", "512");
        // read weights instead of mapping them: the CPU repack copies them anyway, and the mapped pages
        // would stay resident next to the copy (findings F50, F58; D30)
        apply_flag(ctx, "--load-mode", "none");
        // the context and the placement are always explicit (D33), so upstream's fit has nothing to do; on
        // the NPU (no reported memory) it only fails (F66)
        apply_flag(ctx, "--fit", "off");

        if (!cfg.contains("model_path")) throw std::invalid_argument("model_path is required");

        // The device is always explicit (F32): without one, llama.cpp would use every GPU-type device, and
        // Hexagon and OpenCL both are. No "devices" key, or an empty list, is CPU only, as `--device none`. Names are
        // resolved here rather than through `--device`, whose handler rescans for backends on every load (F35).
        out.devices = {nullptr};
        out.mmproj_use_gpu = false;

        std::string mmproj_device;
        for (const auto & item : cfg.items()) {
            const std::string & key = item.key();
            const json & v = item.value();
            if (key == "model_path") {
                apply_flag(ctx, "--model", str_of(key, v));
            } else if (key == "mmproj_path") {
                apply_flag(ctx, "--mmproj", str_of(key, v));
            } else if (key == "draft_path") {
                apply_flag(ctx, "--model-draft", str_of(key, v));
                has_draft_path = true;
            } else if (key == "devices") {
                if (!v.is_array()) throw std::invalid_argument("devices must be an array of device names");
                std::vector<ggml_backend_dev_t> devs;
                for (const auto & d : v) {
                    const std::string name = str_of("devices[]", d);
                    ggml_backend_dev_t dev = ggml_backend_dev_by_name(name.c_str());
                    if (!dev || ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_CPU) {
                        throw std::invalid_argument("invalid device: " + name);  // as parse_device_list (common/arg.cpp)
                    }
                    devs.push_back(dev);
                }
                // the image encoder goes on the model's device, not "the first GPU" (two chips for one model); on the
                // NPU too since b12352, which encodes correctly there (F120, F121, D52)
                out.mmproj_use_gpu = !devs.empty();
                out.mmproj_device  = devs.empty() ? nullptr : devs.front();
                devs.push_back(nullptr);
                out.devices = devs;
            } else if (key == "mmproj_device") {
                mmproj_device = str_of(key, v);  // applied after the loop: "devices" sets the default
                if (mmproj_device.empty()) throw std::invalid_argument("mmproj_device must be \"none\" or a device name");
            } else if (key == "n_ctx_cap") {
                if (!v.is_number_integer() || v.get<int64_t>() < 1) throw std::invalid_argument("n_ctx_cap must be a positive integer");
            } else if (key == "memory_budget_mib") {
                if (!v.is_number_integer() || v.get<int64_t>() <= 0) throw std::invalid_argument("memory_budget_mib must be a positive integer");
            } else if (key == "n_gpu_layers") {
                apply_flag(ctx, "--n-gpu-layers", int_of(key, v));
            } else if (key == "n_ctx") {
                if (!(v.is_string() && v == "auto")) apply_flag(ctx, "--ctx-size", int_of(key, v));
            } else if (key == "n_threads") {
                apply_flag(ctx, "--threads", int_of(key, v));
                apply_flag(ctx, "--threads-batch", int_of(key, v));
            } else if (key == "n_batch") {
                apply_flag(ctx, "--batch-size", int_of(key, v));
            } else if (key == "n_ubatch") {
                apply_flag(ctx, "--ubatch-size", int_of(key, v));
            } else if (key == "n_parallel") {
                apply_flag(ctx, "--parallel", int_of(key, v));
            } else if (key == "cache_ram_mib") {
                apply_flag(ctx, "--cache-ram", int_of(key, v));
            } else if (key == "n_ctx_checkpoints") {
                apply_flag(ctx, "--ctx-checkpoints", int_of(key, v));
            } else if (key == "seed") {
                // upstream's --seed: the sampler seed for requests without their own (a realtime session has none)
                apply_flag(ctx, "--seed", int_of(key, v));
            } else if (key == "slot_save_path") {
                apply_flag(ctx, "--slot-save-path", str_of(key, v));
            } else if (key == "media_dir") {
                // llama-server's media root: path attachments reach it as file:// links (request.cpp, D84, F157)
                apply_flag(ctx, "--media-path", str_of(key, v));
            } else if (key == "kv_cache_type") {
                // one type for K and V, as Ollama's OLLAMA_KV_CACHE_TYPE; upstream's handler validates the name
                apply_flag(ctx, "--cache-type-k", str_of(key, v));
                apply_flag(ctx, "--cache-type-v", str_of(key, v));
            } else if (key == "embeddings") {
                if (!v.is_boolean()) throw std::invalid_argument("embeddings must be a boolean");
                if (v.get<bool>()) apply_switch(ctx, "--embeddings");
            } else if (key == "pooling") {
                apply_flag(ctx, "--pooling", str_of(key, v));
            } else if (key == "reranking") {
                if (!v.is_boolean()) throw std::invalid_argument("reranking must be a boolean");
                if (v.get<bool>()) apply_switch(ctx, "--reranking");
            } else if (key == "lora") {
                // [{path, scale}]: what --lora-scaled FNAME:SCALE adds per item (common/arg.cpp), without its
                // comma and colon parsing, which a path could break
                if (!v.is_array()) throw std::invalid_argument("lora must be an array of {path, scale}");
                for (const auto & a : v) {
                    if (!a.is_object() || !a.contains("path")) throw std::invalid_argument("lora[] must be {\"path\": ..., \"scale\": ...}");
                    for (const auto & f : a.items()) {
                        if (f.key() != "path" && f.key() != "scale") throw std::invalid_argument("unknown config key: lora[]." + f.key());
                    }
                    float scale = 1.0f;
                    if (a.contains("scale")) {
                        if (!a.at("scale").is_number()) throw std::invalid_argument("lora[].scale must be a number");
                        scale = a.at("scale").get<float>();
                    }
                    out.lora_adapters.push_back({ str_of("lora[].path", a.at("path")), scale, "", "", nullptr });
                }
            } else if (key == "speculative") {
                if (!v.is_object() || !v.contains("type")) throw std::invalid_argument("speculative must be {\"type\": ...}");
                const std::string type = str_of("speculative.type", v.at("type"));
                for (const auto & item2 : v.items()) {
                    const std::string & k2 = item2.key();
                    if (k2 != "type" && !((type == "mtp" || type == "dflash") && k2 == "n_max") &&
                        !(type == "ngram" && k2 == "mtp_n_max")) {
                        throw std::invalid_argument("unknown config key: speculative." + k2);
                    }
                }
                const std::string n_max = v.contains("n_max") ? int_of("speculative.n_max", v.at("n_max")) : "3";
                if (type == "mtp") {
                    apply_flag(ctx, "--spec-type", "draft-mtp");
                    apply_flag(ctx, "--spec-draft-n-max", n_max);
                } else if (type == "dflash") {
                    is_dflash = true;
                    apply_flag(ctx, "--spec-type", "draft-dflash");
                    apply_flag(ctx, "--spec-draft-n-max", v.contains("n_max") ? n_max : "15");
                    apply_flag(ctx, "--flash-attn", "on");
                } else if (type == "ngram") {
                    // ngram-mod with upstream's --spec-default values (common/arg.cpp), spelled out so that it can be
                    // listed before draft-mtp: speculative.cpp tries n-grams first, MTP when they have no draft (D35, F72)
                    apply_flag(ctx, "--spec-type", v.contains("mtp_n_max") ? "ngram-mod,draft-mtp" : "ngram-mod");
                    apply_flag(ctx, "--spec-ngram-mod-n-match", "24");
                    apply_flag(ctx, "--spec-ngram-mod-n-min", "48");
                    apply_flag(ctx, "--spec-ngram-mod-n-max", "64");
                    if (v.contains("mtp_n_max")) {
                        apply_flag(ctx, "--spec-draft-n-max", int_of("speculative.mtp_n_max", v.at("mtp_n_max")));
                    }
                } else if (type != "none") {
                    throw std::invalid_argument("speculative.type must be none, mtp, ngram or dflash");
                }
            } else {
                throw std::invalid_argument("unknown config key: " + key);
            }
        }

        // upstream's -mmdev: "none" keeps the image encoder on the CPU, a device name puts it there; absent, it follows
        // the model's device. Resolved by name like "devices", without -mmdev's backend rescan (F35)
        if (!mmproj_device.empty()) {
            if (mmproj_device == "none") {
                out.mmproj_use_gpu = false;
                out.mmproj_device  = nullptr;
            } else {
                ggml_backend_dev_t dev = ggml_backend_dev_by_name(mmproj_device.c_str());
                if (!dev || ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_CPU) {
                    throw std::invalid_argument("invalid mmproj_device: " + mmproj_device);
                }
                out.mmproj_use_gpu = true;
                out.mmproj_device  = dev;
            }
        }

        if (has_draft_path && !is_dflash) throw std::invalid_argument("draft_path needs speculative.type dflash");
        if (is_dflash && !has_draft_path) throw std::invalid_argument("speculative.type dflash needs draft_path");

        // always forced: no context shift, no idle sleep (reload failure after sleep aborts)
        apply_switch(ctx, "--no-context-shift");
        apply_flag(ctx, "--sleep-idle-seconds", "-1");
    } catch (const std::exception & e) {
        err = e.what();
        return false;
    }

    // the post-processing common_params_parse_ex does after the flags (common/arg.cpp)
    postprocess_cpu_params(out.cpuparams,       nullptr);
    postprocess_cpu_params(out.cpuparams_batch, &out.cpuparams);
    postprocess_cpu_params(out.speculative.draft.cpuparams,       &out.cpuparams);
    postprocess_cpu_params(out.speculative.draft.cpuparams_batch, &out.cpuparams_batch);
    return true;
}

// <arch>.<suffix> from the GGUF header as an unsigned number, without loading tensors; 0 if absent
static bool gguf_arch_u64(const std::string & path, const char * suffix, uint64_t & value, std::string & err) {
    gguf_init_params gp = { /*no_alloc =*/ true, /*ctx =*/ nullptr };
    gguf_context * g = gguf_init_from_file(path.c_str(), gp);
    if (!g) {
        err = "cannot read GGUF header: " + path;
        return false;
    }
    value = 0;
    const int64_t arch_id = gguf_find_key(g, "general.architecture");
    if (arch_id >= 0 && gguf_get_kv_type(g, arch_id) == GGUF_TYPE_STRING) {
        const std::string key = std::string(gguf_get_val_str(g, arch_id)) + "." + suffix;
        const int64_t id = gguf_find_key(g, key.c_str());
        if (id >= 0) {
            switch (gguf_get_kv_type(g, id)) {
                case GGUF_TYPE_UINT32: value = gguf_get_val_u32(g, id); break;
                case GGUF_TYPE_INT32:  value = (uint64_t) std::max<int32_t>(0, gguf_get_val_i32(g, id)); break;
                case GGUF_TYPE_UINT64: value = gguf_get_val_u64(g, id); break;
                default: break;
            }
        }
    }
    gguf_free(g);
    return true;
}

static bool gguf_ctx_train(const std::string & path, uint64_t & n_train, std::string & err) {
    return gguf_arch_u64(path, "context_length", n_train, err);
}


bool lb_resolve_auto_ctx(common_params & params, std::string & err, uint32_t n_ctx_cap) {
    uint64_t n_train = 0;
    if (!gguf_ctx_train(params.model.path, n_train, err)) return false;
    const uint64_t cap = n_ctx_cap ? n_ctx_cap : 8192;
    // the cap is per slot, as in lb_fit_auto_ctx and upstream's fit: each of n_parallel slots gets it
    const uint64_t n_streams = params.kv_unified ? 1 : (uint64_t) std::max(1, params.n_parallel);
    params.n_ctx = (int32_t) ((n_train > 0 ? std::min<uint64_t>(cap, n_train) : cap) * n_streams);
    return true;
}

uint32_t lb_config_ctx_cap(const json & cfg) {
    return cfg.contains("n_ctx_cap") && cfg.at("n_ctx_cap").is_number_integer()
        ? (uint32_t) std::max<int64_t>(0, cfg.at("n_ctx_cap").get<int64_t>()) : 0;
}

int64_t lb_config_memory_budget_mib(const json & cfg) {
    return cfg.contains("memory_budget_mib") && cfg.at("memory_budget_mib").is_number_integer()
        ? cfg.at("memory_budget_mib").get<int64_t>() : 0;
}

// Upstream's fit measurement swaps llama.cpp's process-wide log callback for its own and back while it runs
// (common/fit.cpp, common_fit_log_scope), so two measurements at once (two estimates, or an estimate and a load's
// automatic context) left one forwarding to the other's finished stack frame and crashed (F172). They run one at a
// time; a measurement reads only headers and takes well under a second.
static std::mutex g_fit_mutex;

int64_t lb_measure_ctx(common_params & params, uint32_t n_ctx, uint32_t * n_ctx_train, int64_t * draft_bytes) {
    std::lock_guard<std::mutex> one_at_a_time(g_fit_mutex);
    llama_model_params   mparams = common_model_params_to_llama(params);
    llama_context_params cparams = common_context_params_to_llama(params);
    cparams.n_ctx = n_ctx;
    int64_t sum = 0;
    for (const auto & row : common_fit_measure(params.model.get_paths(), &mparams, &cparams, n_ctx_train, nullptr,
                                               GGML_LOG_LEVEL_ERROR)) {
        sum += (int64_t) (row.model + row.context + row.compute);
    }
    if (!params.speculative.draft.mparams.path.empty()) {
        common_params p_dft = common_base_params_to_speculative(params);
        llama_model_params dft_mparams = common_model_params_to_llama(p_dft);
        llama_context_params dft_cparams = common_context_params_to_llama(p_dft);
        // speculative.cpp gives the draft the target's total context and links it to the target context.
        // DFlash requires ctx_other even for a fit probe, so make a no-allocation target probe here.
        llama_model_params target_mparams = mparams;
        target_mparams.no_alloc = true;
        target_mparams.load_mode = LLAMA_LOAD_MODE_NONE;
        llama_model_ptr target_model(llama_model_load_from_file(params.model.path.c_str(), target_mparams));
        if (!target_model) throw std::runtime_error("failed to load target model for draft fit");
        llama_context_ptr target_ctx(llama_init_from_model(target_model.get(), cparams));
        if (!target_ctx) throw std::runtime_error("failed to create target context for draft fit");
        dft_cparams.n_ctx = n_ctx;
        dft_cparams.n_rs_seq = 0;
        dft_cparams.ctx_other = target_ctx.get();
        int64_t draft = 0;
        for (const auto & row : common_fit_measure(p_dft.model.get_paths(), &dft_mparams, &dft_cparams,
                                                   nullptr, nullptr, GGML_LOG_LEVEL_ERROR)) {
            draft += (int64_t) (row.model + row.context + row.compute);
        }
        sum += draft;
        if (draft_bytes) *draft_bytes = draft;
    }
    return sum;
}

bool lb_fit_auto_ctx(common_params & params, int64_t budget_bytes, json & report, bool & insufficient, std::string & err,
                     uint32_t n_ctx_cap) {
    constexpr int64_t MiB = 1024 * 1024;
    insufficient = false;
    const llama_context_params cparams = common_context_params_to_llama(params);
    const uint32_t n_streams = cparams.kv_unified ? 1 : std::max<uint32_t>(1, cparams.n_seq_max);
    try {
        uint64_t n_train_gguf = 0;
        if (!gguf_ctx_train(params.model.path, n_train_gguf, err)) return false;
        const uint32_t n_train = (uint32_t) std::max<uint64_t>(1, std::min<uint64_t>(n_train_gguf ? n_train_gguf : 8192, UINT32_MAX / n_streams));
        const uint32_t n_top   = n_ctx_cap ? std::min(n_ctx_cap, n_train) : n_train;   // the ceiling (D34)
        const uint32_t n_min   = std::min<uint32_t>((uint32_t) params.fit_params_min_ctx, n_top) * n_streams;  // 4096, as --fit
        const uint32_t n_max   = n_top * n_streams;

        int64_t draft_min = 0;
        const int64_t used_min = lb_measure_ctx(params, n_min, nullptr, &draft_min);
        report = {{"budget_mib", budget_bytes / MiB}, {"n_ctx_train", n_train}, {"n_ctx_cap", n_top}, {"n_ctx_min", n_min},
                  {"used_min_mib", used_min / MiB}, {"n_streams", n_streams}};  // n_ctx_cap is per stream, the rest total
        if (draft_min > 0) report["draft_mib_min"] = draft_min / MiB;
        if (used_min > budget_bytes) {
            insufficient = true;
            err = "not enough memory: the model needs " + std::to_string(used_min / MiB) + " MiB at the smallest context (" +
                  std::to_string(n_min) + " tokens), " + std::to_string(budget_bytes / MiB) + " MiB available";
            return false;
        }
        uint32_t n_ctx = n_min;
        if (n_max > n_min) {
            const int64_t used_max = lb_measure_ctx(params, n_max);
            report["n_ctx_max"] = n_max;
            report["used_max_mib"] = used_max / MiB;
            if (used_max <= budget_bytes) {
                n_ctx = n_max;
            } else {
                // upstream's step 2 (common/fit.cpp): linear between the two measurements, rounded down to 256 per stream
                n_ctx = n_min + (uint32_t) ((int64_t) (n_max - n_min) * (budget_bytes - used_min) / (used_max - used_min));
                const uint32_t align = 256 * n_streams;
                n_ctx = std::max(n_ctx - n_ctx % align, n_min);
            }
        }
        params.n_ctx = (int32_t) n_ctx;
        report["n_ctx"] = n_ctx;
        return true;
    } catch (const std::exception & e) {
        err = std::string("memory measurement failed: ") + e.what();
        return false;
    }
}
