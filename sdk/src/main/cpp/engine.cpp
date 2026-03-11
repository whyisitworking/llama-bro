#include "engine.h"
#include "utils/ggml_variant_chooser.h"

LlamaEngine::LlamaEngine(const NativeEngineParams &config) : threads{config.threads} {
    ggml_backend_load(resolve_best_ggml_backend());
    llama_backend_init();

    auto params = llama_model_default_params();

    params.use_mmap = config.use_mmap;
    params.use_mlock = config.use_mlock;
    params.n_gpu_layers = 0; // Only CPU for now, so no offloading

    llama_model.reset(llama_model_load_from_file(config.model_path.c_str(), params));
}

LlamaEngine::~LlamaEngine() {
    llama_backend_free();
}

LlamaSession *LlamaEngine::session(const NativeSessionParams &config) {
    return new LlamaSession(this->llama_model.get(), this->threads, config);
}
