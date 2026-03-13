#pragma once

#include <jni.h>
#include <string>
#include "error_codes.h"
#include "llama_exception.h"

/**
 * The single, exclusive point where Java/JNI exceptions are thrown from native code.
 *
 * Message format sent to Kotlin: "<code_int>:<detail>"
 * The Kotlin NativeErrorMapper parses this into a typed LlamaError subclass.
 *
 * ── Rules ────────────────────────────────────────────────────────────────────
 * 1. Native code (engine.cpp, session.cpp) MUST throw LlamaException — never call this directly.
 * 2. JNI bridge code MUST catch LlamaException and call throwLlamaError(env, ex) — never call
 *    env->ThrowNew() directly.
 * 3. Always return a zero/null sentinel from the JNI function immediately after calling this.
 */
inline void throwLlamaError(JNIEnv *env, LlamaErrorCode code, const char *detail = "") {
    std::string message = std::to_string(static_cast<int>(code)) + ":" + detail;
    jclass clazz = env->FindClass("java/lang/RuntimeException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message.c_str());
        env->DeleteLocalRef(clazz);
    }
}

/** Convenience overload: converts a LlamaException directly. One-liner at every catch site. */
inline void throwLlamaError(JNIEnv *env, const LlamaException &ex) {
    throwLlamaError(env, ex.code, ex.what());
}
