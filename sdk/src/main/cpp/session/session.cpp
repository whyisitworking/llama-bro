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

    static llama_sampler *create_sampler(const NativeSessionParams &config) {
        auto sampler_chain = llama_sampler_chain_init(llama_sampler_chain_default_params());

        // Penalties first — modify logits before any truncation so filters
        // operate on already-penalised probabilities (matches llama.cpp canonical order).
        llama_sampler_chain_add(sampler_chain,
                                llama_sampler_init_penalties(
                                        static_cast<int32_t>(config.context_size / 2),
                                        config.rep_pen,
                                        0.0f,
                                        config.presence_pen
                                )
        );

        // Optional truncation samplers
        if (config.top_k_enabled) {
            llama_sampler_chain_add(sampler_chain,
                                    llama_sampler_init_top_k(config.top_k));
        }

        if (config.top_p_enabled) {
            llama_sampler_chain_add(sampler_chain,
                                    llama_sampler_init_top_p(config.top_p, 1));
        }

        if (config.min_p_enabled) {
            llama_sampler_chain_add(sampler_chain,
                                    llama_sampler_init_min_p(config.min_p, 1));
        }

        // Temperature and final selection
        if (config.temp == 0.0f) {
            llama_sampler_chain_add(sampler_chain,
                                    llama_sampler_init_greedy());
        } else {
            llama_sampler_chain_add(sampler_chain,
                                    llama_sampler_init_temp(config.temp));
            llama_sampler_chain_add(sampler_chain,
                                    llama_sampler_init_dist(config.seed));
        }

        return sampler_chain;
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

    Session::Session(llama_model *model,
                     const NativeSessionParams &config) {
        auto ctx = llama_init_from_model(model, get_context_init_params(model, config));

        if (!ctx) {
            throw std::runtime_error("Failed to initialize llama context.");
        }

        auto system_info = llama_print_system_info();
        LOGI("Initialized llama context with system info:\n%s", system_info);

        llama_context.reset(ctx);
        llama_sampler_chain.reset(create_sampler(config));
        llama_batch = llama_batch_init(static_cast<int32_t>(llama_n_batch(ctx)), 0, 1);

        overflow_strategy = decide_overflow_strategy(ctx, config);
        n_drop = config.overflow_drop_tokens;
    }

    Session::~Session() {
        llama_batch_free(llama_batch);
    }

    ResultCode Session::set_system_prompt(std::string_view prompt) {
        return ingest_prompt(prompt, true);
    }

    ResultCode Session::add_user_prompt(std::string_view prompt) {
        return ingest_prompt(prompt, false);
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


    //  -------- private ---------------

    ResultCode Session::ingest_prompt(std::string_view text, bool reset_sequence) {
        is_aborted.store(false);

        if (reset_sequence) {
            clear_kv_cache(0, -1);
            n_keep = 0;
        }

        auto ctx = llama_context.get();
        auto model = llama_get_model(ctx);
        auto vocab = llama_model_get_vocab(model);
        auto n_ctx = llama_n_ctx(ctx);
        auto n_batch_limit = llama_n_batch(ctx);
        auto should_add_bos = llama_vocab_get_add_bos(vocab);

        auto tokens = tokenize(vocab, text, should_add_bos, true);

        if (tokens.empty()) {
            return ResultCode::OK;
        }

        if (tokens.size() > n_ctx) {
            return ResultCode::CONTEXT_OVERFLOW;
        }

        for (size_t i = 0; i < tokens.size(); i += n_batch_limit) {
            if (is_aborted.load()) {
                return ResultCode::CANCELLED;
            }

            auto chunk_size = std::min(n_batch_limit, static_cast<uint32_t>(tokens.size() - i));
            if (!roll_kv_cache_if_needed(chunk_size)) {
                return ResultCode::CONTEXT_OVERFLOW;
            }

            batch_clear(llama_batch);
            for (uint32_t j = 0; j < chunk_size; j++) {
                auto token_pos = static_cast<llama_pos>(n_past + j);
                auto is_last_token = i + j == tokens.size() - 1;
                batch_add(llama_batch, tokens[i + j], token_pos, is_last_token);
            }

            auto decode_result = decode_current_batch();
            if (decode_result != ResultCode::OK) {
                return decode_result;
            }

            n_past += static_cast<int32_t>(chunk_size);
        }

        if (reset_sequence) {
            n_keep = n_past;
        }

        return ResultCode::OK;
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
        }

        return true;
    }
}
