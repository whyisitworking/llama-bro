package com.suhel.llamabro.hexagon

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelConfig
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.Speculative
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

/** Compares SDK DFlash on HTP0 with the upstream greedy JSON reply from the draft-model spike. */
@RunWith(AndroidJUnit4::class)
class DflashDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val llama = LlamaBro.create(context)
    private val modelPath = File(context.getExternalFilesDir(null), "Qwen3.5-4B-Q4_K_M.gguf").absolutePath
    private val draftPath = File(context.getExternalFilesDir(null), "Qwen3.5-4B-DFlash-Q8_0.gguf").absolutePath

    private companion object {
        const val MIB = 1L shl 20
    }

    private fun asset(name: String): String =
        context.assets
            .open(name)
            .bufferedReader()
            .use { it.readText() }

    private suspend fun call(model: Model, endpoint: Endpoint, body: String): JsonObject =
        Json.parseToJsonElement(model.rawRequest(endpoint, body).single()).jsonObject

    private suspend fun document(model: Model): String {
        val source = asset("source.txt")
        val tokenBody = """{"content":${Json.encodeToString(JsonPrimitive(source))}}"""
        val tokens = call(model, Endpoint.Tokenize, tokenBody).getValue("tokens").jsonArray.take(2048)
        val detokenBody = """{"tokens":${JsonArray(tokens)}}"""
        return call(model, Endpoint.Detokenize, detokenBody).getValue("content").jsonPrimitive.content
    }

    @Test fun dflashMatchesUpstreamJsonAndDrafts() =
        runBlocking<Unit> {
            assertTrue("missing main GGUF: $modelPath", File(modelPath).isFile)
            assertTrue("missing DFlash GGUF: $draftPath", File(draftPath).isFile)
            // Auto first, with the draft first, while the most memory is free; each load waits for the last one's memory.
            for (withDraft in listOf(true, false)) {
                val settled = settleMemory()
                val candidate = ModelSource.file(modelPath) { if (withDraft) draft = draftPath }
                val config: ModelConfig.() -> Unit = {
                    devicePolicy = DevicePolicy.Only(DeviceKind.Npu)
                    contextSize = ContextSize.Auto
                    // The test is about DFlash, not the prompt cache, so it uses none (as F88's fit test): this phone, short
                    // of the draft's 6,060 MiB with the default 1 GiB cache, then has room and the success path runs (D45).
                    ramCacheMib = 0
                    speculative = if (withDraft) Speculative.DFlash() else Speculative.Off
                }
                val estimate = llama.estimateFit(candidate, config)
                val auto =
                    try {
                        llama.loadModel(candidate, config)
                    } catch (e: LlamaBroException.ModelLoadFailed) {
                        // With the draft, Qwen 4B needs about 6.1 GB at the minimum context (F90). On a phone with less even
                        // so, a refusal is the right answer; it must agree with the estimate made just before (memory moves).
                        if (!withDraft ||
                            e.reason != LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory
                        ) {
                            throw e
                        }
                        val (needed, available) =
                            Regex("""needs (\d+) MiB .* (\d+) MiB available""")
                                .find(e.message.orEmpty())
                                ?.destructured
                                ?.let { (n, a) -> n.toLong() to a.toLong() } ?: throw e
                        assertEquals("the load's minimum is the estimate's", estimate.bytesAtMinimum / MIB, needed)
                        assertTrue("a refusal has less than the minimum", available < needed)
                        estimate.budgetBytes?.let { budget ->
                            assertTrue(
                                "estimate budget ${budget / MIB} MiB vs load $available MiB",
                                kotlin.math.abs(budget / MIB - available) <= 256,
                            )
                        }
                        Log.i(
                            "DflashDevice",
                            "auto withDraft=true refused: needs $needed MiB, $available MiB available " +
                                "(estimate: fits=${estimate.fits}, budget=${estimate.budgetBytes?.div(
                                    MIB,
                                )} MiB), settled=$settled MiB",
                        )
                        continue
                    }
                try {
                    Log.i(
                        "DflashDevice",
                        "auto withDraft=$withDraft context=${auto.loadReport.contextSize}, MemAvailable settled=$settled MiB loaded=${memAvailableMiB()} MiB",
                    )
                } finally {
                    auto.closeAndJoin()
                }
            }

            val before = settleMemory()
            val source = ModelSource.file(modelPath) { draft = draftPath }
            val model =
                llama.loadModel(source) {
                    devicePolicy = DevicePolicy.Only(DeviceKind.Npu)
                    contextSize = ContextSize.Fixed(8192)
                    speculative = Speculative.DFlash()
                }
            try {
                assertEquals(DeviceKind.Npu, model.loadReport.device)
                assertEquals(Speculative.DFlash(), model.loadReport.speculative)
                val prompt =
                    "List every backend the document above describes as a JSON array of objects with the keys " +
                        "\"name\", \"cmake_flag\" and \"platforms\" (an array of strings). Output only the JSON."
                val content = document(model) + "\n\n" + prompt
                val body = """{"messages":[{"role":"user","content":${Json.encodeToString(
                    JsonPrimitive(content),
                )}}],"max_tokens":256,"seed":42,"temperature":0,"cache_prompt":false,"chat_template_kwargs":{"enable_thinking":false}}"""
                val reply = call(model, Endpoint.ChatCompletions, body)
                val actual =
                    reply
                        .getValue("choices")
                        .jsonArray[0]
                        .jsonObject
                        .getValue("message")
                        .jsonObject
                        .getValue("content")
                        .jsonPrimitive.content
                assertEquals(asset("npu-r1-dflash-json-greedy.txt"), actual)
                val timings = reply.getValue("timings").jsonObject
                val draftN = timings.getValue("draft_n").jsonPrimitive.int
                assertTrue("draft_n=$draftN", draftN > 0)
                Log.i(
                    "DflashDevice",
                    "fixed context=${model.loadReport.contextSize}, decode=${timings.getValue(
                        "predicted_per_second",
                    ).jsonPrimitive.double} t/s, draft_n=$draftN, MemAvailable before=$before MiB after reply=${memAvailableMiB()} MiB",
                )
            } finally {
                model.closeAndJoin()
            }
        }
}
