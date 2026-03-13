#include <jni.h>
#include "utils/jni_config_reader.h"
#include "utils/jni_error_thrower.h"
#include "utils/llama_exception.h"
#include "engine.h"
#include "llama.h"

// ── Shared helper ─────────────────────────────────────────────────────────────

static NativeEngineParams readEngineParams(JNIEnv *env, jobject kConfig) {
    auto configReader = JniConfigReader(env, kConfig);
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
                                                                     jobject kConfig) {
    try {
        return reinterpret_cast<jlong>(new LlamaEngine(readEngineParams(env, kConfig)));
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
                                                                                 jobject kConfig,
                                                                                 jobject kListener) {
    auto config = readEngineParams(env, kConfig);

    // Resolve the callback method ID once before entering the blocking load
    jclass listenerClass = env->GetObjectClass(kListener);
    jmethodID onProgress = env->GetMethodID(listenerClass, "onProgress", "(F)Z");
    env->DeleteLocalRef(listenerClass);

    // env + kListener + onProgressId are all valid for the lifetime of this JNI call
    // (llama_model_load_from_file is synchronous — the callback never outlives this frame)
    config.progress_callback = [env, kListener, onProgress](float progress) -> bool {
        return env->CallBooleanMethod(kListener, onProgress,
                                      static_cast<jfloat>(progress)) == JNI_TRUE;
    };

    try {
        return reinterpret_cast<jlong>(new LlamaEngine(config));
    } catch (const LlamaException &ex) {
        throwLlamaError(env, ex);
        return 0L;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaEngineImpl_00024Jni_destroy(JNIEnv *, jclass,
                                                                      jlong ptr) {
    delete reinterpret_cast<LlamaEngine *>(ptr);
}
