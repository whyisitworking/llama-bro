#include <jni.h>
#include "utils/jni_config_reader.h"
#include "utils/jni_error_thrower.h"
#include "utils/llama_exception.h"
#include "engine.h"

// ── create ────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jlong JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_create(JNIEnv *env,
                                                                      jclass,
                                                                      jlong kEnginePtr,
                                                                      jobject kParams) {
    auto engine = reinterpret_cast<LlamaEngine *>(kEnginePtr);
    auto configReader = JniConfigReader(env, kParams);

    auto config = NativeSessionParams{
            .context_size          = configReader.getInt("contextSize"),
            .overflow_strategy_id  = configReader.getInt("overflowStrategyId"),
            .overflow_drop_tokens  = configReader.getInt("overflowDropTokens"),
            .top_k_enabled         = configReader.getBool("topKEnabled"),
            .top_k                 = configReader.getInt("topK"),
            .top_p_enabled         = configReader.getBool("topPEnabled"),
            .top_p                 = configReader.getFloat("topP"),
            .min_p_enabled         = configReader.getBool("minPEnabled"),
            .min_p                 = configReader.getFloat("minP"),
            .rep_pen               = configReader.getFloat("repPen"),
            .presence_pen          = configReader.getFloat("presencePen"),
            .temp                  = configReader.getFloat("temp"),
            .seed                  = configReader.getInt("seed"),
            .batch_size            = configReader.getInt("batchSize"),
            .micro_batch_size      = configReader.getInt("microBatchSize"),
            .system_prompt_reserve = configReader.getInt("systemPromptReserve"),
    };

    try {
        return reinterpret_cast<jlong>(engine->session(config));
    } catch (const LlamaException &ex) {
        throwLlamaError(env, ex);
        return 0L;
    }
}

// ── prompt ────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_setSystemPrompt(JNIEnv *env, jclass,
                                                                               jlong kSessionPtr,
                                                                               jstring kText) {
    auto session = reinterpret_cast<LlamaSession *>(kSessionPtr);
    auto text = env->GetStringUTFChars(kText, nullptr);
    std::string textStr(text);
    env->ReleaseStringUTFChars(kText, text);

    try {
        session->setSystemPrompt(textStr);
    } catch (const LlamaException &ex) {
        throwLlamaError(env, ex);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_injectPrompt(JNIEnv *env, jclass,
                                                                            jlong kSessionPtr,
                                                                            jstring kText) {
    auto session = reinterpret_cast<LlamaSession *>(kSessionPtr);
    auto text = env->GetStringUTFChars(kText, nullptr);
    std::string textStr(text);
    env->ReleaseStringUTFChars(kText, text);

    try {
        session->injectPrompt(textStr);
    } catch (const LlamaException &ex) {
        throwLlamaError(env, ex);
    }
}

// ── clear ─────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_clear(JNIEnv *, jclass,
                                                                     jlong kSessionPtr) {
    reinterpret_cast<LlamaSession *>(kSessionPtr)->clear();
}

// ── abort ─────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_abort(JNIEnv *, jclass,
                                                                     jlong kSessionPtr) {
    reinterpret_cast<LlamaSession *>(kSessionPtr)->abort();
}

// ── generate ─────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jstring JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_generate(JNIEnv *env, jclass,
                                                                        jlong kSessionPtr) {
    auto result = reinterpret_cast<LlamaSession *>(kSessionPtr)->generate();

    if (result.has_value()) {
        const auto &utf16 = result.value();
        return env->NewString(reinterpret_cast<const jchar *>(utf16.data()),
                              static_cast<jsize>(utf16.size()));
    }
    return nullptr;
}

// ── destroy ───────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_destroy(JNIEnv *, jclass,
                                                                       jlong kSessionPtr) {
    delete reinterpret_cast<LlamaSession *>(kSessionPtr);
}
