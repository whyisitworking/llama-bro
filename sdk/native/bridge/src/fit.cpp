// lb_fit_estimate: the dry run of lb_model_load's file check and context choice, and the shared pieces of both.
#include "llamabro.h"
#include "lb_common.h"
#include "config.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>

// A file that can't be opened gets its own code, so apps can tell it apart (F51). llama.cpp would fail on it too, but
// says why only in its log.
bool lb_check_model_files(const common_params & params, std::string & err) {
    for (const std::string * path : {&params.model.path, &params.mmproj.path, &params.speculative.draft.mparams.path}) {
        if (path->empty()) continue;
        FILE * f = std::fopen(path->c_str(), "rb");
        if (!f) {
            err = "cannot open " + *path + ": " + std::strerror(errno);
            return false;
        }
        std::fclose(f);
    }
    return true;
}

// "n_ctx": "auto" (or absent): with a memory budget the largest context that fits it (D33), else the unfitted choice;
// an explicit n_ctx is left as parsed.
bool lb_choose_ctx(const json & cfg, common_params & params, json & report, bool & insufficient, std::string & err) {
    if (!lb_config_wants_auto_ctx(cfg)) return true;
    const uint32_t cap = lb_config_ctx_cap(cfg);
    const int64_t budget_mib = lb_config_memory_budget_mib(cfg);
    return budget_mib > 0 ? lb_fit_auto_ctx(params, budget_mib * 1024 * 1024, report, insufficient, err, cap)
                          : lb_resolve_auto_ctx(params, err, cap);
}

extern "C" {

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
            if (!lb_check_model_files(params, err)) {
                lb_set_error(error_json, "failed to load model: " + err, "file_not_found");
                return LB_ERR_LOAD_FAILED;
            }

            constexpr int64_t MiB = 1024 * 1024;
            const int64_t budget_mib = lb_config_memory_budget_mib(cfg);
            const bool has_budget = budget_mib > 0;
            json result = {{"fits", nullptr}, {"n_ctx", nullptr}, {"n_ctx_min", nullptr},
                           {"used_min_mib", nullptr}, {"used_max_mib", nullptr}, {"budget_mib", nullptr}};
            if (has_budget) result["budget_mib"] = budget_mib;
            json report;
            bool insufficient = false;
            if (lb_config_wants_auto_ctx(cfg) && has_budget) {
                if (!lb_choose_ctx(cfg, params, report, insufficient, err) && !insufficient) {
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
                if (!lb_choose_ctx(cfg, params, report, insufficient, err)) {  // no budget, or an explicit n_ctx: the unfitted choice
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

}  // extern "C"
