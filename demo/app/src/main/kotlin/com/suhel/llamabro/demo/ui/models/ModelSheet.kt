package com.suhel.llamabro.demo.ui.models

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhel.llamabro.demo.designsystem.components.DemoDialog
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.DemoSheetState
import com.suhel.llamabro.demo.designsystem.components.DownloadPanel
import com.suhel.llamabro.demo.designsystem.components.DownloadStage
import com.suhel.llamabro.demo.designsystem.components.DownloadView
import com.suhel.llamabro.demo.designsystem.components.FadeThrough
import com.suhel.llamabro.demo.designsystem.components.FitSymbol
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.Inline
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.MarkdownText
import com.suhel.llamabro.demo.designsystem.components.ModelFactsLine
import com.suhel.llamabro.demo.designsystem.components.Notice
import com.suhel.llamabro.demo.designsystem.components.OnGutter
import com.suhel.llamabro.demo.designsystem.components.PrimaryButton
import com.suhel.llamabro.demo.designsystem.components.QuantHeader
import com.suhel.llamabro.demo.designsystem.components.QuantRow
import com.suhel.llamabro.demo.designsystem.components.QuietButton
import com.suhel.llamabro.demo.designsystem.components.RadioMark
import com.suhel.llamabro.demo.designsystem.components.ReserveTallest
import com.suhel.llamabro.demo.designsystem.components.RowCard
import com.suhel.llamabro.demo.designsystem.components.SegmentedSwitch
import com.suhel.llamabro.demo.designsystem.components.SheetReveal
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.Unfold
import com.suhel.llamabro.demo.designsystem.components.isLargeText
import com.suhel.llamabro.demo.designsystem.components.rememberDemoSheetState
import com.suhel.llamabro.demo.designsystem.components.rememberSheetReveal
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.ui.Screens
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A model's card: the stateful end. Its view model lives in a store that is cleared when the card closes, which stops
 * its work, and kept across a rotation. Every action that closes the card slides it down first (models audit S9): Get
 * asks about notifications, starts the download and closes; "Start a chat" opens a new chat on the model and closes;
 * a null [onChatWith] (a card opened from a chat, on the model it runs) offers none. [onExactFit] hears the
 * Recommended file's measured fit once.
 */
@Composable
fun ModelSheetRoute(
    screens: Screens,
    row: ModelEntry,
    onStarted: (HfModel) -> Unit,
    onDismiss: () -> Unit,
    onChatWith: ((HfModel) -> Unit)?,
    onExactFit: (FitView) -> Unit = {},
    startTier: SizeTier? = null,
) {
    val viewModel =
        viewModel(viewModelStoreOwner = rememberSheetViewModelOwner("sheet:${row.repo}")) {
            screens.modelSheetViewModel(row, onExactFit, startTier)
        }
    val view by viewModel.view.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val asking by viewModel.asking.collectAsStateWithLifecycle()
    val sheet = rememberDemoSheetState()
    val scope = rememberCoroutineScope()
    val closer = remember(sheet, scope) { CardCloser(sheet, scope) }
    val close = { then: () -> Unit -> closer.close(view, then) }
    val get =
        rememberAskNotificationsThen(screens.asksForNotifications) {
            viewModel.get()?.let(onStarted)
            close(onDismiss)
        }
    val actions =
        remember(viewModel, get, onChatWith, onDismiss) {
            CardActions(
                onMode = viewModel::setMode,
                onSelect = viewModel::select,
                onGet = get,
                onRetry = viewModel::retry,
                onToggle = viewModel::toggle,
                onCancel = { viewModel.cancel() },
                onConfirmCancel = viewModel::confirmCancel,
                onKeep = viewModel::keepDownload,
                onAnotherSize = viewModel::getAnotherSize,
                onDoneChoosing = viewModel::doneChoosing,
                onDismissProblem = viewModel::dismissProblem,
                onStartChat = {
                    viewModel.onPhone()?.let { key ->
                        close {
                            onChatWith?.invoke(key)
                            onDismiss()
                        }
                    }
                },
            )
        }
    // the sheet opens once every local read has answered, so its first frame is final (D118)
    closer.shown(view)?.let {
        ModelCardSheet(
            it,
            actions,
            onDismiss,
            sheet,
            problem,
            asking,
            chatWith =
                onChatWith != null,
        )
    }
}

/**
 * Closes the card: while the sheet slides away it keeps the card it had, its Get foot and all (Get starts the download
 * at once, and the foot turning into the ring panel mid-slide read as the foot vanishing first). A drag that catches
 * the sheet on its way down keeps it open, and the card follows its view again.
 */
