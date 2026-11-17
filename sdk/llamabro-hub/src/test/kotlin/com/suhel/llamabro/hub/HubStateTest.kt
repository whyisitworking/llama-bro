package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.JvmStorage
import com.suhel.llamabro.hub.internal.repoFolder
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

internal class HubStateTest {
    @Before fun reset() = hub.reset()

    @Test fun progressReadFromDiskAfterRestart() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-state-partial-").toFile()
            val ref = HfModel("test/progress:Q4_K_M")
            val first = instance(cache)
            val plan = first.recordedPlan(ref)
            val model = plan.files.single { it.role == HubFile.Role.Model }
            val mmproj = plan.files.single { it.role == HubFile.Role.Mmproj }
            val partial = File(cache, "${repoFolder(ref.repo)}/blobs/${model.oid}.downloadInProgress")
            requireNotNull(partial.parentFile).mkdirs()
            RandomAccessFile(partial, "rw").use { it.setLength(3L shl 20) }
            val projector = File(cache, "${repoFolder(ref.repo)}/snapshots/${plan.commit}/${mmproj.path}")
            requireNotNull(projector.parentFile).mkdirs()
            RandomAccessFile(projector, "rw").use { it.setLength(1L shl 20) }
            val restarted = instance(cache)
            restarted.awaitDiskState(ref)
            val state = restarted.progress(ref).value as DownloadState.Partial
            assertEquals(4L shl 20, state.bytesDone)
            assertEquals(7L shl 20, state.bytesTotal)
        }

    @Test fun completeAfterRestartAndSourceIsOffline() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-state-complete-").toFile()
            val ref = HfModel("test/choice:Q8_0")
            instance(cache).download(ref) { draft = DraftRequest.dflash() }.toList()
            hub.reset()
            val restarted = instance(cache)
            restarted.awaitDiskState(ref) { draft = DraftRequest.dflash() }
            assertEquals(DownloadState.Complete, restarted.progress(ref) { draft = DraftRequest.dflash() }.value)
            val source = restarted.source(ref) { draft = DraftRequest.dflash() }
            assertTrue(source.toString().contains("model-Q8_0.gguf"))
            assertTrue(hub.log().isEmpty())
        }

    @Test fun progressSharesOneStateFlowForSelection() {
        val client = instance(Files.createTempDirectory("hub-state-flow-").toFile())
        val ref = HfModel("test/choice:Q8_0")
        assertSame(client.progress(ref), client.progress(ref))
        assertTrue(client.progress(ref) !== client.progress(ref) { mmproj = false })
    }

    @Test fun completeWithoutPlanRecordUsesOfflineCache() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-no-plan-").toFile()
            val ref = HfModel("test/download")
            instance(cache).download(ref).toList()
            File(cache, ".llamabro/plans").deleteRecursively()
            val restarted = instance(cache)
            restarted.awaitDiskState(ref)
            assertEquals(DownloadState.Complete, restarted.progress(ref).value)
        }

    @Test fun deleteUpdatesObservedProgress() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-delete-state-").toFile()
            val ref = HfModel("test/choice:Q8_0")
            val client = instance(cache)
            client.download(ref).toList()
            val state = client.progress(ref)
            assertEquals(DownloadState.Complete, state.value)
            assertTrue(client.delete(ref))
            assertEquals(DownloadState.Absent, state.value)
        }

    @Test fun sourceMissingSelectedFileThrows() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-source-missing-").toFile()
            val ref = HfModel("test/choice:Q8_0")
            val client = instance(cache)
            try {
                client.source(ref)
                fail("uncached source must fail")
            } catch (_: HubException.NotFound) {
            }
            client.download(ref).toList()
            val plan = client.plan(ref)
            val mmproj = plan.files.single { it.role == HubFile.Role.Mmproj }
            File(cache, "${repoFolder(ref.repo)}/snapshots/${plan.commit}/${mmproj.path}").delete()
            try {
                client.source(ref)
                fail("missing selected projector must fail")
            } catch (_: HubException.NotFound) {
            }
        }

    @Test fun verifiedUnlinkedBlobCountsAfterRestart() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-state-unlinked-").toFile()
            val ref = HfModel("test/progress:Q4_K_M")
            val first = instance(cache)
            first.download(ref).toList()
            val plan = first.plan(ref)
            val model = plan.files.single { it.role == HubFile.Role.Model }
            // killed before the link
            File(cache, "${repoFolder(ref.repo)}/snapshots/${plan.commit}/${model.path}").delete()
            val restarted = instance(cache)
            restarted.awaitDiskState(ref)
            val state = restarted.progress(ref).value as DownloadState.Partial
            assertEquals(7L shl 20, state.bytesDone)
        }

    @Test fun collectorExceptionLeavesStateAlone() =
        runBlocking {
            val client = instance(Files.createTempDirectory("hub-state-transparent-").toFile())
            val ref = HfModel("test/flat")
            try {
                client.download(ref).collect { throw HubException.NotFound("the app's own error", emptyList()) }
                fail("the collector's exception must reach the collector")
            } catch (e: HubException.NotFound) {
                assertEquals("the app's own error", e.message)
            }
            assertTrue(client.progress(ref).value !is DownloadState.Failed)
        }

    @Test fun splitModelMissingAPartIsNotComplete() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-state-split-").toFile()
            val ref = HfModel("test/split:Q3_K_M")
            val client = instance(cache)
            client.download(ref).toList()
            File(cache, ".llamabro/plans").deleteRecursively()
            val commit = client.plan(ref).commit
            File(cache, "${repoFolder(ref.repo)}/snapshots/$commit/Q3_K_M/model-Q3_K_M-00002-of-00002.gguf").delete()
            File(cache, ".llamabro/plans").deleteRecursively()
            val restarted = instance(cache)
            restarted.awaitDiskState(ref)
            assertTrue(restarted.progress(ref).value !is DownloadState.Complete)
            try {
                restarted.source(ref)
                fail("a split model missing a part is not cached")
            } catch (
                _: HubException.NotFound,
            ) {
            }
        }

    @Test fun offlineMissingTagIsRetryable() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-state-offline-tag-").toFile()
            val client = instance(cache)
            client.download(HfModel("test/choice:Q8_0")).toList()
            hub.control("[{\"kind\":\"status\",\"host\":\"api\",\"match\":\"refs\",\"status\":503}]")
            try {
                client.plan(HfModel("test/choice:Q4_K_M"))
                fail("an uncached tag can't be planned offline")
            } catch (e: HubException.Network) {
                assertTrue("the Hub may have it: retry", e.retryable)
            }
        }

    @Test fun verifiedUnlinkedBlobIsNotToDownload() =
        runBlocking {
            val cache = Files.createTempDirectory("hub-state-unlinked-plan-").toFile()
            val ref = HfModel("test/download")
            val client = instance(cache)
            client.download(ref).toList()
            val plan = client.plan(ref)
            File(cache, "${repoFolder(ref.repo)}/snapshots/${plan.commit}/${plan.files.single().path}").delete()
            assertEquals(0L, client.plan(ref).bytesToDownload)
        }

    private fun instance(cache: File): LlamaBroHub {
        val config =
            HubConfig {
                endpoint = hub.apiUrl
                cacheDir = cache
            }
        return LlamaBroHub(config, cache, HttpTransport(hub.apiUrl, null, null), JvmStorage())
    }

    companion object {
        private lateinit var hub: FakeHub

        @BeforeClass @JvmStatic
        fun start() {
            hub = FakeHub()
        }

        @AfterClass @JvmStatic
        fun stop() {
            hub.close()
        }
    }
}
