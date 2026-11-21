package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.demo.chat.Markdown
import com.suhel.llamabro.demo.designsystem.components.DownloadStage
import com.suhel.llamabro.demo.designsystem.components.DownloadView
import com.suhel.llamabro.demo.models.Cached
import com.suhel.llamabro.demo.models.Capability
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.DownloadRecord
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Fits
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.demo.models.PageFacts
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.models.Sizes
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException

/**
 * This repo here: the download under way, if any; the first quant on the phone; every quant on the phone, and the
 * files on the phone ([installed]), whose own sizes a size on the phone shows.
 */
internal data class RepoHere(
    val downloading: HfModel?,
    val onPhone: LibraryModel?,
    val quants: Set<String>,
    val installed: List<LibraryModel> = listOfNotNull(onPhone),
) {
    /** The file of [quant] on the phone, if it is. */
    fun file(quant: String?): LibraryModel? = installed.firstOrNull { it.key.tag.equals(quant, ignoreCase = true) }
}

/** A download of this repo: its [status], its [record] (once read), the name of the one [ahead] while it waits. */
internal data class RepoDownload(
    val key: HfModel,
    val status: DownloadStatus,
    val record: DownloadRecord?,
    val ahead: String?,
)

/**
 * What the card reads from the phone, each read from Room once before the card shows: the repo here, its download,
 * and the Hub's cached files, page and facts.
 */
internal data class CardInputs(
    val here: RepoHere,
    val download: RepoDownload?,
    val files: Cached<List<RepoFile>>,
    val page: Cached<PageFacts> = Cached(null, false, null),
    val facts: Cached<SearchModel> = Cached(null, false, null),
)

/** The person's part: the mode, the quant picked, "Another size"; and the phone's facts once read. */
internal data class CardChoices(
    val mode: SheetMode = SheetMode.Basic,
    val pick: String? = null,
    val choosing: Boolean = false,
    val device: DeviceFacts? = null,
)

/** What Get would fetch: the quant and the whole download's size. */
internal data class CardTarget(val quant: String, val bytes: Long, val modelBytes: Long = bytes)

/**
 * The card's view from its inputs, a pure function (docs/design/models-page.md §4): the header, the state line, the
 * size choice, the page and the pinned foot. Labels name the quant, never a tier (§1); every size is the whole
 * download, the file and its image reader (§7); a file's fit counts every parameter for memory and only the active
 * ones for speed, as the row's guess does (F175).
 */
internal class CardViews(private val row: ModelEntry, private val startTier: SizeTier?) {
    /** Where the repo stands, as the card branches on it. */
    private sealed interface Situation {
        data object CantChat : Situation

        data class Getting(val download: RepoDownload) : Situation

        data class OnPhone(val key: HfModel, val bytes: Long?) : Situation

        data object Absent : Situation
    }

    /** The card from its [inputs], its [page] (parsed apart, off the main thread) and the person's [choices]. */
    fun of(inputs: CardInputs, page: PageView, choices: CardChoices): CardView {
        val situation = situationOf(inputs)
        val choosing = choices.choosing && situation is Situation.OnPhone
        val choice =
            when {
                situation == Situation.Absent -> choiceOf(inputs, choices)
                choosing -> choiceOf(inputs, choices).copy(alreadyHere = (situation as Situation.OnPhone).key.tag)
                else -> null
            }
        val (state, foot) =
            when (situation) {
                Situation.CantChat -> cantChatLine() to CardFoot.CantChat
                is Situation.Getting -> getting(situation.download)
                is Situation.OnPhone -> onPhoneLine(situation) to onPhoneFoot(inputs, choice, choosing)
                Situation.Absent -> "Not on this phone" to getFoot(inputs, choice)
            }
        return CardView(headerOf(inputs, choices, situation), CardState(state), choice, page, foot)
    }

