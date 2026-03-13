#pragma once

#include <string>
#include <functional>

#include "llama-cpp.h"
#include "session.h"

struct NativeEngineParams {
    std::string model_path;
    int threads;
    bool use_mmap;
    bool use_mlock;
    // Optional progress callback; nullptr means no progress reporting
    std::function<bool(float)> progress_callback = nullptr;
};

class LlamaEngine {
private:
    llama_model_ptr llama_model;
    int threads;

public:
    LlamaEngine(const NativeEngineParams &config);

    ~LlamaEngine();

    LlamaEngine(const LlamaEngine &) = delete;
    LlamaEngine(LlamaEngine &&) = delete;
    LlamaEngine &operator=(const LlamaEngine &) = delete;
    LlamaEngine &operator=(LlamaEngine &&) = delete;

    LlamaSession *session(const NativeSessionParams &config);
};
