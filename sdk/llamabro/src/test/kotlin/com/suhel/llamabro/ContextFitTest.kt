package com.suhel.llamabro

import com.suhel.llamabro.internal.LlamaBroRuntime
import com.suhel.llamabro.internal.MemoryBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** `ContextSize.Auto` fitted to Android's available memory (D33, F66). */
class ContextFitTest {
    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024

    @Test
    fun budgetFromMemoryInfo() {
        // 7 GiB available, 1 GiB low-memory threshold, 1 GiB prompt cache, a 640 MiB image encoder, 512 MiB reserve
        assertEquals(7L * 1024 - 1024 - 640 - 1024 - 512, MemoryBudget.budgetMib(7 * gib, gib, 1024, 640 * mib))
        // never zero or negative: native then reports InsufficientMemory with the numbers
        assertEquals(1L, MemoryBudget.budgetMib(gib, gib, 1024, 0))
    }

    private fun runtime(engine: FakeEngine, avail: Long?) =
        LlamaBroRuntime(
            engine,
            tempDir(),
            null,
            LlamaBroConfig.Default,
            memorySnapshot = { avail?.let { MemoryBudget.Snapshot(it, gib) } },
        )

    private fun budgetIn(config: String): Long? =
        (Json.parseToJsonElement(config) as JsonObject)["memory_budget_mib"]?.jsonPrimitive?.long

    private fun capIn(config: String): Long? =
        (Json.parseToJsonElement(config) as JsonObject)["n_ctx_cap"]?.jsonPrimitive?.long

    @Test
    fun aLoadRightAfterACloseWaitsForTheMemoryToComeBack() =
        runBlocking {
            // F124: the NPU gives a closed model's memory back about a second after its native close returns
            val readings = ArrayDeque(listOf(2L, 3L, 5L, 6L, 6L).map { it * gib })
            var last = readings.first()
            val e = FakeEngine()
            val llama =
                LlamaBro(
                    LlamaBroRuntime(
                        e,
                        tempDir(),
                        null,
                        LlamaBroConfig.Default,
                        memorySnapshot = {
                            if (readings.isNotEmpty()) last = readings.removeFirst()
                            MemoryBudget.Snapshot(last, gib)
                        },
                    ),
                )
            llama.loadModel(ModelSource.file("/a.gguf")).closeAndJoin()
            readings.clear()
            readings.addAll(listOf(2L, 3L, 5L, 6L, 6L).map { it * gib })
            llama.loadModel(ModelSource.file("/b.gguf")).close()
            // the budget is read once the memory stopped rising: 6 GiB, not the 2 GiB right after the close
            assertEquals(6L * 1024 - 1024 - 1024 - 512, budgetIn(e.loadConfigs.last()))
        }

    @Test
    fun twoLoadsAtOnceEachFitToTheMemoryTheOtherLeft() =
        runBlocking {
            // A2: without one load at a time, both read 8 GiB free before either holds its 3 GiB, and both fit to it
            val loaded = AtomicInteger()
            val reads = CountDownLatch(2)
            val e =
                FakeEngine().apply {
                    progress = listOf(0.5f)
                    // the first load holds on until the other load has read the memory too, or a while if it can't
                    onStep = { reads.await(LOAD_OVERLAP_MILLIS, TimeUnit.MILLISECONDS) }
                    makeModel = { FakeModel().also { loaded.incrementAndGet() } }
                }
            val llama =
                LlamaBro(
                    LlamaBroRuntime(
                        e,
                        tempDir(),
                        null,
                        LlamaBroConfig.Default,
                        memorySnapshot = {
                            reads.countDown()
                            MemoryBudget.Snapshot(8 * gib - loaded.get() * 3 * gib, gib)
                        },
                    ),
                )
            listOf("/a.gguf", "/b.gguf")
                .map { path -> async(Dispatchers.Default) { llama.loadModel(ModelSource.file(path)) } }
                .awaitAll()
                .forEach { it.close() }
            val budgets = synchronized(e.loadConfigs) { e.loadConfigs.map { budgetIn(it) } }
            assertEquals(listOf(8L * 1024 - 2560, 5L * 1024 - 2560), budgets.sortedByDescending { it })
        }

    @Test
    fun memAvailableFromProcMeminfo() {
        val meminfo =
            "MemTotal:       15469920 kB\nMemFree:         1199396 kB\nMemAvailable:    8239044 kB\n" +
                "KReclaimable:    4383000 kB\n"
        assertEquals(8239044L * 1024, MemoryBudget.memAvailable(meminfo))
        assertEquals(null, MemoryBudget.memAvailable("MemTotal: 1 kB\n"))
    }

