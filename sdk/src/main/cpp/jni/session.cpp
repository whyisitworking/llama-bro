#include <jni.h>
#include <exception>

#include "config_reader.hpp"
#include "result/codes.hpp"
#include "engine/engine.hpp"
#include "session/session.hpp"

namespace jni_refs {
    constexpr auto token_generation_result_class = "com/suhel/llamabro/sdk/internal/LlamaSessionImpl$NativeTokenGenerationResult";
    constexpr auto token_generation_result_constructor_sig = "(Ljava/lang/String;Z)V";
}

static jclass jTokenGenerationResultClass = nullptr;
static jmethodID jTokenGenerationResultConstructor = nullptr;

static void cache_refs(JNIEnv *env) {
    auto local = env->FindClass(jni_refs::token_generation_result_class);

    jTokenGenerationResultClass = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    jTokenGenerationResultConstructor = env->GetMethodID(jTokenGenerationResultClass,
                                                         "<init>",
                                                         jni_refs::token_generation_result_constructor_sig);
    env->DeleteLocalRef(local);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    cache_refs(env);
    return JNI_VERSION_1_6;
}

// ── create ────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jlong JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_create(JNIEnv *env,
                                                                      jclass,
                                                                      jlong jEnginePtr,
                                                                      jobject jParams) {
    auto engine = reinterpret_cast<engine::Engine *>(jEnginePtr);
    auto configReader = JniConfigReader(env, jParams);

    auto config = session::NativeSessionParams{
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
    };

    try {
        return reinterpret_cast<jlong>(engine->session(config));
    } catch (const std::exception &ex) {
        return 0L;
    }
}

// ── prompt ────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_setSystemPrompt(JNIEnv *env, jclass,
                                                                               jlong jSessionPtr,
                                                                               jstring jText) {
    auto session = reinterpret_cast<session::Session *>(jSessionPtr);
    auto text = env->GetStringUTFChars(jText, nullptr);
    auto text_str = std::string(text);
    env->ReleaseStringUTFChars(jText, text);

    session->set_system_prompt(text_str);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_addUserPrompt(JNIEnv *env, jclass,
                                                                             jlong jSessionPtr,
                                                                             jstring jText) {
    auto session = reinterpret_cast<session::Session *>(jSessionPtr);
    auto text = env->GetStringUTFChars(jText, nullptr);
    auto text_str = std::string(text);
    env->ReleaseStringUTFChars(jText, text);

    session->add_user_prompt(text_str);
}

// ── clear ─────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_clear(JNIEnv *env, jclass,
                                                                     jlong jSessionPtr) {
    auto session = reinterpret_cast<session::Session *>(jSessionPtr);
    session->clear();
}

// ── abort ─────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_abort(JNIEnv *, jclass,
                                                                     jlong jSessionPtr) {
    auto session = reinterpret_cast<session::Session *>(jSessionPtr);
    session->abort();
}

// ── generate ─────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jobject JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_generate(JNIEnv *env, jclass,
                                                                        jlong jSessionPtr) {
    auto session = reinterpret_cast<session::Session *>(jSessionPtr);
    auto gen = session->generate();
    auto token = gen.token;

    auto jToken = token.has_value()
                  ? env->NewString(reinterpret_cast<const jchar *>(token.value().data()),
                                   static_cast<jsize>(token.value().size()))
                  : nullptr;
    auto jIsComplete = static_cast<jboolean>(gen.is_complete);

    return env->NewObject(jTokenGenerationResultClass, jTokenGenerationResultConstructor,
                          jToken, jIsComplete);
}

// ── destroy ───────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_internal_LlamaSessionImpl_00024Jni_destroy(JNIEnv *, jclass,
                                                                       jlong jSessionPtr) {
    auto session = reinterpret_cast<session::Session *>(jSessionPtr);
    delete session;
}
