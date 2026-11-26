package com.suhel.llamabro.demo.ui.models

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.CombinedLoadStates
import androidx.paging.PagingData
import com.suhel.llamabro.demo.data.settings.FiltersChange
import com.suhel.llamabro.demo.data.settings.SearchFilters
import com.suhel.llamabro.demo.designsystem.components.DownloadView
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Fits
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.HubSearch
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelLibrary
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext

/**
 * The page with the field not focused (docs/design/models-page.md §2, Browse): the intro on a fresh install, the
 * downloads, the models on the phone with what they take ([storage], none with no models), and the catalog models that
 * run great or well here, best first; when none does, [shortOnMemory] names the smallest one instead.
 */
@Immutable
data class Browse(
    val intro: Boolean,
    val downloading: List<ModelEntry>,
    val onPhone: List<ModelEntry>,
    val storage: StorageInfo?,
    val suggested: List<ModelEntry>,
    val shortOnMemory: ModelEntry?,
)

/**
 * What Hugging Face has for the query, under the person's own matches (§2, Search), as the page reads it from the
 * paged rows ([ModelsViewModel.hubPages]) and their load states ([hubAnswer]). The rows themselves are the Pager's,
 * drawn lazily in the page's list; an answer says how many are loaded and what to say beside them. [Loading] is while
 * the first page is fetched, the stored rows showing meanwhile; [Rows] may count none when every row the Hub gave is
 * already listed locally. A failure with rows stored keeps them ([Rows]): offline first.
 */
sealed interface HubAnswer {
    /** Under two characters: the Hub is not asked. */
    data object Idle : HubAnswer

    data class Loading(val rows: Int) : HubAnswer

    /** [moreFailed]: the next page failed to load; the rows stay, and a line under them asks again. */
    data class Rows(val rows: Int, val moreFailed: Boolean = false) : HubAnswer

    data object None : HubAnswer

    data class Failed(val retryable: Boolean) : HubAnswer

    data object Offline : HubAnswer

    /** "Fits this phone" where the cap is under any model (F176, D128): the Hub isn't asked and the page says why. */
    data object NothingFits : HubAnswer
}

/**
 * The page with the field focused: the recent searches, browse's own groups under the same labels (so a row in both
 * modes is one item that stays) narrowed to the models whose name has the query, where
 * the suggestions are every catalog model not taken, whatever its fit, then the Hub's rows.
 */
@Immutable
data class SearchView(
    val query: String,
    val recent: List<String>,
    val downloading: List<ModelEntry>,
    val onPhone: List<ModelEntry>,
    val suggested: List<ModelEntry>,
    /** Hugging Face's part: the page fills it from the paged rows ([hubAnswer]); the view model leaves it [Idle]. */
    val hub: HubAnswer = HubAnswer.Idle,
) {
    /** The groups from one list of local matches: a download by its key, a model on the phone, else a suggestion. */
    constructor(query: String, recent: List<String>, local: List<ModelEntry>, hub: HubAnswer = HubAnswer.Idle) : this(
        query,
        recent,
        local.filter { it.key != null && !it.onPhone },
        local.filter { it.onPhone },
        local.filter { it.key == null },
        hub,
    )

    /** Every local match, the groups in their order. */
    val local: List<ModelEntry> get() = downloading + onPhone + suggested
}

/** What a row's repo is doing on this phone, from the repository; any quant of the repo counts. */
sealed interface RowBadge {
    data object OnPhone : RowBadge

    /** Asked for, waiting its turn behind the download that runs (one at a time). */
    data object Queued : RowBadge

    data class Downloading(val fraction: Float) : RowBadge

    data object Paused : RowBadge

    data class Failed(val line: String) : RowBadge
}

/** A row's state word ([badge]) and, while its repo downloads, the ring panel's [download]. */
data class RowState(val badge: RowBadge, val download: DownloadView?)

/** What the models take and what is left: "6.1 GB · 120 GB free". */
data class StorageInfo(val usedBytes: Long, val freeBytes: Long)

