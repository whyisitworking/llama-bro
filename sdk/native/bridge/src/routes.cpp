#include "routes.h"

#include "upstream.h"

#include <cstring>

namespace {

// llamabro.h's list of endpoints. upstream_path is what llama-server's own HTTP layer would put in req.path (a route
// registered under several paths shows its /v1 one); the single-model handlers do not read it.
constexpr lb_route kRoutes[] = {
    { "chat", "/v1/chat/completions", &server_routes::post_chat_completions, lb_body::chat },
    { "count_tokens", "/v1/chat/completions/input_tokens", &server_routes::post_chat_completions_tok, lb_body::chat },
    { "control", "/v1/chat/completions/control", &server_routes::post_control, lb_body::plain },
    { "completion", "/completion", &server_routes::post_completions, lb_body::plain },
    { "completion_oai", "/v1/completions", &server_routes::post_completions_oai, lb_body::plain },
    { "infill", "/infill", &server_routes::post_infill, lb_body::plain },
    { "responses", "/v1/responses", &server_routes::post_responses_oai, lb_body::plain },
    { "responses_input_tokens", "/v1/responses/input_tokens", &server_routes::post_responses_tok_oai, lb_body::plain },
    { "messages", "/v1/messages", &server_routes::post_anthropic_messages, lb_body::plain },
    { "messages_count_tokens", "/v1/messages/count_tokens", &server_routes::post_anthropic_count_tokens,
      lb_body::plain },
    { "embeddings", "/v1/embeddings", &server_routes::post_embeddings_oai, lb_body::embeddings },
    { "embeddings_native", "/embeddings", &server_routes::post_embeddings, lb_body::embeddings },
    { "rerank", "/v1/rerank", &server_routes::post_rerank, lb_body::plain },
    { "tts", "/tts", &server_routes::post_tts, lb_body::uploads },
    { "speech", "/v1/audio/speech", &server_routes::post_speech_oai, lb_body::uploads },
    { "transcriptions", "/v1/audio/transcriptions", &server_routes::post_transcriptions_oai, lb_body::transcription },
    { "systemone", "/v1/systemone", &server_routes::post_systemone, lb_body::plain },
    { "tokenize", "/tokenize", &server_routes::post_tokenize, lb_body::plain },
    { "detokenize", "/detokenize", &server_routes::post_detokenize, lb_body::plain },
    { "apply_template", "/apply-template", &server_routes::post_apply_template, lb_body::chat },
    { "props", "/props", &server_routes::get_props, lb_body::plain },
    { "props_set", "/props", &server_routes::post_props, lb_body::plain },
    { "models", "/v1/models", &server_routes::get_models, lb_body::plain },
    { "health", "/health", &server_routes::get_health, lb_body::plain },
    { "slots", "/slots", &server_routes::get_slots, lb_body::plain },
    { "slots_action", "/slots/:id_slot", &server_routes::post_slots, lb_body::plain },
    { "metrics", "/metrics", &server_routes::get_metrics, lb_body::plain },
    { "lora_adapters", "/lora-adapters", &server_routes::get_lora_adapters, lb_body::plain },
    { "lora_adapters_set", "/lora-adapters", &server_routes::post_lora_adapters, lb_body::plain },
    { "realtime", "/v1/realtime", nullptr, lb_body::websocket },
};

}  // namespace

const lb_route * lb_find_route(const char * name) {
    for (const lb_route & r : kRoutes) {
        if (std::strcmp(r.name, name) == 0) return &r;
    }
    return nullptr;
}

std::map<std::string, server_http_context::handler_t> lb_make_endpoints(server_routes & r) {
    std::map<std::string, server_http_context::handler_t> out;
    for (const lb_route & route : kRoutes) {
        if (route.handler) out.emplace(route.name, ex_wrapper(r.*route.handler));
    }
    return out;
}
