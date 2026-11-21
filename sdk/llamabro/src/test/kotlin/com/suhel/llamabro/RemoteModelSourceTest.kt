package com.suhel.llamabro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A [RemoteModelSource] is fetched, then its local files load through the usual path (D64, D75). */
class RemoteModelSourceTest {
    private fun download(phase: DownloadPhase, done: Long) = DownloadProgress(phase, done, 100)

    /** A remote source over [local], with header stubs at [stub]; records what the runtime asked of it. */
    private class FakeRemote(
        val engine: FakeEngine,
        val local: ModelSource = ModelSource.file("/fetched.gguf"),
        val stub: ModelSource = ModelSource.file("/stub.gguf"),
        val steps: List<DownloadProgress> = emptyList(),
        val beforeReturn: suspend () -> Unit = {},
    ) : RemoteModelSource {
        var fetches = 0
        var loadsWhenFetched = -1
        var initedWhenFetching = false
        var headersActive = false
        var cleanups = 0
        var estimatesInBlock = -1
        var fetchCancelled = false

        override suspend fun fetch(onProgress: (DownloadProgress) -> Unit): ModelSource {
            fetches++
            initedWhenFetching = "init" in engine.calls
            try {
                for (step in steps) {
                    onProgress(step)
                    yield()
                }
                beforeReturn()
            } catch (e: CancellationException) {
                fetchCancelled = true
                throw e
            }
            loadsWhenFetched = engine.loadConfigs.size
            return local
        }

        override suspend fun <T> withHeaders(block: suspend (local: ModelSource) -> T): T {
            headersActive = true
            try {
                return block(stub).also { estimatesInBlock = engine.fitConfigs.size }
            } finally {
                headersActive = false
                cleanups++
            }
        }
    }

    @Test fun remoteSourceIsFetchedThenLoaded() =
        runBlocking {
            val engine = FakeEngine().apply { progress = listOf(0.5f, 1f) }
            val remote =
                FakeRemote(
                    engine,
                    steps = listOf(download(DownloadPhase.Downloading, 50), download(DownloadPhase.Complete, 100)),
                )
            val seen = mutableListOf<LoadProgress>()
            testLlamaBro(engine).loadModel(remote) { onProgress { seen += it } }

            assertEquals(1, remote.fetches)
            assertEquals("nothing loads before the fetch returns", 0, remote.loadsWhenFetched)
            assertTrue("a config conflict fails before the download", remote.initedWhenFetching)
            assertTrue(engine.loadConfigs.single().contains("\"model_path\":\"/fetched.gguf\""))
            assertTrue("$seen", seen.first() is LoadProgress.Fetching)
            val firstLoading = seen.indexOfFirst { it is LoadProgress.Loading }
            assertTrue("$seen", firstLoading > 0)
            assertTrue("$seen", seen.drop(firstLoading).all { it is LoadProgress.Loading })
            assertEquals(LoadProgress.Loading(1f), seen.last())
            val fetched = seen.filterIsInstance<LoadProgress.Fetching>().map { it.download.bytesDone }
            assertEquals(fetched.sorted(), fetched)
        }

    @Test fun cancellingDuringFetchStopsBeforeLoad() =
        runBlocking {
            val engine = FakeEngine()
            val fetching = CompletableDeferred<Unit>()
            val remote =
                FakeRemote(engine) {
                    fetching.complete(Unit)
                    awaitCancellation()
                }
            var outcome: Throwable? = null
            val job =
                launch(Dispatchers.Default) {
                    outcome = runCatching { testLlamaBro(engine).loadModel(remote) }.exceptionOrNull()
                }
            withTimeout(5_000) { fetching.await() }
            job.cancel()
            job.join()
            assertTrue("$outcome", outcome is CancellationException)
            assertTrue(remote.fetchCancelled)
            assertTrue("no load after a cancelled fetch", engine.loadConfigs.isEmpty())
        }