    @Test
    fun autoCeilingIs16kAndUpToSendsItsOwn() =
        runBlocking {
            val e = FakeEngine()
            LlamaBro(runtime(e, 8 * gib)).loadModel(ModelSource.file("/m.gguf")).close()
            assertEquals(16384L, capIn(e.loadConfigs.last()))
            LlamaBro(
                runtime(e, 8 * gib),
            ).loadModel(ModelSource.file("/m.gguf")) { contextSize = ContextSize.UpTo(65536) }.close()
            assertEquals(65536L, capIn(e.loadConfigs.last()))
            assertEquals(8L * 1024 - 1024 - 1024 - 512, budgetIn(e.loadConfigs.last()))
            LlamaBro(
                runtime(e, 8 * gib),
            ).loadModel(ModelSource.file("/m.gguf")) { contextSize = ContextSize.Fixed(4096) }.close()
            assertEquals(null, capIn(e.loadConfigs.last()))
        }

    @Test
    fun autoSendsTheBudgetFixedAndNoMemoryInfoDont() =
        runBlocking {
            val e = FakeEngine()
            LlamaBro(runtime(e, 8 * gib)).loadModel(ModelSource.file("/m.gguf")).close()
            assertEquals(8L * 1024 - 1024 - 1024 - 512, budgetIn(e.loadConfigs.last()))
            LlamaBro(
                runtime(e, 8 * gib),
            ).loadModel(ModelSource.file("/m.gguf")) { contextSize = ContextSize.Fixed(4096) }.close()
            assertEquals(null, budgetIn(e.loadConfigs.last()))
            LlamaBro(runtime(e, null)).loadModel(ModelSource.file("/m.gguf")).close()
            assertEquals(null, budgetIn(e.loadConfigs.last()))
            // the prompt cache comes out of the budget
            LlamaBro(runtime(e, 8 * gib)).loadModel(ModelSource.file("/m.gguf")) { ramCacheMib = 0 }.close()
            assertEquals(8L * 1024 - 1024 - 512, budgetIn(e.loadConfigs.last()))
        }

    @Test
    fun memoryReserveIsTheAppsToLowerOrRaise() =
        runBlocking {
            val e = FakeEngine()
            // D45: the reserve defaults to D34's 512 MiB and is the app's to change, for Auto and UpTo and the estimate
            // alike
            assertEquals(MemoryBudget.RESERVE_MIB, ModelConfig.Default.memoryReserveMib.toLong())
            LlamaBro(runtime(e, 8 * gib)).loadModel(ModelSource.file("/m.gguf")) { memoryReserveMib = 128 }.close()
            assertEquals(8L * 1024 - 1024 - 1024 - 128, budgetIn(e.loadConfigs.last()))
            LlamaBro(runtime(e, 8 * gib))
                .loadModel(ModelSource.file("/m.gguf")) {
                    contextSize = ContextSize.UpTo(8192)
                    memoryReserveMib = 2048
                }.close()
            assertEquals(8L * 1024 - 1024 - 1024 - 2048, budgetIn(e.loadConfigs.last()))
            val estimate =
                LlamaBro(
                    runtime(e, 8 * gib),
                ).estimateFit(ModelSource.file("/m.gguf")) { memoryReserveMib = 0 }
            assertEquals(8L * 1024 - 1024 - 1024, budgetIn(e.fitConfigs.last()))
            try {
                LlamaBro(runtime(e, 8 * gib)).loadModel(ModelSource.file("/m.gguf")) { memoryReserveMib = -1 }
                fail("a negative reserve is refused")
            } catch (ex: IllegalArgumentException) {
                assertTrue(ex.message!!.contains("memoryReserveMib"))
            }
        }

    @Test
    fun insufficientMemoryStopsDeviceFallback() {
        val e =
            FakeEngine().apply {
                backendsJson = """[{"name":"cpu","loaded":true,"error":null,"devices":["CPU"]},""" +
                    """{"name":"hexagon","loaded":true,"error":null,"devices":["HTP0"]}]"""
                loadError =
                    { """{"error":"failed to load model: not enough memory: ...","code":"insufficient_memory"}""" }
            }
        try {
            runBlocking {
                LlamaBro(runtime(e, gib)).loadModel(ModelSource.file("/m.gguf")) { memoryCheck = MemoryCheck.Refuse }
            }
            fail()
        } catch (ex: LlamaBroException.ModelLoadFailed) {
            assertEquals(LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory, ex.reason)
        }
        assertEquals(1, e.loadConfigs.size) // the CPU shares the same RAM: no point trying it
    }

