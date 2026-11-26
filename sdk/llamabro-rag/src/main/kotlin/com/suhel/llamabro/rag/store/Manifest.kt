package com.suhel.llamabro.rag.store

import com.suhel.llamabro.ModelInfo
import com.suhel.llamabro.Normalization
import com.suhel.llamabro.Pooling
import com.suhel.llamabro.rag.EmbedderIdentity
import com.suhel.llamabro.rag.EmbeddingFormat
import kotlinx.serialization.Serializable

/** The identity of [info]'s model, embedding with [pooling] and [normalization] into vectors of [dimensions]. */
internal fun embedderIdentity(
    info: ModelInfo,
    dimensions: Int,
    pooling: Pooling,
    normalization: Normalization,
    format: EmbeddingFormat,
): EmbedderIdentity {
    val architecture = info.metadata["general.architecture"]
    return embedderIdentity(
        name = info.metadata["general.name"],
        description = info.description,
        sizeBytes = info.sizeBytes,
        dimensions = dimensions,
        pooling = pooling,
        modelPooling = architecture?.let { info.metadata["$it.pooling_type"] },
        normalization = normalization,
        format = format,
    )
}

/** [modelPooling] is the GGUF's `<arch>.pooling_type`, llama.cpp's enum as a number, read when [pooling] defers. */
@Suppress("LongParameterList")
internal fun embedderIdentity(
    name: String?,
    description: String,
    sizeBytes: Long,
    dimensions: Int,
    pooling: Pooling,
    modelPooling: String?,
    normalization: Normalization,
    format: EmbeddingFormat,
): EmbedderIdentity =
    EmbedderIdentity(
        modelName = name?.takeIf { it.isNotBlank() } ?: description,
        fileBytes = sizeBytes,
        dimensions = dimensions,
        pooling = if (pooling == Pooling.ModelDefault) modelPoolingName(modelPooling) else pooling.name.lowercase(),
        normalization = normalizationName(normalization),
        format = format,
    )

// llama.h's llama_pooling_type
private fun modelPoolingName(type: String?): String =
    when (type?.trim()) {
        "0" -> "none"
        "1" -> "mean"
        "2" -> "cls"
        "3" -> "last"
        "4" -> "rank"
        else -> "model-default"
    }

private fun normalizationName(normalization: Normalization): String =
    when (normalization) {
        Normalization.None -> "none"
        Normalization.MaxAbsInt16 -> "max-abs-int16"
        Normalization.Taxicab -> "taxicab"
        Normalization.Euclidean -> "euclidean"
        is Normalization.PNorm -> "p${normalization.p}"
    }

/** A document in the library: its rows are `firstRow until firstRow + rowCount`, all live. */
@Serializable
internal data class StoredDocument(
    val id: String,
    val title: String,
    val source: String?,
    val firstRow: Int,
    val rowCount: Int,
)

/**
 * `manifest.json`, the one file that says what the library holds. It's written last, so it names only bytes already
 * on disk: the data files may run past [rows], [chunkBytes] and [tombstones] after a crash, and the rest is ignored.
 * @property tombstones how many lines of `deleted` count.
 */
@Serializable
internal data class Manifest(
    val version: Int,
    val embedder: IdentityJson,
    val rows: Int,
    val chunkBytes: Long,
    val tombstones: Int,
    val documents: List<StoredDocument>,
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
internal data class IdentityJson(
    val modelName: String,
    val fileBytes: Long,
    val dimensions: Int,
    val pooling: String,
    val normalization: String,
    val queryPrefix: String,
    val documentPrefix: String,
) {
    fun toIdentity(): EmbedderIdentity =
        EmbedderIdentity(
            modelName,
            fileBytes,
            dimensions,
            pooling,
            normalization,
            EmbeddingFormat(queryPrefix, documentPrefix),
        )

    companion object {
        fun of(identity: EmbedderIdentity): IdentityJson =
            with(identity) {
                IdentityJson(
                    modelName,
                    fileBytes,
                    dimensions,
                    pooling,
                    normalization,
                    format.queryPrefix,
                    format.documentPrefix,
                )
            }
    }
}

/** A line of `chunks.jsonl`. */
@Serializable
internal data class ChunkJson(
    val documentId: String,
    val index: Int,
    val text: String,
    val start: Int,
    val end: Int,
    val headings: List<String>,
)
