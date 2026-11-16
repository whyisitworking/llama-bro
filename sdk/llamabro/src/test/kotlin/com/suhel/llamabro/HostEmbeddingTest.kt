package com.suhel.llamabro

import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * D65 on the real core (this machine, CPU): typed embed and rerank on upstream's own server-test models (F69),
 * `bge-small` and `jina-reranker-v1-tiny`, from `LB_TEST_MODELS_DIR` (sdk/scripts/check.sh passes it).
 */
class HostEmbeddingTest {
    private fun llama(): Pair<LlamaBro, File> {
        val lib = System.getProperty("llamabro.host.lib")
        val models = System.getProperty("llamabro.host.models")
        assumeTrue("host end-to-end runs from sdk/scripts/check.sh", lib != null && models != null)
        return LlamaBro(LlamaBroRuntime(JniEngine, tempDir(), lib, LlamaBroConfig.Default)) to File(models!!)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / sqrt(na * nb)
    }

    private fun norm(a: FloatArray): Double = sqrt(a.sumOf { it.toDouble() * it })

    @Test
    fun bgeSmallEmbedsAndSimilarTextsAreCloser() =
        runBlocking {
            val (llama, dir) = llama()
            llama
                .loadModel(ModelSource.file(File(dir, "bge-small-f16.gguf").path)) {
                    task = ModelTask.Embedding(Pooling.Mean)
                }.use { m ->
                    val cat = m.embed("A cat is sleeping on the sofa.")
                    val (kitten, stocks) =
                        m.embed(listOf("A kitten naps on the couch.", "Stock markets fell sharply on Monday."))
                    assertEquals(384, cat.size)
                    assertEquals(1.0, norm(cat), 1e-3) // Euclidean by default
                    val near = cosine(cat, kitten)
                    val far = cosine(cat, stocks)
                    assertTrue("near $near, far $far", near > far + 0.1)

                    // the base64 vector is the float vector llama-server would send (byte order, offsets)
                    val floats =
                        (
                            Json.parseToJsonElement(
                                m
                                    .rawRequest(
                                        Endpoint.Embeddings,
                                        """{"input":"A cat is sleeping on the sofa."}""",
                                    ).single(),
                            ) as JsonObject
                        ).getValue("data")
                            .jsonArray[0]
                            .jsonObject
                            .getValue("embedding")
                            .jsonArray
                            .map { it.jsonPrimitive.float }
                            .toFloatArray()
                    assertArrayEquals(floats, cat, 1e-6f)

                    // one text through the content form is the same input
                    assertArrayEquals(cat, m.embed(Content.Text("A cat is sleeping on the sofa.")), 1e-5f)

                    // no normalisation: the same direction, not unit length
                    val raw = m.embed("A cat is sleeping on the sofa.", Normalization.None)
                    assertTrue("|raw| = ${norm(raw)}", abs(norm(raw) - 1) > 0.01)
                    assertEquals(1.0, cosine(raw, cat), 1e-4)
                    val taxicab = m.embed("A cat is sleeping on the sofa.", Normalization.Taxicab)
                    assertEquals(1.0, taxicab.sumOf { abs(it.toDouble()) }, 1e-3)
                }
        }

    @Test
    fun jinaRanksTheRelevantDocumentFirst() =
        runBlocking {
            val (llama, dir) = llama()
            // upstream's TEST_DOCUMENTS and query (tools/server/tests/unit/test_rerank.py), as TaskDeviceTest
            val docs =
                listOf(
                    "A machine is a physical system that uses power to apply forces and control movement to perform " +
                        "an action. The term is commonly applied to artificial devices, such as those employing " +
                        "engines or motors, but also to natural biological macromolecules, such as molecular machines.",
                    "Learning is the process of acquiring new understanding, knowledge, behaviors, skills, values, " +
                        "attitudes, and preferences. The ability to learn is possessed by humans, non-human animals, " +
                        "and some machines; there is also evidence for some kind of learning in certain plants.",
                    "Machine learning is a field of study in artificial intelligence concerned with the development " +
                        "and study of statistical algorithms that can learn from data and generalize to unseen data, " +
                        "and thus perform tasks without explicit instructions.",
                    "Paris, capitale de la France, est une grande ville européenne et un centre mondial de l'art, de " +
                        "la mode, de la gastronomie et de la culture. Son paysage urbain du XIXe siècle est traversé " +
                        "par de larges boulevards et la Seine.",
                )
            llama
                .loadModel(ModelSource.file(File(dir, "jina-reranker-tiny-f16.gguf").path)) {
                    task = ModelTask.Rerank
                }.use { m ->
                    val all = m.rerank("Machine learning is", docs)
                    assertEquals(4, all.size)
                    assertEquals("$all", 2, all.first().index)
                    assertEquals("$all", 3, all.last().index) // the CPU's order (F69)
                    assertEquals(all.sortedByDescending { it.score }, all)
                    assertEquals(all.take(2), m.rerank("Machine learning is", docs, topN = 2))
                }
        }

    @Test
    fun inputPastTheBatchIsInputTooLong() =
        runBlocking {
            val (llama, dir) = llama()
            val long = List(600) { "word" }.joinToString(" ")
            llama
                .loadModel(ModelSource.file(File(dir, "bge-small-f16.gguf").path)) {
                    task = ModelTask.Embedding(Pooling.Mean)
                }.use { m ->
                    val e = runCatching { m.embed(long) }.exceptionOrNull()
                    assertTrue("$e", e is LlamaBroException.InputTooLong)
                    e as LlamaBroException.InputTooLong
                    assertTrue("${e.tokens}", e.tokens >= 600)
                    assertEquals(512, e.limit)
                }
            llama
                .loadModel(ModelSource.file(File(dir, "jina-reranker-tiny-f16.gguf").path)) {
                    task = ModelTask.Rerank
                }.use { m ->
                    val e = runCatching { m.rerank("query", listOf(long)) }.exceptionOrNull()
                    assertTrue("$e", e is LlamaBroException.InputTooLong && e.limit == 512)
                }
        }
}
