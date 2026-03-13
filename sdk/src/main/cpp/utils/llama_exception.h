#pragma once

#include <stdexcept>
#include <string>
#include "error_codes.h"

/**
 * The canonical exception type thrown by all llama-bro native code.
 *
 * Carries a typed [LlamaErrorCode] alongside a human-readable detail string.
 * The JNI layer catches this and converts it to a Java exception via throwLlamaError().
 *
 * Usage in native code:
 *   throw LlamaException(LlamaErrorCode::MODEL_LOAD_FAILED, config.model_path);
 *
 * Never throw std::runtime_error or any other exception type from native code.
 * Never call env->ThrowNew() from native code — that is the JNI layer's job.
 */
class LlamaException : public std::runtime_error {
public:
    const LlamaErrorCode code;

    LlamaException(LlamaErrorCode code, const std::string &detail = "")
        : std::runtime_error(detail), code(code) {}
};
