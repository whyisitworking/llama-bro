package com.suhel.llamabro.hexagon

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.Pooling
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * F69: embedding and reranker models load with [ModelTask] and answer on the phone, on every kind [DevicePolicy]
 * can pick. The models are upstream's server-test ones (bge-small, jina-reranker-v1-tiny), copied here by
 * sdk/scripts/check.sh; the checks are upstream's test_embedding.py and test_rerank.py.
 */
@RunWith(AndroidJUnit4::class)
class TaskDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val llama = LlamaBro.getInstance(context)

    private fun file(name: String) = File(context.getExternalFilesDir(null), name).path

    private fun call(m: Model, e: Endpoint, body: String): JsonObject =
        runBlocking {
            Json.parseToJsonElement(m.rawRequest(e, body).single()) as JsonObject
        }

    @Test
    fun embeddingsOnEachKind() =
        runBlocking {
            val vectors = mutableMapOf<DeviceKind, List<Double>>()
            for (policy in listOf(
                DevicePolicy.Auto,
                DevicePolicy.Only(DeviceKind.Gpu),
                DevicePolicy.Only(DeviceKind.Cpu),
            )) {
                llama
                    .loadModel(ModelSource.file(file("bge-small-f16.gguf"))) {
                        task = ModelTask.Embedding(Pooling.Mean)
                        devicePolicy = policy
                    }.use { m ->
                        Log.i("TaskDevice", "embedding $policy -> ${m.loadReport}")
                        val e =
                            call(m, Endpoint.Embeddings, """{"input":"I believe the meaning of life is"}""")
                                .getValue("data")
                                .jsonArray
                                .single()
                                .jsonObject
                                .getValue("embedding")
                                .jsonArray
                        assertEquals(384, e.size)
                        val norm = e.sumOf { it.jsonPrimitive.double * it.jsonPrimitive.double }
                        assertTrue("$policy: |e|² = $norm", kotlin.math.abs(norm - 1) < 1e-3)
                        vectors[m.loadReport.device] = e.map { it.jsonPrimitive.double }
                    }
            }
            // the NPU computes in lower precision; its vector must still point the same way as the CPU's
            val cpu = vectors.getValue(DeviceKind.Cpu)
            for ((kind, v) in vectors) {
                val cos = v.indices.sumOf { v[it] * cpu[it] } // both unit length
                Log.i("TaskDevice", "embedding cosine $kind vs Cpu = $cos")
                assertTrue("$kind vs Cpu: cosine $cos", cos > 0.99)
            }
        }

    @Test
    fun rerankOnEachKind() =
        runBlocking {
            // upstream's TEST_DOCUMENTS (tools/server/tests/unit/test_rerank.py), unshortened
            val docs =
                listOf(
                    "A machine is a physical system that uses power to apply forces and control movement to perform an action. The term is commonly applied to artificial devices, such as those employing engines or motors, but also to natural biological macromolecules, such as molecular machines.",
                    "Learning is the process of acquiring new understanding, knowledge, behaviors, skills, values, attitudes, and preferences. The ability to learn is possessed by humans, non-human animals, and some machines; there is also evidence for some kind of learning in certain plants.",
                    "Machine learning is a field of study in artificial intelligence concerned with the development and study of statistical algorithms that can learn from data and generalize to unseen data, and thus perform tasks without explicit instructions.",
                    "Paris, capitale de la France, est une grande ville européenne et un centre mondial de l'art, de la mode, de la gastronomie et de la culture. Son paysage urbain du XIXe siècle est traversé par de larges boulevards et la Seine.",
                )
            val body = """{"query":"Machine learning is","documents":${JsonArray(
                docs.map { JsonPrimitive(it) },
            )}}"""
            for (policy in listOf(
                DevicePolicy.Only(DeviceKind.Cpu),
                DevicePolicy.Only(DeviceKind.Gpu),
                DevicePolicy.Auto,
            )) {
                llama
                    .loadModel(ModelSource.file(file("jina-reranker-tiny-f16.gguf"))) {
                        task = ModelTask.Rerank
                        devicePolicy = policy
                    }.use { m ->
                        val results = call(m, Endpoint.Rerank, body).getValue("results").jsonArray.map { it.jsonObject }
                        Log.i("TaskDevice", "rerank $policy -> ${m.loadReport.device}: $results")
                        val byScore = results.sortedByDescending { it.getValue("relevance_score").jsonPrimitive.double }
                        // every chip ranks the relevant document first (upstream's test)
                        assertEquals(
                            "$policy $results",
                            2,
                            byScore
                                .first()
                                .getValue("index")
                                .jsonPrimitive.int,
                        )
                        if (m.loadReport.device == DeviceKind.Npu) {
                            // the NPU moves the near-zero scores (upstream llama-server on HTP0 gives the same: 0.0151, 0.0036,
                            // 0.2062, 0.0115), so document 1, not 3, comes last there (F69); compare with upstream's NPU run
                            val score =
                                results.associate {
                                    it.getValue("index").jsonPrimitive.int to
                                        it.getValue("relevance_score").jsonPrimitive.double
                                }
                            listOf(0.0151, 0.0036, 0.2062, 0.0115).forEachIndexed { i, s ->
                                assertTrue("$i: ${score[i]}", kotlin.math.abs(score.getValue(i) - s) < 1e-3)
                            }
                        } else {
                            assertEquals(
                                "$policy $results",
                                3,
                                byScore
                                    .last()
                                    .getValue("index")
                                    .jsonPrimitive.int,
                            )
                        }
                    }
            }
        }
}
