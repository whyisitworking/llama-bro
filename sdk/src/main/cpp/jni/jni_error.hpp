#pragma once

#include <jni.h>
#include <string>

#include "result/codes.hpp"
#include "jni_refs.hpp"

/**
 * Throws a Java RuntimeException whose message is the integer value of the
 * given ResultCode. The Kotlin layer parses this integer to produce a typed
 * LlamaError via NativeErrorMapper.
 */
inline void throwResultCode(JNIEnv *env, ResultCode code) {
    auto msg = std::to_string(static_cast<int>(code));
    env->ThrowNew(jni_refs::exceptions::c_runtime_exp, msg.c_str());
}
