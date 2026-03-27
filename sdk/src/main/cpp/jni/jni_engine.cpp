#include <jni.h>

#include "jni_refs.hpp"
#include "jni_config_reader.hpp"
#include "jni_error.hpp"
#include "engine/engine.hpp"
#include "llama.h"

// ── Shared helper ─────────────────────────────────────────────────────────────
static engine::NativeEngineParams readEngineParams(JNIEnv *env,
                                                   jobject jConfig) {
    auto configReader = JniConfigReader(env, jConfig);
    return engine::NativeEngineParams{
            .model_path = configReader.getString(jni_refs::engine::params::model_path),
            .threads    = configReader.getInt(jni_refs::engine::params::threads),
            .use_mmap   = configReader.getBool(jni_refs::engine::params::use_m_map),
            .use_mlock  = configReader.getBool(jni_refs::engine::params::use_m_lock),
    };
}

// ── create ────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jlong JNICALL
Java_com_suhel_llamabro_sdk_engine_internal_LlamaEngineImpl_00024Jni_create(JNIEnv *env, jclass,
                                                                            jobject jConfig) {
    try {
        auto instance = new engine::Engine(readEngineParams(env, jConfig));
        return reinterpret_cast<jlong>(instance);
    } catch (const result_code_error &ex) {
        throwResultCode(env, ex.code);
    } catch (const std::exception &) {
        throwResultCode(env, ResultCode::UNKNOWN);
    }
    return 0L;
}

// ── createWithProgress ────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jlong JNICALL
Java_com_suhel_llamabro_sdk_engine_internal_LlamaEngineImpl_00024Jni_createWithProgress(JNIEnv *env,
                                                                                        jclass,
                                                                                        jobject jConfig,
                                                                                        jobject jListener) {
    auto config = readEngineParams(env, jConfig);

    // It is safe to pass these refs to the callback because this method is synchronous
    config.progress_callback = [env, jListener](float progress) -> bool {
        return env->CallBooleanMethod(jListener, jni_refs::engine::m_listener_on_progress,
                                      static_cast<jfloat>(progress)) == JNI_TRUE;
    };

    try {
        auto instance = new engine::Engine(config);
        return reinterpret_cast<jlong>(instance);
    } catch (const result_code_error &ex) {
        throwResultCode(env, ex.code);
    } catch (const std::exception &) {
        throwResultCode(env, ResultCode::UNKNOWN);
    }
    return 0L;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_engine_internal_LlamaEngineImpl_00024Jni_destroy(JNIEnv *, jclass,
                                                                             jlong jEnginePtr) {
    delete reinterpret_cast<engine::Engine *>(jEnginePtr);
}
