package com.suhel.llamabro

import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * F190 on the real core (this machine, CPU): one runtime holds a Generate model and an embedding model at once, and
 * both answer. The generator is upstream's 15M test model (stories15M_MOE), the smallest in the test set.
 */
class HostTwoModelsTest {
    @Test
    fun aGenerateModelAndAnEmbeddingModelAnswerTogether() =
        runBlocking {
            val lib = System.getProperty("llamabro.host.lib")
            val models = System.getProperty("llamabro.host.models")
            assumeTrue("host end-to-end runs from sdk/scripts/check.sh", lib != null && models != null)
            val llama = LlamaBro(LlamaBroRuntime(JniEngine, tempDir(), lib, LlamaBroConfig.Default))
            llama.loadModel(ModelSource.file(File(models!!, "stories15M_MOE-F16.gguf").path)).use { generator ->
                llama
                    .loadModel(ModelSource.file(File(models, "bge-small-f16.gguf").path)) {
                        task = ModelTask.Embedding(Pooling.Mean)
                    }.use { embedder ->
                        assertEquals(384, embedder.embed("A cat is sleeping on the sofa.").size)
                        val body =
                            generator
                                .rawRequest(
                                    Endpoint.Completion,
                                    """{"prompt":"Once upon a time","n_predict":8,"seed":42}""",
                                ).single()
                        val text =
                            (
                                Json.parseToJsonElement(
                                    body,
                                ) as JsonObject
                            ).getValue("content").jsonPrimitive.content
                        assertTrue("generated: '$text'", text.isNotEmpty())
                        // and the embedder still answers after the generator ran
                        assertEquals(384, embedder.embed("A kitten naps on the couch.").size)
                    }
            }
        }
}
