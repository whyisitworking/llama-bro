#include <jni.h>
#include <exception>
#include <vector>
#include <string>

#include "jni_config_reader.hpp"
#include "jni_error.hpp"
#include "utils/log.hpp"

#include "engine/engine.hpp"
#include "session/session.hpp"

// ── create ────────────────────────────────────────────────────────────────────

static session::NativeInferenceParams parse_inference_params(const JniConfigReader &reader) {
    return {
            .repeat_penalty        = reader.getFloat(jni_refs::session::params::repeat_penalty),
            .frequency_penalty     = reader.getFloat(jni_refs::session::params::frequency_penalty),
            .presence_penalty      = reader.getFloat(jni_refs::session::params::presence_penalty),
            .penalty_last_n        = reader.getInt(jni_refs::session::params::penalty_last_n),

            .dry_multiplier        = reader.getFloat(jni_refs::session::params::dry_multiplier),
            .dry_base              = reader.getFloat(jni_refs::session::params::dry_base),
            .dry_allowed_length    = reader.getInt(jni_refs::session::params::dry_allowed_length),
            .dry_penalty_last_n    = reader.getInt(jni_refs::session::params::dry_penalty_last_n),

            .top_n_sigma           = reader.getFloat(jni_refs::session::params::top_n_sigma),
            .top_k                 = reader.getInt(jni_refs::session::params::top_k),
            .typ_p                 = reader.getFloat(jni_refs::session::params::typ_p),
            .top_p                 = reader.getFloat(jni_refs::session::params::top_p),
            .min_p                 = reader.getFloat(jni_refs::session::params::min_p),

            .temp                  = reader.getFloat(jni_refs::session::params::temperature),
            .seed                  = reader.getInt(jni_refs::session::params::seed),
    };
}

static session::NativeSessionParams parse_session_params(const JniConfigReader &reader) {
    return {
            .context_size          = reader.getInt(jni_refs::session::params::context_size),
            .threads               = reader.getInt(jni_refs::session::params::threads),
            .overflow_strategy_id  = reader.getInt(jni_refs::session::params::overflow_strategy_id),
            .overflow_drop_tokens  = reader.getInt(jni_refs::session::params::overflow_drop_tokens),

            .inference_params      = parse_inference_params(
                    reader.getNestedObject(jni_refs::session::params::inference_params,
                                           jni_refs::session::classpaths::inference_params)),

            .batch_size            = reader.getInt(jni_refs::session::params::batch_size),
            .micro_batch_size      = reader.getInt(jni_refs::session::params::micro_batch_size),
    };
}

// Helper: extract a Java String from a String[] at given index. Returns empty string for null elements.
static std::string jni_string_array_get(JNIEnv *env, jobjectArray array, jsize index) {
    auto jStr = static_cast<jstring>(env->GetObjectArrayElement(array, index));
    if (!jStr) return "";
    auto chars = env->GetStringUTFChars(jStr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jStr, chars);
    env->DeleteLocalRef(jStr);
    return result;
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

// ── chat template ─────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jobject JNICALL
Java_com_suhel_llamabro_sdk_engine_internal_LlamaSessionImpl_00024Jni_initChatTemplates(JNIEnv *env,
                                                                                        jclass,
                                                                                        jlong jSessionPtr) {
    auto session = reinterpret_cast<session::Session *>(jSessionPtr);

    try {
        auto info = session->init_chat_templates();

        auto jStartTag = info.thinking_start_tag.empty()
                         ? nullptr
                         : env->NewStringUTF(info.thinking_start_tag.c_str());
        auto jEndTag = info.thinking_end_tag.empty()
                       ? nullptr
                       : env->NewStringUTF(info.thinking_end_tag.c_str());

        auto result = env->NewObject(jni_refs::session::c_chat_template_info,
                                     jni_refs::session::m_chat_template_info_ctor,
                                     static_cast<jboolean>(info.supports_thinking),
                                     jStartTag, jEndTag);

        if (jStartTag) env->DeleteLocalRef(jStartTag);
        if (jEndTag) env->DeleteLocalRef(jEndTag);

        return result;
    } catch (const result_code_error &ex) {
        throwResultCode(env, ex.code);
    } catch (const std::exception &) {
        throwResultCode(env, ResultCode::UNKNOWN);
    }
    return nullptr;
}

// ── beginCompletion ──────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jobject JNICALL
Java_com_suhel_llamabro_sdk_engine_internal_LlamaSessionImpl_00024Jni_beginCompletion(
        JNIEnv *env,
        jclass,
        jlong jSessionPtr,
        jobjectArray jRoles,
        jobjectArray jContents,
        jobjectArray jReasoningContents,
        jboolean jEnableThinking) {

    auto session = reinterpret_cast<session::Session *>(jSessionPtr);

    try {
        // Build message vector from parallel arrays
        auto count = env->GetArrayLength(jRoles);
        std::vector<common_chat_msg> messages;
        messages.reserve(count);

        for (jsize i = 0; i < count; i++) {
            common_chat_msg msg;
            msg.role = jni_string_array_get(env, jRoles, i);
            msg.content = jni_string_array_get(env, jContents, i);

            if (jReasoningContents) {
                auto reasoning = jni_string_array_get(env, jReasoningContents, i);
                if (!reasoning.empty()) {
                    msg.reasoning_content = reasoning;
                }
            }

            messages.push_back(std::move(msg));
        }

        auto info = session->begin_completion(messages, jEnableThinking);

        auto jGenerationPrompt = info.generation_prompt.empty()
                                 ? nullptr
                                 : env->NewStringUTF(info.generation_prompt.c_str());
        auto jThinkingStartTag = info.thinking_start_tag.empty()
                                 ? nullptr
                                 : env->NewStringUTF(info.thinking_start_tag.c_str());
        auto jThinkingEndTag = info.thinking_end_tag.empty()
                               ? nullptr
                               : env->NewStringUTF(info.thinking_end_tag.c_str());

        auto result = env->NewObject(jni_refs::session::c_completion_info,
                                     jni_refs::session::m_completion_info_ctor,
                                     jGenerationPrompt,
                                     static_cast<jboolean>(info.supports_thinking),
                                     jThinkingStartTag,
                                     jThinkingEndTag,
                                     static_cast<jint>(info.n_tokens_cached),
                                     static_cast<jint>(info.n_tokens_ingested));

        if (jGenerationPrompt) env->DeleteLocalRef(jGenerationPrompt);
        if (jThinkingStartTag) env->DeleteLocalRef(jThinkingStartTag);
        if (jThinkingEndTag) env->DeleteLocalRef(jThinkingEndTag);

        return result;
    } catch (const result_code_error &ex) {
        throwResultCode(env, ex.code);
    } catch (const std::exception &) {
        throwResultCode(env, ResultCode::UNKNOWN);
    }
    return nullptr;
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
