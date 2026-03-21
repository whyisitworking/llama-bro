#pragma once

#include "llama-cpp.h"

#include "parsers/tag.hpp"
#include "parsers/token.hpp"
#include "result/codes.hpp"

#include <string>
#include <vector>
#include <atomic>

namespace session {
    enum OverflowStrategy {
        HALT,
        CLEAR_HISTORY,
        ROLLING_WINDOW,
    };

    struct NativeSessionParams {
        int context_size;
        int threads;
        int overflow_strategy_id;
        int overflow_drop_tokens;

        bool top_k_enabled;
        int top_k;
        bool top_p_enabled;
        float top_p;
        bool min_p_enabled;
        float min_p;

        float rep_pen;
        float presence_pen;
        float temp;

        int seed;

        int batch_size;
        int micro_batch_size;
    };

    struct Generation {
        std::optional<std::u16string> token;
        ResultCode result;
        bool is_complete;
    };

    class Session {
    public:
        Session(llama_model *model, const NativeSessionParams &config);

        ~Session();

        Session(const Session &) = delete;

        Session(Session &&) = delete;

        Session &operator=(const Session &) = delete;

        Session &operator=(Session &&) = delete;

        ResultCode add_user_prompt(std::string_view prompt);

        ResultCode set_system_prompt(std::string_view prompt);

        Generation generate();

        void clear();

        void abort();

    private:
        llama_context_ptr llama_context;
        llama_sampler_ptr llama_sampler_chain;
        std::atomic<bool> is_aborted{false};

        // Core Memory State
        llama_batch llama_batch{0};
        parsers::TokenParser token_parser;

        int32_t n_past = 0;
        int32_t n_keep = 0;
        int32_t n_drop = 500;
        OverflowStrategy overflow_strategy = ROLLING_WINDOW;

        ResultCode ingest_prompt(std::string_view prompt, bool reset_sequence);

        ResultCode decode_current_batch();

        void clear_kv_cache(int32_t start_pos, int32_t end_pos);

        bool roll_kv_cache_if_needed(uint32_t required_tokens);

        void roll_kv_cache_till_system_prompt();

        bool roll_kv_cache_to_accommodate(uint32_t required_tokens);
    };
}
