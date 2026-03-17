#include <jni.h>
#include "utils/jni_config_reader.h"
#include "utils/jni_error_thrower.h"
#include "utils/llama_exception.h"
#include "engine.h"
#include "llama.h"

// ── Shared helper ─────────────────────────────────────────────────────────────

namespace jni_refs {
    constexpr auto progress_listener_method = "onProgress";
    constexpr auto progress_listener_method_sig = "(F)Z";
}

static NativeEngineParams readEngineParams(JNIEnv *env,
                                           jobject jConfig) {
    auto configReader = JniConfigReader(env, jConfig);
    return NativeEngineParams{
            .model_path = configReader.getString("modelPath"),
            .threads    = configReader.getInt("threads"),
            .use_mmap   = configReader.getBool("useMmap"),
            .use_mlock  = configReader.getBool("useMlock"),
    };
}

// ── create ────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jlong JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaEngineImpl_00024Jni_create(JNIEnv *env, jclass,
                                                                     jobject jConfig) {
    try {
        auto instance = new LlamaEngine(readEngineParams(env, jConfig));
        return reinterpret_cast<jlong>(instance);
    } catch (const LlamaException &ex) {
        throwLlamaError(env, ex);
        return 0L;
    }
}

// ── createWithProgress ────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jlong JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaEngineImpl_00024Jni_createWithProgress(JNIEnv *env,
                                                                                 jclass,
                                                                                 jobject jConfig,
                                                                                 jobject jListener) {
    auto config = readEngineParams(env, jConfig);

    // Resolve the callback method ID once before entering the blocking load
    auto jListenerClass = env->GetObjectClass(jListener);
    auto jOnProgress = env->GetMethodID(jListenerClass,
                                        jni_refs::progress_listener_method,
                                        jni_refs::progress_listener_method_sig);
    env->DeleteLocalRef(jListenerClass);

    // It is safe to pass these refs to the callback because this method is synchronous
    config.progress_callback = [env, jListener, jOnProgress](float progress) -> bool {
        return env->CallBooleanMethod(jListener, jOnProgress,
                                      static_cast<jfloat>(progress)) == JNI_TRUE;
    };

    try {
        auto instance = new LlamaEngine(config);
        return reinterpret_cast<jlong>(instance);
    } catch (const LlamaException &ex) {
        throwLlamaError(env, ex);
        return 0L;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaEngineImpl_00024Jni_destroy(JNIEnv *, jclass,
                                                                      jlong jEnginePtr) {
    delete reinterpret_cast<LlamaEngine *>(jEnginePtr);
}