@Stable
internal class CardCloser(private val sheet: DemoSheetState, private val scope: CoroutineScope) {
    private var frozen by mutableStateOf<CardView?>(null)

    /** The card to draw: the one it had while it closes, else [live]. */
    fun shown(live: CardView?): CardView? = frozen ?: live

    fun close(now: CardView?, then: () -> Unit) {
        frozen = now
        scope.launch {
            try {
                sheet.hideThenDismiss(then)
            } finally {
                // the hide was caught (or cancelled) and the sheet is still up: it is live again
                if (sheet.isVisible) frozen = null
            }
        }
    }
}

/** What the card's controls do. */
@Immutable
class CardActions(
    val onMode: (SheetMode) -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onGet: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onToggle: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onConfirmCancel: () -> Unit = {},
    val onKeep: () -> Unit = {},
    val onAnotherSize: () -> Unit = {},
    val onDoneChoosing: () -> Unit = {},
    val onDismissProblem: () -> Unit = {},
    val onStartChat: () -> Unit = {},
)

/**
 * The card, stateless (docs/design/models-page.md §4): the expandable sheet with the [view]'s parts in its one scroll
 * and its foot pinned under it, 64 dp in every state. [problem] (a cancel that couldn't stop the work) sits under the
 * state line; [asking] is the question a Cancel past 300 MB asks, in a dialog whose confirm is in danger.
 */
@Composable
fun ModelCardSheet(
    view: CardView,
    actions: CardActions,
    onDismiss: () -> Unit,
    state: DemoSheetState = rememberDemoSheetState(),
    problem: String? = null,
    asking: String? = null,
    chatWith: Boolean = true,
) {
    DemoSheet(
        onDismissRequest = onDismiss,
        expandable = true,
        state = state,
        pinned = { CardFootView(view.foot, actions, chatWith) },
    ) {
        CardBody(view, actions, problem)
    }
    if (asking != null) {
        DemoDialog(
            title = "Cancel the download?",
            text = asking,
            confirmLabel = "Cancel download",
            onConfirm = actions.onConfirmCancel,
            onDismiss = actions.onKeep,
            dismissLabel = "Keep",
            danger = true,
        )
    }
}

/**
 * The card's scrolling parts, top to bottom, each in the same place in every state (D123: the name, one quiet line, a
 * clear gap, where the model stands, then the page, muted, after a larger gap). The state line is one muted line; it
 * folds away while a download runs and its words fade through as the state changes. Parts that come and go
 * (the line, the choice) unfold and fold, fading, never in one frame.
 */
@Composable
fun ColumnScope.CardBody(view: CardView, actions: CardActions, problem: String? = null) {
    HeaderPart(view.header)
    Unfold(view.state.text) { text ->
        FadeThrough(text, Modifier.fillMaxWidth().padding(top = DemoSpacing.block)) { shown ->
            Text(
                shown,
                Modifier.fillMaxWidth().testTag("sheet_state"),
                color = LocalDemoColors.current.muted,
                style = DemoType.bodyMedium,
            )
        }
    }
    if (problem != null) {
        Notice(
            problem,
            Modifier.testTag("sheet_problem"),
            action = "OK",
            onAction = actions.onDismissProblem,
            inset = false,
            failure = true,
        )
    }
    ShownChoice(view.choice, actions)
    PagePart(view.page, view.header.repo)
}

/**
 * The foot pinned under the scroll: Get, the ring panel, "Start a chat" with "Another size", or why there is
 * nothing to get. Each is one button tall, so the foot is 64 dp in every state and nothing above it moves as a download
 * starts or lands (models audit S18, S29). At large font the two buttons stack and the foot grows rather than clips.
 */
@Composable
fun ColumnScope.CardFootView(foot: CardFoot, actions: CardActions, chatWith: Boolean = true) {
    // a foot that changes kind (Get to the ring panel, the ring panel to "Start a chat") fades through; the ring's
    // own ticks within a kind just update
    val through = @Composable {
        FadeThrough(foot, Modifier.fillMaxWidth(), contentKey = { it::class }) { shown ->
            FootOf(shown, actions, chatWith)
        }
    }
    if (isLargeText()) {
        // at large font the foot is the tallest of its kinds in every state (models-page design §4.5): Get with its
        // time on a second line, the ring panel's lines at two lines each, and the two stacked buttons are measured
        // beside it, so a download starting or landing moves nothing
        ReserveTallest(largeFootKinds(foot, chatWith), Modifier.fillMaxWidth()) { through() }
    } else {
        through()
    }
}

