#include <jni.h>
#include "utils/jni_config_reader.h"
#include "utils/jni_error_thrower.h"
#include "utils/error_codes.h"
#include "engine.h"

extern "C"
JNIEXPORT jlong JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_create(JNIEnv *env,
                                                                      jclass clazz,
                                                                      jlong kEnginePtr,
                                                                      jobject kParams) {
    auto engine = reinterpret_cast<LlamaEngine *>(kEnginePtr);
    auto configReader = JniConfigReader(env, kParams);

    auto config = NativeSessionParams{
            .context_size          = configReader.getInt("contextSize"),
            .system_prompt         = configReader.getString("systemPrompt"),
            .overflow_strategy_id  = configReader.getInt("overflowStrategyId"),
            .overflow_drop_tokens  = configReader.getInt("overflowDropTokens"),
            .top_k_enabled         = configReader.getBool("topKEnabled"),
            .top_k                 = configReader.getInt("topK"),
            .top_p_enabled         = configReader.getBool("topPEnabled"),
            .top_p                 = configReader.getFloat("topP"),
            .min_p_enabled         = configReader.getBool("minPEnabled"),
            .min_p                 = configReader.getFloat("minP"),
            // Always-on samplers — no enable guard
            .rep_pen               = configReader.getFloat("repPen"),
            .temp                  = configReader.getFloat("temp"),
            .seed                  = configReader.getInt("seed"),
            // Decode tuning (was hardcoded)
            .batch_size            = configReader.getInt("batchSize"),
            .micro_batch_size      = configReader.getInt("microBatchSize"),
            .system_prompt_reserve = configReader.getInt("systemPromptReserve"),
    };

    try {
        auto instance = engine->session(config);
        return reinterpret_cast<jlong>(instance);
    } catch (const std::runtime_error &e) {
        jclass exc = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exc, e.what());
        env->DeleteLocalRef(exc);
        return 0L;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_prompt(JNIEnv *env, jclass clazz,
                                                                      jlong kSessionPtr,
                                                                      jstring kText) {
    auto session = reinterpret_cast<LlamaSession *>(kSessionPtr);
    auto text = env->GetStringUTFChars(kText, nullptr);
    session->prompt(text);
    env->ReleaseStringUTFChars(kText, text);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_clear(JNIEnv *env, jclass clazz,
                                                                     jlong kSessionPtr) {
    auto session = reinterpret_cast<LlamaSession *>(kSessionPtr);
    session->clear();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_generate(JNIEnv *env, jclass clazz,
                                                                        jlong kSessionPtr) {
    auto session = reinterpret_cast<LlamaSession *>(kSessionPtr);
    auto result = session->generate();

    if (result.has_value()) {
        const auto &utf16 = result.value();
        return env->NewString(reinterpret_cast<const jchar *>(utf16.data()),
                              static_cast<jsize>(utf16.size()));
    } else {
        return nullptr;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_destroy(JNIEnv *env,
                                                                       jclass clazz,
                                                                       jlong kSessionPtr) {
    auto instance = reinterpret_cast<LlamaSession *>(kSessionPtr);
    delete instance;
}
