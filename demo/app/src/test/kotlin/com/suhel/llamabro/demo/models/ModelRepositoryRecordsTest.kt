package com.suhel.llamabro.demo.models

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.db.ChatDatabase
import com.suhel.llamabro.demo.data.db.HubFilesEntity
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A download's record (name, maker, whole size) is kept in Room from Get until the model lands or is cancelled. */
@RunWith(RobolectricTestRunner::class)
class ModelRepositoryRecordsTest {
    @get:Rule val folder = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val record = DownloadRecord(swift, "Qwen3.5 0.8B", "Alibaba Qwen", 739_863_648)

    private val testHub = TestHub()
    private val scheduler = FakeScheduler()
    private lateinit var db: ChatDatabase
    private val settings: SettingsRepository by lazy {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun close() {
        scopes.forEach { it.cancel() }
        testHub.close()
        db.close()
    }

    private fun repository(): ModelRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        return ModelRepository(testHub.hub, settings, scheduler, scope, recordsDao = db.hubCacheDao())
    }

    private suspend fun ModelRepository.recordIs(wanted: DownloadRecord?) =
        withTimeout(5_000) {
            while (record(swift).first() != wanted) delay(10)
        }

    @Test fun getKeepsTheRecordAndANewRepositoryOverTheSameDatabaseReadsIt() =
        runBlocking {
            val repo = repository()
            repo.get(swift, record)
            repo.recordIs(record)
            assertEquals(mapOf(swift to record), repo.records.first())
            // a restart: the next process builds a new repository over the same database
            assertEquals(record, repository().record(swift).first())
        }

    @Test fun aCancelledDownloadsRecordIsGone() =
        runBlocking {
            val repo = repository()
            repo.get(swift, record)
            repo.recordIs(record)
            assertEquals(true, repo.cancel(swift).await())
            repo.recordIs(null)
            assertEquals(emptyMap<HfModel, DownloadRecord>(), repo.records.first())
        }

    @Test fun aRemovedModelsRecordIsGone() =
        runBlocking {
            val repo = repository()
            repo.get(swift, record)
            repo.recordIs(record)
            repo.remove(swift)
            repo.recordIs(null)
        }

    @Test fun aLandedModelsRecordIsGoneOnceTheLibraryListsIt() =
        runBlocking<Unit> {
            val repo = repository()
            testHub.publish(swift)
            repo.get(swift, record)
            repo.recordIs(record)
            testHub.hub.download(swift).collect()
            withTimeout(5_000) { repo.library.first { rows -> rows.any { it.key == swift } } }
            repo.recordIs(null)
        }

    @Test fun aGetWithoutARecordAfterAPauseKeepsTheRecord() =
        runBlocking {
            val repo = repository()
            repo.get(swift, record)
            repo.recordIs(record)
            repo.pause(swift)
            repo.get(swift)
            // both Gets start the work: waiting for the second one's start is what makes the check follow it
            scheduler.awaitEnqueued(swift, swift)
            assertEquals(record, repo.record(swift).first())
        }

    @Test fun aModelWithoutAGetHasNoRecord() =
        runBlocking {
            assertNull(repository().record(swift).first())
        }

    /**
     * A model's image reader is read from its repo's cached listing in one query for the whole
     * library, which follows the cache: a listing stored after the scan gives the row its reader without a rescan.
     */
    @Test fun aReaderComesFromTheRepoListingAndFollowsIt() =
        runBlocking<Unit> {
            val foo = HfModel.parse("someone/Foo-7B-GGUF:Q4_K_M")
            testHub.install(foo)
            val repo = repository()
            // one collector, as the page is: the same library flow must follow the listing
            val reader = MutableStateFlow<Long?>(null)
            val watch =
                launch(Dispatchers.Default) {
                    repo.library.collect { rows -> reader.value = rows.firstOrNull { it.key == foo }?.readerBytes }
                }
            withTimeout(5_000) { reader.first { it == 0L } }
            val files = listOf(RepoFile("Foo-7B-Q4_K_M.gguf", "Q4_K_M", 4_000_000_000, 600_000_000))
            val json = Json.encodeToString(ListSerializer(RepoFile.serializer()), files)
            db.hubCacheDao().putFiles(HubFilesEntity("someone/foo-7b-gguf", json, 1))
            withTimeout(5_000) { reader.first { it == 600_000_000L } }
            watch.cancel()
        }
}
