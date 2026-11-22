package com.suhel.llamabro.demo.models

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.demo.chat.HostState
import com.suhel.llamabro.demo.chat.LoadConfig
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.TestHub.Refusal
import com.suhel.llamabro.hub.CachedModel
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
    private val third = HfModel.parse("someone/Third-GGUF:Q4_K_M")

    private val testHub = TestHub()
    private val hub get() = testHub.hub
    private val scheduler = FakeScheduler()
    private val settings: SettingsRepository by lazy {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }

    @After fun stop() {
        scopes.forEach { it.cancel() }
        testHub.close()
    }

    /** A repository whose launches run on the caller's thread, so a `get` has finished when it returns. */
    private fun repository(
        host: ModelHost? = null,
        lastUsed: Flow<Map<String, Long>> = flowOf(emptyMap()),
        installed: suspend () -> List<CachedModel> = { hub.installed() },
    ): ModelRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        return ModelRepository(hub, settings, scheduler, scope, host, lastUsed, installed)
    }

    /** A model nobody downloads: pausing it is a step that leaves a trace ([settle]). */
    private val bystander = HfModel.parse("someone/Bystander-GGUF:Q4_K_M")

    /** Returns once every step asked before it has run: steps run one at a time, in the order asked. */
    private fun ModelRepository.settle() {
        val before = scheduler.cancelled.count { it == bystander }
        pause(bystander)
        runBlocking { withTimeout(5_000) { while (scheduler.cancelled.count { it == bystander } == before) delay(10) } }
    }

    /** A host over the fake engine that loads the hub's installed copies, as the app's does. */
    private fun host(): ModelHost {
        val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
        return ModelHost(FakeLlamaBro().llamaBro, { hub.installedModel(it) }, hostScope)
    }

    private suspend fun ModelRepository.stateBecomes(model: HfModel, wanted: (DownloadStatus) -> Boolean) =
        withTimeout(5_000) { state(model).first(wanted) }

    @Test fun getEnqueuesOnce() {
        val repo = repository()
        repo.get(swift)
        repo.settle()
        assertEquals(listOf(swift), scheduler.enqueued)
    }

    @Test fun getOfACachedModelDoesNotEnqueue() {
        testHub.install(swift)
        val repo = repository()
        repo.get(swift)
        repo.settle() // the Get's step, which asks the hub, has run
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test fun pauseKeepsThePartialAndGetResumes() =
        runBlocking {
            val repo = repository()
            testHub.partial(swift)
            repo.pause(swift)
            assertEquals(listOf(swift), scheduler.cancelled)
            val paused = repo.stateBecomes(swift) { it is DownloadStatus.Partial } as DownloadStatus.Partial
            assertTrue("pause must not delete what arrived", paused.bytesDone > 0)
            repo.get(swift)
            scheduler.awaitEnqueued(swift)
        }

    @Test fun cancelDeletesThePartial() =
        runBlocking {
            val repo = repository()
            testHub.partial(swift)
            repo.cancel(swift)
            assertEquals(listOf(swift), scheduler.cancelled)
            repo.stateBecomes(swift) { it == DownloadStatus.Absent }
            assertFalse(testHub.onPhone(swift))
        }

    @Test fun insufficientStorageIsReported() =
        runBlocking {
            val repo = repository()
            testHub.refuse(swift, Refusal.InsufficientStorage)
            val state = repo.stateBecomes(swift) { it is DownloadStatus.Failed } as DownloadStatus.Failed
            assertEquals(DownloadFailure.Kind.InsufficientStorage, state.failure.kind)
            assertTrue("the bytes needed are said", (state.failure.neededBytes ?: 0) > 0)
        }

    @Test fun aDownloadThatCompletesIsListedWithoutBeingTold() =
        runBlocking<Unit> {
            val repo = repository()
            testHub.publish(swift)
            repo.get(swift)
            repo.downloadingIs(listOf(swift))
            // the worker, here the test, downloads through the same hub and tells the repository nothing
            hub.download(swift).collect()
            withTimeout(5_000) { repo.library.first { rows -> rows.any { it.key == swift } } }
            repo.downloadingIs(emptyList())
        }

    @Test fun libraryShowsTheModelsOwnNameForEveryFileOfACatalogRepo() =
        runBlocking {
            testHub.install(swift, swiftQ8, other)
            val rows = repository().library.first().associateBy { it.key }
            // the app never renames a model: the catalog's name for any file of its repo, the repo's own for others
            assertEquals("Qwen3.5 0.8B", rows.getValue(swift).name)
            assertEquals("Qwen3.5 0.8B", rows.getValue(swiftQ8).name)
            assertEquals("Some-Model", rows.getValue(other).name)
            assertTrue("the size is the hub's", rows.getValue(swift).sizeBytes > 0)
        }

    @Test fun libraryMarksTheDefault() =
        runBlocking {
            testHub.install(swift, other)
            settings.pinDefault(other.toString())
            val rows = repository().library.first().associateBy { it.key }
            assertTrue(rows.getValue(other).isDefault)
            assertFalse(rows.getValue(swift).isDefault)
        }

    @Test fun removingTheDefaultUnpinsIt() =
        runBlocking {
            testHub.install(swift)
            settings.pinDefault(swift.toString())
            repository().remove(swift)
            assertNull(settings.settings.first().defaultModel)
            assertFalse(testHub.onPhone(swift))
        }

    @Test fun removingAModelNotTheDefaultKeepsThePin() =
        runBlocking {
            testHub.install(swift, other)
            settings.pinDefault(other.toString())
            repository().remove(swift)
            assertEquals(other.toString(), settings.settings.first().defaultModel)
        }

    @Test fun removingAModelInUseReleasesItFirst() =
        runBlocking {
            testHub.install(swift)
            val host = host()
            host.use(swift, LoadConfig())
            // read as the host turns idle, on the thread that makes it so: the files must still be there then
            val onPhoneWhenIdle = CompletableDeferred<Boolean>()
            val watch =
                CoroutineScope(Dispatchers.Unconfined).also(scopes::add).launch {
                    host.state.first { it is HostState.Idle }
                    onPhoneWhenIdle.complete(testHub.onPhone(swift))
                }
            assertFalse("loaded before the remove", watch.isCompleted)
            repository(host).remove(swift)
            assertTrue("released before its files went", withTimeout(5_000) { onPhoneWhenIdle.await() })
            assertFalse(testHub.onPhone(swift))
        }

    @Test fun removingAModelAReplyStreamsFromWaitsForTheReply() =
        runBlocking {
            testHub.install(swift)
            val host = host()
            val lease = host.hold(swift, LoadConfig())
            val removal = async { repository(host).remove(swift) }
            delay(200)
            assertFalse(removal.isCompleted)
            assertTrue("still on the phone", testHub.onPhone(swift))
            lease.release()
            withTimeout(10_000) { removal.await() }
            assertFalse(testHub.onPhone(swift))
        }

    @Test fun removingTheModelALoadWaitsToReplaceWaitsForItsReply() =
        runBlocking {
            testHub.install(swift, other)
            val host = host()
            val lease = host.hold(swift, LoadConfig())
            val next = async { host.hold(other, LoadConfig()) }
            withTimeout(10_000) { while ((host.state.value as? HostState.Waking)?.outgoing?.key != swift) delay(10) }
            val removal = async { repository(host).remove(swift) }
            delay(200)
            assertTrue("still on the phone", testHub.onPhone(swift))
            // the 0.8B's reply ends: it closes and is deleted, and the load waiting to replace it goes on
            lease.release()
            withTimeout(10_000) { removal.await() }
            assertFalse(testHub.onPhone(swift))
            val held = withTimeout(10_000) { next.await() }
            assertEquals(other, (host.state.value as HostState.Ready).key)
            held.release()
        }

    @Test fun removingAModelTwiceDuringItsReplyDeletesItOnlyAfterTheReply() =
        runBlocking {
            testHub.install(swift)
            val host = host()
            val lease = host.hold(swift, LoadConfig())
            // one repository, as in the app: its deletions run one at a time (the hub's delete of one model, run twice
            // at once, meets its own files gone mid-way)
            val repo = repository(host)
            val first = async { repo.remove(swift) }
            val second = async { repo.remove(swift) }
            delay(200)
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertTrue("still on the phone", testHub.onPhone(swift))
            lease.release()
            withTimeout(10_000) {
                first.await()
                second.await()
            }
            assertFalse(testHub.onPhone(swift))
        }

    @Test fun removingTheNextModelAndThenTheOutgoingOneDeletesNeitherUnderAReply() =
        runBlocking {
            testHub.install(swift, other)
            val host = host()
            val lease = host.hold(swift, LoadConfig())
            val next = async { host.hold(other, LoadConfig()) }
            withTimeout(10_000) { while ((host.state.value as? HostState.Waking)?.outgoing?.key != swift) delay(10) }
            val removeNext = async { repository(host).remove(other) }
            withTimeout(10_000) { while (host.state.value != HostState.Idle) delay(10) }
            val removeOutgoing = async { repository(host).remove(swift) }
            delay(200)
            assertFalse(removeOutgoing.isCompleted)
            assertTrue("still on the phone", testHub.onPhone(swift))
            lease.release()
            withTimeout(10_000) { removeOutgoing.await() }
            assertFalse(testHub.onPhone(swift))
            // the turn that waited for the other model gets it, and it is deleted once that turn ends
            withTimeout(10_000) { next.await() }.release()
            withTimeout(10_000) { removeNext.await() }
            assertFalse(testHub.onPhone(other))
        }

    @Test fun libraryUpdatesAfterAModelIsRemoved() =
        runBlocking {
            testHub.install(swift)
            val repo = repository()
            assertEquals(1, repo.library.first().size)
            repo.remove(swift)
            assertEquals(0, repo.library.first().size)
        }

    /** The pending set is written to settings a moment after `get` returns; wait for [wanted] rather than race it. */
    private suspend fun ModelRepository.downloadingIs(wanted: List<HfModel>) =
        withTimeout(5_000) { downloading.first { it == wanted } }

    @Test fun downloadingListsAStartedModelUntilItIsOnThePhone() =
        runBlocking<Unit> {
            val repo = repository()
            assertEquals(emptyList<HfModel>(), repo.downloading.first())
            repo.get(swift)
            repo.downloadingIs(listOf(swift))
            testHub.install(swift)
            repo.changed()
            repo.downloadingIs(emptyList())
        }

    @Test fun savingATokenRestartsOnlyTheDownloadsRefusedForWantOfOne() =
        runBlocking<Unit> {
            val other = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
            val repo = repository()
            repo.get(swift)
            scheduler.awaitEnqueued(swift)
            repo.get(other)
            // both are written down before the token arrives
            repo.downloadingIs(listOf(swift, other).sortedBy { it.toString() })
            // the 0.8B is refused for want of a token, so the 4B waiting behind it starts, and fails on the network,
            // which the worker would retry: it keeps its place
            testHub.refuse(swift, Refusal.Unauthorized)
            scheduler.endWork(swift) // the worker fails for good
            scheduler.awaitEnqueued(swift, other)
            testHub.refuse(other, Refusal.Network)
            repo.stateBecomes(other) { it is DownloadStatus.Failed }
            scheduler.enqueued.clear()

            repo.tokenSaved()
            delay(300) // the store answers off this thread; a start would have shown by now
            assertEquals("the 0.8B waits for the 4B under way", emptyList<HfModel>(), scheduler.enqueued)
            testHub.install(other)
            scheduler.awaitEnqueued(swift) // the network failure is not the token's business: only the 0.8B starts
        }

    @Test fun aSecondDownloadWaitsForTheFirst() =
        runBlocking<Unit> {
            val repo = repository()
            repo.get(swift)
            scheduler.awaitEnqueued(swift)
            repo.get(other)
            repo.downloadingIs(listOf(other, swift).sortedBy { it.toString() })
            repo.settle()
            assertEquals("one download at a time (D44)", listOf(swift), scheduler.enqueued)
            testHub.install(swift) // the worker completes the first
            scheduler.awaitEnqueued(swift, other)
        }

    @Test fun aRestartCarriesOnTheQueue() =
        runBlocking<Unit> {
            // the last process left three pending: the 0.8B's work still running in WorkManager, Some-Model waiting
            // with no work, and the 4B paused by the person
            for (key in listOf(swift, other, third)) settings.addPendingDownload(key.toString())
            settings.setDownloadPaused(third.toString(), true)
            scheduler.scheduled(swift)
            val repo = repository()
            repo.settle() // the restore has run
            assertEquals("the running one carries on; nothing else starts", emptyList<HfModel>(), scheduler.enqueued)
            scheduler.endWork(swift)
            scheduler.awaitEnqueued(other) // the waiting one starts by itself; the paused one stays paused
            repo.settle()
            assertEquals(listOf(other), scheduler.enqueued)
        }

    @Test fun aRestoredDownloadWhoseWorkEndsBeforeItIsWatchedStillMovesTheQueueOn() =
        runBlocking<Unit> {
            for (key in listOf(swift, other)) settings.addPendingDownload(key.toString())
            scheduler.scheduled(swift)
            // the 0.8B's work is seen running at the restart, and ends before the repository starts watching it
            scheduler.afterIsScheduled = { if (it == swift) scheduler.endWork(swift) }
            repository()
            scheduler.awaitEnqueued(other)
        }

    @Test fun aRestartWithNothingRunningStartsTheFirstWaiting() =
        runBlocking<Unit> {
            settings.addPendingDownload(other.toString())
            repository()
            scheduler.awaitEnqueued(other)
        }

    @Test fun workThatEndsOutsideTheHubMovesTheQueueOn() =
        runBlocking<Unit> {
            val repo = repository()
            repo.get(swift)
            scheduler.awaitEnqueued(swift)
            repo.get(other)
            repo.downloadingIs(listOf(other, swift).sortedBy { it.toString() })
            // the notification's Cancel, or the worker giving up after its stalled tries: the hub's state doesn't end
            scheduler.endWork(swift)
            scheduler.awaitEnqueued(swift, other)
        }

    @Test fun aCompletionBeforeTheWatchStartsIsNotLost() =
        runBlocking<Unit> {
            testHub.publish(swift)
            // the download completes as it is handed over, before the repository watches the hub's state
            scheduler.onEnqueue = { if (it == swift) testHub.install(swift) }
            val repo = repository()
            repo.get(other)
            scheduler.awaitEnqueued(other)
            repo.get(swift)
            scheduler.endWork(other) // Some-Model's work ends, the 0.8B starts and completes at once
            scheduler.awaitEnqueued(other, swift)
            testHub.publish(third)
            repo.get(third)
            scheduler.awaitEnqueued(other, swift, third)
        }

    @Test fun aDownloadStoppedOutsideTheAppIsNotStartedAgainAtTheNextLaunch() =
        runBlocking<Unit> {
            val repo = repository()
            repo.get(swift)
            scheduler.awaitEnqueued(swift)
            // the notification's Cancel, or the worker giving up: the person saw it stop
            scheduler.endWork(swift)
            assertEquals(DownloadStatus.Partial(0, 0), repo.stateBecomes(swift) { it is DownloadStatus.Partial })
            // the next launch: a new repository over the same settings and WorkManager
            scheduler.enqueued.clear()
            val next = repository()
            next.settle() // the restore has run
            assertEquals("nothing starts unasked (D64)", emptyList<HfModel>(), scheduler.enqueued)
            assertEquals(listOf(swift), next.downloading.first())
            next.get(swift) // Resume starts it
            scheduler.awaitEnqueued(swift)
        }

    @Test fun aLateEndOfAPausedRunDoesNotEndTheRunAResumeStarted() =
        runBlocking<Unit> {
            // a cancel takes a moment to be recorded; Resume is tapped meanwhile, and the paused run's end arrives
            // after the resumed run has started
            val recorded = CompletableDeferred<Unit>()
            scheduler.cancelGate = recorded
            val repo = repository()
            repo.get(swift)
            scheduler.awaitEnqueued(swift)
            repo.pause(swift)
            repo.get(swift)
            recorded.complete(Unit)
            scheduler.awaitEnqueued(swift, swift)
            repo.get(other)
            repo.settle()
            assertEquals("the resumed run still runs, so Some-Model waits", listOf(swift, swift), scheduler.enqueued)
            assertEquals(listOf(swift), scheduler.running)
        }

    @Test fun pauseThenResumeLeavesOneDownloadRunning() =
        runBlocking<Unit> {
            val repo = repository()
            repo.get(swift)
            scheduler.awaitEnqueued(swift)
            repo.get(other)
            repo.downloadingIs(listOf(other, swift).sortedBy { it.toString() })
            repo.pause(swift)
            repo.get(swift) // Resume, tapped at once
            scheduler.awaitEnqueued(swift, other)
            repo.settle()
            assertEquals("one download at a time", listOf(other), scheduler.running)
        }

    @Test fun pausingADownloadThatHadNotStartedReadsPaused() =
        runBlocking<Unit> {
            val repo = repository()
            repo.get(swift)
            scheduler.awaitEnqueued(swift)
            repo.get(other)
            repo.downloadingIs(listOf(other, swift).sortedBy { it.toString() })
            repo.pause(other)
            assertEquals(DownloadStatus.Partial(0, 0), repo.stateBecomes(other) { it is DownloadStatus.Partial })
        }

    @Test fun pausingTheDownloadUnderWayStartsTheNext() =
        runBlocking<Unit> {
            val repo = repository()
            repo.get(swift)
            scheduler.awaitEnqueued(swift)
            repo.get(other)
            repo.settle()
            assertEquals("Some-Model waits for the 0.8B", listOf(swift), scheduler.enqueued)
            repo.pause(swift)
            scheduler.awaitEnqueued(swift, other)
            assertEquals(listOf(bystander, swift), scheduler.cancelled)
        }

    @Test fun aModelAlreadyOnThePhoneIsNotListedAsDownloading() =
        runBlocking<Unit> {
            testHub.install(swift)
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
            testHub.install(swift, swiftQ8, other)
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

    @Test fun aReaderAfterAChangeSeesTheNewScan() =
        runBlocking {
            testHub.install(swift)
            val repo = repository()
            val readers = List(3) { async { repo.library.first() } }
            readers.forEach { assertEquals(listOf(swift), it.await().map { row -> row.key }) }
            testHub.install(other)
            repo.changed()
            // a reader after a change waits for the new scan, never the old one
            assertEquals(
                setOf(swift, other),
                repo.library
                    .first()
                    .map { it.key }
                    .toSet(),
            )
        }

    @Test fun theCacheIsScannedOncePerChangeWhoeverCollects() =
        runBlocking {
            testHub.install(swift)
            val scans = AtomicInteger()
            val repo = repository(installed = { hub.installed().also { scans.incrementAndGet() } })
            val readers = List(3) { async { repo.library.first() } }
            readers.forEach { assertEquals(listOf(swift), it.await().map { row -> row.key }) }
            assertEquals(1, scans.get())
            testHub.install(other)
            repo.changed()
            assertEquals(
                setOf(swift, other),
                repo.library
                    .first()
                    .map { it.key }
                    .toSet(),
            )
            // one new scan for the library, plus `changed`'s own look at which downloads finished
            withTimeout(5_000) { while (scans.get() < 3) delay(10) }
            delay(100)
            assertEquals(3, scans.get())
        }

    @Test fun aTokenSavedAfterARestartStartsTheDownloadRefusedForWantOfOne() =
        runBlocking<Unit> {
            val repo = repository()
            repo.get(swift)
            scheduler.awaitEnqueued(swift)
            testHub.refuse(swift, Refusal.Unauthorized)
            repo.stateBecomes(swift) { it is DownloadStatus.Failed }
            scheduler.endWork(swift) // the worker fails for good
            withTimeout(5_000) { settings.settings.first { swift.toString() in it.downloadsNeedingToken } }
            // the next launch: a new hub no longer knows why it stopped, and the row reads paused
            val restarted = TestHub()
            scheduler.enqueued.clear()
            val next =
                ModelRepository(
                    restarted.hub,
                    settings,
                    scheduler,
                    CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add),
                )
            next.settle()
            assertEquals("nothing starts unasked (D64)", emptyList<HfModel>(), scheduler.enqueued)
            next.tokenSaved()
            scheduler.awaitEnqueued(swift)
            restarted.close()
        }

    @Test fun aCancelThatCantStopTheWorkKeepsTheFiles() =
        runBlocking {
            testHub.install(swift)
            val repo = repository()
            scheduler.cancelFailure = IllegalStateException("WorkManager's database is gone")
            repo.remove(swift)
            assertTrue("the work may still be writing, so its files stay", testHub.onPhone(swift))
            assertEquals(listOf(swift), repo.library.first().map { it.key })
            repo.remove(swift) // the next try goes through
            assertFalse(testHub.onPhone(swift))
        }
}
