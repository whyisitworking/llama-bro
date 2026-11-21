package com.suhel.llamabro.demo.models

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.filter
import androidx.paging.map
import com.suhel.llamabro.demo.data.db.HubCacheDao
import com.suhel.llamabro.demo.data.db.HubFilesEntity
import com.suhel.llamabro.demo.data.db.HubPageEntity
import com.suhel.llamabro.demo.data.db.HubRepoEntity
import com.suhel.llamabro.demo.data.db.HubResultEntity
import com.suhel.llamabro.demo.data.db.HubSearchPageEntity
import com.suhel.llamabro.demo.data.db.RecentSearchEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A repo's model page: its README and the original it was quantized from; either may be missing. */
data class PageFacts(val markdown: String?, val baseModel: String?)

/**
 * What the Models page reads of the Hub (docs/design/models-page.md §2): a query's stored rows and their refresh, the
 * recent searches, and a repo's facts for the suggestions. [HubRepository] in the app; a test fakes it with no Room.
 */
interface HubSearch {
    /**
     * [query]'s rows as Room holds them, a page at a time in the Hub's order, the next page fetched as the list nears
     * its end; the first page is refreshed [refreshAfterMillis] after the list opens (models-search design §1).
     */
    fun pages(query: HubQuery, refreshAfterMillis: Long = 0): Flow<PagingData<SearchModel>>

    /** Whether the Hub is being asked for a page of [query]: the field's line. */
    fun fetching(query: HubQuery): Flow<Boolean>

    suspend fun rememberSearch(query: String)

    fun recentSearches(): Flow<List<String>>

    fun repoFacts(repo: String): Flow<Cached<SearchModel>>

    suspend fun refreshRepoFacts(repo: String)

    /**
     * [refreshRepoFacts], once per process for each repo (the suggestions' facts, asked on every visit to the page):
     * a refresh that failed is asked again next time.
     */
    suspend fun refreshRepoFactsOnce(repo: String)

    fun files(repo: String): Flow<Cached<List<RepoFile>>>
}

/**
 * What a model's card reads of the Hub (docs/design/models-page.md §4): the repo's files, its page and its facts, each
 * stored and refreshed on request. [HubRepository] in the app; a test fakes it with no Room.
 */
interface HubCard {
    fun files(repo: String): Flow<Cached<List<RepoFile>>>

    suspend fun refreshFiles(repo: String)

    fun page(repo: String): Flow<Cached<PageFacts>>

    suspend fun refreshPage(repo: String)

    fun repoFacts(repo: String): Flow<Cached<SearchModel>>

    suspend fun refreshRepoFacts(repo: String)
}

/**
 * The Hub's facts, offline first (docs/design/models-page.md §1): every reader emits what Room holds, at once, and
 * never fetches by itself; a screen asks for a refresh when it opens, and a failed one leaves the stored value with
 * the failure beside it. A search's pages are a Pager over Room with a [HubPagesMediator] (models-search design).
 */
