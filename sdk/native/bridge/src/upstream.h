// Copies of static helpers from llama.cpp b12352 tools/server/server.cpp, which server-context doesn't export.
#pragma once

#include "server-http.h"

#include "common.h"

// tools/server/server.cpp:61 (word for word)
server_http_context::handler_t ex_wrapper(server_http_context::handler_t func);

// tools/server/server.cpp:115, role SERVER_ROLE_INSTANCE; --connect is refused (it lives in llama-server-impl)
bool server_prepare_params(common_params & params);
