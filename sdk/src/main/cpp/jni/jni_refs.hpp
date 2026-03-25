#pragma once

#include "jni.h"

namespace jni_refs {
    inline JavaVM *vm = nullptr;

    namespace exceptions {
        inline jclass c_runtime_exp;
    }

    namespace session {
        inline jfieldID f_result_token;
        inline jfieldID f_result_code;
        inline jfieldID f_result_is_complete;

        inline constexpr const char *cn_native_inference_params =
                "Lcom/suhel/llamabro/sdk/engine/internal/LlamaSessionImpl$NativeInferenceParams;";

        inline constexpr const char *pn_context_size = "contextSize";
        inline constexpr const char *pn_threads = "threads";
        inline constexpr const char *pn_overflow_strategy_id = "overflowStrategyId";
        inline constexpr const char *pn_overflow_drop_tokens = "overflowDropTokens";

        inline constexpr const char *pn_inference_params = "inferenceParams";

        inline constexpr const char *pn_repeat_penalty = "repeatPenalty";
        inline constexpr const char *pn_frequency_penalty = "frequencyPenalty";
        inline constexpr const char *pn_presence_penalty = "presencePenalty";
        inline constexpr const char *pn_penalty_last_n = "penaltyLastN";

        inline constexpr const char *pn_dry_multiplier = "dryMultiplier";
        inline constexpr const char *pn_dry_base = "dryBase";
        inline constexpr const char *pn_dry_allowed_length = "dryAllowedLength";
        inline constexpr const char *pn_dry_penalty_last_n = "dryPenaltyLastN";

        inline constexpr const char *pn_top_n_sigma = "topNSigma";
        inline constexpr const char *pn_top_k = "topK";
        inline constexpr const char *pn_typ_p = "typP";
        inline constexpr const char *pn_top_p = "topP";
        inline constexpr const char *pn_min_p = "minP";

        inline constexpr const char *pn_temperature = "temperature";
        inline constexpr const char *pn_seed = "seed";

        inline constexpr const char *pn_batch_size = "batchSize";
        inline constexpr const char *pn_micro_batch_size = "microBatchSize";
    }

    namespace engine {
        inline jmethodID m_listener_on_progress;
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
