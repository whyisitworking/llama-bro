#pragma once

#include <stdexcept>
#include <string>

enum class ResultCode : int {
    OK = 0,

    // ── Engine ──────────────────────────────────────────────────────────────
    MODEL_NOT_FOUND = 1,  // model file path does not exist
    MODEL_LOAD_FAILED = 2,  // file exists but llama_model_load_from_file returned null
    BACKEND_LOAD_FAILED = 3,  // ggml_backend_load returned non-zero
    CANCELLED = 4,  // operation was explicitly aborted via abort()

    // ── Session ─────────────────────────────────────────────────────────────
    CONTEXT_INIT_FAILED = 10, // llama_init_from_model returned null
    CONTEXT_OVERFLOW = 11, // HALT strategy: context is full, cannot recover
    DECODE_FAILED = 12, // llama_decode returned non-zero

    // ── Catch-all ────────────────────────────────────────────────────────────
    UNKNOWN = 99,
};

/**
 * Typed exception carrying a ResultCode. All native failures that should
 * propagate to the JNI boundary throw this instead of std::runtime_error.
 */
class result_code_error : public std::runtime_error {
public:
    ResultCode code;

    explicit result_code_error(ResultCode c)
            : std::runtime_error(std::to_string(static_cast<int>(c))), code(c) {}
};
