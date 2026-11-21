package com.suhel.llamabro.demo.models

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.suhel.llamabro.demo.data.db.ChatDatabase
import com.suhel.llamabro.demo.data.db.HubRepoEntity
import com.suhel.llamabro.hub.testing.FakeLlamaBroHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/** The Hub's facts from Room first, refreshed on request: failures keep what is stored, concurrent asks share. */
@RunWith(RobolectricTestRunner::class)
class HubRepositoryTest {
    private lateinit var db: ChatDatabase
    private lateinit var scope: CoroutineScope
    private var now = 0L
    private val filesCalls = AtomicInteger()
    private var gate: CompletableDeferred<Unit>? = null
    private var offline = false
    private var baseOf: String? = null
    private val pagesAsked = mutableListOf<String>()
    private val baseAsked = mutableListOf<String>()

    private val discovery =
        object : ModelDiscovery(FakeLlamaBroHub { }.hub) {
            override suspend fun search(query: String): List<SearchModel> {
                if (offline) error("offline")
                return listOf(
                    row("a/Qwen3.5-0.8B-GGUF").copy(repos = listOf("a/Qwen3.5-0.8B-GGUF", "d/Qwen3.5-0.8B-GGUF")),
                    row("b/Qwen3.5-0.8B-GGUF"),
                )
            }

            override suspend fun files(repo: String): List<RepoFile> {
                filesCalls.incrementAndGet()
                gate?.await()
                if (offline) error("offline")
                return listOf(RepoFile("m.gguf", "Q4_K_M", 100, 5))
            }

            override suspend fun page(repo: String): String? = "page of $repo".also { pagesAsked += repo }

            override suspend fun baseModel(repo: String): String? = baseOf.also { baseAsked += repo }
        }

