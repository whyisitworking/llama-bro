#pragma once

#include <string>
#include <string_view>
#include <vector>
#include <variant>
#include <optional>
#include <cassert>

namespace parsers {

    struct NormalContent {
        std::string text;
    };
    struct TagContent {
        int tag_id;
        std::string text;
    };
    using EmitEvent = std::variant<NormalContent, TagContent>;

    class TagParser {
    public:
        void add(int id, std::string_view start, std::string_view end) {
            assert(!feeding && "add() must not be called after feed() has begun");
            assert(!start.empty() && !end.empty());

            tags.push_back({id, std::string(start), std::string(end)});

            if (trigger_chars.find(start[0]) == std::string::npos)
                trigger_chars += start[0];
        }

        bool enter_tag(int id) {
            for (size_t i = 0; i < tags.size(); ++i) {
                if (tags[i].id == id) {
                    active_tag_idx = i;
                    return true;
                }
            }
            return false;
        }

        std::vector<EmitEvent> feed(std::string_view token) {
            feeding = true;
            buffer += token; // Combine any leftover prefix with new input
            std::string_view input = buffer;
            std::vector<EmitEvent> events;

            // Helper to merge adjacent events of the same type
            auto emit = [&](std::string_view text) {
                if (text.empty()) return;

                if (active_tag_idx) {
                    int id = tags[*active_tag_idx].id;
                    if (!events.empty()
                        && std::holds_alternative<TagContent>(events.back())
                        && std::get<TagContent>(events.back()).tag_id == id) {
                        std::get<TagContent>(events.back()).text.append(text);
                        return;
                    }
                    events.emplace_back(TagContent{id, std::string(text)});
                } else {
                    if (!events.empty() && std::holds_alternative<NormalContent>(events.back())) {
                        std::get<NormalContent>(events.back()).text.append(text);
                        return;
                    }
                    events.emplace_back(NormalContent{std::string(text)});
                }
            };

            while (!input.empty()) {
                if (active_tag_idx) {
                    const std::string &end_tag = tags[*active_tag_idx].end;
                    size_t pos = input.find(end_tag[0]);

                    if (pos == std::string_view::npos) {
                        emit(input);
                        input = {}; // Consume all
                        break;
                    }

                    emit(input.substr(0, pos));
                    input.remove_prefix(pos);

                    if (input.size() >= end_tag.size()
                        && input.substr(0, end_tag.size()) == end_tag) {
                        active_tag_idx.reset();
                        input.remove_prefix(end_tag.size());
                    } else if (end_tag.compare(0, input.size(), input) == 0) {
                        break; // Partial match at the end of input; keep in buffer
                    } else {
                        emit(input.substr(0, 1)); // False alarm: emit the trigger char
                        input.remove_prefix(1);   // and move on
                    }
                } else {
                    size_t pos = input.find_first_of(trigger_chars);

                    if (pos == std::string_view::npos) {
                        emit(input);
                        input = {};
                        break;
                    }

                    emit(input.substr(0, pos));
                    input.remove_prefix(pos);

                    bool matched = false;
                    bool partial = false;

                    for (size_t i = 0; i < tags.size(); ++i) {
                        const std::string &start_tag = tags[i].start;
                        if (input.size() >= start_tag.size() &&
                            input.substr(0, start_tag.size()) == start_tag) {
                            active_tag_idx = i;
                            input.remove_prefix(start_tag.size());
                            matched = true;
                            break;
                        } else if (start_tag.compare(0, input.size(), input) == 0) {
                            partial = true;
                        }
                    }

                    if (matched) continue;
                    if (partial) break; // Partial match; keep remainder in buffer

                    // False alarm: emit trigger char and continue
                    emit(input.substr(0, 1));
                    input.remove_prefix(1);
                }
            }

            buffer = std::string(input); // Retain any partial matches
            return events;
        }

        std::vector<EmitEvent> flush() {
            std::vector<EmitEvent> events;
            if (!buffer.empty()) {
                if (active_tag_idx) {
                    events.emplace_back(TagContent{tags[*active_tag_idx].id, std::move(buffer)});
                } else {
                    events.emplace_back(NormalContent{std::move(buffer)});
                }
                buffer.clear();
            }
            active_tag_idx.reset();
            feeding = false;
            return events;
        }

    private:
        struct Tag {
            int id;
            std::string start;
            std::string end;
        };

        std::vector<Tag> tags;
        std::optional<size_t> active_tag_idx;
        std::string trigger_chars;
        std::string buffer;
        bool feeding = false;
    };

} // namespace parsers