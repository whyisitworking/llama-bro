package com.suhel.llamabro

import com.suhel.llamabro.internal.BackendDiscovery
import com.suhel.llamabro.internal.LlamaBroRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeTest {
    @Test
    fun loggerReceivesNativeLogsAtOrAboveLevel() {
        val engine = FakeEngine()
        val lines = mutableListOf<Pair<LogLevel, String>>()
        val runtime =
            LlamaBroRuntime(
                engine,
                tempDir(),
                "/b",
                LlamaBroConfig {
                    logLevel = LogLevel.Warn
                    logger =
                        Logger { level, msg -> lines += level to msg }
                },
            )
        runBlocking { runtime.devices() }
        val log = engine.logListener!!
        log(1, "info line\n")
        log(2, "warn line\n")
        assertEquals(listOf(LogLevel.Warn to "warn line"), lines)
    }

    private class Lines {
        val all = mutableListOf<Pair<LogLevel, String>>()
        val logger = Logger { level, msg -> all += level to msg }
    }

    private fun runtime(
        engine: FakeEngine,
        config: LlamaBroConfig,
        defaultLogger: Logger? = null,
        discover: () -> BackendDiscovery.Result = { BackendDiscovery.Result.None },
    ) = LlamaBroRuntime(engine, tempDir(), "/b", config, discoverBackends = discover, defaultLogger = defaultLogger)

    @Test
    fun loggerIsAttachedBeforeNativeInit() {
        val engine = FakeEngine()
        runBlocking { runtime(engine, LlamaBroConfig { logger = Logger { _, _ -> } }).devices() }
        assertEquals(listOf("setLog", "init"), engine.calls)
    }

    @Test
    fun failingInitStillDeliversItsLogLines() {
        val engine = FakeEngine()
        engine.initLines = listOf(3 to "ggml: backend failed\n")
        engine.initResult = { """{"status":1,"error":"boom"}""" }
        val lines = Lines()
        val runtime = runtime(engine, LlamaBroConfig { logger = lines.logger })
        assertThrows(LlamaBroException.InvalidRequest::class.java) { runBlocking { runtime.devices() } }
        assertEquals(listOf(LogLevel.Error to "ggml: backend failed"), lines.all)
    }

    @Test
    fun defaultLoggerReceivesLinesWhenAppGivesNone() {
        val engine = FakeEngine()
        engine.initLines = listOf(2 to "ggml: warn\n")
        val lines = Lines()
        runBlocking { runtime(engine, LlamaBroConfig.Default, defaultLogger = lines.logger).devices() }
        assertEquals(listOf(LogLevel.Warn to "ggml: warn"), lines.all)
    }

    @Test
    fun appLoggerWinsOverDefault() {
        val engine = FakeEngine()
        engine.initLines = listOf(2 to "ggml: warn\n")
        val app = Lines()
        val default = Lines()
        runBlocking {
            runtime(engine, LlamaBroConfig { logger = app.logger }, defaultLogger = default.logger).devices()
        }
        assertEquals(listOf(LogLevel.Warn to "ggml: warn"), app.all)
        assertEquals(emptyList<Pair<LogLevel, String>>(), default.all)
    }

    @Test
    fun throwingLoggerDoesNotBreakInit() {
        val engine = FakeEngine()
        engine.initLines = listOf(3 to "ggml: error\n")
        val runtime = runtime(engine, LlamaBroConfig { logger = Logger { _, _ -> error("logger broke") } })
        assertEquals(1, runBlocking { runtime.devices() }.size)
    }

    @Test
    fun linesAreNotDuplicatedAfterASecondCall() =
        runBlocking {
            val engine = FakeEngine()
            val lines = Lines()
            val runtime = runtime(engine, LlamaBroConfig { logger = lines.logger })
            runtime.devices()
            runtime.devices()
            runtime.load(ModelSource.file("/m.gguf"), ModelConfig.Default)
            engine.logListener!!(3, "once\n")
            assertEquals(listOf(LogLevel.Error to "once"), lines.all)
        }

    @Test
    fun backendThatFailsDiscoveryIsLoggedAsWarning() {
        val lines = Lines()
        val failed = Backend("hexagon", loaded = false, error = "installer threw: boom", devices = emptyList())
        runBlocking {
            runtime(
                FakeEngine(),
                LlamaBroConfig { logger = lines.logger },
                discover = { BackendDiscovery.Result(emptyList(), listOf(failed)) },
            ).devices()
        }
        val (level, text) = lines.all.single()
        assertEquals(LogLevel.Warn, level)
        assertTrue(text, "hexagon" in text && "installer threw: boom" in text)
    }

    /** Native init costs about 13 ms on the phone (F48): create must not run it on the caller's thread. */
    @Test
    fun initRunsOnFirstUseNotOnCreation() =
        runBlocking {
            val engine = FakeEngine()
            val llama = testLlamaBro(engine)
            assertEquals(0, engine.initConfigs.size)
            llama.devices()
            llama.loadModel(ModelSource.file("/m.gguf"))
            assertEquals(1, engine.initConfigs.size)
        }

    @Test
    fun configMismatchThrowsIllegalStateOnFirstUse() {
        val engine = FakeEngine()
        engine.initResult = { config ->
            if (engine.initConfigs.size > 1 && config != engine.initConfigs.first()) {
                """{"status":2,"error":"runtime already initialized with a different config"}"""
            } else {
                null
            }
        }
        runBlocking { LlamaBroRuntime(engine, tempDir(), "/b", LlamaBroConfig { logLevel = LogLevel.Warn }).devices() }
        val second = LlamaBroRuntime(engine, tempDir(), "/b", LlamaBroConfig { logLevel = LogLevel.Error })
        val err =
            runCatching {
                runBlocking {
                    LlamaBro(
                        second,
                    ).loadModel(ModelSource.file("/m.gguf"))
                }
            }.exceptionOrNull()
        assertTrue("$err", err is IllegalStateException && "different LlamaBroConfig" in err.message!!)
    }

    @Test
    fun devicesParsed() {
        val devices = runBlocking { testLlamaBro().devices() }
        assertEquals(listOf(DeviceInfo("CPU", "cpu", DeviceKind.Cpu, 1, 2)), devices)
    }

    /**
     * The sweep also runs when native init is triggered by `devices()` rather than a model load (the load path is
     * ModelConfigTest's), and removes a regular file left in the media folder as well as folders.
     */
    @Test
    fun initThroughDevicesSweepsEndedProcessesMediaFoldersAndStrayFiles() {
        val dir = tempDir()
        val media = File(dir, "llamabro/media")
        val dead = File(media, "300-aaaa").also { it.mkdirs() }
        val sameWhoseFolderEnded = File(media, "100-bbbb").also { it.mkdirs() }
        val running = File(media, "200-cccc").also { it.mkdirs() }
        val stray = File(media, "junk").also { it.mkdirs() }
        val strayFile = File(media, "400-file").also { it.writeText("x") }
        File(dead, "link").writeText("x")
        val runtime =
            LlamaBroRuntime(
                FakeEngine(),
                dir,
                "/b",
                LlamaBroConfig.Default,
                processId = 100,
                processAlive = { it == 200 },
            )
        runBlocking { runtime.devices() }
        assertTrue(running.exists())
        listOf(dead, sameWhoseFolderEnded, stray, strayFile).forEach { assertTrue(it.path, !it.exists()) }
        val own = media.listFiles()!!.filter { it != running }
        assertEquals(own.toString(), 1, own.size)
        assertTrue(own.single().name.startsWith("100-") && own.single().isDirectory)
    }
}
