package com.suhel.llamabro

/**
 * A llama-server route reachable through [Model.rawRequest], one constant per route the C API maps (`llamabro.h`
 * lists them, and the few it leaves out with the reason). A class, not an enum, so a new route isn't a break (D26).
 */
public class Endpoint private constructor(internal val nativeName: String, private val route: String) {
    override fun toString(): String = route

    public companion object {
        /** `POST /v1/chat/completions` */
        @JvmField public val ChatCompletions: Endpoint = Endpoint("chat", "POST /v1/chat/completions")
        /** `POST /v1/chat/completions/input_tokens` */
        @JvmField public val CountTokens: Endpoint = Endpoint("count_tokens", "POST /v1/chat/completions/input_tokens")
        /** `POST /v1/chat/completions/control` */
        @JvmField public val Control: Endpoint = Endpoint("control", "POST /v1/chat/completions/control")
        /** `POST /completion`, llama-server's native completion */
        @JvmField public val Completion: Endpoint = Endpoint("completion", "POST /completion")
        /** `POST /v1/completions` */
        @JvmField public val CompletionOai: Endpoint = Endpoint("completion_oai", "POST /v1/completions")
        /** `POST /infill`, fill-in-the-middle for models with FIM tokens */
        @JvmField public val Infill: Endpoint = Endpoint("infill", "POST /infill")
        /** `POST /v1/responses` */
        @JvmField public val Responses: Endpoint = Endpoint("responses", "POST /v1/responses")
        /** `POST /v1/responses/input_tokens` */
        @JvmField public val ResponsesInputTokens: Endpoint = Endpoint("responses_input_tokens", "POST /v1/responses/input_tokens")
        /** `POST /v1/messages`, the Anthropic Messages API */
        @JvmField public val Messages: Endpoint = Endpoint("messages", "POST /v1/messages")
        /** `POST /v1/messages/count_tokens` */
        @JvmField public val MessagesCountTokens: Endpoint = Endpoint("messages_count_tokens", "POST /v1/messages/count_tokens")
        /** `POST /v1/embeddings`, OpenAI's shape */
        @JvmField public val Embeddings: Endpoint = Endpoint("embeddings", "POST /v1/embeddings")
        /** `POST /embeddings`, llama-server's native shape */
        @JvmField public val EmbeddingsNative: Endpoint = Endpoint("embeddings_native", "POST /embeddings")
        /** `POST /v1/rerank` */
        @JvmField public val Rerank: Endpoint = Endpoint("rerank", "POST /v1/rerank")
        /** `POST /tts`, for models with audio output */
        @JvmField public val Tts: Endpoint = Endpoint("tts", "POST /tts")
        /** `POST /v1/systemone`, for decision models */
        @JvmField public val SystemOne: Endpoint = Endpoint("systemone", "POST /v1/systemone")
        /** `POST /tokenize` */
        @JvmField public val Tokenize: Endpoint = Endpoint("tokenize", "POST /tokenize")
        /** `POST /detokenize` */
        @JvmField public val Detokenize: Endpoint = Endpoint("detokenize", "POST /detokenize")
        /** `POST /apply-template` */
        @JvmField public val ApplyTemplate: Endpoint = Endpoint("apply_template", "POST /apply-template")
        /** `GET /props` */
        @JvmField public val Props: Endpoint = Endpoint("props", "GET /props")
        /** `POST /props` */
        @JvmField public val PropsSet: Endpoint = Endpoint("props_set", "POST /props")
        /** `GET /v1/models` */
        @JvmField public val Models: Endpoint = Endpoint("models", "GET /v1/models")
        /** `GET /health` */
        @JvmField public val Health: Endpoint = Endpoint("health", "GET /health")
        /** `GET /slots` */
        @JvmField public val Slots: Endpoint = Endpoint("slots", "GET /slots")
        /** `POST /slots/:id_slot?action=…`; pass `id_slot` and `action` as params */
        @JvmField public val SlotsAction: Endpoint = Endpoint("slots_action", "POST /slots/:id_slot")
        /** `GET /metrics` */
        @JvmField public val Metrics: Endpoint = Endpoint("metrics", "GET /metrics")
        /** `GET /lora-adapters` */
        @JvmField public val LoraAdapters: Endpoint = Endpoint("lora_adapters", "GET /lora-adapters")
        /** `POST /lora-adapters` */
        @JvmField public val LoraAdaptersSet: Endpoint = Endpoint("lora_adapters_set", "POST /lora-adapters")
    }
}
