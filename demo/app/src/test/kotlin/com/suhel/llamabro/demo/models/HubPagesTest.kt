package com.suhel.llamabro.demo.models

import android.content.Context
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadState
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingState
import androidx.paging.RemoteMediator.MediatorResult
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.data.db.ChatDatabase
import com.suhel.llamabro.demo.data.db.HubResultEntity
import com.suhel.llamabro.demo.data.settings.SearchFilters
import com.suhel.llamabro.demo.data.settings.SizeFilter
import com.suhel.llamabro.hub.testing.FakeLlamaBroHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.Collections

/**
 * A search's pages through Paging 3 (models-search design §1, §2): the mediator fetches the Hub's pages into Room in the
 * Hub's order with the next cursor, and Room's rows are what the list shows, offline too.
 */
@OptIn(ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
class HubPagesTest {
    private lateinit var db: ChatDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: HubRepository
    private var now = 0L

    /** The Hub's pages by the cursor that asks for them; null asks for the first. */
    private val hubPages = mutableMapOf<String?, SearchRowsPage>()
    private val asked: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private var offline = false
    private var gate: CompletableDeferred<Unit>? = null

    /** When the Hub was last asked for a page, by [System.nanoTime]. */
    @Volatile private var askedAtNanos = 0L

    private val discovery =
        object : ModelDiscovery(FakeLlamaBroHub { }.hub) {
            override suspend fun searchPage(query: HubQuery, cursor: String?): SearchRowsPage {
                askedAtNanos = System.nanoTime()
                asked += cursor
                gate?.await()
                if (offline) throw IOException("offline")
                return hubPages.getValue(cursor)
            }
        }

    private val query = SearchFilters().query("qwen", DeviceFacts(4L shl 30, DeviceKind.Npu))
    private val nothingLoaded = PagingState<Int, HubResultEntity>(emptyList(), null, PagingConfig(20), 0)

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repository =
            HubRepository(discovery, db.hubCacheDao(), scope, { writes -> db.withTransaction { writes() } }) { ++now }
    }

    @After fun close() {
        scope.cancel()
        db.close()
    }

    private fun row(
        name: String,
        downloads: Long = 10,
        repo: String = "someone/$name-GGUF",
        repos: List<String> = listOf(repo),
    ) = SearchModel(
        name.lowercase(),
        name,
        repo,
        repo.substringBefore('/'),
        downloads,
        sees = false,
        catalog = null,
        repos = repos,
    )

    private fun mediator(refreshAfterMillis: Long = 0) =
        HubPagesMediator(query, discovery::searchPage, repository, refreshAfterMillis)

    private suspend fun storedNames(): List<String> = db.hubCacheDao().resultsOf(query.key).map { it.groupKey }

    /**
     * What the list shows from Room alone: offline, so the Pager's own refresh fails and changes nothing stored; the
     * rows are read once the failure sits beside them, as the page would draw them.
     */
    private suspend fun shown(): List<SearchModel> {
        offline = true
        val presenter =
            // off the main looper, which a Robolectric test does not run by itself
            object : PagingDataPresenter<SearchModel>(Dispatchers.Default) {
                override suspend fun presentPagingDataEvent(event: PagingDataEvent<SearchModel>) = Unit
            }
        val showing = scope.launch { repository.pages(query).collectLatest(presenter::collectFrom) }
        try {
            withTimeout(5_000) {
                presenter.loadStateFlow.first {
                    it?.mediator?.refresh is LoadState.Error && it.source.refresh is LoadState.NotLoading
                }
            }
            return presenter.snapshot().items
        } finally {
            showing.cancel()
        }
    }

    @Test fun theFirstPageIsStoredInTheHubsOrderWithItsCursor() =
        runBlocking {
            hubPages[null] = SearchRowsPage(listOf(row("B", 1), row("A", 900), row("C", 5)), next = "two")
            val result = mediator().load(LoadType.REFRESH, nothingLoaded)
            assertTrue(result is MediatorResult.Success && !result.endOfPaginationReached)
            assertEquals("never reordered by downloads", listOf("b", "a", "c"), storedNames())
            assertEquals(listOf(0, 1, 2), db.hubCacheDao().resultsOf(query.key).map { it.position })
            assertEquals("two", repository.next(query.key))
        }

    @Test fun theNextPageIsFetchedWithTheStoredCursorAndAddedAfter() =
        runBlocking {
            hubPages[null] = SearchRowsPage(listOf(row("B"), row("A")), next = "two")
            hubPages["two"] = SearchRowsPage(listOf(row("D")), next = "three")
            mediator().load(LoadType.REFRESH, nothingLoaded)
            val result = mediator().load(LoadType.APPEND, nothingLoaded)
            assertTrue(result is MediatorResult.Success && !result.endOfPaginationReached)
            assertEquals(listOf(null, "two"), asked)
            assertEquals(listOf("b", "a", "d"), storedNames())
            assertEquals("three", repository.next(query.key))
        }

    @Test fun theLastPageEndsTheListAndNothingMoreIsAsked() =
        runBlocking {
            hubPages[null] = SearchRowsPage(listOf(row("B")), next = "two")
            hubPages["two"] = SearchRowsPage(listOf(row("D")), next = null)
            mediator().load(LoadType.REFRESH, nothingLoaded)
            val last = mediator().load(LoadType.APPEND, nothingLoaded)
            assertTrue(last is MediatorResult.Success && last.endOfPaginationReached)
            val after = mediator().load(LoadType.APPEND, nothingLoaded)
            assertTrue(after is MediatorResult.Success && after.endOfPaginationReached)
            assertEquals(listOf(null, "two"), asked)
            val before = mediator().load(LoadType.PREPEND, nothingLoaded)
            assertTrue("nothing comes before the first page", before is MediatorResult.Success)
            assertEquals(listOf(null, "two"), asked)
        }

    @Test fun aLaterPagesUploadOfAKnownModelMergesIntoItsRowWhereItStands() =
        runBlocking {
            hubPages[null] = SearchRowsPage(listOf(row("B"), row("A", 900, "first/A-GGUF")), next = "two")
            hubPages["two"] =
                SearchRowsPage(listOf(row("D"), row("A", 5_000, "second/A-GGUF")), next = null)
            mediator().load(LoadType.REFRESH, nothingLoaded)
            mediator().load(LoadType.APPEND, nothingLoaded)
            assertEquals("one row for A, where it first appeared", listOf("b", "a", "d"), storedNames())
            val merged = shown().single { it.groupKey == "a" }
            assertEquals("downloads summed", 5_900L, merged.downloads)
            assertEquals(listOf("first/A-GGUF", "second/A-GGUF"), merged.repos)
            assertEquals("the row keeps the upload it showed, so it keeps its key", "first/A-GGUF", merged.repo)
        }

    @Test fun aRefreshReplacesPageOne() =
        runBlocking {
            hubPages[null] = SearchRowsPage(listOf(row("B"), row("A")), next = "two")
            hubPages["two"] = SearchRowsPage(listOf(row("D")), next = "three")
            mediator().load(LoadType.REFRESH, nothingLoaded)
            mediator().load(LoadType.APPEND, nothingLoaded)
            hubPages[null] = SearchRowsPage(listOf(row("A"), row("E")), next = "two again")
            mediator().load(LoadType.REFRESH, nothingLoaded)
            assertEquals(listOf("a", "e"), storedNames())
            assertEquals("two again", repository.next(query.key))
        }

    @Test fun offlineTheStoredPagesShowAndTheFetchFailsBesideThem() =
        runBlocking {
            hubPages[null] = SearchRowsPage(listOf(row("B"), row("A")), next = "two")
            hubPages["two"] = SearchRowsPage(listOf(row("D")), next = "three")
            mediator().load(LoadType.REFRESH, nothingLoaded)
            mediator().load(LoadType.APPEND, nothingLoaded)
            offline = true
            val failed = mediator().load(LoadType.REFRESH, nothingLoaded)
            assertTrue(failed is MediatorResult.Error)
            assertEquals(
                "every stored page, in the Hub's order",
                listOf("B", "A", "D"),
                shown().map { it.name },
            )
        }

    @Test fun aSearchOfOtherFiltersIsAListOfItsOwn() =
        runBlocking {
            hubPages[null] = SearchRowsPage(listOf(row("B")), next = null)
            mediator().load(LoadType.REFRESH, nothingLoaded)
            val anySize = SearchFilters(size = SizeFilter.Any).query("qwen", DeviceFacts(4L shl 30, DeviceKind.Npu))
            assertTrue(db.hubCacheDao().resultsOf(anySize.key).isEmpty())
        }

    @Test fun theFirstPageIsAskedOnlyAfterTheDebounce() =
        runBlocking {
            hubPages[null] = SearchRowsPage(listOf(row("B")), next = null)
            val start = System.nanoTime()
            val load =
                async(Dispatchers.Default) { mediator(refreshAfterMillis = 400).load(LoadType.REFRESH, nothingLoaded) }
            load.await()
            assertEquals(listOf<String?>(null), asked)
            // a lower bound: a slow machine can only ask later, never inside the debounce
            val waitedMillis = (askedAtNanos - start) / 1_000_000
            assertTrue("asked $waitedMillis ms after opening, inside the 400 ms debounce", waitedMillis >= 400)
        }

    /**
     * The fetched page reaches the list when Room's invalidation reloads the Pager's source, which comes after the
     * mediator reports its refresh done: between the two the load states read idle over the empty first read, so
     * paging-testing's `asSnapshot` (idle for 1 ms) can return before the rows. The test waits for the rows instead.
     */
    @Test fun fetchingIsTrueWhileAPageIsAsked(): Unit =
        runBlocking {
            hubPages[null] = SearchRowsPage(listOf(row("B")), next = null)
            val held = CompletableDeferred<Unit>().also { gate = it }
            assertTrue(!repository.fetching(query).first())
            val presenter =
                // off the main looper, which a Robolectric test does not run by itself
                object : PagingDataPresenter<SearchModel>(Dispatchers.Default) {
                    override suspend fun presentPagingDataEvent(event: PagingDataEvent<SearchModel>) = Unit
                }
            val showing = scope.launch { repository.pages(query).collectLatest(presenter::collectFrom) }
            try {
                withTimeout(5_000) { repository.fetching(query).first { it } }
                held.complete(Unit)
                // the load states replay their latest, so rows shown before this subscribed are seen at once
                withTimeout(5_000) {
                    merge(presenter.loadStateFlow, presenter.onPagesUpdatedFlow).first {
                        presenter.snapshot().items.isNotEmpty()
                    }
                }
                assertEquals(listOf("B"), presenter.snapshot().items.map { it.name })
                withTimeout(5_000) { repository.fetching(query).first { !it } }
            } finally {
                showing.cancel()
            }
        }
}
