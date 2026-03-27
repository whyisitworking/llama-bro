#pragma once

#include "jni.h"

namespace jni_refs {
    inline JavaVM *vm = nullptr;

    namespace exceptions {
        inline jclass c_runtime_exp;
    }

    namespace engine {
        inline jmethodID m_listener_on_progress;

        namespace params {
            inline constexpr const char *model_path = "modelPath";
            inline constexpr const char *threads = "threads";
            inline constexpr const char *use_m_map = "useMMap";
            inline constexpr const char *use_m_lock = "useMLock";
        }
    }

    namespace session {
        inline jclass c_chat_template_info;
        inline jmethodID m_chat_template_info_ctor;

        inline jclass c_completion_info;
        inline jmethodID m_completion_info_ctor;

        inline jfieldID f_result_token;
        inline jfieldID f_result_code;
        inline jfieldID f_result_is_complete;

        namespace classpaths {
            inline constexpr const char *inference_params =
                    "Lcom/suhel/llamabro/sdk/engine/internal/LlamaSessionImpl$NativeInferenceParams;";
        }

        namespace params {
            inline constexpr const char *context_size = "contextSize";
            inline constexpr const char *threads = "threads";
            inline constexpr const char *overflow_strategy_id = "overflowStrategyId";
            inline constexpr const char *overflow_drop_tokens = "overflowDropTokens";

            inline constexpr const char *inference_params = "inferenceParams";

            inline constexpr const char *repeat_penalty = "repeatPenalty";
            inline constexpr const char *frequency_penalty = "frequencyPenalty";
            inline constexpr const char *presence_penalty = "presencePenalty";
            inline constexpr const char *penalty_last_n = "penaltyLastN";

            inline constexpr const char *dry_multiplier = "dryMultiplier";
            inline constexpr const char *dry_base = "dryBase";
            inline constexpr const char *dry_allowed_length = "dryAllowedLength";
            inline constexpr const char *dry_penalty_last_n = "dryPenaltyLastN";

            inline constexpr const char *top_n_sigma = "topNSigma";
            inline constexpr const char *top_k = "topK";
            inline constexpr const char *typ_p = "typP";
            inline constexpr const char *top_p = "topP";
            inline constexpr const char *min_p = "minP";

            inline constexpr const char *temperature = "temperature";
            inline constexpr const char *seed = "seed";

            inline constexpr const char *batch_size = "batchSize";
            inline constexpr const char *micro_batch_size = "microBatchSize";
        }
    }

    namespace types {
        inline constexpr const char *string = "Ljava/lang/String;";
        inline constexpr const char *boolean = "Z";
        inline constexpr const char *int32 = "I";
        inline constexpr const char *float32 = "F";
        inline constexpr const char *long64 = "J";
        inline constexpr const char *double64 = "D";
    }
}
