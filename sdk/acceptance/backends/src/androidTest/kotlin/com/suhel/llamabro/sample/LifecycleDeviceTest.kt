package com.suhel.llamabro.sample

import android.app.ActivityManager
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
import kotlinx.coroutines.DelicateCoroutinesApi
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
 * Closing is safe on the main thread, frees the model's memory, and never overlaps the next load (F40).
 * Uses the models the acceptance test pushes to the app's filesDir. Numbers go to logcat under "Lifecycle".
 */
@RunWith(AndroidJUnit4::class)
class LifecycleDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val llama = LlamaBro.create(context)

    private fun file(name: String): String {
        val f = File(context.filesDir, name)
        assumeTrue("missing model ${f.path}", f.exists())
        return f.path
    }

    private suspend fun load(name: String): Model =
        llama.loadModel(ModelSource.file(file(name))) { contextSize = ContextSize.Fixed(4096) }

    /** Starts a long greedy reply; returns once the first content token has arrived. */
    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun generate(model: Model): Deferred<Throwable?> {
        val first = CompletableDeferred<Unit>()
        val request =
            ChatRequest {
                messages(Message.user("Count from 1 to 1000, one number per line."))
                sampling {
                    temperature = 0f
                    maxTokens = 4000
                }
                reasoning { enableThinking = false }
            }
        val run =
            GlobalScope.async(Dispatchers.Default) {
                runCatching {
                    model
                        .chat(
                            request,
                        ).collect { if (it is ChatEvent.ContentDelta) first.complete(Unit) }
                }.exceptionOrNull()
            }
        withTimeout(120_000) { first.await() }
        return run
    }

    /**
     * The phone's used memory (total minus available), MiB. Not this process's PSS: since b12352 ggml-hexagon's
     * weights live in DSP buffers that PSS doesn't count (76 MiB PSS for a 3 GB model on the NPU), while
     * MemAvailable still drops by the model's size (F121).
     */
    private fun usedMib(): Long {
        Runtime.getRuntime().gc()
        return usedMibNow()
    }

    /**
     * [usedMib] once it holds still: an earlier test's NPU model gives its memory back about a second after close
     * (F124), so a baseline read too early carries it. Two readings 500 ms apart within 64 MiB, at most 5 s.
     */
    private fun settledMib(): Long {
        var last = usedMib()
        repeat(SETTLE_TRIES) {
            Thread.sleep(SETTLE_STEP_MS)
            val now = usedMibNow()
            if (kotlin.math.abs(now - last) < SETTLE_MIB) return now
            last = now
        }
        return last
    }

    /** [usedMib] without the collection, for sampling every 20 ms. */
    private fun usedMibNow(): Long {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return (mi.totalMem - mi.availMem) / (1024 * 1024)
    }

    private fun log(msg: String) = Log.i("Lifecycle", msg)

    /** PSS by kind (native heap, file mappings, other mmap…), for the log when a number looks wrong. */
    private fun breakdown(): String {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return mi.memoryStats.entries.joinToString(
            " ",
        ) { "${it.key.removePrefix("summary.")}=${it.value.toLong() / 1024}" }
    }

    @Test
    fun closeOnMainThreadDoesNotBlock() =
        runBlocking {
            val model = load(QWEN)
            val run = generate(model)
            val violations = mutableListOf<String>()
            var ms = 0.0
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val old = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy
                        .Builder()
                        .detectAll()
                        .penaltyListener(Executors.newSingleThreadExecutor()) { v ->
                            synchronized(violations) {
                                violations +=
                                    v.toString()
                            }
                        }.build(),
                )
                val t0 = System.nanoTime()
                model.close()
                ms = (System.nanoTime() - t0) / 1e6
                StrictMode.setThreadPolicy(old)
            }
            val err = withTimeout(10_000) { run.await() }
            model.closeAndJoin()
            Thread.sleep(200) // StrictMode reports through its executor
            log(
                "closeOnMainThreadDoesNotBlock close_ms=%.2f violations=${violations.size} stream_error=${err?.javaClass?.simpleName}"
                    .format(
                        ms,
                    ),
            )
            assertTrue("close() took $ms ms on the main thread", ms < 16)
            assertTrue("StrictMode: $violations", violations.isEmpty())
            assertTrue("$err", err is LlamaBroException.ModelClosed)
        }

    @Test
    fun closeDuringGenerationFreesMemory() =
        runBlocking {
            val baseline = usedMib()
            log("closeDuringGenerationFreesMemory baseline: ${breakdown()}")
            val model = load(QWEN)
            val run = generate(model)
            val loaded = usedMib()
            log("closeDuringGenerationFreesMemory loaded: ${breakdown()}")
            val t0 = System.nanoTime()
            model.closeAndJoin()
            val joinMs = (System.nanoTime() - t0) / 1_000_000
            run.await()
            // the NPU's buffers come back about a second after close returns (F124): wait up to 5 s for them
            val fileMib = File(context.filesDir, QWEN).length() / (1024 * 1024)
            var after = usedMib()
            val waitStart = System.nanoTime()
            while (loaded - after < fileMib * 8 / 10 && System.nanoTime() - waitStart < 5_000_000_000L) {
                Thread.sleep(100)
                after = usedMibNow()
            }
            val settleMs = (System.nanoTime() - waitStart) / 1_000_000
            log("closeDuringGenerationFreesMemory after: ${breakdown()}")
            log(
                "closeDuringGenerationFreesMemory baseline_mib=$baseline loaded_mib=$loaded after_mib=$after " +
                    "close_and_join_ms=$joinMs settle_ms=$settleMs",
            )
            // what close returns, against the model's file: a baseline can carry memory an earlier test's model is
            // still giving back (F124), which made "back to baseline" fail on noise
            assertTrue(
                "close returned ${loaded - after} MiB of a $fileMib MiB model (baseline $baseline, loaded $loaded, " +
                    "after $after)",
                loaded - after >= fileMib * 8 / 10,
            )
        }

    /** Close A while it generates, load B at once: peak used memory stays near one model's, never A's plus B's. */
    @Test
    fun closeThenLoadNeverHoldsTwoModels() =
        runBlocking {
            val baseline = settledMib()
            val a = load(QWEN)
            val run = generate(a)
            val aMib = usedMib() - baseline
            val sampling =
                java.util.concurrent.atomic
                    .AtomicBoolean(true)
            val peak =
                java.util.concurrent.atomic
                    .AtomicLong(0)
            val sampler =
                thread {
                    while (sampling.get()) {
                        peak.accumulateAndGet(usedMibNow(), ::maxOf)
                        Thread.sleep(20)
                    }
                }
            a.close()
            val b = load(LLAMA)
            sampling.set(false)
            sampler.join()
            run.await()
            val bMib = usedMib() - baseline
            b.closeAndJoin()
            val peakMib = peak.get() - baseline
            log(
                "closeThenLoadNeverHoldsTwoModels baseline_mib=$baseline a_mib=$aMib b_mib=$bMib peak_switch_mib=$peakMib",
            )
            // both together would be about aMib + bMib; allow a fifth of the smaller one for transient buffers
            assertTrue(
                "peak $peakMib MiB while switching (A $aMib, B $bMib)",
                peakMib < maxOf(aMib, bMib) + minOf(aMib, bMib) / 5,
            )
        }

    private companion object {
        const val QWEN = "Qwen3.5-4B-Q4_K_M.gguf"
        const val LLAMA = "Llama-3.2-3B-Instruct-Q4_K_M.gguf"
        const val SETTLE_TRIES = 10
        const val SETTLE_STEP_MS = 500L
        const val SETTLE_MIB = 64L
    }
}