/**
 * The Models page (docs/design/models-page.md §2), over local data: the library, the downloads and their records, and
 * the Hub's facts as Room holds them ([HubSearch]). The page is in search mode while the field is focused or holds a
 * query ([searching]); [browse] and [search] are both kept, so switching mode is instant. Typing filters the local
 * models at once; the Hub's stored pages for a query of two characters or more show at once too ([hubPages], Paging 3
 * over Room, models-search design), shaped by the [filters], and the Hub is asked [debounceMillis] after the last
 * keystroke ([fetching] while any page is). A row's fit ([fit]) and what its repo is doing here ([state]) come on
 * flows of their own, so either changing recomposes that row alone.
 *
 * First run is the page's empty state (§2, §7): while it isn't seen the intro shows, and the first Get ([started])
 * makes a chat on that model with [startChat], marks first run seen and announces the chat on [chatOpened]; later Gets
 * stay on the page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModel(
    private val hub: HubSearch,
    private val models: ModelLibrary,
    facts: suspend () -> DeviceFacts,
    private val freeBytes: suspend () -> Long,
    /** Whether the first run has been seen (settings): the intro shows until it has. */
    firstRunSeen: Flow<Boolean>,
    private val markFirstRunSeen: suspend () -> Unit = {},
    /** Makes a chat on a model and returns its id (the first Get on a fresh install opens it); null makes none. */
    private val startChat: (suspend (HfModel) -> Long)? = null,
    /** Whether the intro shows, as the shell already knows it from the settings; null when it doesn't. */
    initialIntro: Boolean? = null,
    initialQuery: String = "",
    private val debounceMillis: Long = 350,
    private val clock: () -> Long,
    private val zone: ZoneId,
    io: CoroutineContext,
    /** Keeps what was typed across process death. */
    savedState: SavedStateHandle,
    /** The search's filters as the settings hold them (models-search design §5). */
    searchFilters: Flow<SearchFilters> = flowOf(SearchFilters()),
    /** Applies a change to the filters as the settings hold them (D128). */
    private val saveSearchFilters: suspend (FiltersChange) -> Unit = {},
    /** The model server mode serves, or null (docs/design/server.md §3): deleting it asks to stop serving first. */
    private val served: () -> HfModel? = { null },
    /** Stops server mode, releasing its model. */
    private val stopServing: suspend () -> Unit = {},
) : ViewModel() {
    // user input: the field gaining or losing focus
    private val focusInput = MutableStateFlow(false)

    // user input: the row whose card was opened
    private val openInput = MutableStateFlow<ModelEntry?>(null)
    private val firstRunSeenNow = firstRunSeen.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var firstChatMade = false
    private val chatEvents = Channel<Long>(Channel.BUFFERED)

    /**
     * The intro over the field while first run isn't seen: from the settings alone, known from the first frame (the
     * shell read them before it chose where to start), so it never lands above the field after the list does.
     */
    val intro: StateFlow<Boolean?> =
        firstRunSeen
            .map<Boolean, Boolean?> { !it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, initialIntro)

    /** The chat the first Get on a fresh install made, once: the page opens it. */
    val chatOpened: Flow<Long> = chatEvents.receiveAsFlow()

    /** The row whose card is open; kept here so a rotation keeps the card. */
    val opened: StateFlow<ModelEntry?> = openInput.asStateFlow()

    /** The phone's facts, read once: every row's fit is figured from them with no further call. */
    private val deviceFacts: StateFlow<DeviceFacts?> =
        flow { emit(facts()) }.flowOn(io).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Null until the first read of the hub's list. */
    private val library: StateFlow<List<LibraryModel>?> =
        models.library.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private val downloading: StateFlow<List<HfModel>?> =
        models.downloading.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /**
     * The catalog's models with the Hub's facts for each repo as Room holds them, read in the same flow: the first
     * value waits for Room's first answer (local, a moment), so stored download counts are in the first frame rather
     * than popping in one row at a time after it. The catalog's own facts until fetched.
     */
    private val catalogEntries: Flow<List<ModelEntry>> =
        combine(
            Catalog.suggested.map { entry ->
                hub
                    .repoFacts(entry.key.repo)
                    .map { it.value }
                    .distinctUntilChanged()
            },
        ) { facts -> Catalog.suggested.mapIndexed { i, entry -> ModelEntry.suggestion(entry, facts[i]) } }

    /**
     * The makers the Hub's cached facts name for the repos on the phone: a library row has only the catalog's maker,
     * and a landed download's record is gone, so the row and its card read the maker from here (models audit S13).
     */
    private val libraryMakers: Flow<Map<String, String>> =
        library
            .filterNotNull()
            .map { list -> list.map { it.key.repo.lowercase() }.distinct() }
            .distinctUntilChanged()
            .flatMapLatest { repos ->
                if (repos.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(repos.map { repo -> hub.repoFacts(repo).map { repo to it.value?.maker } }) { pairs ->
                        pairs.mapNotNull { (repo, maker) -> maker?.let { repo to Names.maker(it) } }.toMap()
                    }
                }
            }.distinctUntilChanged()

    private val local: Flow<Local> =
        combine(
            library.filterNotNull(),
            downloading.filterNotNull(),
            models.records,
            catalogEntries,
            libraryMakers,
        ) { onPhone, downloads, records, catalog, makers ->
            val taken = onPhone.map { it.key.repo.lowercase() }.toSet() + downloads.map { it.repo.lowercase() }
            // a landed download is in the library before its pending key is dropped (two writes): it is listed once,
            // on the phone, so no row has two places and no list key is used twice
            val here = onPhone.map { it.key.toString().lowercase() }.toSet()
            Local(
                downloading =
                    downloads
                        .filter { it.toString().lowercase() !in here }
                        .map { ModelEntry.downloading(it, records[it]) },
                onPhone =
                    onPhone.map { model ->
                        ModelEntry.of(model).let { it.copy(maker = it.maker ?: makers[model.key.repo.lowercase()]) }
                    },
                library = onPhone,
                catalog = catalog.filter { it.repo.lowercase() !in taken },
            )
        }

    /**
     * Null only until the library and the downloads have been read once (Room, local). It does not wait for the
     * phone's facts: the downloads and the models on the phone show at once with their fit
     * boxes reserved, and the suggestions, which are chosen by fit, join under them once the facts are read.
     */
    val browse: StateFlow<Browse?> =
        combine(local, deviceFacts, firstRunSeenNow.filterNotNull()) { now, phone, seen ->
            val anyRunsWell = phone == null || Catalog.suggested.any { Fits.quick(it, phone).label in GOOD }
            Browse(
                intro = !seen,
                downloading = now.downloading,
                onPhone = now.onPhone,
                storage =
                    now.library
                        .takeIf { it.isNotEmpty() }
                        ?.let { StorageInfo(it.sumOf { m -> m.totalBytes }, freeBytes()) },
                suggested = bestFirst(now.catalog, phone).filter { label(it, phone) in GOOD },
                shortOnMemory =
                    if (anyRunsWell) {
                        null
                    } else {
                        catalogWithFacts(now).minByOrNull { it.size?.bytes ?: Long.MAX_VALUE }
                    },
            )
        }.flowOn(io)
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private val find =
        ModelSearch(
            hub,
            viewModelScope,
            savedState,
            initialQuery,
            debounceMillis,
            searchFilters,
            saveSearchFilters,
            deviceFacts,
            local,
        )

    /** A row's fit, size and state, each on a flow of its own; what rows learned is kept for one search. */
    private val rows =
        RowStates(hub, models, deviceFacts, library, downloading, io).also { rows ->
            viewModelScope.launch { find.hubRequest.collect { rows.forget() } }
        }

    val query: StateFlow<String> = find.query

    /** Search mode: the field is focused, or still holds a query after it lost focus. */
    val searching: StateFlow<Boolean> =
        combine(focusInput, find.query) { focused, text -> focused || text.isNotBlank() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), initialQuery.isNotBlank())

    /** The filters the sheet and the page's dot and line show: the defaults until the settings are read. */
    val filters: StateFlow<SearchFilters> = find.filters

    /** Whether "Fits this phone" is chosen on a phone where nothing fits: its cap (F176) is under any model (D128). */
    val nothingFits: StateFlow<Boolean> = find.nothingFits

    /** The search under way: while a page of the query is fetched. The field's line. */
    val fetching: StateFlow<Boolean> = find.fetching

    /** Search mode's body: the recent searches and the local models whose name has the query. */
    val search: StateFlow<SearchView> = find.search

    /** How many rows the query's last first-page refresh stored, as the page draws: [ModelSearch.refreshStored]. */
    fun refreshStored(states: CombinedLoadStates): Int? = find.refreshStored(states)

    /** Hugging Face's rows for the query, in the Hub's order, less those the local part already lists. */
    val hubPages: Flow<PagingData<ModelEntry>> = find.hubPages

    /** Changes the search's filters; they are remembered in the settings, and the Hub is asked again with them. */
    fun setFilters(change: FiltersChange) = find.setFilters(change)

    // user input: the swipe that asks to remove a model
    private val pendingRemoveInput = MutableStateFlow<LibraryModel?>(null)

    /** The model a swipe is asking about; the dialog names it and its size. */
    val pendingRemove: StateFlow<LibraryModel?> = pendingRemoveInput.asStateFlow()

    // user input: a remove of the served model, asking to stop serving first
    private val pendingStopServingInput = MutableStateFlow<LibraryModel?>(null)

    /** The served model a swipe is asking to remove: "Stop serving <model> to delete it?". */
    val pendingStopServing: StateFlow<LibraryModel?> = pendingStopServingInput.asStateFlow()

    // an action's outcome: a remove or cancel that failed, closed by the person
    private val problemInput = MutableStateFlow<String?>(null)

    /** A row's ring and ✕: the download its repo has, paused, resumed or cancelled (asking first past 300 MB). */
    val downloads = RowDownloads(models, viewModelScope, { downloading.value.orEmpty() }) { problemInput.value = it }

    /**
     * A remove or a cancel that couldn't stop the download's work: nothing was deleted, and this line says so, so the
     * tap isn't lost. Closed with its ✕, or replaced by the next one.
     */
    val problem: StateFlow<String?> = problemInput.asStateFlow()

    init {
        // the suggestions' Hub facts (downloads), once per process rather than per visit; a failure keeps what's stored
        viewModelScope.launch {
            Catalog.suggested.forEach { entry -> launch { hub.refreshRepoFactsOnce(entry.key.repo) } }
        }
        // and the models on the phone's, so a library row has its maker without its card being opened first
        // once per repo per process, like the suggestions'
        viewModelScope.launch {
            models.library
                .map { list -> list.map { it.key.repo }.distinctBy { it.lowercase() } }
                .distinctUntilChanged()
                .collect { repos -> repos.forEach { repo -> launch { hub.refreshRepoFactsOnce(repo) } } }
        }
    }

    fun setFocused(focused: Boolean) {
        focusInput.value = focused
    }

    fun setQuery(text: String) = find.setQuery(text)

    /** The keyboard's search key, or a Hub result opened: the query is kept among the recent searches. */
    fun submit() = find.submit()

    /** A recent search tapped: it becomes the query (searched at once from what is stored) and the newest recent. */
    fun pickRecent(text: String) {
        find.setQuery(text)
        focusInput.value = true
        submit()
    }

    /** Back in search mode, or the field cleared and left: browse again. */
    fun leaveSearch() {
        find.setQuery("")
        focusInput.value = false
    }

    fun retry() = find.retry()

    fun open(row: ModelEntry) {
        openInput.value = row
    }

    fun dismiss() {
        openInput.value = null
    }

    /**
     * A card's Get started [key]'s download. The first while first run isn't seen makes a chat on it and opens it
     * (models-page design §2, "First Get on a fresh install"), as first run's own Get did; a later one stays here.
     */
    fun started(key: HfModel) {
        viewModelScope.launch {
            val seen = firstRunSeenNow.filterNotNull().first()
            // the view model's scope runs on the main thread: the check and the mark can't interleave with another Get
            if (seen || firstChatMade) return@launch
            firstChatMade = true
            val id = startChat?.invoke(key)
            markFirstRunSeen()
            if (id != null) chatEvents.send(id)
        }
    }

    /** How [row] runs here, on a flow in [scope] (the row's own): see [RowStates.fit]. */
    fun fit(row: ModelEntry, scope: CoroutineScope): StateFlow<FitView?> = rows.fit(row, scope)

    /** [row]'s size on its line, on a flow in [scope]: see [RowStates.size]. */
    fun size(row: ModelEntry, scope: CoroutineScope): StateFlow<SizeText?> = rows.size(row, scope)

    /** The card sized [row]'s files: its fit stands over the row's guess. */
    fun exactFit(row: ModelEntry, fit: FitView) = rows.exactFit(row, fit)

    /** What [row]'s repo is doing here, on a flow in [scope]: see [RowStates.state]. */
    fun state(row: ModelEntry, scope: CoroutineScope): StateFlow<RowState?> = rows.state(row, scope)

    /** When [entry], a model on the phone, was last used: "Used today", "3 days ago". */
    fun lastUsed(entry: ModelEntry): String = Format.usedAgo(entry.lastUsed, clock(), zone)

    /** A swipe on [model]: the remove question, or, for the model being served, the stop-serving one. */
    fun askRemove(model: LibraryModel) {
        if (served() == model.key) pendingStopServingInput.value = model else pendingRemoveInput.value = model
    }

    fun dismissRemove() {
        pendingRemoveInput.value = null
    }

    fun dismissStopServing() {
        pendingStopServingInput.value = null
    }

    /**
     * The stop-serving question answered: serving ends, then the model is removed. A delete while it serves would wait
     * for serving to end, with the page's remove and every request on hold meanwhile.
     */
    fun confirmStopServing() {
        val model = pendingStopServingInput.value ?: return
        pendingStopServingInput.value = null
        viewModelScope.launch {
            stopServing()
            remove(model)
        }
    }

    fun dismissProblem() {
        problemInput.value = null
    }

    /** The remove question answered; serving that began since it was asked asks its own question first. */
    fun confirmRemove() {
        val model = pendingRemoveInput.value ?: return
        pendingRemoveInput.value = null
        if (served() == model.key) {
            pendingStopServingInput.value = model
            return
        }
        viewModelScope.launch { remove(model) }
    }

    private suspend fun remove(model: LibraryModel) {
        if (!models.remove(model.key)) problemInput.value = "Couldn't remove ${model.name}. Try again."
    }

    companion object {
        const val MIN_QUERY = 2

        private const val STOP_TIMEOUT = 5_000L
    }
}

