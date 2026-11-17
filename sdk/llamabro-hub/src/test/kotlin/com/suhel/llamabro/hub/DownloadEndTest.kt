package com.suhel.llamabro.hub

import com.suhel.llamabro.DownloadPhase
import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.HubTestHooks
import com.suhel.llamabro.hub.internal.JvmStorage
import com.suhel.llamabro.hub.internal.Response
import com.suhel.llamabro.hub.internal.Transport
import com.suhel.llamabro.hub.internal.repoFolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** How a download ends other than by completing: stopped by a delete, or by a bug that no retry can fix. */
internal class DownloadEndTest {
    @Before fun reset() = hub.reset()

    @Test fun deleteDuringADownloadStopsItAndTheModelStaysGone() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-delete-active-").toFile()
            val client = instance(cache)
            val ref = HfModel("test/download")
            hub.control("[{\"kind\":\"slow\",\"path\":\"model-Q4_K_M.gguf\",\"rate_mib_s\":0.5}]")
            val reached = CompletableDeferred<Unit>()
            val ended =
                async {
                    runCatching {
                        client.download(ref).collect { update ->
                            if (update.bytesDone >= 1L shl 20 && update.phase == DownloadPhase.Downloading) {
                                reached.complete(Unit)
                            }
                        }
                    }.exceptionOrNull()
                }
            withTimeout(10_000) { reached.await() }
            hub.reset() // a transfer that lived on would now fetch the model again at once
            assertTrue(client.delete(ref))
            val error = withTimeout(15_000) { ended.await() }
            assertTrue("the app's flow ends, and no worker retries it: $error", error is HubException.Network)
            assertFalse((error as HubException.Network).retryable)
            assertEquals(emptyList<CachedModel>(), client.cached())
            val blobs = File(cache, "${repoFolder(ref.repo)}/blobs").listFiles().orEmpty().map { it.name }
            assertEquals("no blob and no partial came back", emptyList<String>(), blobs)
            assertEquals(DownloadState.Absent, client.progress(ref).value)
        }

    @Test fun deleteDuringTheLastTryIsStillADeleteNotARetryableFailure() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-delete-last-try-").toFile()
            val client = instance(cache)
            val ref = HfModel("test/download")
            // two failed tries first, so the delete lands on the last one: no backoff after it to turn it into a
            // cancellation
            hub.control(
                "[{\"kind\":\"status\",\"host\":\"cdn\",\"match\":\"blob\",\"method\":\"GET\",\"status\":503," +
                    "\"times\":2},{\"kind\":\"slow\",\"path\":\"model-Q4_K_M.gguf\",\"rate_mib_s\":0.5}]",
            )
            val reached = CompletableDeferred<Unit>()
            val ended =
                async {
                    runCatching {
                        client.download(ref).collect { update ->
                            if (update.bytesDone >= 1L shl 20 && update.phase == DownloadPhase.Downloading) {
                                reached.complete(Unit)
                            }
                        }
                    }.exceptionOrNull()
                }
            withTimeout(20_000) { reached.await() }
            assertEquals("on the last try", 3, cdnGets())
            assertTrue(client.delete(ref))
            val error = withTimeout(15_000) { ended.await() }
            assertTrue("$error", error is HubException.Network && !error.retryable)
            assertEquals(DownloadState.Absent, client.progress(ref).value)
        }

    @Test fun aDownloadStartedWhileADeleteRunsFailsAsDeleted() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-delete-gate-").toFile()
            val client = instance(cache)
            val ref = HfModel("test/download:Q4_K_M")
            client.download(ref).collect()
            val started = AtomicBoolean()
            var raced: Throwable? = null
            HubTestHooks.onPhase = { phase ->
                // after the delete stopped what ran, before it removes the blobs: a UI collecting again, say
                if (phase == "deleteEntries" && started.compareAndSet(false, true)) {
                    raced =
                        runBlocking(Dispatchers.IO) {
                            withTimeout(15_000) { runCatching { client.download(ref).collect() }.exceptionOrNull() }
                        }
                }
            }
            try {
                assertTrue(client.delete(ref))
            } finally {
                HubTestHooks.onPhase = null
            }
            assertTrue(started.get())
            val error = raced
            assertTrue("fails fast as deleted: $error", error is HubException.Network && !error.retryable)
            assertEquals("the model stays deleted", emptyList<CachedModel>(), client.cached())
            val blobs = File(cache, "${repoFolder(ref.repo)}/blobs").listFiles().orEmpty().map { it.name }
            assertEquals(emptyList<String>(), blobs)
        }

    @Test fun aRefusalIsNotRetried() =
        runBlocking {
            val client = instance(Files.createTempDirectory("hub-400-").toFile())
            hub.control("[{\"kind\":\"status\",\"host\":\"cdn\",\"match\":\"blob\",\"method\":\"GET\",\"status\":400}]")
            val error = runCatching { client.download(HfModel("test/download")).collect() }.exceptionOrNull()
            assertTrue("$error", error is HubException.Network && !error.retryable)
            assertEquals("asked once, with no backoff", 1, cdnGets())
        }

    @Test fun aProgrammingErrorIsNotRetriedAsANetworkFailure() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-bug-").toFile()
            val gets = AtomicInteger()
            val bug = IllegalStateException("a parse bug")
            val inner = HttpTransport(hub.apiUrl, null, null)
            val failing =
                object : Transport {
                    override suspend fun open(
                        method: String,
                        url: String,
                        headers: Map<String, String>,
                        range: Long?,
                        rangeEnd: Long?,
                    ): Response {
                        if (method == "GET" && "/resolve/" in url) {
                            gets.incrementAndGet()
                            throw bug
                        }
                        return inner.open(method, url, headers, range, rangeEnd)
                    }
                }
            val client = instance(cache, failing)
            val error = runCatching { client.download(HfModel("test/download")).collect() }.exceptionOrNull()
            // the same type and message: a coroutine boundary may hand on a copy that carries the stack trace
            assertTrue("passed through, not a retryable network failure: $error", error is IllegalStateException)
            assertEquals(bug.message, error?.message)
            assertEquals("tried once", 1, gets.get())
        }

    private fun cdnGets(): Int =
        hub.log().count {
            it.jsonObject
                .getValue("host")
                .jsonPrimitive.content == "cdn" &&
                it.jsonObject
                    .getValue("method")
                    .jsonPrimitive.content == "GET"
        }

    private fun instance(cache: File, transport: Transport = HttpTransport(hub.apiUrl, null, null)): LlamaBroHub {
        val config =
            HubConfig {
                endpoint = hub.apiUrl
                cacheDir = cache
            }
        return LlamaBroHub(config, cache, transport, JvmStorage())
    }

    private companion object {
        lateinit var hub: FakeHub

        @JvmStatic @BeforeClass
        fun startHub() {
            hub = FakeHub()
        }

        @JvmStatic @AfterClass
        fun stopHub() {
            hub.close()
        }
    }
}
