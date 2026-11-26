package com.suhel.llamabro.rag

import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.testing.FakeLlamaBro
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingFormatTest {
    private val nomic = EmbeddingFormat("search_query: ", "search_document: ")
    private val gemma = EmbeddingFormat("task: search result | query: ", "title: none | text: ")
    private val qwen = EmbeddingFormat("Instruct: Given a question, retrieve passages that answer it\nQuery:", "")

    @Test
    fun nomicByArchitectureOrName() {
        assertEquals(nomic, EmbeddingFormat.of("nomic-bert", "anything"))
        assertEquals(nomic, EmbeddingFormat.of("nomic-bert-moe", null))
        assertEquals(nomic, EmbeddingFormat.of("bert", "Nomic Embed Text v1.5"))
        assertEquals(nomic, EmbeddingFormat.of(null, "nomic-embed-text-v2-moe"))
    }

    @Test
    fun embeddingGemmaByArchitectureOrName() {
        assertEquals(gemma, EmbeddingFormat.of("gemma-embedding", null))
        assertEquals(gemma, EmbeddingFormat.of("gemma3", "EmbeddingGemma 300M"))
        assertEquals(gemma, EmbeddingFormat.of(null, "embeddinggemma-300m-qat"))
    }

    @Test
    fun qwen3EmbeddingByName() {
        assertEquals(qwen, EmbeddingFormat.of("qwen3", "Qwen3 Embedding 0.6B"))
        assertEquals(qwen, EmbeddingFormat.of("qwen3", "Qwen3-Embedding-4B"))
    }

    @Test
    fun theQwenChatModelIsNotTheEmbedder() {
        assertEquals(EmbeddingFormat.None, EmbeddingFormat.of("qwen3", "Qwen3 4B Instruct"))
    }

    @Test
    fun unknownIsNone() {
        assertEquals(EmbeddingFormat.None, EmbeddingFormat.of("bert", "bge-small-en-v1.5"))
        assertEquals(EmbeddingFormat.None, EmbeddingFormat.of(null, null))
        assertEquals(EmbeddingFormat("", ""), EmbeddingFormat.None)
    }

    @Test
    fun aModelWithNoMetadataIsNone() {
        val fake = FakeLlamaBro()
        val info = kotlinx.coroutines.runBlocking { fake.llamaBro.loadModel(ModelSource.file("/m.gguf")).info }
        assertEquals(EmbeddingFormat.None, EmbeddingFormat.of(info))
    }
}