    /** The file Get fetches: the selection, else the catalog's own file while the listing isn't known. */
    fun target(inputs: CardInputs, choice: ChoiceView?): CardTarget? {
        val files = inputs.files.value ?: return catalogTarget(inputs)
        val file = files.firstOrNull { it.quant == choice?.selected } ?: return null
        return CardTarget(file.quant, total(file), file.bytes)
    }

    /**
     * A cached page as the card shows it: the leading heading that repeats the name dropped (models audit S5). A page
     * that couldn't be read is [PageView.Failed], which the card leaves unsaid: the choice's one notice retries it
     * (S20).
     */
    fun page(cached: Cached<PageFacts>): PageView {
        val markdown = cached.value?.markdown
        val failure = cached.failure
        return when {
            cached.value != null && markdown.isNullOrBlank() -> {
                PageView.None
            }

            markdown != null -> {
                val blocks = Markdown.parsePage(markdown, dropHeading = row.name)
                if (blocks.blocks.isEmpty()) PageView.None else PageView.Markdown(blocks)
            }

            failure != null && !cached.refreshing -> {
                PageView.Failed(retryable(failure))
            }

            else -> {
                PageView.Unknown
            }
        }
    }

    fun fit(file: RepoFile, device: DeviceFacts): FitView = Fits.quick(total(file), speedBytes(file.bytes), device)

    private fun situationOf(inputs: CardInputs): Situation {
        val download = inputs.download
        val onPhone = inputs.here.onPhone
        return when {
            !row.canChat -> {
                Situation.CantChat
            }

            download != null && download.status != DownloadStatus.Complete -> {
                Situation.Getting(download)
            }

            // landed, until the library lists it
            download != null -> {
                Situation.OnPhone(download.key, onPhone?.totalBytes ?: download.record?.totalBytes ?: row.size?.bytes)
            }

            onPhone != null -> {
                Situation.OnPhone(onPhone.key, onPhone.totalBytes)
            }

            else -> {
                Situation.Absent
            }
        }
    }

    private fun headerOf(inputs: CardInputs, choices: CardChoices, situation: Situation): CardHeader {
        val quantHere =
            when (situation) {
                is Situation.Getting -> situation.download.key.tag
                is Situation.OnPhone -> situation.key.tag
                else -> null
            }
        val maker =
            row.maker ?: inputs.download
                ?.record
                ?.maker
                ?.let { Names.makerOf(row.repo, it) }
                ?: inputs.facts.value
                    ?.maker
                    ?.let(Names::maker)
                ?: inputs.page.value
                    ?.baseModel
                    ?.substringBefore('/')
                    ?.let(Names::maker)
        // while a maker may still arrive (the facts or the page not yet read), its line is held
        val pending = maker == null && (inputs.facts.pending() || inputs.page.pending())
        val fit = headerFit(inputs, choices.device, quantHere)
        // a model that can't chat says so once, on its state line: no "Embeddings, not chat" word too
        val capabilities = if (row.canChat) row.capabilities else row.capabilities - Capability.Embeddings
        return CardHeader(row.name, maker, row.repo, capabilities, fit, makerReserved = pending)
    }

    private fun Cached<*>.pending() = value == null && failure == null

    /** How the file here runs, else the Recommended one, else the catalog's own figure. */
    private fun headerFit(inputs: CardInputs, device: DeviceFacts?, quantHere: String?): FitView? {
        if (device == null) return null
        // the file on the phone: its own sizes, as its row and the switcher figure it
        inputs.here.file(quantHere)?.let { return Fits.quick(it, device) }
        val files = inputs.files.value
        val file =
            files?.firstOrNull { it.quant.equals(quantHere, ignoreCase = true) }
                ?: files?.let(Sizes::tiers)?.get(SizeTier.Recommended)
        return file?.let { fit(it, device) } ?: row.catalog?.let { Fits.quick(it, device) }
    }

