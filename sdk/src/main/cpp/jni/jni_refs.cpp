#include "jni_refs.hpp"

namespace jni_refs {
    static inline void cache_exception_refs(JNIEnv *env) {
        auto ref = env->FindClass("java/lang/RuntimeException");
        exceptions::c_runtime_exp = reinterpret_cast<jclass>(env->NewGlobalRef(ref));
        env->DeleteLocalRef(ref);
    }

    static inline void cache_session_refs(JNIEnv *env) {
        auto class_ref = env->FindClass(
                "com/suhel/llamabro/sdk/engine/internal/LlamaSessionImpl$NativeTokenGenerationResult");

        session::f_result_token = env->GetFieldID(class_ref, "token", types::string);
        session::f_result_code = env->GetFieldID(class_ref, "resultCode", types::int32);
        session::f_result_is_complete = env->GetFieldID(class_ref, "isComplete",
                                                        types::boolean);

        env->DeleteLocalRef(class_ref);
    }

    static inline void cache_engine_refs(JNIEnv *env) {
        auto listener_class = env->FindClass("com/suhel/llamabro/sdk/ProgressListener");
        engine::m_listener_on_progress = env->GetMethodID(listener_class, "onProgress", "(F)Z");
        env->DeleteLocalRef(listener_class);
    }

    static void cache_refs(JNIEnv *env) {
        cache_exception_refs(env);
        cache_session_refs(env);
        cache_engine_refs(env);
    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;

    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jni_refs::cache_refs(env);

    return JNI_VERSION_1_6;
}
