// The route table: every endpoint the C API names (llamabro.h), the path llama-server registers it under, the handler
// on server_routes, and how the bridge treats the request's body. Adding a route is one line in routes.cpp.
// upstream_path is passed through as written: slots_action's "/slots/:id_slot" is the registered pattern, not a
// concrete "/slots/<id>" path; the single-model handlers do not read req.path.
#pragma once

#include "server-context.h"
#include "server-http.h"

#include <map>
#include <string>

enum class lb_body {
    plain,          // the body goes to the handler as the caller wrote it
    chat,           // a chat completion's body: attachment placeholders in messages[].content[] are filled (D84)
    embeddings,     // an embeddings body: placeholders in the content parts of "input" (or "content") are filled
    transcription,  // multipart upload: the audio is attachment "file" (F55), WAV, MP3 or FLAC, mime optional
    uploads,        // multipart upload read from req.files (speech's speaker_ref); attachments need a mime
    websocket,      // /v1/realtime: no body, a WebSocket in memory (D97); not a server_routes handler_t
};

struct lb_route {
    const char * name;           // the endpoint name of the C API
    const char * upstream_path;  // as tools/server/server.cpp registers it; becomes server_http_req::path
    server_http_context::handler_t server_routes::* handler;  // null for websocket
    lb_body body;
};

const lb_route * lb_find_route(const char * name);  // null for a name that is not a route

inline bool lb_route_takes_uploads(const lb_route & r) {
    return r.body == lb_body::transcription || r.body == lb_body::uploads;
}
inline bool lb_route_takes_media_links(const lb_route & r) {
    return r.body == lb_body::chat || r.body == lb_body::embeddings;
}

// Every http route's handler by name, each wrapped in ex_wrapper as server_register_routes() does
std::map<std::string, server_http_context::handler_t> lb_make_endpoints(server_routes & r);
