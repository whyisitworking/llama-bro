@file:OptIn(InternalLlamaBroApi::class)

package com.suhel.llamabro.server

import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.hostLlamaBro
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The real core on this machine behind [LlamaBroServer], for `sdk/native/tests/contract/server-run.sh`: it serves the
 * model on a free port with no key (the contract against upstream `llama-server`, which has none) and, when
 * `llamabro.server.key` is set, on a second free port with that key (the OpenAI and Anthropic client smoke). It writes
 * "port keyedPort" to `<dir>/port` and serves until `<dir>/stop` exists. Skipped without `-Pllamabro.host.lib`,
 * `-Pllamabro.host.model` and `-Pllamabro.server.dir`.
 */
class HostServerContractTest {
    @Test
    fun servesUntilStopped() =
        runBlocking {
            val lib = System.getProperty("llamabro.host.lib")
            val modelPath = System.getProperty("llamabro.host.model")
            val dir = System.getProperty("llamabro.server.dir")?.let(::File)
            assumeTrue("the server contract runs from server-run.sh", lib != null && modelPath != null && dir != null)
            val key = System.getProperty("llamabro.server.key")?.takeIf { it.isNotEmpty() }
            val embed = System.getProperty("llamabro.server.task") == "embed"

            val llama = hostLlamaBro(lib, Files.createTempDirectory("llamabro-server").toFile())
            // upstream's flags in run.sh: -c 8192 -np 1 --cache-ram 1024 --ctx-checkpoints 8 --seed 42 -b 512 -ub 512
            // (--load-mode none and --fit off are the core's own defaults with a fixed context)
            val model =
                llama.loadModel(ModelSource.file(modelPath)) {
                    contextSize = ContextSize.Fixed(CONTEXT)
                    parallelSlots = 1
                    ramCacheMib = CACHE_RAM_MIB
                    checkpointsPerSlot = CHECKPOINTS
                    seed = SEED
                    batchSize = BATCH
                    microBatchSize = BATCH
                    if (embed) task = ModelTask.Embedding()
                }
            val served = OneModel(model, File(modelPath).name)
            val open = LlamaBroServer(ServerConfig { port = 0 }, served)
            val keyed =
                key?.let { k ->
                    LlamaBroServer(
                        ServerConfig {
                            port = 0
                            apiKey = k
                        },
                        served,
                    )
                }
            try {
                val port = open.serve()
                val keyedPort = keyed?.serve() ?: 0
                val written = File(dir, "port.tmp")
                written.writeText("$port $keyedPort\n")
                assertTrue(written.renameTo(File(dir, "port")))
                awaitStop(File(dir, "stop"))
            } finally {
                keyed?.stop()
                open.stop()
                model.closeAndJoin()
            }
        }

    private suspend fun LlamaBroServer.serve(): Int {
        start()
        val state = state.first { it !is ServerState.Starting && it !is ServerState.Stopped }
        check(state is ServerState.Serving) { "the server didn't start: $state" }
        return state.port
    }

    // the script always writes the stop file, even when it fails; the cap only ends a run whose script was killed
    private suspend fun awaitStop(stop: File) {
        val began = TimeSource.Monotonic.markNow()
        while (!stop.exists()) {
            check(began.elapsedNow() < 2.hours) { "no stop file after two hours" }
            delay(200.milliseconds)
        }
    }

    /** Every request gets the one model, whatever its `model` field says: upstream's single-model server ignores it. */
    private class OneModel(private val model: Model, private val id: String) : ServedModels {
        override suspend fun list(): List<ServedModel> = listOf(ServedModel(id, 0))

        override suspend fun acquire(id: String?): ServedLease =
            object : ServedLease {
                override val model: Model = this@OneModel.model

                override fun release() = Unit
            }
    }

    private companion object {
        const val CONTEXT = 8192
        const val CACHE_RAM_MIB = 1024
        const val CHECKPOINTS = 8
        const val SEED = 42L
        const val BATCH = 512
    }
}