/** The foot's kinds as large font draws them, to be measured beside the one shown; the shown one's own words win. */
private fun largeFootKinds(foot: CardFoot, chatWith: Boolean): List<@Composable () -> Unit> {
    val none = CardActions()
    val get = foot as? CardFoot.Get ?: CardFoot.Get(RESERVE_GET, enabled = true, time = RESERVE_TIME)
    val panel = (foot as? CardFoot.Download)?.view ?: RESERVE_PANEL
    return listOf(
        { FootOf(get, none, chatWith) },
        { FootOf(CardFoot.Download(panel), none, chatWith) },
        { FootOf(CardFoot.OnPhone(another = true), none, chatWith) },
    )
}

private const val RESERVE_GET = "Get Q4_K_M · 3.4 GB"
private const val RESERVE_TIME = "about 8 min"
private val RESERVE_PANEL =
    DownloadView(DownloadStage.Downloading, "Q4_K_M", 0f, "0.9 of 3.4 GB · 4 min left")

@Composable
private fun FootOf(foot: CardFoot, actions: CardActions, chatWith: Boolean) {
    when (foot) {
        // the foot bleeds past the gutter for the ring panel's targets (F130); everything else sits on the gutter
        is CardFoot.Get -> {
            // at large font the time is a line of its own, so the file and its size are never what is cut
            val large = isLargeText()
            val text = if (large && foot.time != null) "${foot.lead}\n${foot.time}" else foot.label
            OnGutter {
                PrimaryButton(
                    text,
                    actions.onGet,
                    Modifier.fillMaxWidth().testTag("get_button"),
                    enabled = foot.enabled,
                    maxLines = if (large) Int.MAX_VALUE else 1,
                    subtle = true,
                )
            }
        }

        is CardFoot.Download -> {
            DownloadPanel(foot.view, actions.onToggle, actions.onCancel, Modifier.testTag("sheet_download"))
        }

        is CardFoot.OnPhone -> {
            OnGutter { Column { OnPhoneActions(chatWith, foot.another, actions) } }
        }

        // the state line already says the model can't chat; the foot says nothing more, holding its height
        CardFoot.CantChat -> {
            OnGutter { FootLine("", Modifier.testTag("cant_chat")) }
        }
    }
}

@Composable
private fun OnPhoneActions(chatWith: Boolean, another: Boolean, actions: CardActions) {
    val chat: @Composable (Modifier) -> Unit = { m ->
        PrimaryButton("Start a chat", actions.onStartChat, m.testTag("chat_with"), maxLines = 1, subtle = true)
    }
    val more: @Composable (Modifier) -> Unit = { m ->
        QuietButton(ANOTHER_SIZE, actions.onAnotherSize, m.testTag("another_size"), bordered = true, subtle = true)
    }
    when {
        chatWith && another && isLargeText() -> {
            Stack(Gap.Block) {
                chat(Modifier.fillMaxWidth())
                more(Modifier.fillMaxWidth())
            }
        }

        chatWith && another -> {
            Inline(Gap.Block) {
                chat(Modifier.weight(1f))
                more(Modifier.weight(1f))
            }
        }

        chatWith -> {
            chat(Modifier.fillMaxWidth())
        }

        another -> {
            more(Modifier.fillMaxWidth())
        }

        else -> {
            FootLine("Every size of this model is on this phone.")
        }
    }
}

/** A muted line in place of the foot's button, a button's height. */
@Composable
private fun FootLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = DemoSize.button), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = LocalDemoColors.current.muted, style = DemoType.bodyMedium)
    }
}

/**
 * The name in the serif, Regular, and one quiet line under it (D123): the maker, what the model can do as icons with
 * no words (a screen reader hears them), and the fit symbol in its colour. The line is held from the first frame, so
 * a maker or a fit arriving moves nothing. The repo is the page's source, at its end.
 */
@Composable
private fun HeaderPart(header: CardHeader) {
    val colors = LocalDemoColors.current
    Stack(Gap.Inline, Modifier.fillMaxWidth().testTag("card_header")) {
        Text(header.name, color = colors.ink, style = DemoType.cardTitle)
        ModelFactsLine(
            header.maker,
            header.capabilities.map { it.icon() to it.words() },
            Modifier
                .heightIn(min = DemoSize.fitSymbol)
                .testTag("card_facts")
                .then(
                    if (header.maker == null &&
                        header.makerReserved
                    ) {
                        Modifier.testTag("maker_reserved")
                    } else {
                        Modifier
                    },
                ),
            fit = header.fit?.label?.level(),
            fitDescription =
                header.fit
                    ?.label
                    ?.text()
                    .orEmpty(),
        )
    }
}

