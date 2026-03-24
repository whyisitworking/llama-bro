#pragma once

#include <string>
#include <optional>

namespace parsers {
    static constexpr unsigned char CONTINUATION_MASK = 0xC0;
    static constexpr unsigned char CONTINUATION_VAL = 0x80;

    // Masks to identify leading bytes and their expected lengths
    static constexpr unsigned char LEAD2_MASK = 0xE0; // 110xxxxx
    static constexpr unsigned char LEAD2_VAL = 0xC0;
    static constexpr unsigned char LEAD3_MASK = 0xF0; // 1110xxxx
    static constexpr unsigned char LEAD3_VAL = 0xE0;
    static constexpr unsigned char LEAD4_MASK = 0xF8; // 11110xxx
    static constexpr unsigned char LEAD4_VAL = 0xF0;

    struct TokenParser {
    public:
        explicit TokenParser() {
            token_buffer.reserve(32);
        }

        std::optional<std::u16string> parse(std::string_view text) {
            if (token_buffer.empty()) {
                if (count_incomplete_tail_bytes(text) == 0) {
                    return utf8_to_utf16(text);
                }
            }

            token_buffer.append(text);

            auto incomplete = count_incomplete_tail_bytes(token_buffer);
            auto complete_len = token_buffer.size() - incomplete;

            if (complete_len > 0) {
                auto result = utf8_to_utf16(std::string_view(token_buffer).substr(0, complete_len));
                token_buffer.erase(0, complete_len);
                return result;
            }

            return std::nullopt;
        }

        void reset() {
            token_buffer.clear();
        }

    private:
        std::string token_buffer;

        static size_t count_incomplete_tail_bytes(std::string_view token) {
            if (token.empty()) return 0;

            if ((token.back() & CONTINUATION_VAL) == 0) {
                return 0;
            }

            auto size = token.size();
            for (size_t i = 1; i <= 4 && i <= size; ++i) {
                auto c = static_cast<unsigned char>(token[size - i]);

                // If we found the Lead Byte (not 10xxxxxx)
                if ((c & CONTINUATION_MASK) != CONTINUATION_VAL) {
                    size_t expected = 1;
                    if ((c & LEAD2_MASK) == LEAD2_VAL) expected = 2;
                    else if ((c & LEAD3_MASK) == LEAD3_VAL) expected = 3;
                    else if ((c & LEAD4_MASK) == LEAD4_VAL) expected = 4;

                    return (i < expected) ? i : 0;
                }
            }
            return 0;
        }

        static std::u16string utf8_to_utf16(std::string_view utf8) {
            if (utf8.empty()) return {};

            std::u16string utf16;
            // Optimization 1: Pre-allocate memory.
            // UTF-16 will never have more code units than UTF-8 has bytes.
            utf16.reserve(utf8.size());

            auto cur = reinterpret_cast<const unsigned char *>(utf8.data());
            auto end = cur + utf8.size();

            while (cur < end) {
                auto c = *cur;

                // Optimization 2: The ASCII Fast-Path
                // Most LLM tokens are simple characters. This branch is highly predictable.
                if (c < CONTINUATION_VAL) {
                    utf16.push_back(static_cast<char16_t>(c));
                    cur++;
                    continue;
                }

                // Multi-byte sequences
                uint32_t cp = 0;
                int len = 0;

                if ((c & LEAD2_MASK) == LEAD2_VAL) {
                    cp = c & 0x1F;
                    len = 2;
                } else if ((c & LEAD3_MASK) == LEAD3_VAL) {
                    cp = c & 0x0F;
                    len = 3;
                } else if ((c & LEAD4_MASK) == LEAD4_VAL) {
                    cp = c & 0x07;
                    len = 4;
                } else {
                    // Invalid UTF-8 lead byte: use replacement character
                    utf16.push_back(u'\uFFFD');
                    cur++;
                    continue;
                }

                if (cur + len > end) {
                    utf16.push_back(u'\uFFFD');
                    break;
                }

                // Unroll the continuation byte checks for speed
                bool valid = true;
                for (int i = 1; i < len; ++i) {
                    if ((cur[i] & CONTINUATION_MASK) != CONTINUATION_VAL) {
                        valid = false;
                        break;
                    }
                    cp = (cp << 6) | (cur[i] & 0x3F);
                }

                if (!valid) {
                    utf16.push_back(u'\uFFFD');
                    cur++;
                    continue;
                }

                // Optimization 3: Handle Surrogate Pairs for 4-byte UTF-8
                if (cp <= 0xFFFF) {
                    utf16.push_back(static_cast<char16_t>(cp));
                } else {
                    // Codepoint > 0xFFFF requires two 16-bit units (Surrogate Pair)
                    cp -= 0x10000;
                    utf16.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
                    utf16.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
                }
                cur += len;
            }

            return utf16;
        }
    };
}
