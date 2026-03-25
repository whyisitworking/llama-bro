#include <jni.h>
#include <exception>

#include "jni_config_reader.hpp"
#include "jni_error.hpp"

#include "engine/engine.hpp"
#include "session/session.hpp"

// ── create ────────────────────────────────────────────────────────────────────

static session::NativeInferenceParams parse_inference_params(const JniConfigReader &reader) {
    return {
            .repeat_penalty        = reader.getFloat(jni_refs::session::pn_repeat_penalty),
            .frequency_penalty     = reader.getFloat(jni_refs::session::pn_frequency_penalty),
            .presence_penalty      = reader.getFloat(jni_refs::session::pn_presence_penalty),
            .penalty_last_n        = reader.getInt(jni_refs::session::pn_penalty_last_n),

            .dry_multiplier        = reader.getFloat(jni_refs::session::pn_dry_multiplier),
            .dry_base              = reader.getFloat(jni_refs::session::pn_dry_base),
            .dry_allowed_length    = reader.getInt(jni_refs::session::pn_dry_allowed_length),
            .dry_penalty_last_n    = reader.getInt(jni_refs::session::pn_dry_penalty_last_n),

            .top_n_sigma           = reader.getFloat(jni_refs::session::pn_top_n_sigma),
            .top_k                 = reader.getInt(jni_refs::session::pn_top_k),
            .typ_p                 = reader.getFloat(jni_refs::session::pn_typ_p),
            .top_p                 = reader.getFloat(jni_refs::session::pn_top_p),
            .min_p                 = reader.getFloat(jni_refs::session::pn_min_p),

            .temp                  = reader.getFloat(jni_refs::session::pn_temperature),
            .seed                  = reader.getInt(jni_refs::session::pn_seed),
    };
}

static session::NativeSessionParams parse_session_params(const JniConfigReader &reader) {
    return {
            .context_size          = reader.getInt(jni_refs::session::pn_context_size),
            .threads               = reader.getInt(jni_refs::session::pn_threads),
            .overflow_strategy_id  = reader.getInt(jni_refs::session::pn_overflow_strategy_id),
            .overflow_drop_tokens  = reader.getInt(jni_refs::session::pn_overflow_drop_tokens),

            .inference_params      = parse_inference_params(
                    reader.getNestedObject(jni_refs::session::pn_inference_params,
                                           jni_refs::session::cn_native_inference_params)),

            .batch_size            = reader.getInt(jni_refs::session::pn_batch_size),
            .micro_batch_size      = reader.getInt(jni_refs::session::pn_micro_batch_size),
    };
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_suhel_llamabro_sdk_engine_internal_LlamaSessionImpl_00024Jni_create(JNIEnv *env,
                                                                             jclass,
                                                                             jlong jEnginePtr,
                                                                             jobject jParams) {
    auto engine = reinterpret_cast<engine::Engine *>(jEnginePtr);
    auto configReader = JniConfigReader(env, jParams);
    auto params = parse_session_params(configReader);;

    try {
        return reinterpret_cast<jlong>(engine->session(params));
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

// ── updateSampler ─────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_suhel_llamabro_sdk_engine_internal_LlamaSessionImpl_00024Jni_updateSampler(JNIEnv *env,
                                                                                    jclass,
                                                                                    jlong jSessionPtr,
                                                                                    jobject jParams) {
    auto session = reinterpret_cast<session::Session *>(jSessionPtr);
    auto configReader = JniConfigReader(env, jParams);
    auto params = parse_inference_params(configReader);

    try {
        session->update_sampler(params);
    } catch (const result_code_error &ex) {
        throwResultCode(env, ex.code);
    } catch (const std::exception &) {
        throwResultCode(env, ResultCode::UNKNOWN);
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
