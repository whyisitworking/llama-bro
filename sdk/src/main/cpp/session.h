#pragma once

#include "llama-cpp.h"

#include <string>
#include <optional>

enum OverflowStrategy {
    HALT,
    CLEAR_HISTORY,
    ROLLING_WINDOW,
};

struct NativeSessionParams {
    int context_size;
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
    bool is_complete;
};

#include <atomic>

class LlamaSession {
private:
    llama_context_ptr llama_context;
    llama_sampler_ptr llama_sampler_chain;
    std::atomic<bool> is_aborted{false};

    // Core Memory State
    llama_batch llama_batch{0};
    std::string token_buffer;

    int32_t n_past = 0;
    int32_t n_keep = 0;
    OverflowStrategy overflow_strategy = ROLLING_WINDOW;
    int32_t n_drop = 500;

    bool roll_kv_cache_if_needed(uint32_t required_tokens);

    void clear_kv_cache(int32_t start_pos, int32_t end_pos);

    void roll_kv_cache_till_system_prompt();

    bool roll_kv_cache_to_accommodate(uint32_t required_tokens);

    void ingest_prompt(const std::string &text, bool is_system_prompt, bool add_special);

    bool is_token_buffer_valid();

    std::u16string get_and_clear_token_buffer();

public:
    LlamaSession(llama_model *model, int threads, const NativeSessionParams &config);

    ~LlamaSession();

    LlamaSession(const LlamaSession &) = delete;

    LlamaSession(LlamaSession &&) = delete;

    LlamaSession &operator=(const LlamaSession &) = delete;

    LlamaSession &operator=(LlamaSession &&) = delete;

    void setSystemPrompt(const std::string &prompt, bool add_special);

    void ingestPrompt(const std::string &prompt, bool add_special);

    Generation generate();

    void clear();

    void abort();
};
