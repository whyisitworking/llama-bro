// Helpers shared by the bridge's translation units.
#pragma once

#include "json.h"  // llama.cpp common/json.h: the JSON type server-context uses

#include <string>

using json = common_json;  // same alias as tools/server/server-common.h

char * lb_strdup(const std::string & s);                            // malloc'd copy; free with lb_string_free
// The text of a JSON the bridge returns: invalid UTF-8 (a GGUF's metadata, a file name) is replaced, never thrown
char * lb_json_strdup(const json & j);
// {"error": message}, plus "code" when given (lb_model_load: "file_not_found", "insufficient_memory"; lb_fit_estimate: "file_not_found")
void lb_set_error(char ** error_json, const std::string & message, const char * code = nullptr);

// Collects the ERROR lines llama.cpp logs on this thread while it lives. llama.cpp reports why a model failed to
// load only in its log (llama_model_load: "error loading model: ..."), so the bridge reads it there (F51).
class lb_error_capture {
public:
    lb_error_capture();
    ~lb_error_capture();
    lb_error_capture(const lb_error_capture &) = delete;
    lb_error_capture & operator=(const lb_error_capture &) = delete;
    std::string first_line() const;  // the first error logged, without its newline; empty if none
private:
    std::string text_;
    std::string * prev_;
};
bool lb_runtime_is_initialized();

// Runs the body of an extern "C" function. A C++ exception must not leave one: past a C or JNI frame it is
// std::terminate. Anything the body does not handle itself becomes `fallback`, with its message in error_json.
template <typename R, typename F>
R lb_guard(R fallback, char ** error_json, F && body) noexcept {
    try {
        return body();
    } catch (const std::exception & e) {
        try {
            lb_set_error(error_json, std::string("internal error: ") + e.what());
        } catch (...) {
        }
    } catch (...) {
        try {
            lb_set_error(error_json, "internal error");
        } catch (...) {
        }
    }
    return fallback;
}
