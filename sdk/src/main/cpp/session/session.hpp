#pragma once

#include "llama-cpp.h"
#include "chat.h"

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

    struct NativeInferenceParams {
        float repeat_penalty;
        float frequency_penalty;
        float presence_penalty;
        int penalty_last_n;

        float dry_multiplier;
        float dry_base;
        int dry_allowed_length;
        int dry_penalty_last_n;

        float top_n_sigma;
        int top_k;
        float typ_p;
        float top_p;
        float min_p;

        float temp;
        int seed;
    };

    struct NativeSessionParams {
        int context_size;
        int threads;
        int overflow_strategy_id;
        int overflow_drop_tokens;

        NativeInferenceParams inference_params;

        int batch_size;
        int micro_batch_size;
    };

    struct Generation {
        std::optional<std::u16string> token;
        ResultCode result_code;
        bool is_complete;
    };

    struct ChatTemplateInfo {
        bool supports_thinking;
        std::string thinking_start_tag;
        std::string thinking_end_tag;
    };

    struct CompletionInfo {
        std::string generation_prompt;
        bool supports_thinking;
        std::string thinking_start_tag;
        std::string thinking_end_tag;
        int32_t n_tokens_cached;
        int32_t n_tokens_ingested;
    };

    class Session {
    public:
        Session(llama_model *model, const NativeSessionParams &params);

        ~Session();

        Session(const Session &) = delete;

        Session(Session &&) = delete;

        Session &operator=(const Session &) = delete;

        Session &operator=(Session &&) = delete;

        // Chat template initialization
        ChatTemplateInfo init_chat_templates();

        // Stateless completion entry point (OpenAI-style).
        // Formats all messages via Jinja, tokenizes, performs token-level prefix
        // matching against the KV cache, truncates on divergence, ingests only new tokens.
        CompletionInfo begin_completion(
                const std::vector<common_chat_msg> &messages,
                bool enable_thinking);

        Generation generate();

        void clear();

        void abort();

        void update_sampler(const NativeInferenceParams &params);

    private:
        llama_model *llama_model_ptr = nullptr; // borrowed, not owned
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

        // Token-level prompt cache: mirrors the KV cache contents.
        // Invariant: cached_tokens.size() == n_past
        std::vector<llama_token> cached_tokens;

        // Chat template state
        common_chat_templates_ptr chat_templates;

        // Shared decode loop for pre-tokenized tokens.
        ResultCode ingest_tokens(const llama_token *tokens, int32_t count);

        // Legacy: tokenize a string then ingest. Used by older code paths.
        ResultCode ingest_prompt(std::string_view prompt, bool reset_sequence);

        ResultCode decode_current_batch();

        void clear_kv_cache(int32_t start_pos, int32_t end_pos);

        bool roll_kv_cache_if_needed(uint32_t required_tokens);

        void roll_kv_cache_till_system_prompt();

        bool roll_kv_cache_to_accommodate(uint32_t required_tokens);
    };
}