    @Test fun cancellingDuringTheLoadOfAFetchedSourceLeavesNothingOpen() {
        // D67 on the remote path: the fetch is done, the native load is reading, and the caller is cancelled
        val engine = FakeEngine().apply { progress = listOf(0.25f, 0.5f, 0.75f, 1f) }
        val made = AtomicInteger()
        engine.makeModel = {
            made.incrementAndGet()
            FakeModel()
        }
        val reached = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        engine.onStep = { p ->
            if (p == 0.25f) {
                reached.countDown()
                cancelled.await(5, TimeUnit.SECONDS)
            }
        }
        val remote = FakeRemote(engine)
        val seen = mutableListOf<LoadProgress>()
        var outcome: Throwable? = null
        runBlocking {
            val job =
                launch(Dispatchers.Default) {
                    outcome =
                        runCatching {
                            testLlamaBro(engine).loadModel(remote) { onProgress { synchronized(seen) { seen += it } } }
                        }.exceptionOrNull()
                }
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            job.cancel()
            cancelled.countDown()
            job.join()
        }
        assertTrue("$outcome", outcome is CancellationException)
        assertEquals(1, remote.fetches)
        assertEquals("the load stops at the tick after the cancel", 0.5f, engine.stoppedAt)
        assertEquals("a stopped load hands back no model", 0, made.get())
        assertTrue("$seen", synchronized(seen) { seen.none { it == LoadProgress.Loading(1f) } })
    }

    @Test fun downloadProgressComparesByValue() {
        val a = DownloadProgress(DownloadPhase.Downloading, 5, 10, "m.gguf", 1, 1, 2, 3)
        assertEquals(a, DownloadProgress(DownloadPhase.Downloading, 5, 10, "m.gguf", 1, 1, 2, 3))
        assertEquals(a.hashCode(), DownloadProgress(DownloadPhase.Downloading, 5, 10, "m.gguf", 1, 1, 2, 3).hashCode())
        assertFalse(a == DownloadProgress(DownloadPhase.Downloading, 6, 10, "m.gguf", 1, 1, 2, 3))
        assertTrue(a.toString(), a.toString().contains("bytesDone=5"))
        assertEquals(
            LoadProgress.Fetching(a),
            LoadProgress.Fetching(DownloadProgress(DownloadPhase.Downloading, 5, 10, "m.gguf", 1, 1, 2, 3)),
        )
    }

    @Test fun estimateFitUsesHeadersAndCleansUp() =
        runBlocking {
            val engine = FakeEngine()
            val remote = FakeRemote(engine)
            testLlamaBro(engine).estimateFit(remote)
            assertEquals("estimateFit never downloads", 0, remote.fetches)
            assertEquals("the estimate ran inside withHeaders", 1, remote.estimatesInBlock)
            assertTrue(engine.fitConfigs.single().contains("\"model_path\":\"/stub.gguf\""))
            assertEquals(1, remote.cleanups)

            // a failing estimate still leaves withHeaders by its cleanup, and the failure reaches the app
            val failing = FakeRemote(engine)
            try {
                testLlamaBro(engine).estimateFit(failing) { devicePolicy = DevicePolicy.Only(DeviceKind.Gpu) }
                fail("expected DeviceUnavailable")
            } catch (_: LlamaBroException.DeviceUnavailable) {
            }
            assertEquals(1, failing.cleanups)
            assertFalse(failing.headersActive)
        }

    @Test fun fileSourceStillLoadsUnchanged() =
        runBlocking {
            val engine = FakeEngine().apply { progress = listOf(0f, 0.5f, 1f) }
            val seen = mutableListOf<LoadProgress>()
            testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf")) { onProgress { seen += it } }
            assertTrue(engine.loadConfigs.single().contains("\"model_path\":\"/m.gguf\""))
            assertTrue("$seen", seen.isNotEmpty() && seen.all { it is LoadProgress.Loading })
            assertEquals(LoadProgress.Loading(1f), seen.last())
        }

    @Test fun aFetchMustReturnLocalFiles() =
        runBlocking {
            val engine = FakeEngine()
            val remote = FakeRemote(engine, local = FakeRemote(engine))
            try {
                testLlamaBro(engine).loadModel(remote)
                fail("expected IllegalStateException")
            } catch (_: IllegalStateException) {
            }
            assertTrue(engine.loadConfigs.isEmpty())
        }

    @Test fun dflashIsCheckedOnTheFetchedFiles() =
        runBlocking {
            val engine = FakeEngine()
            val withDraft = FakeRemote(engine, local = ModelSource.file("/m.gguf") { draft = "/d.gguf" })
            testLlamaBro(engine).loadModel(withDraft) { speculative = Speculative.DFlash() }
            assertTrue(engine.loadConfigs.single().contains("\"draft_path\":\"/d.gguf\""))

            val noDraft = FakeRemote(engine)
            try {
                testLlamaBro(engine).loadModel(noDraft) { speculative = Speculative.DFlash() }
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
            assertEquals(1, noDraft.fetches)
        }
}
