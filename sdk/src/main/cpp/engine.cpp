#include "engine.h"
#include "utils/ggml_variant_chooser.h"
#include "utils/llama_exception.h"

LlamaEngine::LlamaEngine(const NativeEngineParams &config) : threads{config.threads} {
    auto backend_result = ggml_backend_load(resolve_best_ggml_backend());
    if (backend_result == nullptr) {
        throw LlamaException(LlamaErrorCode::BACKEND_LOAD_FAILED, resolve_best_ggml_backend());
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
        params.progress_callback_user_data = const_cast<std::function<bool(float)> *>(&config.progress_callback);
        params.progress_callback = [](float progress, void *user_data) -> bool {
            auto *cb = static_cast<std::function<bool(float)> *>(user_data);
            return (*cb)(progress);
        };
    }

    auto *model = llama_model_load_from_file(config.model_path.c_str(), params);

    if (!model) {
        llama_backend_free();
        throw LlamaException(LlamaErrorCode::MODEL_LOAD_FAILED, config.model_path);
    }

    llama_model.reset(model);
}

LlamaEngine::~LlamaEngine() {
    llama_backend_free();
}

LlamaSession *LlamaEngine::session(const NativeSessionParams &config) {
    return new LlamaSession(llama_model.get(), threads, config);
}
