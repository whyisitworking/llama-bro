package com.suhel.llamabro.demo.models

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.demo.chat.HostState
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ModelRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()

    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val swiftQ8 = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q8_0")
    private val other = HfModel.parse("someone/Some-Model-GGUF:Q4_K_M")

    private val hub = FakeHubGateway()
    private val scheduler = FakeScheduler()
    private val settings: SettingsRepository by lazy {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }

    @After fun stop() = scopes.forEach { it.cancel() }

    /** A repository whose launches run on the caller's thread, so a `get` has finished when it returns. */
    private fun repository(
        host: ModelHost? = null,
        gateway: HubGateway = hub,
        lastUsed: Flow<Map<String, Long>> = flowOf(emptyMap()),
    ): ModelRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        return ModelRepository(gateway, settings, scheduler, scope, host, lastUsed)
    }

    @Test fun getEnqueuesOnce() {
        val repo = repository()
        repo.get(swift)
        assertEquals(listOf(swift), scheduler.enqueued)
    }

    @Test fun getOfACachedModelDoesNotEnqueue() {
        hub.cachedEntries += CachedEntry(swift, 740_000_000)
        repository().get(swift)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test fun pauseKeepsThePartialAndGetResumes() {
        val repo = repository()
        hub.status(swift).value = DownloadStatus.Partial(300, 740)
        repo.pause(swift)
        assertEquals(listOf(swift), scheduler.cancelled)
        assertTrue("pause must not delete", hub.deleted.isEmpty())
        assertEquals(DownloadStatus.Partial(300, 740), repo.state(swift).value)
        repo.get(swift)
        assertEquals(listOf(swift), scheduler.enqueued)
    }

    @Test fun cancelDeletesThePartial() {
        val repo = repository()
        hub.status(swift).value = DownloadStatus.Partial(300, 740)
        repo.cancel(swift)
        assertEquals(listOf(swift), scheduler.cancelled)
        assertEquals(listOf(swift), hub.deleted)
        assertEquals(DownloadStatus.Absent, repo.state(swift).value)
    }

    @Test fun insufficientStorageIsReported() {
        val failure =
            DownloadFailure(
                DownloadFailure.Kind.InsufficientStorage,
                retryable = false,
                neededBytes = 2_700_000_000,
                availableBytes = 900_000_000,
            )
        hub.status(swift).value = DownloadStatus.Failed(failure)
        val state = repository().state(swift).value as DownloadStatus.Failed
        assertEquals(DownloadFailure.Kind.InsufficientStorage, state.failure.kind)
        assertEquals(2_700_000_000, state.failure.neededBytes)
    }

    @Test fun libraryShowsTheModelsOwnNameForEveryFileOfACatalogRepo() =
        runBlocking {
            hub.cachedEntries += CachedEntry(swift, 532_517_120)
            hub.cachedEntries += CachedEntry(swiftQ8, 900_000_000)
            hub.cachedEntries += CachedEntry(other, 1_000_000_000)
            val rows = repository().library.first().associateBy { it.key }
            // the app never renames a model: the catalog's name for any file of its repo, the repo's own for others
            assertEquals("Qwen3.5 0.8B", rows.getValue(swift).name)
            assertEquals("Qwen3.5 0.8B", rows.getValue(swiftQ8).name)
            assertEquals("Some-Model", rows.getValue(other).name)
            assertEquals(532_517_120, rows.getValue(swift).sizeBytes)
        }

    @Test fun libraryMarksTheDefault() =
        runBlocking {
            hub.cachedEntries += CachedEntry(swift, 1)
            hub.cachedEntries += CachedEntry(other, 1)
            settings.pinDefault(other.toString())
            val rows = repository().library.first().associateBy { it.key }
            assertTrue(rows.getValue(other).isDefault)
            assertFalse(rows.getValue(swift).isDefault)
        }

    @Test fun removingTheDefaultUnpinsIt() =
        runBlocking {
            hub.cachedEntries += CachedEntry(swift, 1)
            settings.pinDefault(swift.toString())
            repository().remove(swift)
            assertNull(settings.settings.first().defaultModel)
            assertEquals(listOf(swift), hub.deleted)
        }

    @Test fun removingAModelNotTheDefaultKeepsThePin() =
        runBlocking {
            hub.cachedEntries += CachedEntry(swift, 1)
            hub.cachedEntries += CachedEntry(other, 1)
            settings.pinDefault(other.toString())
            repository().remove(swift)
            assertEquals(other.toString(), settings.settings.first().defaultModel)
        }

    @Test fun removingAModelInUseReleasesItFirst() =
        runBlocking {
            hub.cachedEntries += CachedEntry(swift, 1)
            val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
            val host = ModelHost(FakeLlamaBro().llamaBro, hub, hostScope)
            host.use(swift)
            // at the moment of deletion the host must already be idle
            var hostWasIdleAtDelete = false
            val deletingHub =
                object : HubGateway by hub {
                    override suspend fun delete(model: HfModel) {
                        hostWasIdleAtDelete = host.state.value is HostState.Idle
                        hub.delete(model)
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
            ModelRepository(deletingHub, settings, scheduler, scope, host).remove(swift)
            assertTrue(hostWasIdleAtDelete)
            assertEquals(listOf(swift), hub.deleted)
        }

    @Test fun libraryUpdatesAfterAModelIsRemoved() =
        runBlocking {
            hub.cachedEntries += CachedEntry(swift, 1)
            val repo = repository()
            assertEquals(1, repo.library.first().size)
            repo.remove(swift)
            assertEquals(0, repo.library.first().size)
        }

    /** The pending set is written to settings a moment after `get` returns; wait for [wanted] rather than race it. */
    private suspend fun ModelRepository.downloadingIs(wanted: List<HfModel>) =
        kotlinx.coroutines.withTimeout(5_000) { downloading.first { it == wanted } }

    @Test fun downloadingListsAStartedModelUntilItIsOnThePhone() =
        runBlocking<Unit> {
            val repo = repository()
            assertEquals(emptyList<HfModel>(), repo.downloading.first())
            repo.get(swift)
            repo.downloadingIs(listOf(swift))
            hub.cachedEntries += CachedEntry(swift, 740_000_000)
            repo.changed()
            repo.downloadingIs(emptyList())
        }

    @Test fun savingATokenRestartsOnlyTheDownloadsRefusedForWantOfOne() =
        runBlocking<Unit> {
            val other = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
            val repo = repository()
            repo.get(swift)
            repo.get(other)
            // both are written down before the token arrives
            repo.downloadingIs(listOf(swift, other).sortedBy { it.toString() })
            scheduler.enqueued.clear()
            hub.status(swift).value =
                DownloadStatus.Failed(DownloadFailure(DownloadFailure.Kind.Unauthorized, retryable = false))
            hub.status(other).value =
                DownloadStatus.Failed(DownloadFailure(DownloadFailure.Kind.Network, retryable = true))

            repo.tokenSaved()
            withTimeout(5_000) { while (scheduler.enqueued.isEmpty()) delay(10) } // the store answers off this thread
            delay(100) // and a wrong second start would have shown by now

            assertEquals("the network failure is not the token's business", listOf(swift), scheduler.enqueued)
        }

    @Test fun aModelAlreadyOnThePhoneIsNotListedAsDownloading() =
        runBlocking<Unit> {
            hub.cachedEntries += CachedEntry(swift, 1)
            val repo = repository()
            repo.get(swift)
            assertEquals(emptyList<HfModel>(), repo.downloading.first())
        }

    @Test fun cancelAndRemoveDropTheDownload() =
        runBlocking<Unit> {
            val repo = repository()
            repo.get(swift)
            repo.get(other)
            repo.downloadingIs(listOf(other, swift).sortedBy { it.toString() })
            repo.cancel(swift)
            repo.downloadingIs(listOf(other))
            repo.remove(other)
            repo.downloadingIs(emptyList())
        }

    @Test fun pauseKeepsTheDownloadListed() =
        runBlocking<Unit> {
            val repo = repository()
            repo.get(swift)
            repo.downloadingIs(listOf(swift))
            repo.pause(swift)
            assertEquals(listOf(swift), repo.downloading.first())
        }

    @Test fun libraryNamesWhenEachModelWasLastUsedAndPutsTheLatestFirst() =
        runBlocking {
            hub.cachedEntries += CachedEntry(swift, 1)
            hub.cachedEntries += CachedEntry(swiftQ8, 1)
            hub.cachedEntries += CachedEntry(other, 1)
            val used = MutableStateFlow(mapOf(other.toString() to 5L, swift.toString() to 9L))
            val repo = repository(lastUsed = used)
            val rows = repo.library.first()
            assertEquals(listOf(swift, other, swiftQ8), rows.map { it.key })
            assertEquals(listOf(9L, 5L, null), rows.map { it.lastUsed })
            used.value = used.value + (swiftQ8.toString() to 12L) // a reply from the Q8 file
            assertEquals(
                swiftQ8,
                repo.library
                    .first { it.first().lastUsed == 12L }
                    .first()
                    .key,
            )
        }

    @Test fun theCacheIsScannedOncePerChangeWhoeverCollects() =
        runBlocking {
            hub.cachedEntries += CachedEntry(swift, 1)
            val scans = AtomicInteger()
            val counting =
                object : HubGateway by hub {
                    override suspend fun cached(): List<CachedEntry> = hub.cached().also { scans.incrementAndGet() }
                }
            val repo = repository(gateway = counting)
            val readers = List(3) { async { repo.library.first() } }
            readers.forEach { assertEquals(listOf(swift), it.await().map { row -> row.key }) }
            assertEquals(1, scans.get())
            hub.cachedEntries += CachedEntry(other, 1)
            repo.changed()
            // a reader after a change waits for the new scan, never the old one
            assertEquals(
                setOf(swift, other),
                repo.library
                    .first()
                    .map { it.key }
                    .toSet(),
            )
            // one new scan for the library, plus `changed`'s own look at which downloads finished
            assertEquals(3, scans.get())
        }
}
