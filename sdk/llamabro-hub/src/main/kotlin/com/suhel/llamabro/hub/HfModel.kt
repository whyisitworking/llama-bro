package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.internal.isValidSubpath
import com.suhel.llamabro.hub.internal.splitRepoTag
import java.nio.file.Paths

/** A Hugging Face repository, optional quantization tag, or one exact GGUF path. */
public data class HfModel private constructor(
    /** Repository in `owner/name` form. */
    public val repo: String,
    /**
     * Requested quantization tag in upper case, or null for the default choice. `-hf` matches a tag case-insensitively
     * (download.cpp's find_best_model and common_download_remove), so `q4_k_m` and `Q4_K_M` are one selection.
     */
    public val tag: String?,
    /** Exact repository-relative file, or null for automatic choice. */
    public val file: String?,
) {
    /** Canonical reference suitable for WorkManager data and Intent extras. */
    override fun toString(): String = repo + (tag?.let { ":$it" }.orEmpty()) + (file?.let { "#$it" }.orEmpty())

    public companion object {
        /**
         * Parses a Hub reference, upper-casing its tag; invalid repository and file paths throw
         * [IllegalArgumentException].
         */
        public fun parse(ref: String): HfModel {
            val repoAndTag = ref.substringBefore('#')
            val rawFile = ref.substringAfter('#', missingDelimiterValue = "")
            val (repo, tag) = splitRepoTag(repoAndTag)
            val file = rawFile.takeIf { it.isNotEmpty() }
            if (file != null) require(isValidSubpath(Paths.get("/cache"), file)) { "invalid HF file path: $file" }
            return HfModel(repo, tag.takeIf { it.isNotEmpty() }?.uppercase(), file)
        }

        /** Parses a Hub reference. */
        public operator fun invoke(ref: String): HfModel = parse(ref)
    }
}
