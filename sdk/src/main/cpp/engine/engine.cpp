#include <exception>

#include "engine.hpp"
#include "ggml_variant_chooser.hpp"

#include "session/session.hpp"
#include "result/codes.hpp"
#include "ggml-backend.h"
#include "utils/log.hpp"

namespace engine {
    Engine::Engine(const NativeEngineParams &config) {
        auto backend = resolve_best_ggml_backend();
        auto backend_result = ggml_backend_load(backend);
        if (backend_result == nullptr) {
            throw result_code_error(ResultCode::BACKEND_LOAD_FAILED);
        }

        llama_backend_init();

        auto params = llama_model_default_params();
        params.use_mmap = config.use_mmap;
        params.use_mlock = config.use_mlock;
        params.n_gpu_layers = 0; // CPU-only for now

        // Wire up progress callback if provided
        if (config.progress_callback) {
            // Stack-safe: std::function lives in config, which lives in the JNI frame.
            // llama_model_load_from_file is synchronous — the callback is only called here.
            params.progress_callback_user_data = const_cast<std::function<bool(
                    float)> *>(&config.progress_callback);
            params.progress_callback = [](float progress, void *user_data) -> bool {
                auto *cb = static_cast<std::function<bool(float)> *>(user_data);
                return (*cb)(progress);
            };
        }

        auto *model = llama_model_load_from_file(config.model_path.c_str(), params);

        if (!model) {
            llama_backend_free();
            throw result_code_error(ResultCode::MODEL_LOAD_FAILED);
        }

        llama_model.reset(model);
    }

    Engine::~Engine() {
        llama_backend_free();
    }

    session::Session *Engine::session(const session::NativeSessionParams &config) {
        return new session::Session(llama_model.get(), config);
    }
}
