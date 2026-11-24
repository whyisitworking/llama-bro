package com.suhel.llamabro.hub

import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.JvmStorage
import com.suhel.llamabro.hub.internal.repoFolder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `download` and `progress` resolve a selection separately (the first online, the second from the cached listing when
 * no record survives), through one `Planner`: they must choose by the same rule. Each selection here is downloaded,
 * every record is dropped, and a restarted hub must call exactly what was downloaded Complete. The other tests start
 * from a real partial download and require a selection that asks for more than is on disk to be something else.
 *
 * Each hub's `DownloadStates` scope lives until the test JVM ends: the hub has no close to end it.
 */
internal class DownloadProgressAgreementTest {
    @Before fun reset() = hub.reset()

    private class Selection(val name: String, val ref: String, val configure: PlanOptions.() -> Unit = {})

    private val selections =
        listOf(
            Selection("tag with its projector", "test/choice:Q8_0"),
            Selection("tag without the projector", "test/choice:Q8_0") { mmproj = false },
            Selection("bare repo, no tag", "test/download"),
            Selection("explicit file", "test/choice#model-Q8_0.gguf"),
            Selection("draft of its own repo", "test/choice:Q8_0") { draft = DraftRequest.dflash() },
            Selection("draft file of another repo", "test/choice:Q8_0") {
                draft = DraftRequest.dflash(HfModel("test/draft"))
            },
            Selection("draft repo with only a model", "test/choice:Q8_0") {
                draft = DraftRequest.dflash(HfModel("test/draftfull"))
            },
            Selection("split model", "test/split:Q3_K_M"),
        )

    @Test fun progressCallsCompleteWhatDownloadFetched() =
        withCache { cache ->
            for (selection in selections) {
                val ref = HfModel(selection.ref)
                File(cache, ".llamabro").deleteRecursively()
                instance(cache).download(ref, selection.configure).toList()
                File(cache, ".llamabro").deleteRecursively()
                val restarted = instance(cache)
                restarted.awaitDiskState(ref, selection.configure)
                assertEquals(
                    selection.name,
                    DownloadState.Complete,
                    restarted.progress(ref, selection.configure).value,
                )
            }
        }

    @Test fun anOwnRepoDraftIsNotCompleteWhenOnlyTheModelWasDownloaded() =
        withCache { cache ->
            val ref = HfModel("test/choice:Q8_0")
            instance(cache).download(ref).toList()
            assertNotComplete(cache, ref) { draft = DraftRequest.dflash() }
        }

    @Test fun anotherRepoDraftIsNotCompleteWhenOnlyTheModelWasDownloaded() =
        withCache { cache ->
            val ref = HfModel("test/choice:Q8_0")
            instance(cache).download(ref).toList()
            assertNotComplete(cache, ref) { draft = DraftRequest.dflash(HfModel("test/draft")) }
        }

    @Test fun aSplitModelMissingAPartIsNotComplete() =
        withCache { cache ->
            val ref = HfModel("test/split:Q3_K_M")
            val client = instance(cache)
            client.download(ref).toList()
            val commit = client.plan(ref).commit
            val second = "${repoFolder(ref.repo)}/snapshots/$commit/Q3_K_M/model-Q3_K_M-00002-of-00002.gguf"
            assertTrue(File(cache, second).delete())
            assertNotComplete(cache, ref) {}
        }

    /**
     * Today's behaviour, which follows upstream's offline resolution (F82: the cached listing is what loads offline,
     * and it holds only the files on disk): a projector the cached listing doesn't have is not asked for, so a model
     * downloaded without one is Complete for the same selection with one. A change here must be deliberate.
     */
    @Test fun aProjectorAskedForButNotOnDiskDoesNotMakeAModelIncomplete() =
        withCache { cache ->
            val ref = HfModel("test/choice:Q8_0")
            instance(cache).download(ref) { mmproj = false }.toList()
            File(cache, ".llamabro").deleteRecursively()
            val restarted = instance(cache)
            restarted.awaitDiskState(ref) { mmproj = true }
            assertEquals(DownloadState.Complete, restarted.progress(ref) { mmproj = true }.value)
        }

    private suspend fun assertNotComplete(cache: File, ref: HfModel, configure: PlanOptions.() -> Unit) {
        File(cache, ".llamabro").deleteRecursively()
        val restarted = instance(cache)
        restarted.awaitDiskState(ref, configure)
        val state = restarted.progress(ref, configure).value
        assertTrue("was $state", state !is DownloadState.Complete)
    }

    private fun withCache(body: suspend (File) -> Unit) {
        val cache = Files.createTempDirectory("hub-agree-").toFile()
        try {
            runBlocking { body(cache) }
        } finally {
            cache.deleteRecursively()
        }
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
        fun stop() = hub.close()
    }
}
