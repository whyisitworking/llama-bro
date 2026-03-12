#include "engine.h"
#include "utils/ggml_variant_chooser.h"
#include "utils/error_codes.h"

#include <stdexcept>

LlamaEngine::LlamaEngine(const NativeEngineParams &config) : threads{config.threads} {
    // Load the most capable GGML backend for this CPU
    int backend_result = ggml_backend_load(resolve_best_ggml_backend());
    if (backend_result != 0) {
        throw std::runtime_error(
            std::to_string(static_cast<int>(LlamaErrorCode::BACKEND_LOAD_FAILED)) +
            ":" + resolve_best_ggml_backend()
        );
    }

    llama_backend_init();

    auto params = llama_model_default_params();
    params.use_mmap = config.use_mmap;
    params.use_mlock = config.use_mlock;
    params.n_gpu_layers = 0; // CPU-only for now

    auto *model = llama_model_load_from_file(config.model_path.c_str(), params);

    if (!model) {
        llama_backend_free();
        throw std::runtime_error(
            std::to_string(static_cast<int>(LlamaErrorCode::MODEL_LOAD_FAILED)) +
            ":" + config.model_path
        );
    }

    llama_model.reset(model);
}

LlamaEngine::~LlamaEngine() {
    llama_backend_free();
}

LlamaSession *LlamaEngine::session(const NativeSessionParams &config) {
    return new LlamaSession(llama_model.get(), threads, config);
}
