// Model config JSON -> common_params (P1 T3). Every key is applied through the same common/arg.cpp option
// handler as the equivalent llama-server flag, so values get llama-server's validation and side effects
// (for example --slot-save-path checks the directory and appends a separator). Unlike common_params_parse,
// no environment variables or config.ini files are read: an app's process must not be steered by them.
#include "config.h"

#include "arg.h"
#include "gguf.h"

#include <algorithm>
#include <cstring>
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
        // phone defaults (design §8, D17); keys below override them
        apply_flag(ctx, "--parallel", "1");
        apply_flag(ctx, "--cache-ram", "1024");
        apply_flag(ctx, "--ctx-checkpoints", "8");
        apply_flag(ctx, "--batch-size", "512");
        apply_flag(ctx, "--ubatch-size", "512");

        if (!cfg.contains("model_path")) throw std::invalid_argument("model_path is required");

        for (const auto & item : cfg.items()) {
            const std::string & key = item.key();
            const json & v = item.value();
            if (key == "model_path") {
                apply_flag(ctx, "--model", str_of(key, v));
            } else if (key == "mmproj_path") {
                apply_flag(ctx, "--mmproj", str_of(key, v));
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
            } else if (key == "slot_save_path") {
                apply_flag(ctx, "--slot-save-path", str_of(key, v));
            } else if (key == "speculative") {
                if (!v.is_object() || !v.contains("type")) throw std::invalid_argument("speculative must be {\"type\": ...}");
                const std::string type = str_of("speculative.type", v.at("type"));
                for (const auto & item2 : v.items()) {
                    const std::string & k2 = item2.key();
                    if (k2 != "type" && !(type == "mtp" && k2 == "n_max")) {
                        throw std::invalid_argument("unknown config key: speculative." + k2);
                    }
                }
                if (type == "mtp") {
                    apply_flag(ctx, "--spec-type", "draft-mtp");
                    apply_flag(ctx, "--spec-draft-n-max", v.contains("n_max") ? int_of("speculative.n_max", v.at("n_max")) : "3");
                } else if (type != "none") {
                    throw std::invalid_argument("speculative.type must be none or mtp");
                }
            } else {
                throw std::invalid_argument("unknown config key: " + key);
            }
        }

        // always forced (design §8): no context shift, no idle sleep (reload failure after sleep aborts)
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

bool lb_resolve_auto_ctx(common_params & params, std::string & err) {
    gguf_init_params gp = { /*no_alloc =*/ true, /*ctx =*/ nullptr };
    gguf_context * g = gguf_init_from_file(params.model.path.c_str(), gp);
    if (!g) {
        err = "cannot read GGUF header: " + params.model.path;
        return false;
    }
    uint64_t n_train = 0;
    const int64_t arch_id = gguf_find_key(g, "general.architecture");
    if (arch_id >= 0 && gguf_get_kv_type(g, arch_id) == GGUF_TYPE_STRING) {
        const std::string key = std::string(gguf_get_val_str(g, arch_id)) + ".context_length";
        const int64_t id = gguf_find_key(g, key.c_str());
        if (id >= 0) {
            switch (gguf_get_kv_type(g, id)) {
                case GGUF_TYPE_UINT32: n_train = gguf_get_val_u32(g, id); break;
                case GGUF_TYPE_INT32:  n_train = (uint64_t) std::max<int32_t>(0, gguf_get_val_i32(g, id)); break;
                case GGUF_TYPE_UINT64: n_train = gguf_get_val_u64(g, id); break;
                default: break;
            }
        }
    }
    gguf_free(g);
    params.n_ctx = (int32_t) (n_train > 0 ? std::min<uint64_t>(8192, n_train) : 8192);
    return true;
}