    /**
     * A download under way: no state line: the ring panel alone says
     * "Downloading · Q4_K_M" over "0.9 of 3.4 GB", its total in [DownloadViews.totalOf]'s one order (the record's,
     * else the row's, else the hub's).
     */
    private fun getting(download: RepoDownload): Pair<String?, CardFoot> {
        val chosen = download.record?.totalBytes ?: row.size?.bytes
        val view =
            DownloadViews.of(download.key, download.status, chosen, download.ahead)
                ?: DownloadView(DownloadStage.Downloading, download.key.tag.orEmpty(), 1f, "")
        return null to CardFoot.Download(view)
    }

    private fun onPhoneLine(situation: Situation.OnPhone): String =
        listOfNotNull(ON_PHONE, situation.key.tag, situation.bytes?.takeIf { it > 0 }?.let(Format::sizeInGb))
            .joinToString(" · ")

    private fun onPhoneFoot(inputs: CardInputs, choice: ChoiceView?, choosing: Boolean): CardFoot =
        if (choosing) getFoot(inputs, choice) else CardFoot.OnPhone(another = hasAnother(inputs))

    private fun getFoot(inputs: CardInputs, choice: ChoiceView?): CardFoot.Get {
        val target = target(inputs, choice) ?: return CardFoot.Get("Get", enabled = false)
        return CardFoot.Get(
            "Get ${target.quant} · ${Format.sizeInGb(target.bytes)}",
            enabled = true,
            time = Format.downloadTime(target.bytes),
        )
    }

    private fun catalogTarget(inputs: CardInputs): CardTarget? {
        // the catalog's own file is offered whether the listing is still coming or failed: a newcomer can
        // start offline, as first run could, and the download waits for the network. A card opened on another size
        // than the catalog's own (Recommended) waits for the listing, so its Get never changes quant
        if (startTier != null && startTier != SizeTier.Recommended) return null
        val entry = row.catalog ?: return null
        val quant = entry.key.tag?.takeIf { it.uppercase() !in inputs.here.quants } ?: return null
        return CardTarget(quant, entry.modelBytes + entry.mmprojBytes, entry.modelBytes)
    }

    private fun hasAnother(inputs: CardInputs): Boolean {
        val files = inputs.files.value ?: return true
        return Sizes.tiers(files).values.any { it.quant.uppercase() !in inputs.here.quants }
    }

    private fun choiceOf(inputs: CardInputs, choices: CardChoices): ChoiceView {
        val files = inputs.files.value ?: return unlisted(inputs, choices)
        val tiers = Sizes.tiers(files)
        if (tiers.isEmpty()) return ChoiceView(choices.mode, emptyList(), emptyList(), null, null, ChoiceNotice.NoFile)
        val installed = inputs.here.quants
        val recommended = tiers[SizeTier.Recommended]
        val sorted = files.filter(::isModel).sortedBy { it.bytes }
        val fit = { file: RepoFile -> choices.device?.let { fit(file, it) } }
        return ChoiceView(
            mode = choices.mode,
            tiers =
                SizeTier.entries.map { tier ->
                    val file = tiers[tier]
                    if (file == null) {
                        TierView(tier, null, null, null, missing = true)
                    } else {
                        // a size on the phone shows the file on the phone's own size and fit, as the state line does
                        // (one size everywhere)
                        val here = inputs.here.file(file.quant)
                        TierView(
                            tier,
                            file.quant,
                            here?.totalBytes ?: total(file),
                            here?.let { h -> choices.device?.let { Fits.quick(h, it) } } ?: fit(file),
                            onPhone = file.quant.uppercase() in installed,
                        )
                    }
                },
            quants =
                sorted.map {
                    val here = inputs.here.file(it.quant)
                    QuantView(
                        it.quant,
                        here?.totalBytes ?: total(it),
                        here?.let { h -> choices.device?.let { d -> Fits.quick(h, d) } } ?: fit(it),
                        it == recommended,
                        it.quant.uppercase() in installed,
                    )
                },
            selected = selectedOf(tiers, sorted, installed, choices),
            readerBytes = (row.catalog?.mmprojBytes ?: recommended?.encoderBytes)?.takeIf { it > 0 },
        )
    }

