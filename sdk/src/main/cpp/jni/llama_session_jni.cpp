#include <jni.h>
#include "utils/jni_config_reader.h"
#include "engine.h"

extern "C"
JNIEXPORT jlong JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_create(JNIEnv *env,
                                                                      jclass clazz,
                                                                      jlong kEnginePtr,
                                                                      jobject kParams) {
    auto engine = reinterpret_cast<LlamaEngine *>(kEnginePtr);
    auto configReader = JniConfigReader(env, kParams);

    auto contextSize = configReader.getInt("contextSize");
    auto systemPrompt = configReader.getString("systemPrompt");
    auto overflowStrategyId = configReader.getInt("overflowStrategyId");
    auto overflowDropTokens = configReader.getInt("overflowDropTokens");
    auto topKEnabled = configReader.getBool("topKEnabled");
    auto topK = configReader.getInt("topK");
    auto topPEnabled = configReader.getBool("topPEnabled");
    auto topP = configReader.getFloat("topP");
    auto minPEnabled = configReader.getBool("minPEnabled");
    auto minP = configReader.getFloat("minP");
    auto repPenEnabled = configReader.getBool("repPenEnabled");
    auto repPen = configReader.getFloat("repPen");
    auto tempEnabled = configReader.getBool("tempEnabled");
    auto temp = configReader.getFloat("temp");
    auto seed = configReader.getInt("seed");

    auto config = NativeSessionParams{
            .context_size = contextSize,
            .system_prompt = systemPrompt,
            .overflow_strategy_id = overflowStrategyId,
            .overflow_drop_tokens = overflowDropTokens,
            .top_k_enabled = topKEnabled,
            .top_k = topK,
            .top_p_enabled = topPEnabled,
            .top_p = topP,
            .min_p_enabled = minPEnabled,
            .min_p = minP,
            .rep_pen_enabled = repPenEnabled,
            .rep_pen = repPen,
            .temp_enabled = tempEnabled,
            .temp = temp,
            .seed = seed,
    };

    auto instance = engine->session(config);
    return reinterpret_cast<jlong>(instance);
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
