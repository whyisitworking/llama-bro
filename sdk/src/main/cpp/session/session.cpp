#include "session.hpp"

#include "batch.hpp"
#include "result/codes.hpp"
#include "utils/log.hpp"

#include <vector>
#include <exception>

#include "llama.h"

namespace session {
    namespace constants {
        constexpr int STRATEGY_ID_HALT = 0;
        constexpr int STRATEGY_ID_CLEAR_HISTORY = 1;
        constexpr int STRATEGY_ID_ROLLING_WINDOW = 2;
    }

    static llama_sampler *create_sampler(llama_model *model, const NativeInferenceParams &params) {
        auto chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
        auto vocab = llama_model_get_vocab(model);
        auto n_ctx_train = llama_model_n_ctx_train(model);

        auto top_p = std::clamp(params.top_p, 0.0f, 1.0f);
        auto temp = std::max(params.temp, 0.0f);
        auto top_k = std::max(params.top_k, 0);

        if (params.repeat_penalty != 1.0f ||
            params.frequency_penalty != 0.0f ||
            params.presence_penalty != 0.0f) {

            llama_sampler_chain_add(
                    chain,
                    llama_sampler_init_penalties(
                            std::min(params.penalty_last_n, 128),
                            params.repeat_penalty,
                            params.frequency_penalty,
                            params.presence_penalty
                    )
            );
        }

        if (params.dry_multiplier > 0.0f) {
            static const char *breakers[] = {"\n", ":", "\""};

            llama_sampler_chain_add(
                    chain,
                    llama_sampler_init_dry(
                            vocab,
                            n_ctx_train,
                            params.dry_multiplier,
                            params.dry_base,
                            params.dry_allowed_length,
                            params.dry_penalty_last_n,
                            breakers,
                            sizeof(breakers) / sizeof(breakers[0])
                    )
            );
        }

        if (params.top_n_sigma > 0.0f) {
            llama_sampler_chain_add(
                    chain,
                    llama_sampler_init_top_n_sigma(params.top_n_sigma)
            );
        }

        if (top_k > 0) {
            llama_sampler_chain_add(
                    chain,
                    llama_sampler_init_top_k(top_k)
            );
        }

        if (params.typ_p < 1.0f) {
            llama_sampler_chain_add(
                    chain,
                    llama_sampler_init_typical(params.typ_p, 1)
            );
        }

        if (top_p < 1.0f) {
            llama_sampler_chain_add(
                    chain,
                    llama_sampler_init_top_p(top_p, 1)
            );
        }

        if (params.min_p > 0.0f) {
            llama_sampler_chain_add(
                    chain,
                    llama_sampler_init_min_p(params.min_p, 1)
            );
        }

        if (temp <= 0.0f || top_k == 1) {
            llama_sampler_chain_add(
                    chain,
                    llama_sampler_init_greedy()
            );
        } else {
            llama_sampler_chain_add(
                    chain,
                    llama_sampler_init_temp(temp)
            );

            llama_sampler_chain_add(
                    chain,
                    llama_sampler_init_dist(params.seed)
            );
        }

        return chain;
    }

    static llama_context_params get_context_init_params(const llama_model *model,
                                                        const NativeSessionParams &config) {
        auto params = llama_context_default_params();
        params.n_ctx = config.context_size;

        // Clamp to training context to avoid OOM and RoPE degradation on mobile.
        auto n_ctx_train = static_cast<uint32_t>(llama_model_n_ctx_train(model));
        if (params.n_ctx > n_ctx_train) {
            LOGW("Requested context size %u exceeds model training context %u; clamping.",
                 params.n_ctx, n_ctx_train);
            params.n_ctx = n_ctx_train;
        }

        params.n_threads = config.threads;
        params.n_threads_batch = config.threads;
        params.n_batch = config.batch_size;
        params.n_ubatch = config.micro_batch_size;
        params.n_seq_max = 1;
        params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        params.type_k = GGML_TYPE_Q8_0; // Saves 50% space with very little loss
        params.type_v = GGML_TYPE_Q8_0;

        return params;
    }

