package com.suhel.llamabro

import com.suhel.llamabro.internal.LlamaBroRuntime
import com.suhel.llamabro.internal.MemoryBudget
import com.suhel.llamabro.spi.Engine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FitEstimateTest {
    @Test fun estimateFitUsesFirstLoadableDeviceAndSameBudget() =
        runBlocking {
            val fake =
                FakeEngine().apply {
                    backendsJson = """[{"name":"cpu","loaded":true,"error":null,"devices":["CPU"]},
                {"name":"opencl","loaded":true,"error":null,"devices":["GPUOpenCL"]},
                {"name":"hexagon","loaded":true,"error":null,"devices":["HTP0"]}]"""
                }
            var sent: String? = null
            val engine =
                object : Engine by fake {
                    override fun fitEstimate(configJson: String): String {
                        sent = configJson
                        return """{"fits":true,"n_ctx":8192,"n_ctx_min":4096,"used_min_mib":3805,
                    "used_max_mib":4300,"budget_mib":5632}"""
                    }
                }
            val gib = 1024L * 1024 * 1024
            val llama =
                LlamaBro(
                    LlamaBroRuntime(
                        engine,
                        tempDir(),
                        null,
                        LlamaBroConfig.Default,
                        memorySnapshot = { MemoryBudget.Snapshot(8 * gib, gib) },
                    ),
                )
            val source = ModelSource.file("/model.gguf") { draft = "/draft.gguf" }
            val estimate =
                llama.estimateFit(source) {
                    devicePolicy = DevicePolicy.Prefer(DeviceKind.Gpu)
                    speculative = Speculative.DFlash()
                }
            assertEquals(DeviceKind.Npu, estimate.device)
            assertEquals("HTP0", estimate.deviceName)
            assertEquals(true, estimate.fits)
            assertEquals(8192, estimate.contextTokens)
            assertEquals(4096, estimate.minContextTokens)
            assertEquals(3805L * 1024 * 1024, estimate.bytesAtMinimum)
            assertEquals(4300L * 1024 * 1024, estimate.bytesAtCeiling)
            assertEquals(5632L * 1024 * 1024, estimate.budgetBytes)
            assertEquals(FitEstimate.Method.Projection, estimate.method)
            val config = Json.parseToJsonElement(requireNotNull(sent)).jsonObject
            assertEquals(listOf("HTP0"), config.getValue("devices").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("5632", config.getValue("memory_budget_mib").jsonPrimitive.content)
            assertEquals("/draft.gguf", config.getValue("draft_path").jsonPrimitive.content)
            assertTrue(fake.loadConfigs.isEmpty())
        }

    @Test fun fakeEngineEstimateHasNullFits() =
        runBlocking {
            val estimate = testLlamaBro().estimateFit(ModelSource.file("/model.gguf"))
            assertNull(estimate.fits)
            assertNull(estimate.contextTokens)
            assertNull(estimate.budgetBytes)
            assertEquals(0L, estimate.bytesAtMinimum)
            assertEquals(FitEstimate.Method.Projection, estimate.method)
        }

    @Test fun dflashWithoutDraftFailsBeforeNativeInit() {
        val fake = FakeEngine()
        try {
            runBlocking {
                testLlamaBro(fake).estimateFit(ModelSource.file("/model.gguf")) {
                    speculative = Speculative.DFlash()
                }
            }
            fail("DFlash needs a draft")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("draft ="))
        }
        assertTrue(fake.initConfigs.isEmpty())
    }

    private val cpu = """{"name":"cpu","loaded":true,"error":null,"devices":["CPU"]}"""
    private val opencl = """{"name":"opencl","loaded":true,"error":null,"devices":["GPUOpenCL"]}"""

    @Test fun estimateSkipsTheGpuWhenDraftingAndNamesWhy() {
        for (spec in listOf(Speculative.Mtp(4), Speculative.Ngram(), Speculative.Ngram(then = Speculative.Mtp(4)))) {
            val fake = FakeEngine().apply { backendsJson = "[$cpu,$opencl]" }
            val estimate =
                runBlocking { testLlamaBro(fake).estimateFit(ModelSource.file("/m.gguf")) { speculative = spec } }
            assertEquals(spec.toString(), DeviceKind.Cpu, estimate.device)
            val plain = runBlocking { testLlamaBro(fake).estimateFit(ModelSource.file("/m.gguf")) }
            assertEquals(DeviceKind.Gpu, plain.device)
            try {
                runBlocking {
                    testLlamaBro(fake).estimateFit(ModelSource.file("/m.gguf")) {
                        speculative = spec
                        devicePolicy = DevicePolicy.Only(DeviceKind.Gpu)
                    }
                }
                fail("Only(Gpu) with $spec estimated")
            } catch (x: LlamaBroException.DeviceUnavailable) {
                assertEquals(DeviceKind.Gpu, x.kind)
                assertTrue(x.message, x.message!!.startsWith("no Gpu device could estimate the model: "))
                assertTrue(x.message, x.message!!.contains("OpenCL"))
            }
        }
    }

    private val hexagon = """{"name":"hexagon","loaded":true,"error":null,"devices":["HTP0"]}"""

    @Test fun estimateWithMtpGoesToTheNpuWhenThereIsOne() {
        val fake = FakeEngine().apply { backendsJson = "[$cpu,$opencl,$hexagon]" }
        val estimate =
            runBlocking {
                testLlamaBro(fake).estimateFit(ModelSource.file("/m.gguf")) { speculative = Speculative.Mtp(4) }
            }
        assertEquals(DeviceKind.Npu, estimate.device)
    }

    @Test fun estimateWithDFlashSkipsTheGpu() {
        val fake = FakeEngine().apply { backendsJson = "[$cpu,$opencl]" }
        val source = ModelSource.file("/m.gguf") { draft = "/d.gguf" }
        val estimate = runBlocking { testLlamaBro(fake).estimateFit(source) { speculative = Speculative.DFlash() } }
        assertEquals(DeviceKind.Cpu, estimate.device)
    }
}
