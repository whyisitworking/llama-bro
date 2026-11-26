package com.suhel.llamabro

import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * F190 on the real core (this machine, CPU): `POST /tokenize` on a `ModelTask.Embedding` load answers, as upstream's
 * `llama-server --embeddings` does (research/rag/tokenize-upstream.sh), so a chunker can count an embedder's tokens.
 */
class HostEmbeddingTokenizeTest {
    @Test
    fun tokenizeAnswersOnAnEmbeddingLoad() =
        runBlocking {
            val lib = System.getProperty("llamabro.host.lib")
            val models = System.getProperty("llamabro.host.models")
            assumeTrue("host end-to-end runs from sdk/scripts/check.sh", lib != null && models != null)
            LlamaBro(LlamaBroRuntime(JniEngine, tempDir(), lib, LlamaBroConfig.Default))
                .loadModel(ModelSource.file(File(models!!, "bge-small-f16.gguf").path)) {
                    task = ModelTask.Embedding(Pooling.Mean)
                }.use { m ->
                    val body =
                        m
                            .rawRequest(
                                Endpoint.Tokenize,
                                """{"content":"A cat is sleeping on the sofa."}""",
                            ).single()
                    val tokens = (Json.parseToJsonElement(body) as JsonObject).getValue("tokens").jsonArray
                    // the same ids upstream gives: [CLS]-less, eight word pieces
                    assertEquals(
                        listOf(1037, 4937, 2003, 5777, 2006, 1996, 10682, 1012),
                        tokens.map { it.toString().toInt() },
                    )
                    assertTrue(tokens.isNotEmpty())
                }
        }
}
