#include "session.h"

#include "tools/mtmd/mtmd.h"
#include "utils/llama_utils.h"
#include "utils/utf8_utils.h"

#include <exception>
#include <vector>

#include "llama.h"

LlamaSession::LlamaSession(llama_model *model, int threads, const NativeSessionParams &config) {
    auto params = llama_context_default_params();

    params.n_ctx = config.context_size;
    params.n_threads = threads;
    params.n_threads_batch = threads;
    params.n_batch = 2048;
    params.n_ubatch = 512;
    params.n_seq_max = 1;
    params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    params.type_k = GGML_TYPE_Q8_0;
    params.type_v = GGML_TYPE_Q8_0;

    auto ctx = llama_init_from_model(model, params);

    if (!ctx) {
        throw std::runtime_error("Failed to create llama context");
    }

    auto sampler_chain = llama_sampler_chain_init(llama_sampler_chain_default_params());

    if (config.top_k_enabled) {
        // Hard limit: Keep only the top K tokens
        llama_sampler_chain_add(sampler_chain,
                                llama_sampler_init_top_k(config.top_k));
    }

    if (config.top_p_enabled) {
        // Nucleus limit: Keep tokens until cumulative probability hits P
        llama_sampler_chain_add(sampler_chain,
                                llama_sampler_init_top_p(config.top_p, 1));
    }

    if (config.min_p_enabled) {
        // Dynamic limit: Keep tokens with probability >= (minP * max_probability)
        llama_sampler_chain_add(sampler_chain,
                                llama_sampler_init_min_p(config.min_p, 1));
    }

    if (config.rep_pen_enabled) {
        llama_sampler_chain_add(sampler_chain,
                                llama_sampler_init_penalties(
                                        static_cast<int32_t>(config.context_size / 2),
                                        config.rep_pen,
                                        0.05f,
                                        0.05f
                                ));
    }

    if (config.temp_enabled) {
        llama_sampler_chain_add(sampler_chain, llama_sampler_init_temp(config.temp));
    }

    if (config.temp_enabled && config.temp == 0.0f) {
        llama_sampler_chain_add(sampler_chain,
                                llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler_chain,
                                llama_sampler_init_dist(config.seed));
    }

    llama_context.reset(ctx);
    llama_sampler_chain.reset(sampler_chain);
    llama_batch = llama_batch_init(static_cast<int32_t>(llama_n_batch(ctx)), 0, 1);

    switch (config.overflow_strategy_id) {
        case 0:
            overflow_strategy = HALT;
            break;
        case 1:
            overflow_strategy = CLEAR_HISTORY;
            break;
        case 2:
            overflow_strategy = ROLLING_WINDOW;
            n_drop = config.overflow_drop_tokens;
            break;
        default:
            overflow_strategy = ROLLING_WINDOW;
            n_drop = 500; // TODO: make this configurable
            break;
    }
}

LlamaSession::~LlamaSession() {
    llama_batch_free(llama_batch);
}

bool LlamaSession::roll_kv_cache_if_needed(uint32_t required_tokens) {
    auto ctx = llama_context.get();
    auto n_ctx = llama_n_ctx(ctx);

    if (n_past + required_tokens <= n_ctx) {
        return true;
    }

    switch (overflow_strategy) {
        case HALT: {
            return false;
        }

        case CLEAR_HISTORY: {
            // Wipe everything EXCEPT the protected system prompt.
            // Passing -1 as the end position tells it to delete until the end of the cache.
            llama_memory_seq_rm(llama_get_memory(ctx), 0, n_keep, -1);

            // Reset our tracking pointer right back to the end of the system prompt.
            n_past = n_keep;
            return true;
        }

        case ROLLING_WINDOW:
        default: {
            while (n_past + required_tokens > n_ctx) {
                auto safe_drop = std::min(n_drop, n_past - n_keep);

                if (safe_drop <= 0) {
                    return false;
                }

                llama_memory_seq_rm(llama_get_memory(ctx), 0, n_keep,
                                    n_keep + safe_drop);
                llama_memory_seq_add(llama_get_memory(ctx), 0, n_keep + safe_drop,
                                     -1, -safe_drop);

                n_past -= safe_drop;
            }
            return true;
        }
    }
}

