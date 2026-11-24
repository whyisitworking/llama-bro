package com.suhel.llamabro.demo.ui.models

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.suhel.llamabro.demo.models.Cached
import com.suhel.llamabro.demo.models.HubQuery
import com.suhel.llamabro.demo.models.HubSearch
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SearchModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The Hub's cache as [HubSearch] gives it, in memory: a query's pages are [stored] rows per query text, read when its
 * list opens and handed over as one page; [fetchingNow] is the field's line per query key. Every query asked for is in
 * [asked].
 */
internal class FakeHubSearch : HubSearch {
    val stored = MutableStateFlow<Map<String, List<SearchModel>>>(emptyMap())
    val asked = mutableListOf<HubQuery>()

    /** The debounce each ask was given: the first page waits it before the Hub is asked. */
    val delays = mutableListOf<Long>()
    val fetchingNow = MutableStateFlow<Set<String>>(emptySet())
    val recent = MutableStateFlow<List<String>>(emptyList())
    val remembered = mutableListOf<String>()
    val facts = MutableStateFlow<Map<String, SearchModel>>(emptyMap())
    val factsAsked = mutableListOf<String>()
    val factsAskedOnce = mutableListOf<String>()
    val files = MutableStateFlow<Map<String, List<RepoFile>>>(emptyMap())

    override fun pages(query: HubQuery, refreshAfterMillis: Long): Flow<PagingData<SearchModel>> {
        asked += query
        delays += refreshAfterMillis
        // a real Pager, as the app's is: rows read when the list loads, all in one page
        return Pager(PagingConfig(pageSize = PAGE)) { ListSource(stored.value[query.text].orEmpty()) }.flow
    }

    private class ListSource(private val rows: List<SearchModel>) : PagingSource<Int, SearchModel>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SearchModel> =
            LoadResult.Page(rows, prevKey = null, nextKey = null)

        override fun getRefreshKey(state: PagingState<Int, SearchModel>): Int? = null
    }

    override fun fetching(query: HubQuery): Flow<Boolean> = fetchingNow.map { query.key in it }

    override suspend fun rememberSearch(query: String) {
        remembered += query
    }

    override fun recentSearches(): Flow<List<String>> = recent

    override fun repoFacts(repo: String): Flow<Cached<SearchModel>> =
        facts.map { Cached(it[repo.lowercase()], false, null) }

    override suspend fun refreshRepoFacts(repo: String) {
        factsAsked += repo.lowercase()
    }

    override suspend fun refreshRepoFactsOnce(repo: String) {
        factsAskedOnce += repo.lowercase()
    }

    override fun files(repo: String): Flow<Cached<List<RepoFile>>> =
        files.map { Cached(it[repo.lowercase()], false, null) }

    private companion object {
        const val PAGE = 50
    }
}
