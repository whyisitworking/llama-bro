#pragma once
#include <string>
#include <vector>
#include "llama.h"

namespace utils {
    static std::vector<llama_token> tokenize_text(
            const llama_vocab* vocab,
            const std::string& text,
            bool add_special,
            bool parse_special
    ) {
        // Step 1: Get required token count
        auto n_tokens = llama_tokenize(
                vocab, text.c_str(), static_cast<int32_t>(text.size()),
                nullptr, 0, add_special, parse_special
        );

        if (n_tokens < 0) { n_tokens = -n_tokens; }
        if (n_tokens == 0) { return {}; }

        // Step 2: Actually retrieve the tokens
        std::vector<llama_token> tokens(n_tokens);
        auto n_written = llama_tokenize(
                vocab, text.c_str(), static_cast<int32_t>(text.size()),
                tokens.data(), static_cast<int32_t>(tokens.size()),
                add_special, parse_special
        );

        if (n_written < 0) { return {}; }
        if (n_written != n_tokens) { tokens.resize(n_written); }

        return tokens;
    }
}