bool LlamaSession::ingest_prompt(const std::string &text, bool is_system_prompt) {
    auto ctx = llama_context.get();
    auto model = llama_get_model(ctx);
    auto vocab = llama_model_get_vocab(model);

    auto tokens = utils::tokenize(vocab, text, true, true);
    if (tokens.empty()) return false;

    uint32_t n_ctx = llama_n_ctx(ctx);
    uint32_t n_batch_limit = llama_n_batch(ctx);
    uint32_t max_usable_tokens = is_system_prompt
                                 ? (n_ctx - 100) // TODO: Hardcoded
                                 : (n_ctx - n_keep - 100);

    if (tokens.size() > max_usable_tokens) {
        tokens.erase(tokens.begin(), tokens.end() - max_usable_tokens);
    }

    for (size_t i = 0; i < tokens.size(); i += n_batch_limit) {
        auto chunk_size = std::min(n_batch_limit, static_cast<uint32_t>(tokens.size() - i));

        if (!roll_kv_cache_if_needed(chunk_size)) {
            return false;
        }

        utils::batch_clear(llama_batch);
        for (int32_t j = 0; j < chunk_size; j++) {
            utils::batch_add(
                    llama_batch,
                    tokens[i + j],
                    n_past + j,
                    {0},
                    i + j == tokens.size() - 1
            );
        }

        if (llama_decode(ctx, llama_batch) != 0) {
            return false;
        }

        n_past += static_cast<int32_t>(chunk_size);
    }

    if (is_system_prompt) {
        n_keep = n_past;
    }

    return true;
}

bool LlamaSession::is_token_buffer_valid() {
    return !token_buffer.empty() && utils::llm_is_valid_utf8(token_buffer);
}

std::u16string LlamaSession::get_token_buffer_as_u16string() {
    auto result = utils::llm_utf8_to_utf16_sanitized(token_buffer);
    token_buffer.clear();
    return result;
}

bool LlamaSession::set_system_prompt(const std::string &system_prompt) {
    if (system_prompt.empty()) {
        return false;
    }

    clear();

    return ingest_prompt(system_prompt, true);
}

bool LlamaSession::prompt(const std::string &user_message) {
    return ingest_prompt(user_message, false);
}

std::optional<std::u16string> LlamaSession::generate() {
    auto ctx = llama_context.get();
    auto model = llama_get_model(ctx);
    auto vocab = llama_model_get_vocab(model);
    auto sampler = llama_sampler_chain.get();

    while (true) {
        auto new_token = llama_sampler_sample(sampler, ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token)) {
            if (is_token_buffer_valid()) {
                return get_token_buffer_as_u16string();
            }
            return std::nullopt;
        }

        auto piece = utils::token_to_piece(vocab, new_token, true);
        token_buffer.append(piece);

        if (!roll_kv_cache_if_needed(1)) {
            if (is_token_buffer_valid()) {
                return get_token_buffer_as_u16string();
            }
            return std::nullopt;
        }

        utils::batch_clear(llama_batch);
        utils::batch_add(llama_batch, new_token, n_past, {0}, true);

        if (llama_decode(ctx, llama_batch) != 0) {
            return std::nullopt;
        }

        n_past += 1;

        if (is_token_buffer_valid()) {
            return get_token_buffer_as_u16string();
        }
    }
}

void LlamaSession::clear() {
    auto ctx = llama_context.get();
    auto memory = llama_get_memory(ctx);
    llama_memory_clear(memory, true);
    n_past = 0;
    token_buffer.clear();
}