    static OverflowStrategy decide_overflow_strategy(const llama_context *ctx,
                                                     const NativeSessionParams &config) {
        switch (config.overflow_strategy_id) {
            case constants::STRATEGY_ID_HALT:
                return HALT;
            case constants::STRATEGY_ID_CLEAR_HISTORY:
                return CLEAR_HISTORY;
            case constants::STRATEGY_ID_ROLLING_WINDOW:
            default:
                if (llama_memory_can_shift(llama_get_memory(ctx))) {
                    return ROLLING_WINDOW;
                } else {
                    return CLEAR_HISTORY;
                }
        }
    }

    // Helper: compute common prefix length between two token vectors
    static int32_t get_common_prefix(const std::vector<llama_token> &a,
                                     const std::vector<llama_token> &b) {
        const auto max_idx = std::min(a.size(), b.size());
        for (size_t i = 0; i < max_idx; ++i) {
            if (a[i] != b[i]) return static_cast<int32_t>(i);
        }
        return static_cast<int32_t>(max_idx);
    }

    Session::Session(llama_model *model,
                     const NativeSessionParams &params) {
        auto ctx = llama_init_from_model(model, get_context_init_params(model, params));

        if (!ctx) {
            throw result_code_error(ResultCode::CONTEXT_INIT_FAILED);
        }

        auto system_info = llama_print_system_info();
        LOGI("Initialized llama context with system info:\n%s", system_info);

        llama_model_ptr = model;
        llama_context.reset(ctx);
        llama_sampler_chain.reset(create_sampler(model, params.inference_params));
        llama_batch = llama_batch_init(static_cast<int32_t>(llama_n_batch(ctx)), 0, 1);

        overflow_strategy = decide_overflow_strategy(ctx, params);
        n_drop = params.overflow_drop_tokens;
    }

    Session::~Session() {
        llama_batch_free(llama_batch);
    }

    Generation Session::generate() {
        auto ctx = llama_context.get();
        auto model = llama_get_model(ctx);
        auto vocab = llama_model_get_vocab(model);
        auto sampler = llama_sampler_chain.get();
        auto result = ResultCode::OK;

        is_aborted.store(false);

        while (true) {
            if (is_aborted.load()) {
                result = ResultCode::CANCELLED;
                break;
            }

            auto new_token = llama_sampler_sample(sampler, ctx, -1);
            auto is_end_token = llama_vocab_is_eog(vocab, new_token);

            if (!roll_kv_cache_if_needed(1)) {
                result = ResultCode::CONTEXT_OVERFLOW;
                break;
            }

            batch_clear(llama_batch);
            batch_add(llama_batch, new_token, n_past, !is_end_token);

            result = decode_current_batch();
            if (result == ResultCode::OK) {
                n_past += 1;
                cached_tokens.push_back(new_token);
            } else {
                break;
            }

            if (is_end_token) {
                token_parser.reset();
                break;
            }

            auto piece = token_to_piece(vocab, new_token, true);
            auto token = token_parser.parse(piece);

            if (token.has_value()) {
                return {
                        .token = token,
                        .result_code = ResultCode::OK,
                        .is_complete = false,
                };
            }
        }

        return {
                .token = std::nullopt,
                .result_code = result,
                .is_complete = true,
        };
    }

    void Session::clear() {
        roll_kv_cache_till_system_prompt();
    }

    void Session::abort() {
        is_aborted.store(true);
    }

    void Session::update_sampler(const NativeInferenceParams &params) {
        llama_sampler_chain.reset(create_sampler(llama_model_ptr, params));
    }

    // ── Chat template methods ────────────────────────────────────────────────

    ChatTemplateInfo Session::init_chat_templates() {
        chat_templates = common_chat_templates_init(llama_model_ptr,
                                                    /* chat_template_override= */ "");

        // Probe template capabilities by doing a dummy apply
        common_chat_templates_inputs probe_inputs;
        probe_inputs.messages = {{.role = "user", .content = "test"}};
        probe_inputs.add_generation_prompt = true;
        probe_inputs.use_jinja = true;
        probe_inputs.enable_thinking = true;

        auto probe_result = common_chat_templates_apply(chat_templates.get(), probe_inputs);

        LOGI("Chat template initialized: supports_thinking=%d, thinking_start='%s', thinking_end='%s', generation_prompt='%s'",
             probe_result.supports_thinking,
             probe_result.thinking_start_tag.c_str(),
             probe_result.thinking_end_tag.c_str(),
             probe_result.generation_prompt.c_str());

        return {
                .supports_thinking = probe_result.supports_thinking,
                .thinking_start_tag = probe_result.thinking_start_tag,
                .thinking_end_tag = probe_result.thinking_end_tag,
        };
    }

