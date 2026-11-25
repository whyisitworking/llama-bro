package com.suhel.llamabro.demo.ui.models

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.designsystem.components.DownloadView
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlocks
import com.suhel.llamabro.demo.models.Cached
import com.suhel.llamabro.demo.models.Capability
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.DownloadRecord
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.HubCard
import com.suhel.llamabro.demo.models.HubLinks
import com.suhel.llamabro.demo.models.LinkTarget
import com.suhel.llamabro.demo.models.ModelLibrary
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.models.Sizes
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

enum class SheetMode { Basic, Advanced }

/**
 * The card's header: the name, "by [maker]", the [repo] on its own line, what the model can do, and how it runs here
 * ([fit], spelled out only here; null until the phone's facts and a file are known).
 */
@Immutable
data class CardHeader(
    val name: String,
    val maker: String?,
    val repo: String,
    val capabilities: List<Capability>,
    val fit: FitView?,
    /** No maker yet, but one may still arrive: the "by …" line keeps its height. */
    val makerReserved: Boolean = false,
)

/**
 * The one state line: "Not on this phone", "On this phone · Q4_K_M · 3.4 GB"; null while a download runs, which the
 * pinned ring panel alone describes.
 */
@Immutable
data class CardState(val text: String?)

/**
 * One of Basic's three sizes: titled by its [tier], with the [quant] and the whole size ([bytes], file and image
 * reader) under the title. [missing] when the upload has no file for it ("Not in this upload", disabled); [reserved]
 * while the listing isn't known, so the choice holds its final shape. A tier [onPhone] can't be got again.
 */
@Immutable
data class TierView(
    val tier: SizeTier,
    val quant: String?,
    val bytes: Long?,
    val fit: FitView?,
    val missing: Boolean = false,
    val onPhone: Boolean = false,
    /** Why a size isn't known yet, said in its place ("Needs the network"); null holds it blank. */
    val note: String? = null,
) {
    val reserved: Boolean get() = quant == null && !missing
}

/** One quant in Advanced: its whole size, how it runs here, and "Recommended" or "On phone" under its name. */
@Immutable
data class QuantView(
    val quant: String,
    val bytes: Long,
    val fit: FitView?,
    val recommended: Boolean,
    val onPhone: Boolean,
)

/** Why the choice has no sizes to offer, said in its place. */
@Immutable
sealed interface ChoiceNotice {
    data class Failed(val retryable: Boolean) : ChoiceNotice

    /** The catalog's own size is shown; the listing of the others couldn't be read. */
    data class OtherSizes(val retryable: Boolean) : ChoiceNotice

    data object NoFile : ChoiceNotice
}

/** The size choice: the [mode], Basic's [tiers], Advanced's [quants], the quant [selected], the image reader's size. */
@Immutable
data class ChoiceView(
    val mode: SheetMode,
    val tiers: List<TierView>,
    val quants: List<QuantView>,
    val selected: String?,
    val readerBytes: Long?,
    val notice: ChoiceNotice? = null,
    /**
     * The quant already on the phone when the choice was opened by "Another size": the choice says so in one muted
     * line, which is also why that size isn't the one selected, beside a Done that closes the choice.
     */
    val alreadyHere: String? = null,
)

/** The model's page under the card's own parts; nothing while it isn't known. */
@Immutable
sealed interface PageView {
    data object Unknown : PageView

    data class Markdown(val blocks: MarkdownBlocks) : PageView

    data object None : PageView

    data class Failed(val retryable: Boolean) : PageView
}

/** The pinned foot (docs/design/models-page.md §4.5), 64 dp in every one of these. */
@Immutable
sealed interface CardFoot {
    /**
     * "Get Q4_K_M · 3.4 GB · about 8 min", the same formula in Basic and Advanced: [lead] names the file and its size,
     * [time] how long it takes, a line of its own at large font.
     */
    data class Get(val lead: String, val enabled: Boolean, val time: String? = null) : CardFoot {
        val label: String get() = listOfNotNull(lead, time).joinToString(" · ")
    }

    /** The ring panel, the same words as the row's. */
    data class Download(val view: DownloadView) : CardFoot

    /** "Start a chat", and "Another size" when the upload has a size not on the phone ([another]). */
    data class OnPhone(val another: Boolean) : CardFoot