    /** What the native fit check does when even the smallest context is over the budget (config.cpp). */
    private fun refusesBudgetedLoads() =
        FakeEngine().apply {
            loadError = { config ->
                if ("memory_budget_mib" in config) {
                    """{"error":"failed to load model: not enough memory: the model needs 5000 MiB at the smallest """ +
                        """context (4096 tokens), 3000 MiB available","code":"insufficient_memory"}"""
                } else {
                    null
                }
            }
        }

    @Test
    fun aModelOverTheBudgetLoadsAtTheSmallestContextWithAWarning() {
        val e = refusesBudgetedLoads()
        val model = runBlocking { LlamaBro(runtime(e, gib)).loadModel(ModelSource.file("/m.gguf")) }
        assertEquals(2, e.loadConfigs.size)
        val retry = Json.parseToJsonElement(e.loadConfigs.last()) as JsonObject
        assertEquals(4096, retry["n_ctx"]!!.jsonPrimitive.int)
        assertEquals(null, retry["memory_budget_mib"])
        assertEquals(
            "not enough memory: the model needs 5000 MiB at the smallest context (4096 tokens), 3000 MiB available",
            model.loadReport.memoryWarning,
        )
        assertTrue(model.loadReport.contextReason.contains("MemoryCheck.Warn"))
    }

    @Test
    fun aSmallerCeilingIsTheSmallestContext() {
        val e = refusesBudgetedLoads()
        runBlocking {
            LlamaBro(runtime(e, gib)).loadModel(ModelSource.file("/m.gguf")) {
                contextSize = ContextSize.UpTo(2048)
            }
        }
        val retry = Json.parseToJsonElement(e.loadConfigs.last()) as JsonObject
        assertEquals(2048, retry["n_ctx"]!!.jsonPrimitive.int)
    }

    @Test
    fun aModelThatFitsHasNoWarning() {
        val model = runBlocking { LlamaBro(runtime(FakeEngine(), 8 * gib)).loadModel(ModelSource.file("/m.gguf")) }
        assertEquals(null, model.loadReport.memoryWarning)
    }

    private fun loadWithInfo(info: String, configure: ModelConfig.Builder.() -> Unit = {}): LoadReport {
        val e = FakeEngine().apply { model.infoJson = info }
        return runBlocking {
            LlamaBro(
                runtime(e, 8 * gib),
            ).loadModel(ModelSource.file("/m.gguf"), configure = configure)
        }.loadReport
    }

    private fun info(nCtx: Int, fit: String?) =
        """{"devices":["CPU"],"ctx_fit":${fit ?: "null"},"n_ctx":$nCtx,"n_ctx_train":262144,"n_params":1,"size":1,""" +
            """"desc":"x","metadata":{},"props":{}}"""

    @Test
    fun contextReasonSaysWhy() {
        val fitted =
            loadWithInfo(
                info(
                    37632,
                    """{"budget_mib":5000,"n_ctx_train":262144,"n_ctx_min":4096,"used_min_mib":3500,"n_ctx_max":262144,"used_max_mib":9100}""",
                ),
            )
        assertEquals(37632, fitted.contextSize)
        assertEquals("fitted to 5000 MiB available: 3500 MiB at 4096 tokens, 9100 MiB at 262144", fitted.contextReason)
        val whole =
            loadWithInfo(
                info(
                    262144,
                    """{"budget_mib":12000,"n_ctx_train":262144,"n_ctx_cap":262144,"n_ctx_min":4096,"used_min_mib":3500,"n_ctx_max":262144,"used_max_mib":9100,"n_ctx":262144}""",
                ),
            )
        assertEquals("the training context fits: 9100 MiB of 12000 MiB available", whole.contextReason)
        val ceiling =
            loadWithInfo(
                info(
                    16384,
                    """{"budget_mib":6000,"n_ctx_train":262144,"n_ctx_cap":16384,"n_ctx_min":4096,"used_min_mib":3500,"n_ctx_max":16384,"used_max_mib":4100,"n_ctx":16384}""",
                ),
            )
        assertEquals("the ceiling of 16384 tokens fits: 4100 MiB of 6000 MiB available", ceiling.contextReason)
        assertEquals(
            "set by the app",
            loadWithInfo(info(4096, null)) { contextSize = ContextSize.Fixed(4096) }.contextReason,
        )
        val noInfo = loadWithInfo(info(8192, null))
        assertTrue(noInfo.contextReason, "no memory figure" in noInfo.contextReason)
        assertFalse(noInfo.contextReason.isBlank())
    }
}

private const val LOAD_OVERLAP_MILLIS = 500L
