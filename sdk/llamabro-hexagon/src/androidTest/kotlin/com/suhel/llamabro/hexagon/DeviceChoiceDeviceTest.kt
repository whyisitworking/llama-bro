package com.suhel.llamabro.hexagon

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.LoadReport
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2 T6 Step 4, with both backend artifacts in the test APK (until T10's `full` flavor): `Auto` picks the NPU, and
 * `Only` loads and chats on each kind. The model is Qwen3.5 4B, copied into this app's external files directory by
 * sdk/scripts/check.sh.
 */
@RunWith(AndroidJUnit4::class)
class DeviceChoiceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val llama = LlamaBro.getInstance(context)
    private val model = File(context.getExternalFilesDir(null), "Qwen3.5-4B-Q4_K_M.gguf").path

    private fun log(msg: String) = Log.i("DeviceChoice", msg)

    private fun load(policy: DevicePolicy): Model = runBlocking {
        val mi = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        log("memory before $policy: availMem=${mi.availMem shr 20} MiB threshold=${mi.threshold shr 20} MiB totalMem=${mi.totalMem shr 20} MiB lowMemory=${mi.lowMemory}")
        val t0 = System.nanoTime()
        llama.loadModel(ModelSource.file(model)) { devicePolicy = policy }.also {
            log("$policy -> ${it.loadReport} in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    /** The T1 probe's request: 16 greedy tokens of llama-server's native completion. */
    private fun Model.greedy16(): String = runBlocking {
        val body = """{"prompt":"Write a short story about a lighthouse keeper.","n_predict":16,"temperature":0,"cache_prompt":false}"""
        (Json.parseToJsonElement(rawRequest(Endpoint.Completion, body).single()) as JsonObject).getValue("content").jsonPrimitive.content
    }

    /**
     * A prompt with one right answer, so a backend that answers garbage fails (F71: identical garbage passed a
     * same-twice check). Short on purpose: llama-server sends its last 4 tokens as a batch of their own (for
     * Qwen3.5's checkpoints), the 2–4-row matmuls that ggml-opencl's coopk kernel got wrong (F75).
     */
    private fun Model.capital(): String = runBlocking {
        val body = """{"prompt":"The capital of France is","n_predict":8,"temperature":0,"cache_prompt":false}"""
        (Json.parseToJsonElement(rawRequest(Endpoint.Completion, body).single()) as JsonObject).getValue("content").jsonPrimitive.content
    }

    private fun assertReport(r: LoadReport, kind: DeviceKind, device: String, backend: String) {
        assertEquals(r.toString(), kind, r.device)
        assertEquals(r.toString(), device, r.deviceName)
        assertEquals(r.toString(), backend, r.backend)
        assertEquals(r.toString(), emptyList<LoadReport.Attempt>(), r.attempts)
        // ContextSize.Auto is fitted to Android's available memory (P2 T7, D33)
        assertTrue(r.toString(), r.contextSize >= 4096)
        assertTrue(r.toString(), listOf("fitted to", "the ceiling of", "the training context fits").any { r.contextReason.startsWith(it) })
        assertTrue(r.toString(), r.contextSize <= com.suhel.llamabro.ContextSize.AUTO_CEILING)
        // Speculative.Auto leaves Qwen3.5's MTP head off on every chip (P2 T8, F67)
        assertEquals(r.toString(), com.suhel.llamabro.Speculative.Off, r.speculative)
        assertTrue(r.toString(), r.speculativeReason.startsWith("off: MTP"))
    }

    @Test
    fun autoPicksTheNpuAndMatchesTheShellRun() {
        load(DevicePolicy.Auto).use { m ->
            assertReport(m.loadReport, DeviceKind.Npu, "HTP0", "hexagon")
            // F36: the shell user's and the T1 probe app's reply on HTP0 (NPU output is deterministic, F4)
            assertEquals("\n\n<think>\nHere's a thinking process that leads to the story above:\n\n", m.greedy16())
        }
    }

    @Test
    fun onlyGpuLoadsAnswersRightAndIsDeterministic() {
        load(DevicePolicy.Only(DeviceKind.Gpu)).use { m ->
            assertEquals(DeviceKind.Gpu, m.loadReport.device)
            assertEquals("GPUOpenCL", m.loadReport.deviceName)
            val capital = m.capital()
            log("Only(Gpu) capital = ${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(capital))}")
            assertTrue(capital, "Paris" in capital)
            val first = m.greedy16()
            log("Only(Gpu) greedy16 = ${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(first))}")
            assertTrue(first, first.isNotBlank())
            assertEquals(first, m.greedy16())
        }
    }

    @Test
    fun onlyCpuLoadsAndChats() {
        load(DevicePolicy.Only(DeviceKind.Cpu)).use { m ->
            assertReport(m.loadReport, DeviceKind.Cpu, "CPU", "cpu")
            assertTrue(m.greedy16().isNotBlank())
            val capital = m.capital()
            assertTrue(capital, "Paris" in capital)
        }
    }

    @Test
    fun devicesHaveTheirKinds() = runBlocking {
        val kinds = llama.devices().associate { it.name to it.kind }
        assertEquals(DeviceKind.Npu, kinds["HTP0"])
        assertEquals(DeviceKind.Gpu, kinds["GPUOpenCL"])
        assertEquals(DeviceKind.Cpu, kinds["CPU"])
    }
}