internal val GOOD = setOf(FitLabel.RunsGreat, FitLabel.RunsWell)

/** A catalog row's fit label on [phone]; null before the phone's facts are read. */
internal fun label(entry: ModelEntry, phone: DeviceFacts?): FitLabel? =
    entry.catalog?.let { c -> phone?.let { Fits.quick(c, it).label } }

/**
 * The catalog's rows best first on [phone] (the catalog's order within a label), as browse lists its suggestions and
 * search lists its own: one order, so a row in both keeps its place as the mode changes.
 */
internal fun bestFirst(catalog: List<ModelEntry>, phone: DeviceFacts?): List<ModelEntry> =
    catalog.sortedBy { label(it, phone)?.ordinal ?: Int.MAX_VALUE }

/** The catalog's every model, the taken ones too: the smallest is named even when it is already here. */
private fun catalogWithFacts(now: Local): List<ModelEntry> {
    val byRepo = now.catalog.associateBy { it.repo }
    return Catalog.suggested.map { byRepo[it.key.repo] ?: ModelEntry.of(it) }
}

/** Everything known locally: the downloads with their records, the models on the phone, the catalog's not taken. */
internal class Local(
    val downloading: List<ModelEntry>,
    val onPhone: List<ModelEntry>,
    val library: List<LibraryModel>,
    val catalog: List<ModelEntry>,
)
