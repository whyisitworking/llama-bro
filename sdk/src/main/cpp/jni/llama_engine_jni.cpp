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

    // Create a GlobalRef to guarantee the object survives the JNI frame securely
    auto globalListener = env->NewGlobalRef(kListener);

    // Get the JavaVM so the lambda can attach/detach thread or use the current env
    JavaVM* jvm;
    env->GetJavaVM(&jvm);

    config.progress_callback = [jvm, globalListener, onProgress](float progress) -> bool {
        JNIEnv *currentEnv;
        // In this specific codebase, llama_model_load_from_file is synchronous,
        // but getting the env from JVM is the correct modern JNI pattern.
        auto res = jvm->GetEnv(reinterpret_cast<void**>(&currentEnv), JNI_VERSION_1_6);
        if (res == JNI_OK) {
            return currentEnv->CallBooleanMethod(globalListener, onProgress, static_cast<jfloat>(progress)) == JNI_TRUE;
        }
        return false;
    };

    try {
        auto *engine = new LlamaEngine(config);
        env->DeleteGlobalRef(globalListener); // Safe to delete after synchronous load
        return reinterpret_cast<jlong>(engine);
    } catch (const LlamaException &ex) {
        env->DeleteGlobalRef(globalListener); // Clean up on error
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
