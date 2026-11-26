package com.suhel.llamabro.rag

import com.suhel.llamabro.ModelInfo

/**
 * What made a library's vectors. Two libraries can be searched with the same query vector only if every field matches:
 * another model, a different quantisation of the same one, another pooling, normalisation or [format] all put vectors
 * in a different space, and mixing them returns confident nonsense.
 * @property modelName the GGUF's `general.name`, or llama.cpp's description of it when it has none.
 * @property fileBytes the model's weights in bytes ([ModelInfo.sizeBytes]): a model doesn't report its file's name or
 * length, and the weights' size tells one quantisation from another.
 * @property dimensions the length of each vector.
 * @property pooling how per-token vectors became one: `mean`, `cls`, `last`, `none` or `rank`.
 * @property normalization how each vector was scaled: `euclidean`, `taxicab`, `max-abs-int16`, `none` or `p<n>`.
 * @property format the instruction text put before queries and passages.
 */
public data class EmbedderIdentity(
    val modelName: String,
    val fileBytes: Long,
    val dimensions: Int,
    val pooling: String,
    val normalization: String,
    val format: EmbeddingFormat,
)

/**
 * A library was built by one embedder and opened with another ([EmbedderIdentity]); its vectors can't be compared
 * with the new one's. Rebuild the library with [wanted], or open it with [built].
 */
public class IndexMismatch internal constructor(
    public val built: EmbedderIdentity,
    public val wanted: EmbedderIdentity,
) : Exception(describe(built, wanted))

private fun describe(built: EmbedderIdentity, wanted: EmbedderIdentity): String {
    val differences =
        listOfNotNull(
            "model".takeIf { built.modelName != wanted.modelName },
            "model size".takeIf { built.fileBytes != wanted.fileBytes },
            "dimensions".takeIf { built.dimensions != wanted.dimensions },
            "pooling".takeIf { built.pooling != wanted.pooling },
            "normalization".takeIf { built.normalization != wanted.normalization },
            "query and passage format".takeIf { built.format != wanted.format },
        )
    return "the library was built by $built and is opened with $wanted: the ${differences.joinToString()} differ"
}
