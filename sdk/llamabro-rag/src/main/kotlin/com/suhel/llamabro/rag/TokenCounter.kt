package com.suhel.llamabro.rag

import com.suhel.llamabro.Endpoint
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import com.suhel.llamabro.Model as LoadedModel

/**
 * How many tokens a text is, to the model that will embed it. A [Chunker] takes one; tests can pass a lambda.
 */
public fun interface TokenCounter {
    public suspend fun count(text: String): Int

    /**
     * Three characters to a token, rounded up: conservative for English (about four) and code, but not for CJK, where
     * a token can be a single character; the chunker checks a counter other than this one on text outside Latin
     * scripts.
     */
    public data object Estimate : TokenCounter {
        private const val CHARS_PER_TOKEN = 3

        override suspend fun count(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }

    /**
     * The embedding model's own tokenizer, through `POST /tokenize`, which an embedding load answers
     * ([F190](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f190)). One request per
     * text; the [Chunker] asks only where the estimate is near its limit.
     * @param embedder the loaded embedding model ([com.suhel.llamabro.ModelTask.Embedding]).
     */
    public class Model(private val embedder: LoadedModel) : TokenCounter {
        override suspend fun count(text: String): Int {
            val body = buildJsonObject { put("content", text) }.toString()
            val response = embedder.rawRequest(Endpoint.Tokenize, body).toList().joinToString("")
            val tokens = (Json.parseToJsonElement(response) as JsonObject)["tokens"]
            return checkNotNull(
                tokens,
            ) { "tokenize answered without tokens: ${response.take(ERROR_EXCERPT)}" }.jsonArray.size
        }

        private companion object {
            const val ERROR_EXCERPT = 200
        }
    }
}
