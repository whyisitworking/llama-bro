#pragma once

#include <string>
#include <vector>
#include "llama.h"

namespace session {
    // Taken from common
    static void batch_clear(struct llama_batch &batch) {
        batch.n_tokens = 0;
    }

    static bool batch_add(struct llama_batch &batch,
                          llama_token id,
                          llama_pos pos,
                          bool output) {
        if (!batch.seq_id[batch.n_tokens]) {
            return false;
        }

        batch.token[batch.n_tokens] = id;
        batch.pos[batch.n_tokens] = pos;
//        Since we aren't batching yet
//        batch.n_seq_id[batch.n_tokens] = static_cast<int32_t>(seq_ids.size());
//        for (size_t i = 0; i < seq_ids.size(); ++i) {
//            batch.seq_id[batch.n_tokens][i] = seq_ids[i];
//        }
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id[batch.n_tokens][0] = 0;
        batch.logits[batch.n_tokens] = output ? 1 : 0;

        batch.n_tokens++;

        return true;
    }

    static std::vector<llama_token> tokenize(const struct llama_vocab *vocab,
                                             std::string_view text,
                                             bool add_special,
                                             bool parse_special) {
        // upper limit for the number of tokens
        auto n_tokens = text.length() + 2 * add_special;
        std::vector<llama_token> result(n_tokens);
        n_tokens = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.length()),
                                  result.data(), static_cast<int32_t>(result.size()),
                                  add_special, parse_special);
        if (n_tokens == std::numeric_limits<int32_t>::min()) {
            return {};
        }

        if (n_tokens < 0) {
            result.resize(-n_tokens);
            int check = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.length()),
                                       result.data(), static_cast<int32_t>(result.size()),
                                       add_special, parse_special);
            if (check != -n_tokens) {
                return {};
            }
        } else {
            result.resize(n_tokens);
        }
        return result;
    }

    static std::string token_to_piece(const struct llama_vocab *vocab,
                                      llama_token token, bool special) {
        std::string piece;
        piece.resize(piece.capacity());  // using string internal cache, 15 bytes + '\n'
        const int n_chars = llama_token_to_piece(vocab, token, &piece[0],
                                                 static_cast<int32_t>(piece.size()), 0, special);
        if (n_chars < 0) {
            piece.resize(-n_chars);
            int check = llama_token_to_piece(vocab, token, &piece[0],
                                             static_cast<int32_t>(piece.size()), 0, special);
            if (check != -n_chars) {
                return {};
            }
        } else {
            piece.resize(n_chars);
        }

        return piece;
    }
}
