package com.suhel.llamabro.demo.ui.models

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import com.suhel.llamabro.demo.chat.Limits
import com.suhel.llamabro.demo.designsystem.components.DownloadView
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.DownloadRecord
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FiltersChange
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Fits
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.Guess
import com.suhel.llamabro.demo.models.HubQuery
import com.suhel.llamabro.demo.models.HubSearch
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelLibrary
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SearchFilters
import com.suhel.llamabro.demo.models.SizeFilter
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.models.Sizes
import com.suhel.llamabro.hub.HfModel
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
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
) : ViewModel() {
    // user input: what is typed, kept across process death
    private val queryInput = savedState.getMutableStateFlow(QUERY_KEY, initialQuery)

    // user input: the field gaining or losing focus
    private val focusInput = MutableStateFlow(false)

    // user input: each Try again, a counter so the same query is asked again
    private val retryInput = MutableStateFlow(0)

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

    // external event: the card reports the fit it measured
    private val exactFits = MutableStateFlow<Map<String, FitView>>(emptyMap())

    val query: StateFlow<String> = queryInput.asStateFlow()

    /** Search mode: the field is focused, or still holds a query after it lost focus. */
    val searching: StateFlow<Boolean> =
        combine(focusInput, queryInput) { focused, text -> focused || text.isNotBlank() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), initialQuery.isNotBlank())

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

    /** The filters as the settings hold them; null until they are read, so nothing is asked under the defaults. */
    private val savedFilters: StateFlow<SearchFilters?> =
        searchFilters.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The filters the sheet and the page's dot and line show: the defaults until the settings are read. */
    val filters: StateFlow<SearchFilters> =
        savedFilters
            .filterNotNull()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SearchFilters())

    /** Whether "Fits this phone" is chosen on a phone where nothing fits: its cap (F176) is under any model (D128). */
    val nothingFits: StateFlow<Boolean> =
        combine(savedFilters, deviceFacts) { chosen, phone -> fitsNothing(chosen, phone) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), false)

    /**
     * What the Hub is asked for the typed query, or null under two characters, before the saved filters and the
     * phone's facts (the "Fits this phone" cap) are read, and when nothing can fit; a retry makes it a new request
     * even for the same query.
     */
    private val hubRequest: StateFlow<HubRequest?> =
        combine(queryInput.map { it.trim() }.distinctUntilChanged(), savedFilters, deviceFacts, retryInput) {
            text,
            chosen,
            phone,
            attempt,
            ->
            when {
                text.length < MIN_QUERY || chosen == null || phone == null -> null
                fitsNothing(chosen, phone) -> null
                else -> HubRequest(chosen.query(text, phone), attempt)
            }
        }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

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
            }.cachedIn(viewModelScope)

    /** The search under way: while a page of the query is fetched. The field's line. */
    val fetching: StateFlow<Boolean> =
        hubRequest
            .flatMapLatest { request -> request?.let { hub.fetching(it.query) } ?: flowOf(false) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), false)

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
                viewModelScope,
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
        viewModelScope.launch { saveSearchFilters(change) }
    }

    // user input: the swipe that asks to remove a model
    private val pendingRemoveInput = MutableStateFlow<LibraryModel?>(null)

    /** The model a swipe is asking about; the dialog names it and its size. */
    val pendingRemove: StateFlow<LibraryModel?> = pendingRemoveInput.asStateFlow()

    // an action's outcome: a remove or cancel that failed, closed by the person
    private val problemInput = MutableStateFlow<String?>(null)

    /** A row's ring and ✕: the download its repo has, paused, resumed or cancelled (asking first past 300 MB). */
    val downloads = RowDownloads(models, viewModelScope, { downloading.value.orEmpty() }) { problemInput.value = it }

    /**
     * A remove or a cancel that couldn't stop the download's work: nothing was deleted, and this line says so, so the
     * tap isn't lost. Closed with its ✕, or replaced by the next one.
     */
    val problem: StateFlow<String?> = problemInput.asStateFlow()

    private val fits = ConcurrentHashMap<String, StateFlow<FitView?>>()
    private val rowStates = ConcurrentHashMap<String, StateFlow<RowState?>>()
    private val sizes = ConcurrentHashMap<String, StateFlow<SizeText?>>()

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

    fun setQuery(text: String) {
        queryInput.value = text
    }

    /** The keyboard's search key, or a Hub result opened: the query is kept among the recent searches. */
    fun submit() {
        val text = queryInput.value.trim()
        if (text.isNotEmpty()) viewModelScope.launch { hub.rememberSearch(text) }
    }

    /** A recent search tapped: it becomes the query (searched at once from what is stored) and the newest recent. */
    fun pickRecent(text: String) {
        queryInput.value = text
        focusInput.value = true
        submit()
    }

    /** Back in search mode, or the field cleared and left: browse again. */
    fun leaveSearch() {
        queryInput.value = ""
        focusInput.value = false
    }

    fun retry() {
        retryInput.update { it + 1 }
    }

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

    /**
     * How [row] runs here, with no call of its own: a catalog model from the sizes it ships, any other guessed from
     * the Hub's facts the search brought (F175); none when the Hub gave no parameter count. An [exactFit] replaces a
     * guess. Kept per file ([fileOf]), never per model: a suggestion, its download in another quant and the file on
     * the phone each have their own fit.
     */
    fun fit(row: ModelEntry): StateFlow<FitView?> =
        fits.getOrPut(fileOf(row)) {
            val file = fileOf(row)
            val exact = exactFits.map { it[file] }.distinctUntilChanged()
            combine(deviceFacts, exact) { phone, known -> known ?: phone?.let { guess(row, it) } }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT),
                    exactFits.value[file] ?: deviceFacts.value?.let { guess(row, it) },
                )
        }

    /**
     * [row]'s size on its line: its own (the catalog's, the file on the phone, a download's record), and for a row
     * whose size is only guessed, the exact size once the card has cached the repo's files: the Recommended file the
     * card would offer, with its image reader (docs/design/models-page.md §7). One stored read per row, no request.
     */
    fun size(row: ModelEntry): StateFlow<SizeText?> =
        sizes.getOrPut(row.key?.toString() ?: row.repo.lowercase()) {
            val own = row.size
            if (own is SizeText.Exact || row.key != null) {
                // a constant: a row's own size never changes
                MutableStateFlow(own).asStateFlow()
            } else {
                hub
                    .files(row.repo)
                    .map { cached -> cached.value?.let(::recommendedSize) ?: own }
                    .distinctUntilChanged()
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), own)
            }
        }

    /**
     * The card sized [row]'s files: the fit of the file it recommends stands over the guess of a row with no file of
     * its own (a Hub result, whose size is that file's); a row with a file already has its exact fit.
     */
    fun exactFit(row: ModelEntry, fit: FitView) {
        if (row.key == null && row.catalog == null) exactFits.update { it + (fileOf(row) to fit) }
    }

    /**
     * What [row]'s repo is doing here, from the repository alone, so a download started anywhere (another screen, an
     * earlier visit, the notification) shows: under way, waiting, paused or failed while any quant of it downloads,
     * with the ring panel's view, else on the phone when any quant is.
     */
    fun state(row: ModelEntry): StateFlow<RowState?> {
        val repo = row.repo.lowercase()
        return rowStates.getOrPut(repo) {
            val where = { onPhone: List<LibraryModel>?, downloads: List<HfModel>? ->
                val installed = onPhone.orEmpty().any { it.key.repo.lowercase() == repo }
                downloads.orEmpty().firstOrNull { it.repo.lowercase() == repo } to installed
            }
            combine(library, downloading) { onPhone, downloads -> where(onPhone, downloads) }
                .distinctUntilChanged()
                .flatMapLatest { (key, installed) ->
                    when {
                        key != null -> downloadState(key)
                        installed -> flowOf(RowState(RowBadge.OnPhone, null))
                        else -> flowOf(null)
                    }
                }.distinctUntilChanged()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT),
                    firstState(row, where(library.value, downloading.value)),
                )
        }
    }

    /**
     * The row's state as the first frame draws it, from what is already read: a download's row has its panel from the
     * first frame (§3, "heights never change within a mode"), its total from the row's own size, which is the record's.
     */
    private fun firstState(row: ModelEntry, where: Pair<HfModel?, Boolean>): RowState? {
        val (key, installed) = where
        return when {
            key != null -> {
                val status = models.state(key).value
                RowState(badgeOf(status), DownloadViews.of(key, status, row.size?.bytes.takeIf { row.key == key }))
            }

            installed -> {
                RowState(RowBadge.OnPhone, null)
            }

            else -> {
                null
            }
        }
    }

    private fun downloadState(key: HfModel): Flow<RowState> =
        combine(models.state(key), models.records.map { it[key] }.distinctUntilChanged()) { status, record ->
            status to record
        }.flatMapLatest { (status, record) ->
            val ahead = if (status == DownloadStatus.Absent) runningBesides(key) else flowOf(null)
            ahead.map { name -> RowState(badgeOf(status), DownloadViews.of(key, status, record?.totalBytes, name)) }
        }

    /** The name of the download that runs while [key] waits its turn, if one does. */
    private fun runningBesides(key: HfModel): Flow<String?> =
        combine(downloading.filterNotNull(), models.records) { keys, records -> keys.filter { it != key } to records }
            .flatMapLatest { (others, records) ->
                if (others.isEmpty()) {
                    flowOf(null)
                } else {
                    combine(others.map { other -> models.state(other).map { other.takeIf { _ -> it.running() } } }) {
                        it.firstNotNullOfOrNull { running -> running }?.let { k -> nameOf(k, records[k]) }
                    }
                }
            }.distinctUntilChanged()

    /** When [entry], a model on the phone, was last used: "Used today", "3 days ago". */
    fun lastUsed(entry: ModelEntry): String = Format.usedAgo(entry.lastUsed, clock(), zone)

    fun askRemove(model: LibraryModel) {
        pendingRemoveInput.value = model
    }

    fun dismissRemove() {
        pendingRemoveInput.value = null
    }

    fun dismissProblem() {
        problemInput.value = null
    }

    fun confirmRemove() {
        val model = pendingRemoveInput.value ?: return
        pendingRemoveInput.value = null
        viewModelScope.launch {
            if (!models.remove(model.key)) problemInput.value = "Couldn't remove ${model.name}. Try again."
        }
    }

    companion object {
        const val MIN_QUERY = 2

        private const val QUERY_KEY = "query"
        private const val STOP_TIMEOUT = 5_000L
    }
}

