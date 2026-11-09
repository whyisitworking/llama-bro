package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.internal.isValidSubpath
import com.suhel.llamabro.hub.internal.splitRepoTag
import java.nio.file.Paths

/** A Hugging Face repository, optional quantization tag, or one exact GGUF path. */
public class HfModel private constructor(
    /** Repository in `owner/name` form. */
    public val repo: String,
    /** Requested quantization tag, or null for the default choice. */
    public val tag: String?,
    /** Exact repository-relative file, or null for automatic choice. */
    public val file: String?,
) {
    /** Canonical reference suitable for WorkManager data and Intent extras. */
    override fun toString(): String = repo + (tag?.let { ":$it" } ?: "") + (file?.let { "#$it" } ?: "")

    override fun equals(other: Any?): Boolean =
        other is HfModel && repo == other.repo && tag == other.tag && file == other.file

    override fun hashCode(): Int = 31 * (31 * repo.hashCode() + (tag?.hashCode() ?: 0)) + (file?.hashCode() ?: 0)

    public companion object {
        /** Parses a Hub reference; invalid repository and file paths throw [IllegalArgumentException]. */
        @JvmStatic
        public fun parse(ref: String): HfModel {
            val repoAndTag = ref.substringBefore('#')
            val rawFile = ref.substringAfter('#', missingDelimiterValue = "")
            val (repo, tag) = splitRepoTag(repoAndTag)
            val file = rawFile.takeIf { it.isNotEmpty() }
            if (file != null) require(isValidSubpath(Paths.get("/cache"), file)) { "invalid HF file path: $file" }
            return HfModel(repo, tag.takeIf { it.isNotEmpty() }, file)
        }

        /** Parses a Hub reference. */
        public operator fun invoke(ref: String): HfModel = parse(ref)
    }
}