    CompletionInfo Session::begin_completion(
            const std::vector<common_chat_msg> &messages,
            bool enable_thinking) {

        if (!chat_templates) {
            throw result_code_error(ResultCode::CONTEXT_INIT_FAILED);
        }

        if (messages.empty()) {
            throw result_code_error(ResultCode::DECODE_FAILED);
        }

        // 1. Format all messages via Jinja template
        common_chat_templates_inputs inputs;
        inputs.messages = messages;
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        inputs.enable_thinking = enable_thinking;

        auto result = common_chat_templates_apply(chat_templates.get(), inputs);

        // 2. Tokenize the full formatted prompt
        auto ctx = llama_context.get();
        auto model = llama_get_model(ctx);
        auto vocab = llama_model_get_vocab(model);
        auto n_ctx = llama_n_ctx(ctx);
        auto should_add_bos = llama_vocab_get_add_bos(vocab);

        auto input_tokens = tokenize(vocab, result.prompt, should_add_bos, true);

        if (input_tokens.empty()) {
            throw result_code_error(ResultCode::DECODE_FAILED);
        }

        if (input_tokens.size() > n_ctx) {
            throw result_code_error(ResultCode::CONTEXT_OVERFLOW);
        }

        // 3. Token-level prefix matching (same pattern as llama.cpp server)
        auto n_common = get_common_prefix(cached_tokens, input_tokens);

        LOGI("begin_completion: input_tokens=%zu, cached=%zu, n_common=%d, generation_prompt='%s'",
             input_tokens.size(), cached_tokens.size(), n_common,
             result.generation_prompt.c_str());

        // 4. Truncate KV cache on divergence
        if (n_common < n_past) {
            clear_kv_cache(n_common, -1);
            n_past = n_common;
            // cached_tokens is updated inside clear_kv_cache
        }

        // 5. Compute n_keep: protect system prompt tokens from rolling eviction.
        //    Format just the system message to find its token boundary.
        if (!messages.empty() && messages[0].role == "system") {
            common_chat_templates_inputs sys_inputs;
            sys_inputs.messages = {messages[0]};
            sys_inputs.add_generation_prompt = false;
            sys_inputs.use_jinja = true;
            sys_inputs.enable_thinking = enable_thinking;

            try {
                auto sys_prompt = common_chat_templates_apply(
                        chat_templates.get(), sys_inputs).prompt;
                auto sys_tokens = tokenize(vocab, sys_prompt, should_add_bos, true);
                n_keep = static_cast<int32_t>(sys_tokens.size());
            } catch (...) {
                // Some templates require a user message — fall back to 0
                n_keep = 0;
            }
        } else {
            n_keep = 0;
        }

        // 6. Ingest only new tokens (from n_past to end of input_tokens)
        auto n_new = static_cast<int32_t>(input_tokens.size()) - n_past;

        if (n_new > 0) {
            auto ingest_result = ingest_tokens(
                    input_tokens.data() + n_past, n_new);
            if (ingest_result != ResultCode::OK) {
                throw result_code_error(ingest_result);
            }
        }

        return {
                .generation_prompt = result.generation_prompt,
                .supports_thinking = result.supports_thinking,
                .thinking_start_tag = result.thinking_start_tag,
                .thinking_end_tag = result.thinking_end_tag,
                .n_tokens_cached = n_common,
                .n_tokens_ingested = n_new,
        };
    }

    // ── Private ──────────────────────────────────────────────────────────────