/**
 * How [row] runs on [phone] before the card measures it, by the one formula every row, the switcher and the card use
 * ([Fits.quick]: the whole size for memory, the model file for speed, D121): from its size, which is
 * the file on the phone's, the catalog's, a download's record or the guess from the Hub's facts (F175).
 */
private fun guess(row: ModelEntry, phone: DeviceFacts): FitView? =
    row.size?.takeIf { it.bytes > 0 }?.let {
        Fits.quick(it.bytes, Fits.speedBytes(it.modelBytes, row.repo, row.parameters, row.activeParameters), phone)
    }

/** The file a row stands for, its fit's key: its own, the catalog's for a suggestion, else its repo's Recommended. */
private fun fileOf(row: ModelEntry): String = ModelEntry.listKey(row)

private val GOOD = setOf(FitLabel.RunsGreat, FitLabel.RunsWell)

/** A catalog row's fit label on [phone]; null before the phone's facts are read. */
private fun label(entry: ModelEntry, phone: DeviceFacts?): FitLabel? =
    entry.catalog?.let { c -> phone?.let { Fits.quick(c, it).label } }

/**
 * The catalog's rows best first on [phone] (the catalog's order within a label), as browse lists its suggestions and
 * search lists its own: one order, so a row in both keeps its place as the mode changes.
 */