/**
 * The size choice where the card has one. One that arrives after the card opened ("Another size" on a card for a
 * model on the phone) is the person's own doing: it unfolds and the size Get would fetch is revealed in one motion:
 * the sheet grows with its scroll held when that size isn't in view, and only then, if it still
 * isn't, the least scroll brings it there; in Basic and in Advanced.
 */
@Composable
private fun ShownChoice(choice: ChoiceView?, actions: CardActions) {
    val atOpen = remember { choice != null }
    val still = animationsDisabled()
    Unfold(choice) { shown ->
        val reveal = rememberSheetReveal()
        if (!atOpen) {
            LaunchedEffect(reveal) {
                reveal.reveal(if (still) 0L else (DemoMotion.TEXT_OUT_MILLIS + DemoMotion.STANDARD_MILLIS).toLong())
            }
        }
        ChoicePart(shown, actions, reveal)
    }
}

/** The size choice: Basic's three sizes or every quant, or in their place why there are none. */
@Composable
private fun ChoicePart(choice: ChoiceView, actions: CardActions, reveal: SheetReveal? = null) {
    Stack(Gap.Block, Modifier.fillMaxWidth().testTag("size_choice")) {
        when (val notice = choice.notice) {
            is ChoiceNotice.Failed -> {
                Notice(
                    "Couldn't read this model's sizes.",
                    action = if (notice.retryable) "Try again" else null,
                    onAction = actions.onRetry,
                    inset = false,
                )
            }

            ChoiceNotice.NoFile -> {
                Notice("This model has no file this app can run.", inset = false)
            }

            is ChoiceNotice.OtherSizes, null -> {
                choice.alreadyHere?.let { quant -> AlreadyHere(quant, actions.onDoneChoosing) }
                SegmentedSwitch(
                    options = listOf("Basic", "Advanced"),
                    selected = choice.mode.ordinal,
                    onSelect = { actions.onMode(SheetMode.entries[it]) },
                    modifier = Modifier.fillMaxWidth().testTag("mode_switch"),
                    quiet = true,
                )
                ChoiceSizes(choice, actions, reveal)
                if (notice is ChoiceNotice.OtherSizes) {
                    Notice(
                        "Other sizes need the network.",
                        action = if (notice.retryable) "Try again" else null,
                        onAction = actions.onRetry,
                        inset = false,
                    )
                }
            }
        }
    }
}

/**
 * Basic's three sizes and Advanced's every quant: a body that changes kind fades through while the part's height eases
 * between them. The size Get would fetch carries [reveal]'s modifier, in either mode.
 */
@Composable
private fun ChoiceSizes(choice: ChoiceView, actions: CardActions, reveal: SheetReveal?) {
    val target = reveal?.modifier ?: Modifier
    FadeThrough(choice, Modifier.fillMaxWidth(), contentKey = { it.mode }, sizeEases = true) { shown ->
        Stack(Gap.Block, Modifier.fillMaxWidth()) {
            when (shown.mode) {
                SheetMode.Basic -> {
                    shown.tiers.forEach {
                        // a placeholder is never the selected one
                        val selected = it.quant != null && it.quant == shown.selected
                        TierOption(it, selected, actions, if (selected) target else Modifier)
                    }
                }

                SheetMode.Advanced -> {
                    AdvancedQuants(shown, actions, reveal)
                }
            }
        }
    }
}

/**
 * "Q4_K_M is already on this phone." over the sizes "Another size" opened, which is why another is selected, with
 * Done, the quiet way back to "Start a chat".
 */
@Composable
private fun AlreadyHere(quant: String, onDone: () -> Unit) {
    Inline(Gap.Block, Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$quant is already on this phone.",
            Modifier.weight(1f).testTag("already_here"),
            color = LocalDemoColors.current.muted,
            style = DemoType.bodyMedium,
        )
        QuietButton("Done", onDone, Modifier.testTag("done_choosing"), bordered = true, subtle = true)
    }
}

/**
 * One of Basic's sizes: its title, the quant and the whole size under it ("Q4_K_M · 3.4 GB"), the fit symbol and a
 * radio mark. A size the upload lacks stays, disabled, as "Not in this upload"; one not yet known holds its place.
 */
