#pragma once

#include <string>

namespace utils {
    static bool llm_is_valid_utf8(const std::string &str) {
        size_t i = 0;
        while (i < str.length()) {
            auto c = static_cast<unsigned char>(str[i]);
            int len = 0;
            if (c < 0x80) {
                len = 1;
            } else if ((c & 0xE0) == 0xC0) {
                len = 2;
            } else if ((c & 0xF0) == 0xE0) {
                len = 3;
            } else if ((c & 0xF8) == 0xF0) {
                len = 4;
            } else {
                return false;
            }

            if (i + len > str.length()) {
                return false;
            }
            for (int j = 1; j < len; ++j) {
                if ((static_cast<unsigned char>(str[i + j]) & 0xC0) != 0x80) {
                    return false;
                }
            }
            i += len;
        }
        return true;
    }

    static std::u16string llm_utf8_to_utf16_sanitized(const std::string &utf8) {
        std::u16string out;
        size_t i = 0;

        while (i < utf8.size()) {
            uint32_t codepoint = 0xFFFD;
            auto c = static_cast<unsigned char>(utf8[i]);
            size_t remaining = utf8.size() - i;

            if (c < 0x80) {
                codepoint = c;
                i += 1;
            } else if ((c & 0xE0) == 0xC0 && remaining >= 2) {
                auto c1 = static_cast<unsigned char>(utf8[i + 1]);
                if ((c1 & 0xC0) == 0x80) {
                    codepoint = ((c & 0x1F) << 6) | (c1 & 0x3F);
                    if (codepoint < 0x80) { codepoint = 0xFFFD; }
                    i += 2;
                } else { i += 1; }
            } else if ((c & 0xF0) == 0xE0 && remaining >= 3) {
                auto c1 = static_cast<unsigned char>(utf8[i + 1]);
                auto c2 = static_cast<unsigned char>(utf8[i + 2]);
                if ((c1 & 0xC0) == 0x80 && (c2 & 0xC0) == 0x80) {
                    codepoint = ((c & 0x0F) << 12) | ((c1 & 0x3F) << 6) | (c2 & 0x3F);
                    if (codepoint < 0x800 || (codepoint >= 0xD800 && codepoint <= 0xDFFF)) {
                        codepoint = 0xFFFD;
                    }
                    i += 3;
                } else { i += 1; }
            } else if ((c & 0xF8) == 0xF0 && remaining >= 4) {
                auto c1 = static_cast<unsigned char>(utf8[i + 1]);
                auto c2 = static_cast<unsigned char>(utf8[i + 2]);
                auto c3 = static_cast<unsigned char>(utf8[i + 3]);
                if ((c1 & 0xC0) == 0x80 && (c2 & 0xC0) == 0x80 && (c3 & 0xC0) == 0x80) {
                    codepoint = ((c & 0x07) << 18) | ((c1 & 0x3F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F);
                    if (codepoint < 0x10000 || codepoint > 0x10FFFF) {
                        codepoint = 0xFFFD;
                    }
                    i += 4;
                } else { i += 1; }
            } else { i += 1; }

            if (codepoint == 0xFFFD) {
                out.push_back(u'\uFFFD');
                continue;
            }

            if (codepoint <= 0xFFFF) {
                out.push_back(static_cast<char16_t>(codepoint));
            } else {
                codepoint -= 0x10000;
                auto high = static_cast<char16_t>(0xD800 + (codepoint >> 10));
                auto low = static_cast<char16_t>(0xDC00 + (codepoint & 0x3FF));
                out.push_back(high);
                out.push_back(low);
            }
        }
        return out;
    }
}
