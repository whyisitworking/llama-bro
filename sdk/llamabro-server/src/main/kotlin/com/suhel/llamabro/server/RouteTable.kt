package com.suhel.llamabro.server

import com.suhel.llamabro.Endpoint
import io.ktor.http.HttpMethod

/**
 * A llama-server route the server answers: [method] and [path] (Ktor's pattern) to [endpoint], served unless
 * [onByDefault] is false and the config's [Routes] doesn't enable it. [answeredHere] routes are the server's own
 * (`/health`, `/v1/models`), not forwarded.
 */
internal class ServedRoute(
    val method: HttpMethod,
    val path: String,
    val endpoint: Endpoint,
    val onByDefault: Boolean = true,
    val answeredHere: Boolean = false,
)

private fun post(path: String, endpoint: Endpoint, onByDefault: Boolean = true) =
    ServedRoute(HttpMethod.Post, path, endpoint, onByDefault)

private fun get(path: String, endpoint: Endpoint) = ServedRoute(HttpMethod.Get, path, endpoint)

/**
 * The design's route table ([§2 Routes](https://github.com/whyisitworking/llama-bro/blob/main/docs/design/server.md)).
 * Off by default: the routes that change or save the shared model, which upstream gates too (`POST /props` needs
 * `--props`, slot save and restore `--slot-save-path`). Audio has its own routes, and `/v1/realtime` isn't served.
 */
internal val routeTable: List<ServedRoute> =
    listOf(
        post("/v1/chat/completions", Endpoint.ChatCompletions),
        post("/v1/chat/completions/input_tokens", Endpoint.CountTokens),
        // ends a running completion's reasoning early: the client's own request, as upstream leaves it open
        post("/v1/chat/completions/control", Endpoint.Control),
        post("/v1/completions", Endpoint.CompletionOai),
        post("/completion", Endpoint.Completion),
        post("/infill", Endpoint.Infill),
        post("/v1/messages", Endpoint.Messages),
        post("/v1/messages/count_tokens", Endpoint.MessagesCountTokens),
        post("/v1/responses", Endpoint.Responses),
        post("/v1/responses/input_tokens", Endpoint.ResponsesInputTokens),
        post("/v1/embeddings", Endpoint.Embeddings),
        post("/embeddings", Endpoint.EmbeddingsNative),
        post("/v1/rerank", Endpoint.Rerank),
        post("/tokenize", Endpoint.Tokenize),
        post("/detokenize", Endpoint.Detokenize),
        post("/apply-template", Endpoint.ApplyTemplate),
        ServedRoute(HttpMethod.Get, "/health", Endpoint.Health, answeredHere = true),
        ServedRoute(HttpMethod.Get, "/v1/models", Endpoint.Models, answeredHere = true),
        get("/props", Endpoint.Props),
        get("/slots", Endpoint.Slots),
        get("/metrics", Endpoint.Metrics),
        // reads the adapters only; setting them is the POST, off below
        get("/lora-adapters", Endpoint.LoraAdapters),
        post("/props", Endpoint.PropsSet, onByDefault = false),
        post("/lora-adapters", Endpoint.LoraAdaptersSet, onByDefault = false),
        post("/slots/{id_slot}", Endpoint.SlotsAction, onByDefault = false),
    )

/** Whether [config] serves this route. */
internal fun ServedRoute.isServed(config: ServerConfig): Boolean = onByDefault || endpoint in config.routes.enabled
