package com.suhel.llamabro

import com.suhel.llamabro.internal.BackendDiscovery
import com.suhel.llamabro.internal.BackendLibraries
import com.suhel.llamabro.internal.LlamaBroRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2 T2: backend artifacts found through the manifest, loaded by native init, failures reported, never fatal. */
class BackendsTest {
    private val hexagon = BackendLibraries(listOf("libllamabro-ggml-hexagon.so"), mapOf("ADSP_LIBRARY_PATH" to "/skel"))
    private val cpuOnly = Backend("cpu", loaded = true, error = null, devices = listOf("CPU"))

    private fun runtime(engine: FakeEngine, discover: () -> BackendDiscovery.Result) =
        LlamaBroRuntime(engine, tempDir(), null, LlamaBroConfig.Default, discoverBackends = discover)

    @Test
    fun noBackendArtifactsMeansNoBackendsKeyAndCpuOnly() = runBlocking {
        val engine = FakeEngine()
        val backends = testLlamaBro(engine).backends()
        assertFalse(engine.initConfigs.single(), "\"backends\":" in engine.initConfigs.single())
        assertEquals(listOf(cpuOnly), backends)
    }

    @Test
    fun discoveryReadsOnlyBackendEntriesInNameOrder() {
        val calls = mutableListOf<String>()
        val result = BackendDiscovery.discover(
            mapOf(
                "com.suhel.llamabro.backend.opencl" to "x.OpenCl",
                "unrelated.key" to 5,
                "com.suhel.llamabro.backend.hexagon" to "x.Hexagon",
            ),
        ) { cls -> calls += cls; if (cls == "x.Hexagon") hexagon else BackendLibraries(listOf("libllamabro-ggml-opencl.so")) }
        assertEquals(listOf("x.Hexagon", "x.OpenCl"), calls)
        assertEquals(emptyList<Backend>(), result.failed)
        assertEquals(
            """[{"name":"hexagon","files":["libllamabro-ggml-hexagon.so"],"env":{"ADSP_LIBRARY_PATH":"/skel"}},""" +
                """{"name":"opencl","files":["libllamabro-ggml-opencl.so"]}]""",
            result.toJson().toString(),
        )
    }

    @Test
    fun brokenInstallerIsReportedNotFatal() = runBlocking {
        val engine = FakeEngine()
        engine.backendsJson = """[{"name":"cpu","loaded":true,"error":null,"devices":["CPU"]},""" +
            """{"name":"hexagon","loaded":true,"error":null,"devices":["HTP0"]}]"""
        val rt = runtime(engine) {
            BackendDiscovery.discover(
                mapOf("com.suhel.llamabro.backend.hexagon" to "x.Hexagon", "com.suhel.llamabro.backend.vulkan" to "x.Vulkan"),
            ) { cls -> if (cls == "x.Vulkan") throw IllegalStateException("no driver") else hexagon }
        }
        val backends = rt.backends()
        // only the working backend reaches native init
        val config = engine.initConfigs.single()
        assertTrue(config, """"name":"hexagon"""" in config && "vulkan" !in config)
        assertEquals(listOf("cpu", "hexagon", "vulkan"), backends.map { it.name })
        assertEquals(listOf("HTP0"), backends[1].devices)
        val vulkan = backends[2]
        assertFalse(vulkan.loaded)
        assertEquals("installer x.Vulkan failed: java.lang.IllegalStateException: no driver", vulkan.error)
        // and a model still loads
        LlamaBro(rt).loadModel(ModelSource.file("/m.gguf"))
        Unit
    }

    @Test
    fun badEntriesAreReportedWithReasons() {
        val result = BackendDiscovery.discover(
            mapOf(
                "com.suhel.llamabro.backend." to "x.Empty",
                "com.suhel.llamabro.backend.cpu" to "x.Cpu",
                "com.suhel.llamabro.backend.notastring" to 42,
                "com.suhel.llamabro.backend.nofiles" to "x.NoFiles",
                "com.suhel.llamabro.backend.blankfile" to "x.BlankFile",
                "com.suhel.llamabro.backend.badenv" to "x.BadEnv",
                "com.suhel.llamabro.backend.good" to "x.Good",
            ),
        ) { cls ->
            when (cls) {
                "x.BlankFile" -> BackendLibraries(listOf(" "))
                "x.BadEnv" -> BackendLibraries(listOf("libx.so"), mapOf("A=B" to "1"))
                "x.Good" -> BackendLibraries(listOf("libgood.so"))
                else -> BackendLibraries(emptyList())
            }
        }
        // one bad installer never takes the others (or the runtime) down: native would reject the whole config
        assertEquals(listOf("good"), result.found.map { it.name })
        assertEquals(
            mapOf(
                "" to "invalid backend name in <meta-data> com.suhel.llamabro.backend.",
                "badenv" to "installer x.BadEnv set an invalid environment variable name",
                "blankfile" to "installer x.BlankFile named a blank library file",
                "cpu" to "invalid backend name in <meta-data> com.suhel.llamabro.backend.cpu",
                "nofiles" to "installer x.NoFiles named no libraries",
                "notastring" to "<meta-data> com.suhel.llamabro.backend.notastring must name the backend's installer class",
            ),
            result.failed.associate { it.name to it.error },
        )
    }

    @Test
    fun nativeFailureCarriesItsReason() = runBlocking {
        val engine = FakeEngine()
        engine.backendsJson = """[{"name":"cpu","loaded":true,"error":null,"devices":["CPU"]},""" +
            """{"name":"hexagon","loaded":false,"error":"load_backend: failed to load libllamabro-ggml-hexagon.so: dlopen failed","devices":[]}]"""
        val backends = runtime(engine) { BackendDiscovery.discover(mapOf("com.suhel.llamabro.backend.hexagon" to "x.H")) { hexagon } }.backends()
        assertEquals(
            Backend("hexagon", loaded = false, error = "load_backend: failed to load libllamabro-ggml-hexagon.so: dlopen failed", devices = emptyList()),
            backends[1],
        )
    }

    @Test
    fun discoveryRunsOncePerInit() = runBlocking {
        var runs = 0
        val rt = runtime(FakeEngine()) { runs++; BackendDiscovery.Result.None }
        rt.backends()
        rt.devices()
        LlamaBro(rt).loadModel(ModelSource.file("/m.gguf"))
        assertEquals(1, runs)
    }
}
