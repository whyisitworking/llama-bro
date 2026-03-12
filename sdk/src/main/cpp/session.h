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
    std::string system_prompt;
    int overflow_strategy_id;
    int overflow_drop_tokens;
    bool top_k_enabled;
    int top_k;
    bool top_p_enabled;
    float top_p;
    bool min_p_enabled;
    float min_p;
    bool rep_pen_enabled;
    float rep_pen;
    bool temp_enabled;
    float temp;
    int seed;
};


class LlamaSession {
private:
    llama_context_ptr llama_context;
    llama_sampler_ptr llama_sampler_chain;

    // Core Memory State
    llama_batch llama_batch{0};
    std::string token_buffer;

    int32_t n_past = 0;
    int32_t n_keep = 0;
    OverflowStrategy overflow_strategy = ROLLING_WINDOW;
    int32_t n_drop = 500;

    bool roll_kv_cache_if_needed(uint32_t required_tokens);
    bool ingest_prompt(const std::string &text, bool is_system_prompt);
    bool is_token_buffer_valid();
    std::u16string get_token_buffer_as_u16string();

public:
    LlamaSession(llama_model *model, int threads, const NativeSessionParams &config);
    ~LlamaSession();

    LlamaSession(const LlamaSession &) = delete;

    LlamaSession(LlamaSession &&) = delete;

    LlamaSession &operator=(const LlamaSession &) = delete;

    LlamaSession &operator=(LlamaSession &&) = delete;

    bool set_system_prompt(const std::string &system_prompt);

    bool prompt(const std::string &prompt);

    std::optional<std::u16string> generate();

    void clear();

    void set_n_keep(int32_t keep) { this->n_keep = keep; }
};