    ResultCode Session::ingest_tokens(const llama_token *tokens, int32_t count) {
        is_aborted.store(false);

        auto ctx = llama_context.get();
        auto n_batch_limit = llama_n_batch(ctx);

        for (int32_t i = 0; i < count; i += static_cast<int32_t>(n_batch_limit)) {
            if (is_aborted.load()) {
                return ResultCode::CANCELLED;
            }

            auto chunk_size = std::min(
                    static_cast<int32_t>(n_batch_limit),
                    count - i);

            if (!roll_kv_cache_if_needed(static_cast<uint32_t>(chunk_size))) {
                return ResultCode::CONTEXT_OVERFLOW;
            }

            batch_clear(llama_batch);
            for (int32_t j = 0; j < chunk_size; j++) {
                auto token_pos = static_cast<llama_pos>(n_past + j);
                auto is_last = (i + j == count - 1);
                batch_add(llama_batch, tokens[i + j], token_pos, is_last);
            }

            auto decode_result = decode_current_batch();
            if (decode_result != ResultCode::OK) {
                return decode_result;
            }

            // Update cached_tokens and n_past
            cached_tokens.insert(cached_tokens.end(),
                                 tokens + i, tokens + i + chunk_size);
            n_past += chunk_size;
        }

        return ResultCode::OK;
    }

    ResultCode Session::ingest_prompt(std::string_view text, bool reset_sequence) {
        if (reset_sequence) {
            clear_kv_cache(0, -1);
            n_past = n_keep = 0;
        }

        auto ctx = llama_context.get();
        auto model = llama_get_model(ctx);
        auto vocab = llama_model_get_vocab(model);
        auto n_ctx = llama_n_ctx(ctx);
        auto should_add_bos = llama_vocab_get_add_bos(vocab);

        auto tokens = tokenize(vocab, text, should_add_bos, true);

        if (tokens.empty()) {
            return ResultCode::OK;
        }

        if (tokens.size() > n_ctx) {
            return ResultCode::CONTEXT_OVERFLOW;
        }

        auto result = ingest_tokens(tokens.data(), static_cast<int32_t>(tokens.size()));

        if (result == ResultCode::OK && reset_sequence) {
            n_keep = n_past;
        }

        return result;
    }

    ResultCode Session::decode_current_batch() {
        auto ctx = llama_context.get();
        auto decode_result = llama_decode(ctx, llama_batch);

        if (decode_result == 0) {
            return ResultCode::OK;
        }

        if (decode_result == 1) {
            return ResultCode::CONTEXT_OVERFLOW;
        }

        if (decode_result == 2 || decode_result < -1) {
            // Partial batches might remain in memory, need to clear till last point
            clear_kv_cache(n_past, -1);
        }

        return ResultCode::DECODE_FAILED;
    }

    void Session::clear_kv_cache(int32_t start_pos, int32_t end_pos) {
        auto ctx = llama_context.get();
        auto memory = llama_get_memory(ctx);

        llama_memory_seq_rm(memory, 0, start_pos, end_pos);

        // Keep cached_tokens in sync
        if (end_pos == -1) {
            cached_tokens.erase(
                    cached_tokens.begin() + start_pos,
                    cached_tokens.end());
        } else {
            cached_tokens.erase(
                    cached_tokens.begin() + start_pos,
                    cached_tokens.begin() + end_pos);
        }
    }

    bool Session::roll_kv_cache_if_needed(uint32_t required_tokens) {
        auto ctx = llama_context.get();
        auto n_ctx = llama_n_ctx(ctx);

        if (n_past + required_tokens <= n_ctx) {
            return true;
        }

        switch (overflow_strategy) {
            case HALT:
                return false;

            case CLEAR_HISTORY:
                roll_kv_cache_till_system_prompt();
                return true;

            case ROLLING_WINDOW:
            default:
                return roll_kv_cache_to_accommodate(required_tokens);
        }
    }

    void Session::roll_kv_cache_till_system_prompt() {
        clear_kv_cache(n_keep, -1);
        n_past = n_keep;
        // cached_tokens already truncated inside clear_kv_cache
    }

    bool Session::roll_kv_cache_to_accommodate(uint32_t required_tokens) {
        auto ctx = llama_context.get();
        auto n_ctx = llama_n_ctx(ctx);
        auto memory = llama_get_memory(ctx);

        while (n_past + required_tokens > n_ctx) {
            auto safe_drop = std::min(n_drop, n_past - n_keep);
            if (safe_drop <= 0) {
                return false; // Cannot drop enough tokens without eating system prompt
            }

            clear_kv_cache(n_keep, n_keep + safe_drop);
            llama_memory_seq_add(memory, 0, n_keep + safe_drop, -1, -safe_drop);

            n_past -= safe_drop;
            // cached_tokens already updated by clear_kv_cache
        }

        return true;
    }
}
