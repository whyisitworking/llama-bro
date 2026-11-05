package com.suhel.llamabro.sample

import android.os.Debug
import android.os.StrictMode
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.chat
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * PA T4 (F40): closing is safe on the main thread, frees the model's memory, and never overlaps the next load.
 * Uses the models the acceptance test pushes to the app's filesDir. Numbers go to logcat under "Lifecycle".
 */
@RunWith(AndroidJUnit4::class)
class LifecycleDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val llama = LlamaBro.getInstance(context)

    private fun file(name: String): String {
        val f = File(context.filesDir, name)
        assumeTrue("missing model ${f.path}", f.exists())
        return f.path
    }

    private suspend fun load(name: String): Model =
        llama.loadModel(ModelSource.file(file(name))) { contextSize = ContextSize.Fixed(4096) }

    /** Starts a long greedy reply; returns once the first content token has arrived. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private suspend fun generate(model: Model): Deferred<Throwable?> {
        val first = CompletableDeferred<Unit>()
        val request = ChatRequest {
            messages(Message.user("Count from 1 to 1000, one number per line."))
            sampling { temperature = 0f; maxTokens = 4000 }
            reasoning { enableThinking = false }
        }
        val run = GlobalScope.async(Dispatchers.Default) {
            runCatching { model.chat(request).collect { if (it is ChatEvent.ContentDelta) first.complete(Unit) } }.exceptionOrNull()
        }
        withTimeout(120_000) { first.await() }
        return run
    }

    private fun pssMib(): Long {
        Runtime.getRuntime().gc()
        return Debug.getPss() / 1024
    }

    private fun log(msg: String) = Log.i("Lifecycle", msg)

    /** PSS by kind (native heap, file mappings, other mmap…), for the log when a number looks wrong. */
    private fun breakdown(): String {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return mi.memoryStats.entries.joinToString(" ") { "${it.key.removePrefix("summary.")}=${it.value.toLong() / 1024}" }
    }

    @Test
    fun closeOnMainThreadDoesNotBlock() = runBlocking {
        val model = load(QWEN)
        val run = generate(model)
        val violations = mutableListOf<String>()
        var ms = 0.0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val old = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll()
                    .penaltyListener(Executors.newSingleThreadExecutor()) { v -> synchronized(violations) { violations += v.toString() } }
                    .build(),
            )
            val t0 = System.nanoTime()
            model.close()
            ms = (System.nanoTime() - t0) / 1e6
            StrictMode.setThreadPolicy(old)
        }
        val err = withTimeout(10_000) { run.await() }
        model.closeAndJoin()
        Thread.sleep(200)  // StrictMode reports through its executor
        log("closeOnMainThreadDoesNotBlock close_ms=%.2f violations=${violations.size} stream_error=${err?.javaClass?.simpleName}".format(ms))
        assertTrue("close() took $ms ms on the main thread", ms < 16)
        assertTrue("StrictMode: $violations", violations.isEmpty())
        assertTrue("$err", err is LlamaBroException.ModelClosed)
    }

    @Test
    fun closeDuringGenerationFreesMemory() = runBlocking {
        val baseline = pssMib()
        log("closeDuringGenerationFreesMemory baseline: ${breakdown()}")
        val model = load(QWEN)
        val run = generate(model)
        val loaded = pssMib()
        log("closeDuringGenerationFreesMemory loaded: ${breakdown()}")
        val t0 = System.nanoTime()
        model.closeAndJoin()
        val joinMs = (System.nanoTime() - t0) / 1_000_000
        run.await()
        val after = pssMib()
        log("closeDuringGenerationFreesMemory after: ${breakdown()}")
        log("closeDuringGenerationFreesMemory baseline_mib=$baseline loaded_mib=$loaded after_mib=$after close_and_join_ms=$joinMs")
        assertTrue("the model took no memory? loaded=$loaded baseline=$baseline", loaded - baseline > 500)
        assertTrue("PSS after close $after MiB, baseline $baseline MiB", after - baseline < (loaded - baseline) / 10)
    }

    /** Close A while it generates, load B at once: peak PSS stays near one model's, never A's plus B's. */
    @Test
    fun closeThenLoadNeverHoldsTwoModels() = runBlocking {
        val baseline = pssMib()
        val a = load(QWEN)
        val run = generate(a)
        val aMib = pssMib() - baseline
        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val peak = java.util.concurrent.atomic.AtomicLong(0)
        val sampler = thread { while (sampling.get()) { peak.accumulateAndGet(Debug.getPss() / 1024, ::maxOf); Thread.sleep(20) } }
        a.close()
        val b = load(LLAMA)
        sampling.set(false)
        sampler.join()
        run.await()
        val bMib = pssMib() - baseline
        b.closeAndJoin()
        val peakMib = peak.get() - baseline
        log("closeThenLoadNeverHoldsTwoModels baseline_mib=$baseline a_mib=$aMib b_mib=$bMib peak_switch_mib=$peakMib")
        // both together would be about aMib + bMib; allow a fifth of the smaller one for transient buffers
        assertTrue("peak $peakMib MiB while switching (A $aMib, B $bMib)", peakMib < maxOf(aMib, bMib) + minOf(aMib, bMib) / 5)
    }

    private companion object {
        const val QWEN = "Qwen3.5-4B-Q4_K_M.gguf"
        const val LLAMA = "Llama-3.2-3B-Instruct-Q4_K_M.gguf"
    }
}
