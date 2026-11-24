package com.suhel.llamabro.demo.ui.models

import androidx.lifecycle.SavedStateHandle
import androidx.paging.CombinedLoadStates
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import com.suhel.llamabro.demo.data.settings.FiltersChange
import com.suhel.llamabro.demo.data.settings.SearchFilters
import com.suhel.llamabro.demo.data.settings.SizeFilter
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.Guess
import com.suhel.llamabro.demo.models.HubQuery
import com.suhel.llamabro.demo.models.HubSearch
import com.suhel.llamabro.demo.models.query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Models page's search (models-search design), on the view model's [scope]: what is typed ([query], kept across
 * process death in [savedState]), the [filters], the local models whose name has the query ([search]), and the Hub's
 * stored pages for a query of two characters or more ([hubPages], Paging 3 over Room), asked of the Hub
 * [debounceMillis] after the last keystroke ([fetching] while any page is).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ModelSearch(
    private val hub: HubSearch,
    private val scope: CoroutineScope,
    savedState: SavedStateHandle,
    initialQuery: String,
    debounceMillis: Long,
    searchFilters: Flow<SearchFilters>,
    private val saveSearchFilters: suspend (FiltersChange) -> Unit,
    deviceFacts: StateFlow<DeviceFacts?>,
    local: Flow<Local>,
) {
    // user input: what is typed, kept across process death
    private val queryInput = savedState.getMutableStateFlow(QUERY_KEY, initialQuery)

    // user input: each Try again, a counter so the same query is asked again
    private val retryInput = MutableStateFlow(0)

    val query: StateFlow<String> = queryInput.asStateFlow()

    /** The filters as the settings hold them; null until they are read, so nothing is asked under the defaults. */
    private val savedFilters: StateFlow<SearchFilters?> = searchFilters.stateIn(scope, SharingStarted.Eagerly, null)

    /** The filters the sheet and the page's dot and line show: the defaults until the settings are read. */
    val filters: StateFlow<SearchFilters> =
        savedFilters
            .filterNotNull()
            .stateIn(scope, SharingStarted.Eagerly, SearchFilters())

    /** Whether "Fits this phone" is chosen on a phone where nothing fits: its cap (F176) is under any model (D128). */
    val nothingFits: StateFlow<Boolean> =
        combine(savedFilters, deviceFacts) { chosen, phone -> fitsNothing(chosen, phone) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), false)

    /**
     * What the Hub is asked for the typed query, or null under two characters, before the saved filters and the
     * phone's facts (the "Fits this phone" cap) are read, and when nothing can fit; a retry makes it a new request
     * even for the same query.
     */
    val hubRequest: StateFlow<HubRequest?> =
        combine(queryInput.map { it.trim() }.distinctUntilChanged(), savedFilters, deviceFacts, retryInput) {
            text,
            chosen,
            phone,
            attempt,
            ->
            when {
                text.length < ModelsViewModel.MIN_QUERY || chosen == null || phone == null -> null
                fitsNothing(chosen, phone) -> null
                else -> HubRequest(chosen.query(text, phone), attempt)
            }
        }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private fun fitsNothing(chosen: SearchFilters?, phone: DeviceFacts?): Boolean =
        chosen?.size == SizeFilter.FitsPhone && phone != null && Guess.fitCap(phone) < Guess.SMALLEST_MODEL

    /**
     * The Hub's pages for the query and filters as typed: a newer query cancels the one before, so an older answer
     * can never land after it. Kept across the page's recompositions and rotations ([cachedIn]).
     */
    private val pages: Flow<PagingData<ModelEntry>> =
        hubRequest
            .flatMapLatest { request ->
                if (request == null) {
                    flowOf(PagingData.empty())
                } else {
                    hub.pages(request.query, debounceMillis).map { page -> page.map(ModelEntry::of) }
                }
            }.cachedIn(scope)

    private val pendingStored = PendingStored()

    /**
     * How many rows the query's last first-page refresh stored, read as the page draws with its load [states]
     * ([hubAnswer]), until Room's reload of them has come ([PendingStored]).
     */
    fun refreshStored(states: CombinedLoadStates): Int? =
        pendingStored.of(hubRequest.value?.let { hub.refreshStored(it.query) }, states)

    /** The search under way: while a page of the query is fetched. The field's line. */
    val fetching: StateFlow<Boolean> =
        hubRequest
            .flatMapLatest { request -> request?.let { hub.fetching(it.query) } ?: flowOf(false) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), false)

    /**
     * Search mode's body: the recent searches and the local models whose name has the query (all of them with none).
     * Hugging Face's rows are [hubPages].
     */
    val search: StateFlow<SearchView> =
        combine(queryInput, hub.recentSearches(), local, deviceFacts) { typed, recent, here, phone ->
            val text = typed.trim()
            val has = { entry: ModelEntry -> text.isEmpty() || entry.name.contains(text, ignoreCase = true) }
            // the suggestions in search are every catalog model whose name has the query, whatever its fit here:
            // a person who searches a model by name finds it, offline too; browse keeps great or well
            // the three groups from one snapshot of what is local, so a Get or a landing never lists a model twice
            // or not at all for a moment
            SearchView(
                typed,
                recent,
                here.downloading.filter(has),
                here.onPhone.filter(has),
                // in browse's order (best first), so with no query search lists browse's suggestions where they
                // were, the rest after them: focusing the field moves no row
                bestFirst(here.catalog, phone).filter(has),
            )
        }.distinctUntilChanged()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT),
                SearchView(initialQuery, emptyList(), emptyList(), emptyList(), emptyList(), HubAnswer.Idle),
            )

    /**
     * Hugging Face's rows for the query, in the Hub's order (never reordered here: a page arriving would reshuffle the
     * list, models-search design §2), less those the local part already lists (by repo, any upload of the row, or
     * group key).
     */
    val hubPages: Flow<PagingData<ModelEntry>> =
        combine(pages, search.map { Listed.of(it.local) }.distinctUntilChanged()) { page, listed ->
            page.filter { !listed.has(it) }
        }

    /** Changes the search's filters; they are remembered in the settings, and the Hub is asked again with them. */
    fun setFilters(change: FiltersChange) {
        scope.launch { saveSearchFilters(change) }
    }

    fun setQuery(text: String) {
        queryInput.value = text
    }

    /** The keyboard's search key, or a Hub result opened: the query is kept among the recent searches. */
    fun submit() {
        val text = queryInput.value.trim()
        if (text.isNotEmpty()) scope.launch { hub.rememberSearch(text) }
    }

    fun retry() {
        retryInput.update { it + 1 }
    }

    private companion object {
        const val QUERY_KEY = "query"
        const val STOP_TIMEOUT = 5_000L
    }
}

/** One ask of the Hub: the query with its filters, and the retry it belongs to. */
internal data class HubRequest(val query: HubQuery, val attempt: Int)

/** What the local part lists, to leave out of the Hub's rows: repos, every upload's, and group keys. */
private data class Listed(val repos: Set<String>, val keys: Set<String>) {
    fun has(row: ModelEntry): Boolean =
        row.repo.lowercase() in repos || row.groupKey in keys || row.uploads.any { it.lowercase() in repos }

    companion object {
        fun of(local: List<ModelEntry>) =
            Listed(local.map { it.repo.lowercase() }.toSet(), local.map { it.groupKey }.toSet())
    }
}
