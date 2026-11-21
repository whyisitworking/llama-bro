package com.suhel.llamabro

import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.internal.Attachments
import com.suhel.llamabro.internal.ChatJson
import com.suhel.llamabro.internal.ErrorMapping
import com.suhel.llamabro.internal.Media
import com.suhel.llamabro.spi.EngineAttachment
import kotlinx.coroutines.flow.single
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * How llama-server scales each embedding vector (its `embd_normalize`, `common_embd_normalize`). [Euclidean], the
 * default, gives unit vectors, so a dot product is the cosine similarity.
 */
public sealed class Normalization {
    internal abstract val upstream: Int

    /** The model's vector as it is (-1). */
    public data object None : Normalization() {
        override val upstream: Int = -1
    }

    /** Scaled so the largest component is ±32760, the int16 range (0). */
    public data object MaxAbsInt16 : Normalization() {
        override val upstream: Int = 0
    }

    /** Unit length by the sum of absolute values (1). */
    public data object Taxicab : Normalization() {
        override val upstream: Int = 1
    }

    /** Unit length (2), llama-server's default. */
    public data object Euclidean : Normalization() {
        override val upstream: Int = 2
    }

    /** Unit length by the [p]-norm, for [p] above 2 ([Taxicab] is 1, [Euclidean] is 2). */
    public data class PNorm(public val p: Int) : Normalization() {
        init {
            require(p > 2) { "p must be above 2 (Taxicab is 1, Euclidean is 2): $p" }
        }

        override val upstream: Int get() = p
    }
}

/** A document's place in [rerank]'s answer: its [index] in the documents passed, and its relevance [score]. */
public data class Ranked(public val index: Int, public val score: Float)

/**
 * The embedding vector of [text] (`POST /v1/embeddings`), on a model loaded with [ModelTask.Embedding].
 *
 * @throws IllegalStateException if the model was loaded for another task; no request is made.
 * @throws LlamaBroException.InputTooLong if [text] is longer than the batch; the SDK doesn't split it.
 */
public suspend fun Model.embed(text: String, normalization: Normalization = Normalization.Euclidean): FloatArray =
    embedInput(JsonPrimitive(text), normalization).single()

/** One vector per text, in [texts]' order, in one request. As [embed] for one text. */
public suspend fun Model.embed(
    texts: List<String>,
    normalization: Normalization = Normalization.Euclidean,
): List<FloatArray> = embedInput(JsonArray(texts.map { JsonPrimitive(it) }), normalization)

/**
 * One vector for [content] read as one input: text, and pictures or clips on a model whose embeddings take them
 * (loaded with its encoder). As in chat (D84), a picture's or clip's file or descriptor is read natively during the
 * call and the native side writes its base64. A picture on a model that can't read it throws
 * [LlamaBroException.InvalidRequest] with llama-server's reason; a clip on a model that doesn't hear, or audio that
 * isn't WAV, MP3 or FLAC, throws it before any request. Otherwise as [embed] for one text.
 */
public suspend fun Model.embed(
    vararg content: Content,
    normalization: Normalization = Normalization.Euclidean,
): FloatArray {
    requireTask<ModelTask.Embedding>("embed", "ModelTask.Embedding()")
    content.forEach { if (it is Content.Audio) Media.checkAudio(info, it.source) }
    val media = Attachments()
    val input = buildJsonObject { put("content", JsonArray(content.map { ChatJson.part(it, media) })) }
    return try {
        embedInput(input, normalization, media.all).single()
    } catch (e: LlamaBroException) {
        throw ErrorMapping.unsupportedMedia(e)
    }
}

/**
 * [documents] ordered by relevance to [query], best first (`POST /v1/rerank`), on a model loaded with
 * [ModelTask.Rerank]. [topN] keeps only the best; null keeps all. Each document is read with the query as one input.
 *
 * @throws IllegalStateException if the model was loaded for another task; no request is made.
 * @throws LlamaBroException.InputTooLong if the query and a document together are longer than the batch.
 */
public suspend fun Model.rerank(query: String, documents: List<String>, topN: Int? = null): List<Ranked> {
    requireTask<ModelTask.Rerank>("rerank", "ModelTask.Rerank")
    val body =
        buildJsonObject {
            put("query", query)
            put("documents", JsonArray(documents.map { JsonPrimitive(it) }))
            topN?.let { put("top_n", it) }
        }
    val reply = rawRequest(Endpoint.Rerank, body.toString())
    // upstream sorts best first and applies top_n (format_response_rerank)
    return ErrorMapping.readReply("rerank") {
        Json.parseToJsonElement(reply.single()).jsonObject.getValue("results").jsonArray.map {
            val r = it.jsonObject
            Ranked(r.getValue("index").jsonPrimitive.int, r.getValue("relevance_score").jsonPrimitive.float)
        }
    }
}

private suspend fun Model.embedInput(
    input: JsonElement,
    normalization: Normalization,
    attachments: List<EngineAttachment> = emptyList(),
): List<FloatArray> {
    requireTask<ModelTask.Embedding>("embed", "ModelTask.Embedding()")
    val body =
        buildJsonObject {
            put("input", input)
            // base64 is the floats' own bytes: a quarter of the decimal text, and exact
            put("encoding_format", "base64")
            put("embd_normalize", normalization.upstream)
        }
    val reply = request(Endpoint.Embeddings, body.toString(), attachments)
    return ErrorMapping.readReply("embeddings") {
        Json
            .parseToJsonElement(reply.single())
            .jsonObject
            .getValue("data")
            .jsonArray
            .map { it.jsonObject }
            .sortedBy { it.getValue("index").jsonPrimitive.int }
            .map { decode(it.getValue("embedding").jsonPrimitive.content) }
    }
}

internal inline fun <reified T : ModelTask> Model.requireTask(call: String, loadWith: String) {
    check(task is T) { "$call needs a model loaded with task = $loadWith; this one was loaded with ModelTask.$task" }
}

/** llama-server's base64 is the vector's memory, in the machine's byte order: little-endian on every target. */
private fun decode(base64: String): FloatArray {
    val buffer = ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(buffer.remaining()).also { buffer.get(it) }
}