@Composable
private fun TierOption(tier: TierView, selected: Boolean, actions: CardActions, modifier: Modifier = Modifier) {
    val quant = tier.quant
    val enabled = quant != null && !tier.onPhone
    val line =
        when {
            tier.missing -> {
                "Not in this upload"
            }

            quant == null -> {
                tier.note.orEmpty()
            }

            else -> {
                listOfNotNull(quant, tier.bytes?.let(Format::sizeInGb), ON_PHONE.takeIf { tier.onPhone })
                    .joinToString(" · ")
            }
        }
    RowCard(
        modifier.testTag("tier_${tier.tier.name}"),
        selected = selected,
        onClick = if (enabled) ({ actions.onSelect(checkNotNull(quant)) }) else null,
        // a size on the phone looks as Advanced's file on the phone does
        available = !tier.onPhone,
    ) {
        ListRow(
            title = tier.tier.title(),
            subtitle = line,
            // Regular, in the card's quiet type: only the chosen segment above carries weight (D123)
            titleStyle = DemoType.bodyMedium,
            end = tier.fit?.let { fit -> { FitSymbol(fit.label.level(), fit.label.text()) } },
            control = { RadioMark(selected, enabled = enabled) },
        )
    }
}

private fun SizeTier.title() =
    when (this) {
        SizeTier.Smaller -> "Smaller"
        SizeTier.Recommended -> "Recommended"
        SizeTier.Best -> "Best quality"
    }

/**
 * Every quant in the sheet's one scroll (no nested list, models audit S15), its fit column titled "Runs here" (S8),
 * "Recommended" or "On phone" as words under the quant (S2), then the image reader as a matching row.
 */
@Composable
private fun AdvancedQuants(choice: ChoiceView, actions: CardActions, reveal: SheetReveal? = null) {
    val target = reveal?.modifier ?: Modifier
    QuantHeader("Quant", "Size", "Runs here")
    for (quant in choice.quants) {
        val selected = quant.quant == choice.selected
        QuantRow(
            quant = quant.quant,
            size = Format.sizeInGb(quant.bytes),
            selected = selected,
            modifier = (if (selected) target else Modifier).testTag("quant_row"),
            // a file already on the phone can't be got again, as Basic's tier on the phone can't ("Open")
            included = quant.onPhone,
            onClick = { if (!quant.onPhone) actions.onSelect(quant.quant) },
            note =
                when {
                    quant.onPhone -> "On phone"
                    quant.recommended -> "Recommended"
                    else -> null
                },
            notes = true,
            symbol = { quant.fit?.let { FitSymbol(it.label.level(), it.label.text()) } },
        )
    }
    // the image reader is a fact under the list, not a choice: flat, no card edge, in words
    choice.readerBytes?.let { bytes ->
        QuantRow(
            quant = "Image reader",
            size = Format.sizeInGb(bytes),
            selected = false,
            modifier = Modifier.testTag("image_reader"),
            included = true,
            flat = true,
            symbol = { Text("Included", color = LocalDemoColors.current.muted, style = DemoType.bodySmall) },
        )
    }
}

/**
 * The model's page under the card's own parts, after a larger gap: a quiet "About" that says whose words follow
 * (models audit S6), the page in page mode, muted and small (D123), and its source, the [repo], at its end; nothing
 * while it isn't known (no bar, S4), and nothing when it couldn't be read: the choice's one notice and its Try again
 * cover it (S20).
 */
@Composable
private fun PagePart(page: PageView, repo: String) {
    when (page) {
        PageView.Unknown, PageView.None, is PageView.Failed -> {
            Unit
        }

        is PageView.Markdown -> {
            // the page follows the choice at the column's one gap; no push off the compact fold, which left a hole
            // once the sheet was open (D124)
            Stack(Gap.Block, Modifier.fillMaxWidth().padding(top = DemoSpacing.item)) {
                val soft = LocalDemoColors.current.soft
                Text(PAGE_OVERLINE, Modifier.testTag("page_overline"), color = soft, style = DemoType.quietLabel)
                MarkdownText(
                    page.blocks,
                    onCopyCode = {},
                    Modifier.fillMaxWidth().testTag("model_page"),
                    page = true,
                )
                Text(
                    "Source: $repo",
                    Modifier.testTag("page_source"),
                    color = soft,
                    style = DemoType.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
    }
}

internal const val PAGE_OVERLINE = "About"
internal const val ANOTHER_SIZE = "Another size"
private const val ON_PHONE = "On this phone"
