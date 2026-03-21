#pragma once

#include <string>
#include <functional>

#include "llama-cpp.h"
#include "session/session.hpp"

namespace engine {
    struct NativeEngineParams {
        std::string model_path;
        int threads;
        bool use_mmap;
        bool use_mlock;
        // Optional progress callback; nullptr means no progress reporting
        std::function<bool(float)> progress_callback = nullptr;
    };

    class Engine {
    private:
        llama_model_ptr llama_model;

    public:
        Engine(const NativeEngineParams &config);

        ~Engine();

        Engine(const Engine &) = delete;

        Engine(Engine &&) = delete;

        Engine &operator=(const Engine &) = delete;

        Engine &operator=(Engine &&) = delete;

        session::Session *session(const session::NativeSessionParams &config);
    };
}
