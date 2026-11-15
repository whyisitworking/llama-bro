package com.suhel.llamabro

import com.suhel.llamabro.internal.Engine
import com.suhel.llamabro.internal.EngineModel
import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import com.suhel.llamabro.internal.NativeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A cancelled loadModel stops the real native load through JNI (D67, F145): the progress answer reaches the bridge,
 * llama.cpp stops reading weights, JNI throws the cancelled status, and the caller gets a CancellationException. The
 * next load of the same file works, so the stopped one left nothing behind. Run by sdk/scripts/check.sh's e2e step.
 */
class HostNativeLoadCancelTest {
    /** Keeps the status of each load the real engine refused. */
    private class Statuses(private val inner: Engine) : Engine by inner {
        val failed = mutableListOf<Int>()

        override fun load(configJson: String, onProgress: (Float) -> Boolean): EngineModel =
            try {
                inner.load(configJson, onProgress)
            } catch (e: NativeError) {
                synchronized(failed) { failed += e.status }
                throw e
            }
    }

    @Test
    fun cancelledLoadThrowsCancellation() =
        runBlocking {
            val lib = System.getProperty("llamabro.host.lib")
            val modelPath = System.getProperty("llamabro.host.model")
            assumeTrue("host end-to-end runs from sdk/scripts/check.sh", lib != null && modelPath != null)
            val engine = Statuses(JniEngine)
            val llama = LlamaBro(LlamaBroRuntime(engine, tempDir(), lib, LlamaBroConfig.Default))
            val config: ModelConfig.() -> Unit = { contextSize = ContextSize.Fixed(4096) }

            val firstTick = CompletableDeferred<Unit>()
            var outcome: Throwable? = null
            val job =
                launch(Dispatchers.Default) {
                    outcome =
                        runCatching {
                            llama.loadModel(ModelSource.file(modelPath)) {
                                config()
                                onProgress { firstTick.complete(Unit) }
                            }
                        }.exceptionOrNull()
                }
            withTimeout(60_000) { firstTick.await() }
            val cancelledAt = System.nanoTime()
            job.cancel()
            job.join()
            val ms = (System.nanoTime() - cancelledAt) / 1_000_000
            assertTrue("$outcome", outcome is CancellationException)
            // the native load stopped; a load that finished and was then freed would leave this empty
            assertEquals(listOf(NativeError.CANCELLED), engine.failed)
            assertTrue("loadModel returned $ms ms after the cancel", ms < 1_000)

            val model = llama.loadModel(ModelSource.file(modelPath), config)
            assertTrue(model.info.contextSize > 0)
            model.closeAndJoin()
        }
}
