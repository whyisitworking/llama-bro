package com.suhel.llamabro

/** llama-server routes reachable through [Model.rawRequest]; names map to the C API's endpoints. */
public enum class Endpoint(internal val nativeName: String) {
    ChatCompletions("chat"),
    Completion("completion"),
    CompletionOai("completion_oai"),
    Control("control"),
    Embeddings("embeddings"),
    Rerank("rerank"),
    Tokenize("tokenize"),
    Detokenize("detokenize"),
    CountTokens("count_tokens"),
    ApplyTemplate("apply_template"),
    Props("props"),
    Slots("slots"),
    SlotsAction("slots_action"),
    Metrics("metrics"),
}
