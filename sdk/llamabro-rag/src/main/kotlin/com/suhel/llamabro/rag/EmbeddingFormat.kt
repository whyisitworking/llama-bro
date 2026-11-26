package com.suhel.llamabro.rag

import com.suhel.llamabro.ModelInfo

/**
 * The instruction text an embedding model was trained to see before a search query and before a passage. Many
 * embedders retrieve noticeably worse without it; others take plain text. The format is part of an index's identity: a
 * library built with one cannot be searched with another.
 * @property queryPrefix put before a search query.
 * @property documentPrefix put before every [Chunk]'s text.
 */
public data class EmbeddingFormat(val queryPrefix: String, val documentPrefix: String) {
    public companion object {
        /** Plain text for both. */
        public val None: EmbeddingFormat = EmbeddingFormat("", "")

        private val Nomic = EmbeddingFormat("search_query: ", "search_document: ")
        private val Gemma = EmbeddingFormat("task: search result | query: ", "title: none | text: ")

        // the model card has no space after "Query:"
        private val Qwen3 = EmbeddingFormat("Instruct: Given a question, retrieve passages that answer it\nQuery:", "")

        /**
         * The preset for a loaded model, read from its GGUF architecture and name: nomic-embed, EmbeddingGemma and
         * Qwen3-Embedding. Any other model gets [None]; pass your own [EmbeddingFormat] for it.
         */
        public fun of(info: ModelInfo): EmbeddingFormat =
            of(
                architecture = info.metadata["general.architecture"] ?: info.description.substringBefore(' '),
                name = info.metadata["general.name"],
            )

        internal fun of(architecture: String?, name: String?): EmbeddingFormat {
            val arch = architecture.orEmpty().lowercase()
            val key = name.orEmpty().lowercase().filter { it.isLetterOrDigit() }
            return when {
                arch.startsWith("nomic-bert") || "nomic" in key -> Nomic
                arch == "gemma-embedding" || "embeddinggemma" in key -> Gemma
                "qwen3embedding" in key -> Qwen3
                else -> None
            }
        }
    }
}
