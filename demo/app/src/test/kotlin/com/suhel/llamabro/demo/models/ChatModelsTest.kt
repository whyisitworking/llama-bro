package com.suhel.llamabro.demo.models

import com.suhel.llamabro.hub.HfModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An embedding model is never offered as a chat model: the switcher, the Models page and the Server page all ask the
 * one predicate, [isChatModel].
 */
class ChatModelsTest {
    private fun on(key: HfModel) = LibraryModel(key, Names.of(key), 1, lastUsed = null, isDefault = false)

    @Test fun theLibrarysEmbedderIsNoChatModelInAnyQuant() {
        assertFalse(isChatModel(Catalog.embedder.key))
        assertFalse(isChatModel(HfModel.parse("${Catalog.embedder.key.repo}:F16")))
    }

    @Test fun modelsNamedForEmbeddingOrRerankingAreNoChatModels() {
        assertFalse(isChatModel(HfModel.parse("nomic-ai/nomic-embed-text-v1.5-GGUF:Q8_0")))
        assertFalse(isChatModel(HfModel.parse("Qwen/Qwen3-Embedding-0.6B-GGUF:Q8_0")))
        assertFalse(isChatModel(HfModel.parse("gpustack/jina-reranker-v1-tiny-en-GGUF:Q8_0")))
    }

    @Test fun everyCatalogChatModelIsOne() {
        Catalog.suggested.forEach { assertTrue(it.realName, isChatModel(it.key)) }
    }

    @Test fun aListKeepsOnlyChatModelsInItsOrder() {
        val chat = Catalog.suggested.map { on(it.key) }
        assertEquals(chat, (listOf(on(Catalog.embedder.key)) + chat).forChat())
    }
}
