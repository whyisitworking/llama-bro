#include <jni.h>
#include <exception>

#include "jni_config_reader.hpp"
#include "jni_error.hpp"

#include "engine/engine.hpp"
#include "session/session.hpp"

// ── create ────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jlong JNICALL
Java_com_suhel_llamabro_sdk_engine_internal_LlamaSessionImpl_00024Jni_create(JNIEnv *env,
                                                                             jclass,
                                                                             jlong jEnginePtr,
                                                                             jobject jParams) {
    auto engine = reinterpret_cast<engine::Engine *>(jEnginePtr);
    auto configReader = JniConfigReader(env, jParams);

    auto config = session::NativeSessionParams{
            .context_size          = configReader.getInt("contextSize"),
            .threads               = configReader.getInt("threads"),
            .overflow_strategy_id  = configReader.getInt("overflowStrategyId"),
            .overflow_drop_tokens  = configReader.getInt("overflowDropTokens"),

            .repeat_penalty        = configReader.getFloat("repeatPenalty"),
            .frequency_penalty     = configReader.getFloat("frequencyPenalty"),
            .presence_penalty      = configReader.getFloat("presencePenalty"),
            .penalty_last_n        = configReader.getInt("penaltyLastN"),

            .dry_multiplier        = configReader.getFloat("dryMultiplier"),
            .dry_base              = configReader.getFloat("dryBase"),
            .dry_allowed_length    = configReader.getInt("dryAllowedLength"),
            .dry_penalty_last_n    = configReader.getInt("dryPenaltyLastN"),

            .top_n_sigma           = configReader.getFloat("topNSigma"),
            .top_k                 = configReader.getInt("topK"),
            .typ_p                 = configReader.getFloat("typP"),
            .top_p                 = configReader.getFloat("topP"),
            .min_p                 = configReader.getFloat("minP"),

            .temp                  = configReader.getFloat("temperature"),
            .seed                  = configReader.getInt("seed"),

            .batch_size            = configReader.getInt("batchSize"),
            .micro_batch_size      = configReader.getInt("microBatchSize"),
    };

    try {
        return reinterpret_cast<jlong>(engine->session(config));
    } catch (const result_code_error &ex) {
        throwResultCode(env, ex.code);
    } catch (const std::exception &) {
        throwResultCode(env, ResultCode::UNKNOWN);
    }
    return 0L;
}

// ── prompt ────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_engine_internal_LlamaSessionImpl_00024Jni_setSystemPrompt(JNIEnv *env,
                                                                                      jclass,
                                                                                      jlong jSessionPtr,
                                                                                      jstring jText) {
    auto session = reinterpret_cast<session::Session *>(jSessionPtr);
    auto text = env->GetStringUTFChars(jText, nullptr);
    auto text_str = std::string(text);
    env->ReleaseStringUTFChars(jText, text);

    auto result = session->set_system_prompt(text_str);
    if (result != ResultCode::OK) {
        throwResultCode(env, result);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_engine_internal_LlamaSessionImpl_00024Jni_addUserPrompt(JNIEnv *env,
                                                                                    jclass,
                                                                                    jlong jSessionPtr,
                                                                                    jstring jText) {
    auto session = reinterpret_cast<session::Session *>(jSessionPtr);
    auto text = env->GetStringUTFChars(jText, nullptr);
    auto text_str = std::string(text);
    env->ReleaseStringUTFChars(jText, text);

    auto result = session->add_user_prompt(text_str);
    if (result != ResultCode::OK) {
        throwResultCode(env, result);
    }
}

// ── clear ─────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_engine_internal_LlamaSessionImpl_00024Jni_clear(JNIEnv *env, jclass,
                                                                            jlong jSessionPtr) {
    auto session = reinterpret_cast<session::Session *>(jSessionPtr);
    session->clear();
}

// ── abort ─────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_engine_internal_LlamaSessionImpl_00024Jni_abort(JNIEnv *, jclass,
                                                                            jlong jSessionPtr) {
    auto session = reinterpret_cast<session::Session *>(jSessionPtr);
    session->abort();
}

// ── generate ─────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_engine_internal_LlamaSessionImpl_00024Jni_generate(JNIEnv *env, jclass,
                                                                               jlong jSessionPtr,
                                                                               jobject jResultObj) {
    auto session = reinterpret_cast<session::Session *>(jSessionPtr);

    try {
        auto gen = session->generate();
        auto token = gen.token;
        auto result_code = gen.result_code;

        auto jToken = token
                      ? env->NewString(reinterpret_cast<const jchar *>(token->data()),
                                       static_cast<jsize>(token->size()))
                      : nullptr;

        env->SetObjectField(jResultObj, jni_refs::session::f_result_token,
                            jToken);
        env->SetIntField(jResultObj, jni_refs::session::f_result_code,
                         static_cast<jint>(result_code));
        env->SetBooleanField(jResultObj, jni_refs::session::f_result_is_complete,
                             static_cast<jboolean>(gen.is_complete));
    } catch (const std::exception &) {
        env->SetIntField(jResultObj, jni_refs::session::f_result_code,
                         static_cast<jint>(ResultCode::UNKNOWN));
        env->SetBooleanField(jResultObj, jni_refs::session::f_result_is_complete,
                             JNI_TRUE);
    }
}

// ── destroy ───────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_engine_internal_LlamaSessionImpl_00024Jni_destroy(JNIEnv *, jclass,
                                                                              jlong jSessionPtr) {
    auto session = reinterpret_cast<session::Session *>(jSessionPtr);
    delete session;
}
