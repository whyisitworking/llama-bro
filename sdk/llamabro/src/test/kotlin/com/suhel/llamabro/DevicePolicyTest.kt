package com.suhel.llamabro

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** P2 T6: DevicePolicy resolution and loadReport, over FakeEngine (D24, D31, D32). */
class DevicePolicyTest {
    private val cpu = """{"name":"cpu","loaded":true,"error":null,"devices":["CPU"]}"""
    private val hexagon = """{"name":"hexagon","loaded":true,"error":null,"devices":["HTP0"]}"""
    private val opencl = """{"name":"opencl","loaded":true,"error":null,"devices":["GPUOpenCL"]}"""

    private fun engine(vararg backends: String) =
        FakeEngine().apply { backendsJson = "[${backends.joinToString(",")}]" }

    private fun devicesOf(config: String): List<String> =
        (Json.parseToJsonElement(config) as JsonObject).getValue("devices").jsonArray.map { it.jsonPrimitive.content }

    private fun failOn(device: String, error: String = "boom on $device") =
        { config: String ->
            if (devicesOf(config) == listOf(device)) """{"error":"$error"}""" else null
        }

    private fun load(engine: FakeEngine, configure: ModelConfig.() -> Unit = {}): Model =
        runBlocking { testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"), configure) }

    @Test
    fun explicitDeviceAlways() {
        val cpuOnly = engine(cpu)
        load(cpuOnly)
        // CPU only, never llama.cpp's default
        assertEquals(emptyList<String>(), devicesOf(cpuOnly.loadConfigs.single()))
        assertFalse("n_gpu_layers" in cpuOnly.loadConfigs.single())
        val withNpu = engine(cpu, hexagon)
        load(withNpu)
        val config = withNpu.loadConfigs.single()
        assertEquals(listOf("HTP0"), devicesOf(config))
        assertTrue(config, """"n_gpu_layers":999""" in config)
    }

    @Test
    fun autoOrderIsNpuGpuCpu() {
        val e = engine(cpu, opencl, hexagon)
        e.loadError = { config -> if (devicesOf(config).isNotEmpty()) """{"error":"no"}""" else null } // only CPU loads
        val model = load(e)
        assertEquals(listOf(listOf("HTP0"), listOf("GPUOpenCL"), emptyList()), e.loadConfigs.map(::devicesOf))
        assertEquals(DeviceKind.Cpu, model.loadReport.device)
        assertEquals(listOf(DeviceKind.Npu, DeviceKind.Gpu), model.loadReport.attempts.map { it.kind })
    }

    @Test
    fun draftingNeverLoadsOnTheGpu() { // drafted batches abort ggml-opencl, which no try can catch (F67, F80)
        for (spec in listOf(Speculative.Mtp(4), Speculative.Ngram(), Speculative.Ngram(then = Speculative.Mtp(4)))) {
            val e = engine(cpu, opencl, hexagon)
            e.loadError = failOn("HTP0")
            val model = load(e) { speculative = spec }
            // GPU never tried
            assertEquals(spec.toString(), listOf(listOf("HTP0"), emptyList()), e.loadConfigs.map(::devicesOf))
            assertEquals(DeviceKind.Cpu, model.loadReport.device)
            val gpu = model.loadReport.attempts.single { it.kind == DeviceKind.Gpu }
            assertEquals("GPUOpenCL", gpu.deviceName)
            assertTrue(gpu.reason, gpu.reason.contains("F80"))
            try {
                load(engine(cpu, opencl)) {
                    speculative = spec
                    devicePolicy = DevicePolicy.Only(DeviceKind.Gpu)
                }
                fail("Only(Gpu) with $spec loaded")
            } catch (x: LlamaBroException.DeviceUnavailable) {
                assertEquals(DeviceKind.Gpu, x.kind)
                assertTrue(x.message, x.message!!.contains("F80"))
            }
        }
        // without drafting the GPU is tried as before
        val plain = engine(cpu, opencl)
        assertEquals(DeviceKind.Gpu, load(plain).loadReport.device)
    }

    @Test
    fun dflashSkipsGpu() {
        val source = ModelSource.file("/m.gguf") { draft = "/draft.gguf" }
        val e = engine(cpu, opencl, hexagon).apply { loadError = failOn("HTP0") }
        val model = runBlocking { testLlamaBro(e).loadModel(source) { speculative = Speculative.DFlash() } }
        assertEquals(listOf(listOf("HTP0"), emptyList<String>()), e.loadConfigs.map(::devicesOf))
        assertEquals(DeviceKind.Cpu, model.loadReport.device)
        assertTrue(
            model.loadReport.attempts
                .single { it.kind == DeviceKind.Gpu }
                .reason
                .contains("DFlash"),
        )
        try {
            runBlocking {
                testLlamaBro(engine(cpu, opencl)).loadModel(source) {
                    speculative = Speculative.DFlash()
                    devicePolicy = DevicePolicy.Only(DeviceKind.Gpu)
                }
            }
            fail("DFlash loaded on GPU")
        } catch (error: LlamaBroException.DeviceUnavailable) {
            assertEquals(DeviceKind.Gpu, error.kind)
            assertTrue(error.message!!.contains("DFlash"))
        }
    }

    @Test
    fun autoPicksNpuFirst() {
        val model = load(engine(cpu, opencl, hexagon))
        val r = model.loadReport
        assertEquals(
            listOf(DeviceKind.Npu, "HTP0", "hexagon", emptyList<LoadReport.Attempt>()),
            listOf(r.device, r.deviceName, r.backend, r.attempts),
        )
    }

    @Test
    fun autoFallsBackWhenNpuLoadFails() {
        val e = engine(cpu, opencl, hexagon).apply { loadError = failOn("HTP0", "ggml-hex: failed to open session") }
        val report = load(e).loadReport
        assertEquals(DeviceKind.Gpu, report.device)
        assertEquals("GPUOpenCL", report.deviceName)
        assertEquals(
            listOf(LoadReport.Attempt(DeviceKind.Npu, "HTP0", "ggml-hex: failed to open session")),
            report.attempts,
        )
    }

    @Test
    fun preferPutsItsKindFirstThenAutoOrder() {
        val e = engine(cpu, opencl, hexagon).apply { loadError = failOn("GPUOpenCL") }
        val report = load(e) { devicePolicy = DevicePolicy.Prefer(DeviceKind.Gpu) }.loadReport
        assertEquals(listOf(listOf("GPUOpenCL"), listOf("HTP0")), e.loadConfigs.map(::devicesOf))
        assertEquals(DeviceKind.Npu, report.device)
    }

    @Test
    fun onlyThrowsTypedError() {
        // the kind isn't in this app
        try {
            load(engine(cpu)) { devicePolicy = DevicePolicy.Only(DeviceKind.Npu) }
            fail()
        } catch (e: LlamaBroException.DeviceUnavailable) {
            assertEquals(DeviceKind.Npu, e.kind)
            assertTrue(e.message!!, "no Npu backend in this app" in e.message!!)
        }
        // it's there but the load fails: no fallback to another kind
        val failing = engine(cpu, hexagon).apply { loadError = failOn("HTP0") }
        try {
            load(failing) { devicePolicy = DevicePolicy.Only(DeviceKind.Npu) }
            fail()
        } catch (e: LlamaBroException.DeviceUnavailable) {
            assertTrue(e.message!!, "HTP0: boom on HTP0" in e.message!!)
            assertTrue(e.cause is LlamaBroException.ModelLoadFailed)
            assertEquals(1, failing.loadConfigs.size)
        }
    }

    @Test
    fun failedBackendIsSkippedWithItsReason() {
        val broken =
            """{"name":"hexagon","loaded":false,"error":"dlopen failed: libcdsprpc.so not found",""" +
                """"devices":[]}"""
        val report = load(engine(cpu, broken, opencl)).loadReport
        assertEquals(DeviceKind.Gpu, report.device)
        assertEquals(
            listOf(
                LoadReport.Attempt(DeviceKind.Npu, null, "hexagon didn't load: dlopen failed: libcdsprpc.so not found"),
            ),
            report.attempts,
        )
    }

    @Test
    fun missingFileStopsAtOnce() {
        val e = engine(cpu, opencl, hexagon)
        e.loadError = { """{"error":"failed to load model: no such file","code":"file_not_found"}""" }
        try {
            load(e)
            fail()
        } catch (ex: LlamaBroException.ModelLoadFailed) {
            assertEquals(LlamaBroException.ModelLoadFailed.Reason.FileNotFound, ex.reason)
        }
        assertEquals(1, e.loadConfigs.size)
    }

    @Test
    fun cpuFailingUnderAutoThrowsItsOwnError() {
        val e = engine(cpu).apply { loadError = { """{"error":"bad gguf"}""" } }
        try {
            load(e)
            fail()
        } catch (ex: LlamaBroException.ModelLoadFailed) {
            assertEquals("bad gguf", ex.message)
        }
    }

    @Test
    fun devicesCarryTheirKindFromTheirBackend() {
        val e = engine(cpu, hexagon, opencl)
        e.devicesJson = """[{"name":"CPU","description":"cpu","type":"cpu","memory_free":1,"memory_total":2},""" +
            """{"name":"HTP0","description":"Hexagon","type":"gpu","memory_free":0,"memory_total":0},""" +
            """{"name":"GPUOpenCL","description":"Adreno","type":"gpu","memory_free":1,"memory_total":2}]"""
        val kinds = runBlocking { testLlamaBro(e).devices() }.associate { it.name to it.kind }
        assertEquals(mapOf("CPU" to DeviceKind.Cpu, "HTP0" to DeviceKind.Npu, "GPUOpenCL" to DeviceKind.Gpu), kinds)
    }
}
