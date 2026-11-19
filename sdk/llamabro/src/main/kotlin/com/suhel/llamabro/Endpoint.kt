package com.suhel.llamabro

/**
 * A llama-server route reachable through [Model.rawRequest], one constant per route the C API maps (`llamabro.h`
 * lists them, and the few it leaves out with the reason). A class, not an enum, so a new route isn't a break (D26).
 */
public class Endpoint private constructor(internal val nativeName: String, private val route: String) {
    override fun toString(): String = route

    public companion object {
        /** `POST /v1/chat/completions` */
        public val ChatCompletions: Endpoint = Endpoint("chat", "POST /v1/chat/completions")

        /** `POST /v1/chat/completions/input_tokens` */
        public val CountTokens: Endpoint = Endpoint("count_tokens", "POST /v1/chat/completions/input_tokens")

        /** `POST /v1/chat/completions/control` */
        public val Control: Endpoint = Endpoint("control", "POST /v1/chat/completions/control")

        /** `POST /completion`, llama-server's native completion */
        public val Completion: Endpoint = Endpoint("completion", "POST /completion")

        /** `POST /v1/completions` */
        public val CompletionOai: Endpoint = Endpoint("completion_oai", "POST /v1/completions")

        /** `POST /infill`, fill-in-the-middle for models with FIM tokens */
        public val Infill: Endpoint = Endpoint("infill", "POST /infill")

        /** `POST /v1/responses` */
        public val Responses: Endpoint = Endpoint("responses", "POST /v1/responses")

        /** `POST /v1/responses/input_tokens` */
        public val ResponsesInputTokens: Endpoint =
            Endpoint("responses_input_tokens", "POST /v1/responses/input_tokens")

        /** `POST /v1/messages`, the Anthropic Messages API */
        public val Messages: Endpoint = Endpoint("messages", "POST /v1/messages")

        /** `POST /v1/messages/count_tokens` */
        public val MessagesCountTokens: Endpoint =
            Endpoint("messages_count_tokens", "POST /v1/messages/count_tokens")

        /** `POST /v1/embeddings`, OpenAI's shape */
        public val Embeddings: Endpoint = Endpoint("embeddings", "POST /v1/embeddings")

        /** `POST /embeddings`, llama-server's native shape */
        public val EmbeddingsNative: Endpoint = Endpoint("embeddings_native", "POST /embeddings")

        /** `POST /v1/rerank` */
        public val Rerank: Endpoint = Endpoint("rerank", "POST /v1/rerank")

        /**
         * `POST /v1/audio/transcriptions`. llama-server takes its audio as a multipart upload; here the body is the
         * form fields as a JSON object of strings, and the audio goes beside it (D84), which [Model.rawRequest] can't
         * send: use [transcribe].
         */
        public val Transcriptions: Endpoint =
            Endpoint("transcriptions", "POST /v1/audio/transcriptions")

        // internal until a request returns bytes: rawRequest's strings can't carry the audio intact (F74)
        internal val Tts: Endpoint = Endpoint("tts", "POST /tts")

        // OpenAI's speech request over Tts: internal for the same reason
        internal val Speech: Endpoint = Endpoint("speech", "POST /v1/audio/speech")

        /**
         * `GET /v1/realtime`, a WebSocket conversation (D97). Not public: it sends events both ways, which
         * [Model.rawRequest] can't; `Model.voiceSession` opens it.
         */
        internal val Realtime: Endpoint = Endpoint("realtime", "GET /v1/realtime")

        /** `POST /v1/systemone`, for decision models */
        public val SystemOne: Endpoint = Endpoint("systemone", "POST /v1/systemone")

        /** `POST /tokenize` */
        public val Tokenize: Endpoint = Endpoint("tokenize", "POST /tokenize")

        /** `POST /detokenize` */
        public val Detokenize: Endpoint = Endpoint("detokenize", "POST /detokenize")

        /** `POST /apply-template` */
        public val ApplyTemplate: Endpoint = Endpoint("apply_template", "POST /apply-template")

        /** `GET /props` */
        public val Props: Endpoint = Endpoint("props", "GET /props")

        /** `POST /props` */
        public val PropsSet: Endpoint = Endpoint("props_set", "POST /props")

        /** `GET /v1/models` */
        public val Models: Endpoint = Endpoint("models", "GET /v1/models")

        /** `GET /health` */
        public val Health: Endpoint = Endpoint("health", "GET /health")

        /** `GET /slots` */
        public val Slots: Endpoint = Endpoint("slots", "GET /slots")

        /** `POST /slots/:id_slot?action=…`; pass `id_slot` and `action` as params */
        public val SlotsAction: Endpoint = Endpoint("slots_action", "POST /slots/:id_slot")

        /** `GET /metrics` */
        public val Metrics: Endpoint = Endpoint("metrics", "GET /metrics")

        /** `GET /lora-adapters` */
        public val LoraAdapters: Endpoint = Endpoint("lora_adapters", "GET /lora-adapters")

        /** `POST /lora-adapters` */
        public val LoraAdaptersSet: Endpoint = Endpoint("lora_adapters_set", "POST /lora-adapters")
    }
}
