// Helpers shared by the bridge's translation units.
#pragma once

#include "json.h"  // llama.cpp common/json.h: the JSON type server-context uses

#include <string>

using json = common_json;  // same alias as tools/server/server-common.h

char * lb_strdup(const std::string & s);                            // malloc'd copy; free with lb_string_free
void lb_set_error(char ** error_json, const std::string & message); // {"error": message}
bool lb_runtime_is_initialized();