private fun bestFirst(catalog: List<ModelEntry>, phone: DeviceFacts?): List<ModelEntry> =
    catalog.sortedBy { label(it, phone)?.ordinal ?: Int.MAX_VALUE }

/** The catalog's every model, the taken ones too: the smallest is named even when it is already here. */
private fun catalogWithFacts(now: Local): List<ModelEntry> {
    val byRepo = now.catalog.associateBy { it.repo }
    return Catalog.suggested.map { byRepo[it.key.repo] ?: ModelEntry.of(it) }
}

/** One ask of the Hub: the query with its filters, and the retry it belongs to. */
private data class HubRequest(val query: HubQuery, val attempt: Int)

/** What the local part lists, to leave out of the Hub's rows: repos, every upload's, and group keys. */
private data class Listed(val repos: Set<String>, val keys: Set<String>) {
    fun has(row: ModelEntry): Boolean =
        row.repo.lowercase() in repos || row.groupKey in keys || row.uploads.any { it.lowercase() in repos }

    companion object {
        fun of(local: List<ModelEntry>) =
            Listed(local.map { it.repo.lowercase() }.toSet(), local.map { it.groupKey }.toSet())
    }
}

private fun DownloadStatus.running() = this is DownloadStatus.Downloading

private fun nameOf(key: HfModel, record: DownloadRecord?) = ModelEntry.downloading(key, record).name

private fun badgeOf(status: DownloadStatus): RowBadge =
    when (status) {
        DownloadStatus.Absent -> RowBadge.Queued
        is DownloadStatus.Downloading -> RowBadge.Downloading(status.fraction())
        is DownloadStatus.Partial -> RowBadge.Paused
        is DownloadStatus.Failed -> RowBadge.Failed(Limits.forDownload(status).line)
        DownloadStatus.Complete -> RowBadge.OnPhone
    }

/** Everything known locally: the downloads with their records, the models on the phone, the catalog's not taken. */
private class Local(
    val downloading: List<ModelEntry>,
    val onPhone: List<ModelEntry>,
    val library: List<LibraryModel>,
    val catalog: List<ModelEntry>,
)

private fun recommendedSize(files: List<RepoFile>): SizeText? =
    Sizes.tiers(files)[SizeTier.Recommended]?.let { SizeText.Exact(it.bytes + it.encoderBytes, it.bytes) }
