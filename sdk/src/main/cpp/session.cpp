#include "session.h"

#include "llama.h"
#include "utils/llama_utils.h"
#include "utils/utf8_utils.h"
#include <exception>
#include <vector>

#define DEFAULT_BATCH_SIZE 512

LlamaSession::LlamaSession(llama_model *model, int threads, const NativeSessionParams &config) {
    auto params = llama_context_default_params();

    params.n_ctx = config.context_size;
    params.n_threads = threads;
    params.n_threads_batch = threads;
    params.n_batch = DEFAULT_BATCH_SIZE;
    params.n_ubatch = DEFAULT_BATCH_SIZE;
    params.n_seq_max = 1;
    params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

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

    this->llama_context.reset(ctx);
    this->llama_sampler_chain.reset(sampler_chain);
    this->llama_batch = llama_batch_init(static_cast<int32_t>(llama_n_batch(ctx)), 0, 1);
    this->llama_vocab = llama_model_get_vocab(model);

    switch (config.overflow_strategy_id) {
        case 0:
            this->overflow_strategy = HALT;
            break;
        case 1:
            this->overflow_strategy = CLEAR_HISTORY;
            break;
        case 2:
            this->overflow_strategy = ROLLING_WINDOW;
            this->n_drop = config.overflow_drop_tokens;
            break;
        default:
            this->overflow_strategy = ROLLING_WINDOW;
            this->n_drop = 500; // TODO: make this configurable
            break;
    }
}

LlamaSession::~LlamaSession() {
    llama_batch_free(this->llama_batch);
}

bool LlamaSession::roll_kv_cache_if_needed(uint32_t required_tokens) {
    auto ctx = this->llama_context.get();
    uint32_t n_ctx = llama_n_ctx(ctx);

    // If we have enough room, just proceed.
    if (this->n_past + required_tokens <= n_ctx) {
        return true;
    }

    // Context is full. Execute the requested overflow strategy.
    switch (this->overflow_strategy) {

        case 0: { // Strategy: Halt
            // We cannot make space. Tell the caller to abort generation/ingestion.
            return false;
        }

        case 1: { // Strategy: Clear History
            // Wipe everything EXCEPT the protected system prompt.
            // Passing -1 as the end position tells it to delete until the end of the cache.
            llama_memory_seq_rm(llama_get_memory(ctx), 0, this->n_keep, -1);

            // Reset our tracking pointer right back to the end of the system prompt.
            this->n_past = this->n_keep;
            return true;
        }

        case 2: // Strategy: Rolling Window
        default: {
            while (this->n_past + required_tokens > n_ctx) {
                int32_t safe_drop = std::min(this->n_drop, this->n_past - this->n_keep);

                // Edge case: If the system prompt itself fills the entire context,
                // we can't roll without deleting it. Halt instead.
                if (safe_drop <= 0) return false;

                llama_memory_seq_rm(llama_get_memory(ctx), 0, this->n_keep,
                                    this->n_keep + safe_drop);
                llama_memory_seq_add(llama_get_memory(ctx), 0, this->n_keep + safe_drop, -1,
                                     -safe_drop);

                this->n_past -= safe_drop;
            }
            return true;
        }
    }
}

void LlamaSession::ingest_prompt(const std::string &text, bool is_system_prompt) {
    auto tokens = utils::tokenize_text(this->llama_vocab, text, true, true);
    if (tokens.empty()) return;

    auto ctx = this->llama_context.get();
    uint32_t n_ctx = llama_n_ctx(ctx);
    uint32_t n_batch_limit = llama_n_batch(ctx);

    // --- Dynamic Trimming ---
    // If it's the system prompt, we have the full context window available.
    // If it's a user prompt, we must respect the locked system prompt (n_keep).
    uint32_t max_usable_tokens = is_system_prompt
                                 ? (n_ctx - 100) // TODO: Hardcoded
                                 : (n_ctx - this->n_keep - 100);

    if (tokens.size() > max_usable_tokens) {
        tokens.erase(tokens.begin(), tokens.end() - max_usable_tokens);
    }

    // --- Chunked Decoding Loop ---
    for (size_t i = 0; i < tokens.size(); i += n_batch_limit) {
        auto chunk_size = std::min(n_batch_limit, static_cast<uint32_t>(tokens.size() - i));

        if (!roll_kv_cache_if_needed(chunk_size)) {
            break; // Halt strategy triggered
        }

        this->llama_batch.n_tokens = 0;
        for (int32_t j = 0; j < chunk_size; j++) {
            this->llama_batch.token[this->llama_batch.n_tokens] = tokens[i + j];
            this->llama_batch.pos[this->llama_batch.n_tokens] = this->n_past + j;
            this->llama_batch.n_seq_id[this->llama_batch.n_tokens] = 1;
            this->llama_batch.seq_id[this->llama_batch.n_tokens][0] = 0;

            this->llama_batch.logits[this->llama_batch.n_tokens] =
                    (i + j == tokens.size() - 1) ? 1 : 0;

            this->llama_batch.n_tokens++;
        }

        if (llama_decode(ctx, this->llama_batch) != 0) {
            return; // Decode failed
        }

        this->n_past += static_cast<int32_t>(chunk_size);
    }

    // --- The KV Cache Lock ---
    // If we just ingested the system prompt, protect these tokens from ever being rolled.
    if (is_system_prompt) {
        this->n_keep = this->n_past;
    }
}

void LlamaSession::set_system_prompt(const std::string &system_prompt) {
    if (system_prompt.empty()) return;

    this->clear();

    ingest_prompt(system_prompt, true);
}

void LlamaSession::prompt(const std::string &user_message) {
    ingest_prompt(user_message, false);
}

std::optional<std::u16string> LlamaSession::generate() {
    auto ctx = this->llama_context.get();

    while (true) {
        auto new_token = llama_sampler_sample(this->llama_sampler_chain.get(), ctx, -1);

        if (llama_vocab_is_eog(this->llama_vocab, new_token)) { // TODO: Repeating
            if (!this->token_buffer.empty()) {
                auto result = utils::llm_utf8_to_utf16_sanitized(token_buffer);
                this->token_buffer.clear();
                return result;
            }
            return std::nullopt;
        }

        auto n = llama_token_to_piece(this->llama_vocab, new_token, piece, sizeof(piece), 0, true);
        if (n < 0) return std::nullopt;
        this->token_buffer.append(piece, n);

        if (!roll_kv_cache_if_needed(1)) { // TODO: This is repeating a lot
            if (!this->token_buffer.empty() && utils::llm_is_valid_utf8(token_buffer)) {
                auto result = utils::llm_utf8_to_utf16_sanitized(token_buffer);
                this->token_buffer.clear();
                return result;
            }
            return std::nullopt; // Safely terminate the generation stream
        }

        this->llama_batch.token[0] = new_token;
        this->llama_batch.pos[0] = this->n_past;
        this->llama_batch.n_seq_id[0] = 1;
        this->llama_batch.seq_id[0][0] = 0;
        this->llama_batch.logits[0] = 1;
        this->llama_batch.n_tokens = 1;

        if (llama_decode(ctx, this->llama_batch) != 0) {
            return std::nullopt;
        }

        this->n_past += 1;

        if (utils::llm_is_valid_utf8(this->token_buffer)) {
            auto result = utils::llm_utf8_to_utf16_sanitized(this->token_buffer);
            this->token_buffer.clear();
            return result;
        }
    }
}

void LlamaSession::clear() {
    llama_memory_clear(llama_get_memory(this->llama_context.get()), true);
    this->n_past = 0;
    this->token_buffer.clear();
}
