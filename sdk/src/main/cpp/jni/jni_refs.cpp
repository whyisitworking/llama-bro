#include "jni_refs.hpp"

namespace jni_refs {
    namespace classpaths {
        static constexpr const char *runtime_exception = "java/lang/RuntimeException";
        static constexpr const char *token_generation_result = "com/suhel/llamabro/sdk/engine/internal/LlamaSessionImpl$NativeTokenGenerationResult";
        static constexpr const char *chat_template_info = "com/suhel/llamabro/sdk/engine/internal/NativeChatTemplateInfo";
        static constexpr const char *completion_info = "com/suhel/llamabro/sdk/engine/internal/NativeCompletionInfo";
        static constexpr const char *progress_listener = "com/suhel/llamabro/sdk/ProgressListener";
    }

    namespace methods {
        static constexpr const char *ctor = "<init>";
        static constexpr const char *progress_listener_on_progress = "onProgress";
    }

    namespace signatures {
        static constexpr const char *chat_template_info_ctor = "(ZLjava/lang/String;Ljava/lang/String;)V";
        static constexpr const char *completion_info_ctor = "(Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;II)V";
        static constexpr const char *progress_listener_on_progress = "(F)Z";
    }

    static inline void cache_exception_refs(JNIEnv *env) {
        auto ref = env->FindClass(classpaths::runtime_exception);
        exceptions::c_runtime_exp = reinterpret_cast<jclass>(env->NewGlobalRef(ref));
        env->DeleteLocalRef(ref);
    }

    static inline void cache_engine_refs(JNIEnv *env) {
        auto listener_class = env->FindClass(classpaths::progress_listener);
        engine::m_listener_on_progress = env->GetMethodID(listener_class,
                                                          methods::progress_listener_on_progress,
                                                          signatures::progress_listener_on_progress);
        env->DeleteLocalRef(listener_class);
    }

    static inline void cache_session_refs(JNIEnv *env) {
        // NativeTokenGenerationResult
        {
            auto class_ref = env->FindClass(classpaths::token_generation_result);

            session::f_result_token = env->GetFieldID(class_ref, "token", types::string);
            session::f_result_code = env->GetFieldID(class_ref, "resultCode", types::int32);
            session::f_result_is_complete = env->GetFieldID(class_ref, "isComplete",
                                                            types::boolean);
            env->DeleteLocalRef(class_ref);
        }

        // NativeChatTemplateInfo
        {
            auto class_ref = env->FindClass(classpaths::chat_template_info);
            session::c_chat_template_info = reinterpret_cast<jclass>(env->NewGlobalRef(class_ref));
            session::m_chat_template_info_ctor = env->GetMethodID(class_ref,
                                                                  methods::ctor,
                                                                  signatures::chat_template_info_ctor);
            env->DeleteLocalRef(class_ref);
        }

        // NativeCompletionInfo
        {
            auto class_ref = env->FindClass(classpaths::completion_info);
            session::c_completion_info = reinterpret_cast<jclass>(env->NewGlobalRef(class_ref));
            session::m_completion_info_ctor = env->GetMethodID(class_ref,
                                                               methods::ctor,
                                                               signatures::completion_info_ctor);
            env->DeleteLocalRef(class_ref);
        }
    }

    static void cache_refs(JNIEnv *env) {
        cache_exception_refs(env);
        cache_engine_refs(env);
        cache_session_refs(env);
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
