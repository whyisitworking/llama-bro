#pragma once

#include <jni.h>
#include <string>
#include "error_codes.h"

/**
 * Throws a Java RuntimeException via JNI that encodes a [LlamaErrorCode].
 *
 * Message format: "<code_int>:<detail>"
 * e.g. "2:Failed to open /data/local/model.gguf"
 *
 * The Kotlin internal layer parses the leading integer to map it to the
 * correct LlamaError subclass before re-throwing to API consumers.
 *
 * Usage:
 *   throwLlamaError(env, LlamaErrorCode::MODEL_NOT_FOUND, config.model_path.c_str());
 *   return 0L; // always return immediately after throwing
 */
inline void throwLlamaError(JNIEnv *env, LlamaErrorCode code, const char *detail = "") {
    int code_int = static_cast<int>(code);
    std::string message = std::to_string(code_int) + ":" + detail;
    jclass clazz = env->FindClass("java/lang/RuntimeException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message.c_str());
        env->DeleteLocalRef(clazz);
    }
}