    /**
     * The listing isn't known: the three sizes hold their place, or a failure says so in their place. When the
     * catalog names the file Get would fetch, it is the Recommended size, shown and selected, so the choice
     * never sits blank above a Get that names a size; the other sizes need the listing, and offline say so with one
     * Try again.
     */
    private fun unlisted(inputs: CardInputs, choices: CardChoices): ChoiceView {
        val cached = inputs.files
        val mode = choices.mode
        val failure = cached.failure?.takeIf { !cached.refreshing }
        val known = catalogTarget(inputs)
        val entry = row.catalog
        if (known != null && entry != null) {
            val fit = choices.device?.let { Fits.quick(entry, it) }
            val waiting = if (failure != null) NEEDS_NETWORK else null
            val tiers =
                SizeTier.entries.map { tier ->
                    if (tier == SizeTier.Recommended) {
                        TierView(tier, known.quant, known.bytes, fit)
                    } else {
                        TierView(tier, null, null, null, note = waiting)
                    }
                }
            return ChoiceView(
                mode,
                tiers,
                listOf(QuantView(known.quant, known.bytes, fit, recommended = true, onPhone = false)),
                known.quant,
                entry.mmprojBytes.takeIf { it > 0 },
                failure?.let { ChoiceNotice.OtherSizes(retryable(it)) },
            )
        }
        return if (failure != null) {
            ChoiceView(mode, emptyList(), emptyList(), null, null, ChoiceNotice.Failed(retryable(failure)))
        } else {
            ChoiceView(mode, SizeTier.entries.map { TierView(it, null, null, null) }, emptyList(), null, null)
        }
    }

    /**
     * The quant selected: the one picked, else the size the card opened on, else Recommended; never one on the phone,
     * and in Basic always one of its three sizes.
     */
    private fun selectedOf(
        tiers: Map<SizeTier, RepoFile>,
        sorted: List<RepoFile>,
        installed: Set<String>,
        choices: CardChoices,
    ): String? {
        val free = { quant: String? -> quant?.takeIf { it.uppercase() !in installed } }
        val recommended = tiers[SizeTier.Recommended]?.quant
        val tierQuants = SizeTier.entries.mapNotNull { tiers[it]?.quant }
        val wanted = free(choices.pick) ?: free(startTier?.let { tiers[it]?.quant }) ?: free(recommended)
        val basic = choices.mode == SheetMode.Basic
        // Basic offers three sizes: a quant picked in Advanced that isn't one of them gives way to Recommended
        val offered = if (basic && wanted !in tierQuants) free(recommended) else wanted
        return offered ?: (if (basic) tierQuants else sorted.map { it.quant }).firstNotNullOfOrNull(free)
    }

    private fun cantChatLine(): String =
        if (Capability.Embeddings in row.capabilities) {
            "Not for chat: this is an embedding model"
        } else {
            "Not for chat: this model can't hold a conversation"
        }

    /** One size everywhere: the file and its image reader (the catalog's figure, else the hub's encoder). */
    private fun total(file: RepoFile): Long = file.bytes + (row.catalog?.mmprojBytes ?: file.encoderBytes)

    /** The bytes a token reads: a mixture-of-experts file's active share, as every fit counts it (F175). */
    private fun speedBytes(fileBytes: Long): Long =
        Fits.speedBytes(fileBytes, row.repo, row.parameters, row.activeParameters)

    private fun isModel(file: RepoFile) = !file.path.substringAfterLast('/').contains("mmproj", ignoreCase = true)

    private fun retryable(failure: Throwable) = (failure as? HubException)?.retryable ?: true

    private companion object {
        const val ON_PHONE = "On this phone"
        const val NEEDS_NETWORK = "Needs the network"
    }
}