    data object CantChat : CardFoot
}

/** The whole card, drawn final from local data: each part sits in the same place in every state. */
@Immutable
data class CardView(
    val header: CardHeader,
    val state: CardState,
    val choice: ChoiceView?,
    val page: PageView,
    val foot: CardFoot,
)

/** What a tap on Cancel did: it asked first ([Confirm], with the dialog's line), or it cancelled. */
sealed interface CancelAsk {
    data class Confirm(val text: String) : CancelAsk

    data object Done : CancelAsk
}

/**
 * A model's card (docs/design/models-page.md §4). Everything shown is [view], one `combine` of local flows: the
 * library and the queue, the download's state and record, and the Hub's cached files, page and facts; the person's
 * inputs are the mode, the quant picked and "Another size". [view] is null until each of those local reads has
 * answered once (Room only, never the network), so the card's first drawn frame is final from the cache; the reserved
 * shapes (three blank sizes, the maker's line) show only when the cache truly holds nothing (D118). It asks the Hub
 * to refresh the files, the page and the facts once. [CardViews] derives the view. [onExactFit] hears the Recommended
 * file's fit once: the list's guess gives way to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelSheetViewModel(
    val row: ModelEntry,
    private val hub: HubCard,
    private val models: ModelLibrary,
    private val facts: suspend () -> DeviceFacts,
    private val onExactFit: (FitView) -> Unit = {},
    /** The size the card starts on instead of Recommended ("Smaller size", from a chat on this model). */
    startTier: SizeTier? = null,
    /** Where a page is parsed: off the main thread. */
    private val work: CoroutineContext = Dispatchers.Default,
    /** Whether Get asks for the notification permission first (the phone tests turn it off). */
    val asksForNotifications: Boolean = true,
    /** What a link on the page opens: a model's card or the browser (`ModelDiscovery.related`, D156). */
    private val related: suspend (String) -> LinkTarget = { LinkTarget.Browser(it) },
) : ViewModel() {
    private val repo = row.repo.lowercase()
    private val cards = CardViews(row, startTier)

    // user input: Basic or Advanced
    private val modeInput = MutableStateFlow(SheetMode.Basic)

    // user input: the quant picked
    private val pickInput = MutableStateFlow<String?>(null)

    // a card opened on a size (from a chat on this very model) is there to get another one
    // user input: "Another size" opened, Done closed it
    private val choosingInput = MutableStateFlow(startTier != null)

    // an action's outcome: a cancel that failed, closed by the person
    private val problemInput = MutableStateFlow<String?>(null)

    // user input: the Cancel question asked about one download, answered by Keep or Cancel download
    private val askingInput = MutableStateFlow<CancelQuestion?>(null)

    // an action's progress: the repo a tapped link names, while the Hub is asked what it opens
    private val openingInput = MutableStateFlow<String?>(null)

    // a link's answer, acted on once by the route: a card pushed or the browser opened, after a rotation too
    private val openedTargets = Channel<LinkTarget>(Channel.BUFFERED)
    private var lookup: Job? = null
    private var taps = 0

    // the card is sliding away (a link's card or Back): it takes no more taps and opens nothing more
    private var leaving = false

    /**
     * This repo here. The download followed is the row's own file when it is queued (two quants of one repo may be),
     * else the repo's first.
     */
    private val here: Flow<RepoHere> =
        combine(models.library, models.downloading) { onPhone, downloads ->
            val installed = onPhone.filter { it.key.repo.lowercase() == repo }
            RepoHere(
                downloading =
                    downloads.firstOrNull { it == row.key } ?: downloads.firstOrNull { it.repo.lowercase() == repo },
                onPhone = installed.firstOrNull { it.key == row.key } ?: installed.firstOrNull(),
                quants = installed.mapNotNull { it.key.tag?.uppercase() }.toSet(),
                installed = installed,
            )
        }.distinctUntilChanged()

    private val download: Flow<RepoDownload?> =
        here
            .map { it.downloading }
            .distinctUntilChanged()
            .flatMapLatest { key -> if (key == null) flowOf(null) else downloadOf(key) }

    private val local: StateFlow<CardInputs?> =
        combine(here, download, hub.files(repo), hub.page(repo), hub.repoFacts(repo), ::CardInputs)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The page, parsed apart from the other inputs and off the main thread, so a long README never holds them up;
     * parsed again only when what is stored or its failure changes, not while a refresh merely runs.
     */
    private val pageView: Flow<PageView> =
        hub
            .page(repo)
            .map { Cached(it.value, false, it.failure.takeIf { _ -> !it.refreshing }) }
            .distinctUntilChanged()
            .map(cards::page)
            .flowOn(work)

    /**
     * The phone's facts: local, read once the app starts, so the card waits for them rather than drawing its fit line
     * blank and filling it a moment later.
     */
    private val device: Flow<DeviceFacts?> = flow<DeviceFacts?> { emit(facts()) }

    private val choices: Flow<CardChoices> = combine(modeInput, pickInput, choosingInput, device, ::CardChoices)

    /** The card, null until every local read has answered once; the route shows the sheet from then on. */
    val view: StateFlow<CardView?> =
        combine(local.filterNotNull(), pageView, choices, cards::of)
            // a maker's line held once the card is drawn stays: its going would move everything under it
            .runningReduce { shown, next ->
                if (shown.header.makerReserved && next.header.maker == null) {
                    next.copy(header = next.header.copy(makerReserved = true))
                } else {
                    next
                }
            }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A cancel that couldn't stop the download's work: nothing was deleted, and this line says so. */
    val problem: StateFlow<String?> = problemInput.asStateFlow()

    /** The repo a tapped link names while its card is looked up, for the notice "Opening <repo>…"; else null. */
    val opening: StateFlow<String?> = openingInput.asStateFlow()

    /** Where each tapped link goes, once: the latest tap's alone, an earlier lookup still running being cancelled. */
    val opened: Flow<LinkTarget> = openedTargets.receiveAsFlow()

    /** The question a Cancel past [CANCEL_ASKS_BYTES] asks ("0.9 GB has arrived. Cancelling deletes it."). */
    val asking: StateFlow<String?> =
        askingInput.map { it?.text }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { hub.refreshFiles(row.repo) }
        viewModelScope.launch { hub.refreshPage(row.repo) }
        viewModelScope.launch { hub.refreshRepoFacts(row.repo) }
        // the Recommended file's fit, once it is known, stands over the guess the list showed
        viewModelScope.launch {
            val exact =
                combine(local.filterNotNull(), device) { local, device ->
                    val recommended =
                        local.files.value
                            ?.let(Sizes::tiers)
                            ?.get(SizeTier.Recommended)
                    if (recommended == null || device == null) null else cards.fit(recommended, device)
                }.filterNotNull().first()
            onExactFit(exact)
        }
    }

    fun setMode(mode: SheetMode) {
        modeInput.value = mode
    }

    fun select(quant: String) {
        pickInput.value = quant
    }

    /** The card's one Try again (models audit S20): asks again for each of the files, page and facts that failed. */
    fun retry() {
        val now = local.value ?: return
        if (now.files.failure != null) viewModelScope.launch { hub.refreshFiles(row.repo) }
        if (now.page.failure != null) viewModelScope.launch { hub.refreshPage(row.repo) }
        if (now.facts.failure != null) viewModelScope.launch { hub.refreshRepoFacts(row.repo) }
    }

    fun getAnotherSize() {
        choosingInput.value = true
    }

    /** "Done" in the choice "Another size" opened: back to the foot's "Start a chat". */
    fun doneChoosing() {
        choosingInput.value = false
    }

    /**
     * Starts the download of the selected file, recording the name, maker, whole size and model file's size (the fit's
     * speed) it was chosen with; returns
     * its key, or null when the foot offers no Get.
     */
    fun get(): HfModel? {
        val now = view.value ?: return null
        val foot = now.foot
        if (foot !is CardFoot.Get || !foot.enabled) return null
        val target = cards.target(checkNotNull(local.value), now.choice) ?: return null
        val key = HfModel.parse("${row.repo}:${target.quant}")
        models.get(key, DownloadRecord(key, row.name, now.header.maker, target.bytes, target.modelBytes))
        return key
    }

    /** The file of this repo on the phone that "Start a chat" opens; null when none is. */
    fun onPhone(): HfModel? =
        local.value
            ?.here
            ?.onPhone
            ?.key
            ?: local.value
                ?.download
                ?.takeIf { it.status == DownloadStatus.Complete }
                ?.key

    /** The ring's control: pauses a download, resumes a paused one, tries a stopped one again. */
    fun toggle() {
        val download = local.value?.download ?: return
        when (download.status) {
            is DownloadStatus.Downloading -> models.pause(download.key)
            is DownloadStatus.Partial, is DownloadStatus.Failed -> models.get(download.key)
            DownloadStatus.Absent, DownloadStatus.Complete -> Unit
        }
    }

    /** Cancels the download, or asks first when more than [CANCEL_ASKS_BYTES] have arrived ([asking]). */
    fun cancel(): CancelAsk {
        val download = local.value?.download ?: return CancelAsk.Done
        val text = DownloadViews.cancelQuestion(download.status, download.record?.totalBytes ?: row.size?.bytes)
        if (DownloadViews.arrivedOf(download.status) > CANCEL_ASKS_BYTES && text != null) {
            askingInput.value = CancelQuestion(download.key, text)
            return CancelAsk.Confirm(text)
        }
        cancelNow(download.key)
        return CancelAsk.Done
    }

    /**
     * Cancels the download the question named, by its key: if that one has since landed or gone while the dialog was
     * open, there is nothing to cancel, even when another quant of the repo has started.
     */
    fun confirmCancel() {
        val asked = askingInput.value ?: return
        askingInput.value = null
        when (models.state(asked.key).value) {
            is DownloadStatus.Downloading, is DownloadStatus.Partial, is DownloadStatus.Failed -> cancelNow(asked.key)
            DownloadStatus.Absent, DownloadStatus.Complete -> Unit
        }
    }

    fun keepDownload() {
        askingInput.value = null
    }

    fun dismissProblem() {
        problemInput.value = null
    }

    /**
     * A link on the page was tapped: asks what [url] opens ([opened]). A newer tap cancels the older lookup, so a slow
     * answer never lands over the card the person asked for since.
     */
    fun openLink(url: String) {
        if (leaving) return
        lookup?.cancel()
        val tap = ++taps
        openingInput.value = HubLinks.repoOf(url)
        lookup =
            viewModelScope.launch {
                try {
                    val target = related(url).awayFromHere(url)
                    if (!leaving) openedTargets.send(target)
                } finally {
                    // a cancelled lookup leaves the notice to the tap that replaced it
                    if (tap == taps) openingInput.value = null
                }
            }
    }

    // a link that opens this very card (its original, say) would push it again: its page goes to the browser instead
    private fun LinkTarget.awayFromHere(url: String): LinkTarget =
        if (this is LinkTarget.Card && model.repo.lowercase() == repo) LinkTarget.Browser(url) else this

    /**
     * The card started to slide away for a link's card or Back: the lookup still running is cancelled and an answer not
     * yet acted on is dropped, so nothing opens later, when the person comes back to this card; taps are ignored until
     * [stay].
     */
    fun leave() {
        leaving = true
        lookup?.cancel()
        lookup = null
        openingInput.value = null
        while (openedTargets.tryReceive().isSuccess) Unit
    }

    /** The slide away was caught and the card is shown again, or it is shown again by Back: its links work again. */
    fun stay() {
        leaving = false
    }

    private fun cancelNow(key: HfModel) {
        val cancelled = models.cancel(key)
        viewModelScope.launch {
            if (!cancelled.await()) problemInput.value = "Couldn't cancel the download of ${row.name}. Try again."
        }
    }

    private fun downloadOf(key: HfModel): Flow<RepoDownload> =
        combine(models.state(key), models.records.map { it[key] }.distinctUntilChanged()) { status, record ->
            status to record
        }.flatMapLatest { (status, record) ->
            val ahead = if (status == DownloadStatus.Absent) models.aheadOf(key) else flowOf(null)
            ahead.map { RepoDownload(key, status, record, it) }
        }

    private companion object {
        /** Past this much arrived, Cancel asks first (models-page design §4.5). */
        const val CANCEL_ASKS_BYTES = 300_000_000L
    }
}