    private lateinit var repository: HubRepository

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repository = HubRepository(discovery, db.hubCacheDao(), scope, { it() }) { ++now }
    }

    @After fun close() {
        scope.cancel()
        db.close()
    }

    private fun row(repo: String) =
        SearchModel("g", "G", repo, repo.substringBefore('/'), 10, sees = false, catalog = null, maker = "Qwen")

    /** A stored row's name is cleaned when read, so a refresh that lands never relabels it. */
    @Test fun aStoredRowsNameIsCleanedWhenReadAndItsRefreshChangesNoLabel() =
        runBlocking {
            fun facts(name: String) =
                """{"groupKey":"gemma-4-12b-it",$name,"repo":"u/gemma-4-12b-it-GGUF",""" +
                    """"author":"u","downloads":1,"sees":false,"maker":null,"capabilities":[],"canChat":true,""" +
                    """"parameters":null,"activeParameters":null,"baseModel":null}"""
            val repo = "u/gemma-4-12b-it-GGUF"
            // a row stored before the raw name was kept: its name as it was cleaned then ("it" not yet dropped)
            db.hubCacheDao().putRepo(HubRepoEntity(repo.lowercase(), facts(""""displayName":"Gemma 4 12B it""""), 1))
            assertEquals(
                "Gemma 4 12B",
                repository
                    .repoFacts(repo)
                    .first()
                    .value!!
                    .displayName,
            )
            // what a refresh stores now is the Hub's own name; read, it is the same label
            db.hubCacheDao().putRepo(HubRepoEntity(repo.lowercase(), facts(""""name":"gemma-4-12b-it""""), 2))
            assertEquals(
                "Gemma 4 12B",
                repository
                    .repoFacts(repo)
                    .first()
                    .value!!
                    .displayName,
            )
        }

    @Test fun aSuccessfulRefreshClearsTheFailure() =
        runBlocking {
            offline = true
            repository.refreshFiles("a/b")
            assertNotNull(repository.files("a/b").first().failure)
            offline = false
            repository.refreshFiles("a/b")
            val ok = repository.files("a/b").first()
            assertNull(ok.failure)
            assertEquals(listOf(RepoFile("m.gguf", "Q4_K_M", 100, 5)), ok.value)
        }

    @Test fun twoConcurrentRefreshesOfOneRepoAskOnce() =
        runBlocking {
            gate = CompletableDeferred()
            val one = async(Dispatchers.Default) { repository.refreshFiles("a/b") }
            val two = async(Dispatchers.Default) { repository.refreshFiles("A/B") }
            while (filesCalls.get() == 0) Thread.sleep(5)
            Thread.sleep(50)
            assertTrue(repository.files("a/b").first().refreshing)
            gate!!.complete(Unit)
            one.await()
            two.await()
            assertEquals(1, filesCalls.get())
            assertFalse(repository.files("a/b").first().refreshing)
        }

    @Test fun recentSearchesKeepEightNewestFirst() =
        runBlocking {
            repeat(10) { repository.rememberSearch("q$it") }
            assertEquals((9 downTo 2).map { "q$it" }, repository.recentSearches().first())
        }

    @Test fun aSearchTypedInAnotherCaseIsOneEntryWithTheLatestCasing() =
        runBlocking {
            repository.rememberSearch("Qwen")
            repository.rememberSearch("other")
            repository.rememberSearch(" qwen ")
            assertEquals(listOf("qwen", "other"), repository.recentSearches().first())
        }

    @Test fun aRepoThatIsOnlyAMemberOfAGroupHasItsFactsToo() =
        runBlocking {
            repository.refreshRepoFacts("d/Qwen3.5-0.8B-GGUF")
            val facts = repository.repoFacts("d/Qwen3.5-0.8B-GGUF").first()
            assertNull(facts.failure)
            assertEquals("d/Qwen3.5-0.8B-GGUF", facts.value!!.repo)
        }

    @Test fun repoFactsAreTheRowOfThatRepoIgnoringCase() =
        runBlocking {
            repository.refreshRepoFacts("B/qwen3.5-0.8b-gguf")
            assertEquals(
                "b/Qwen3.5-0.8B-GGUF",
                repository
                    .repoFacts("b/Qwen3.5-0.8B-GGUF")
                    .first()
                    .value!!
                    .repo,
            )
        }

    @Test fun aRepoTheSearchDoesNotReturnStoresNothingAndFails() =
        runBlocking {
            repository.refreshRepoFacts("c/Qwen3.5-0.8B-GGUF")
            val facts = repository.repoFacts("c/Qwen3.5-0.8B-GGUF").first()
            assertNull(facts.value)
            assertTrue(facts.failure is NoSuchElementException)
        }

    @Test fun thePageIsTheBaseModelsWhenTheRepoNamesOne() =
        runBlocking {
            baseOf = "Qwen/Qwen3.5-0.8B"
            repository.refreshPage("a/b")
            assertEquals(listOf("Qwen/Qwen3.5-0.8B"), pagesAsked)
            assertEquals(
                PageFacts("page of Qwen/Qwen3.5-0.8B", "Qwen/Qwen3.5-0.8B"),
                repository.page("a/b").first().value,
            )
            baseOf = null
            pagesAsked.clear()
            repository.refreshPage("a/b")
            assertEquals(listOf("a/b"), pagesAsked)
        }

    @Test fun aCatalogReposPageIsItsKnownOriginalsWithNoBaseModelRequest() =
        runBlocking {
            // the catalog names each upload's original, so the card's page needs one request, not two
            val swift = Catalog.suggested[0]
            baseOf = "someone/else"
            repository.refreshPage(swift.key.repo)
            assertEquals(emptyList<String>(), baseAsked)
            assertEquals(listOf(swift.baseModel), pagesAsked)
            assertEquals(
                swift.baseModel,
                repository
                    .page(swift.key.repo)
                    .first()
                    .value
                    ?.baseModel,
            )
        }

    @Test fun suggestionFactsAreRefreshedOncePerProcessAndAgainAfterAFailure() =
        runBlocking {
            val searches = AtomicInteger()
            val counting =
                object : ModelDiscovery(FakeLlamaBroHub { }.hub) {
                    override suspend fun search(query: String): List<SearchModel> {
                        searches.incrementAndGet()
                        if (offline) error("offline")
                        return listOf(row("a/Qwen3.5-0.8B-GGUF"))
                    }
                }
            val repo = HubRepository(counting, db.hubCacheDao(), scope, { it() }) { ++now }
            offline = true
            repo.refreshRepoFactsOnce("a/Qwen3.5-0.8B-GGUF")
            offline = false
            // the page opened again, and again: the failed one is asked once more, then never
            repo.refreshRepoFactsOnce("a/Qwen3.5-0.8B-GGUF")
            repo.refreshRepoFactsOnce("A/qwen3.5-0.8b-gguf")
            assertEquals(2, searches.get())
            assertNotNull(repo.repoFacts("a/Qwen3.5-0.8B-GGUF").first().value)
        }
}