class HubRepository(
    private val discovery: ModelDiscovery,
    private val dao: HubCacheDao,
    private val scope: CoroutineScope,
    /** Runs a page's writes as one transaction (the database's `withTransaction`), so a reader never sees half. */
    private val transaction: suspend (suspend () -> Unit) -> Unit,
    private val clock: () -> Long,
) : HubSearch,
    HubCard,
    SearchPageStore {
    private val lock = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<Unit>>()

    // external event: each refresh's progress and failure, as the network answers
    private val status = MutableStateFlow<Map<String, Status>>(emptyMap())

    /**
     * The repos whose facts this process has refreshed by [refreshRepoFactsOnce]: an atomic set (never observed), so
     * two callers can't both claim a repo's first refresh.
     */
    private val factsAsked = MutableStateFlow<Set<String>>(emptySet())

    @OptIn(ExperimentalPagingApi::class)
    override fun pages(query: HubQuery, refreshAfterMillis: Long): Flow<PagingData<SearchModel>> =
        Pager(
            config = PagingConfig(pageSize = PAGE_ROWS, initialLoadSize = PAGE_ROWS, enablePlaceholders = false),
            remoteMediator =
                HubPagesMediator(query, discovery::searchPage, this, refreshAfterMillis) { asking ->
                    setStatus("pages:${query.key}") { it.copy(refreshing = asking) }
                },
        ) { dao.results(query.key) }
            .flow
            // a row stored in a shape that no longer reads is left out; the next refresh rewrites the page
            .map { page -> page.filter { rowOf(it) != null }.map { checkNotNull(rowOf(it)) } }

    override fun fetching(query: HubQuery): Flow<Boolean> =
        status.map { it["pages:${query.key}"]?.refreshing == true }.distinctUntilChanged()

    override suspend fun next(search: String): String? = dao.searchPage(search)?.let { it.next ?: SearchPageStore.END }

    /**
     * A later page's row of a model already stored merges into it ([SearchRows.merge]: downloads summed, uploads
     * joined) where it stands, keeping the upload that stands for it, so the row neither moves nor changes its key.
     */
    override suspend fun write(search: String, rows: List<SearchModel>, next: String?, first: Boolean) =
        transaction {
            if (first) dao.deleteResults(search)
            val stored =
                if (first) emptyMap() else dao.resultsOf(search, rows.map { it.groupKey }).associateBy { it.groupKey }
            var position = if (first) 0 else (dao.lastPosition(search) ?: -1) + 1
            val entities =
                rows.map { row ->
                    val known = stored[row.groupKey]
                    val earlier = known?.let(::rowOf)
                    if (known != null && earlier != null) {
                        val merged = SearchRows.merge(listOf(earlier, row)).single()
                        known.copy(rowJson = encodeRow(merged.copy(repo = earlier.repo, author = earlier.author)))
                    } else {
                        HubResultEntity(search, row.groupKey, known?.position ?: position++, encodeRow(row))
                    }
                }
            dao.putResults(entities)
            dao.putSearchPage(HubSearchPageEntity(search, next, clock()))
        }

    /** Records a search the person submitted (not every debounced one), keeping the last few. */
    override suspend fun rememberSearch(query: String) {
        val key = searchKey(query)
        if (key.isEmpty()) return
        dao.putRecentSearch(RecentSearchEntity(key, query.trim(), clock()))
        dao.trimRecentSearches(RECENT_SEARCHES)
    }

    /** The last few searches, newest first. */
    override fun recentSearches(): Flow<List<String>> =
        dao.recentSearches(RECENT_SEARCHES).map { rows ->
            rows.map { it.text }
        }

    override fun repoFacts(repo: String): Flow<Cached<SearchModel>> =
        cached(
            "repo:${repoKey(repo)}",
            dao.repo(repoKey(repo)).map {
                it?.let { e ->
                    decode(e.factsJson) { s -> JSON.decodeFromString<StoredModel>(s).model() }
                }
            },
        )

    /** One search for the repo's family name, taking the row that is [repo]; none is a failure, nothing is stored. */
    override suspend fun refreshRepoFacts(repo: String) {
        val key = repoKey(repo)
        refresh("repo:$key") {
            val family = repo.substringAfter('/').replace(GGUF_SUFFIX, "")
            // a row's repo is only its group's representative; the asked upload may be another member
            val rows = discovery.search(family)
            val row =
                rows.firstOrNull { r -> r.repo.equals(repo, true) || r.repos.any { it.equals(repo, true) } }
                    ?: rows.firstOrNull { it.groupKey == Names.key(family) }
                    ?: throw NoSuchElementException("$repo is not in the Hub's search for $family")
            dao.putRepo(
                HubRepoEntity(
                    key,
                    JSON.encodeToString(
                        StoredModel(
                            row.copy(
                                repo =
                                    row.repos.firstOrNull { it.equals(repo, true) }
                                        ?: row.repo.takeIf { it.equals(repo, true) }
                                        ?: repo,
                            ),
                        ),
                    ),
                    clock(),
                ),
            )
        }
    }

    override suspend fun refreshRepoFactsOnce(repo: String) {
        val key = repoKey(repo)
        if (key in factsAsked.getAndUpdate { it + key }) return
        refreshRepoFacts(repo)
        if (status.value["repo:$key"]?.failure != null) factsAsked.update { it - key }
    }

    override fun files(repo: String): Flow<Cached<List<RepoFile>>> =
        cached(
            "files:${repoKey(repo)}",
            dao.files(repoKey(repo)).map {
                it?.let { e ->
                    decode(e.filesJson) { s -> JSON.decodeFromString(filesSerializer, s) }
                }
            },
        )

    override suspend fun refreshFiles(repo: String) {
        val key = repoKey(repo)
        refresh("files:$key") {
            val files = discovery.files(repo)
            dao.putFiles(HubFilesEntity(key, JSON.encodeToString(filesSerializer, files), clock()))
        }
    }

    override fun page(repo: String): Flow<Cached<PageFacts>> =
        cached(
            "page:${repoKey(repo)}",
            dao.page(repoKey(repo)).map { it?.let { e -> PageFacts(e.markdown, e.baseModel) } },
        )

    /**
     * The base model of [repo] when it names one (the catalog's, for a catalog upload, else the Hub's), then the page
     * of that original, else of [repo] itself.
     */
    override suspend fun refreshPage(repo: String) {
        val key = repoKey(repo)
        refresh("page:$key") {
            // a catalog upload's original is known: no request for it
            val base = Catalog.byRepo(repo)?.baseModel ?: discovery.baseModel(repo)
            val markdown = discovery.page(base ?: repo)
            dao.putPage(HubPageEntity(key, markdown, base, clock()))
        }
    }

    private data class Status(val refreshing: Boolean = false, val failure: Throwable? = null)

    private fun <T : Any> cached(key: String, stored: Flow<T?>): Flow<Cached<T>> =
        combine(stored, status.map { it[key] ?: Status() }.distinctUntilChanged()) { value, state ->
            Cached(value, state.refreshing, state.failure)
        }

    /** Concurrent refreshes of one [key] share one request, which runs on the app's scope, not the caller's. */
    private suspend fun refresh(key: String, fetch: suspend () -> Unit) {
        val job =
            lock.withLock {
                inFlight.getOrPut(key) { scope.async { run(key, fetch) } }
            }
        job.await()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun run(key: String, fetch: suspend () -> Unit) {
        setStatus(key) { it.copy(refreshing = true) }
        try {
            fetch()
            setStatus(key) { Status() }
        } catch (e: CancellationException) {
            setStatus(key) { it.copy(refreshing = false) }
            throw e
        } catch (e: Exception) {
            setStatus(key) { Status(failure = e) }
        } finally {
            withContext(NonCancellable) { lock.withLock { inFlight.remove(key) } }
        }
    }

    private fun setStatus(key: String, change: (Status) -> Status) =
        status.update { it + (key to change(it[key] ?: Status())) }

    private fun rowOf(entity: HubResultEntity): SearchModel? =
        decode(entity.rowJson) { JSON.decodeFromString<StoredModel>(it).model() }

    private fun encodeRow(row: SearchModel): String = JSON.encodeToString(StoredModel(row))

    /** A stored value that no longer reads (an older shape) counts as not stored: the next refresh rewrites it. */
    private fun <T> decode(json: String, read: (String) -> T): T? =
        try {
            read(json)
        } catch (_: SerializationException) {
            null
        }

    /**
     * A [SearchModel] as stored: the catalog entry is looked up again on reading, since the catalog ships with the app
     * and a stored copy would only go stale.
     */
    @Suppress("LongParameterList") // one field for each of SearchModel's
    @Serializable
    private class StoredModel(
        val groupKey: String,
        /** The Hub's own name; a row's name is derived from it when read. */
        val name: String = "",
        val repo: String,
        val author: String,
        val downloads: Long,
        val sees: Boolean,
        val maker: String?,
        val capabilities: List<Capability>,
        val canChat: Boolean,
        val parameters: Long?,
        val activeParameters: Long?,
        val baseModel: String?,
        val repos: List<String> = emptyList(),
        /** A row stored before [name]: the name as it was cleaned then, cleaned again when read. */
        val displayName: String = "",
    ) {
        constructor(m: SearchModel) : this(
            m.groupKey,
            m.name,
            m.repo,
            m.author,
            m.downloads,
            m.sees,
            m.maker,
            m.capabilities,
            m.canChat,
            m.parameters,
            m.activeParameters,
            m.baseModel,
            m.repos,
        )

        fun model() =
            SearchModel(
                groupKey,
                name.ifEmpty { displayName },
                repo,
                author,
                downloads,
                sees,
                Catalog.byRepo(repo),
                maker,
                capabilities,
                canChat,
                parameters,
                activeParameters,
                baseModel,
                repos,
            )
    }

    private companion object {
        const val RECENT_SEARCHES = 8

        /** Rows the list reads from Room at a time; the Hub's pages are the SDK's 40 repos, a row per model. */
        const val PAGE_ROWS = 20
        val JSON = Json { ignoreUnknownKeys = true }
        val filesSerializer = ListSerializer(RepoFile.serializer())
        val GGUF_SUFFIX = Regex("[-_.]?gguf$", RegexOption.IGNORE_CASE)

        fun searchKey(query: String) = query.trim().lowercase()

        fun repoKey(repo: String) = repo.lowercase()
    }
}
