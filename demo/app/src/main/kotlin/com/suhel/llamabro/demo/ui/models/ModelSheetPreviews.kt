package com.suhel.llamabro.demo.ui.models

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.suhel.llamabro.demo.designsystem.components.DialogCard
import com.suhel.llamabro.demo.designsystem.components.DownloadStage
import com.suhel.llamabro.demo.designsystem.components.DownloadView
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.components.gutterPadding
import com.suhel.llamabro.demo.models.Capability
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.ui.chat.Markdown
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame

// Previews of ModelSheet.kt: every state of the card and every foot, in light and dark, and at 2x.

/** The card's states, built as its view model builds them, for the previews and the layout test. */
internal object CardPreviews {
    private const val READER = 675_569_344L
    private val data = ModelsPreviewData

    private fun tier(tier: SizeTier, quant: String, bytes: Long, fit: FitView) =
        TierView(tier, quant, bytes + READER, fit)

    private val tiers =
        listOf(
            tier(SizeTier.Smaller, "Q3_K_M", data.q3.bytes, data.great),
            tier(SizeTier.Recommended, "Q4_K_M", data.q4.bytes, data.well),
            tier(SizeTier.Best, "Q8_0", data.q8.bytes, data.slow),
        )
    private val quants =
        listOf(
            QuantView("Q3_K_M", data.q3.bytes + READER, data.great, recommended = false, onPhone = false),
            QuantView("Q4_K_M", data.q4.bytes + READER, data.well, recommended = true, onPhone = false),
            QuantView("Q8_0", data.q8.bytes + READER, data.slow, recommended = false, onPhone = false),
            QuantView("BF16", data.bf16.bytes + READER, data.tooBig, recommended = false, onPhone = false),
        )
    val basic = ChoiceView(SheetMode.Basic, tiers, quants, "Q4_K_M", READER)
    private val header =
        CardHeader(data.deep.name, data.deep.maker, data.deep.repo, data.deep.capabilities, data.well)
    val page =
        PageView.Markdown(
            Markdown.parsePage(
                "<p align=\"center\"><img src=\"logo.png\"/></p>\n\n# Qwen3.5-4B\n\n" +
                    "Qwen3.5 is the latest generation of **Qwen** models.\n\n## Highlights\n\n" +
                    "| Benchmark | Qwen3.5-4B | Qwen3-4B |\n|---|---|---|\n| MMLU | 79.1 | 75.3 |",
                dropHeading = data.deep.name,
            ),
        )
    private val get = CardFoot.Get("Get Q4_K_M · 3.4 GB", enabled = true, time = "about 8 min")

    val notHere = CardView(header, CardState("Not on this phone"), basic, page, get)

    private fun downloading(stage: DownloadStage, detail: String) =
        notHere.copy(
            // no state line while a download runs: the ring panel says it
            state = CardState(null),
            choice = null,
            foot = CardFoot.Download(DownloadView(stage, "Q4_K_M", 0.27f, detail)),
        )

    val states: List<Pair<String, CardView>> =
        listOf(
            "Basic" to notHere,
            "Advanced" to notHere.copy(choice = basic.copy(mode = SheetMode.Advanced), page = PageView.Unknown),
            "Smaller selected" to
                notHere.copy(
                    choice = basic.copy(selected = "Q3_K_M"),
                    foot = CardFoot.Get("Get Q3_K_M · 2.6 GB", enabled = true, time = "about 6 min"),
                ),
            "Not yet listed" to
                notHere.copy(
                    header = header.copy(fit = null),
                    choice =
                        basic.copy(
                            tiers = SizeTier.entries.map { TierView(it, null, null, null) },
                            selected = null,
                        ),
                    page = PageView.Unknown,
                    foot = CardFoot.Get("Get", enabled = false),
                ),
            // offline with the catalog's own size known: it is the Recommended size, selected, and the others wait
            // for the network
            "Offline, the catalog's size" to
                notHere.copy(
                    choice =
                        basic.copy(
                            tiers =
                                SizeTier.entries.map { tier ->
                                    if (tier == SizeTier.Recommended) {
                                        tiers[1]
                                    } else {
                                        TierView(tier, null, null, null, note = "Needs the network")
                                    }
                                },
                            selected = tiers[1].quant,
                            notice = ChoiceNotice.OtherSizes(retryable = true),
                        ),
                    page = PageView.Failed(retryable = true),
                ),
            "Couldn't list" to
                notHere.copy(
                    choice = basic.copy(tiers = emptyList(), notice = ChoiceNotice.Failed(retryable = true)),
                    page = PageView.Failed(retryable = true),
                    foot = CardFoot.Get("Get", enabled = false),
                ),
            "One size in the upload" to
                notHere.copy(
                    choice =
                        basic.copy(
                            tiers =
                                listOf(
                                    TierView(SizeTier.Smaller, null, null, null, missing = true),
                                    tiers[1],
                                    TierView(SizeTier.Best, null, null, null, missing = true),
                                ),
                        ),
                ),
            "Downloading" to downloading(DownloadStage.Downloading, "0.9 of 3.4 GB · 4 min left"),
            "Paused" to downloading(DownloadStage.Paused, "0.9 of 3.4 GB"),
            "Waiting" to downloading(DownloadStage.Waiting, "after Gemma 3 4B"),
            // the bytes kept, with the ring frozen at them
            "Stopped" to downloading(DownloadStage.Stopped, "The connection dropped · 0.9 of 3.4 GB kept"),
            "On this phone" to
                notHere.copy(
                    state = CardState("On this phone · Q4_K_M · 3.4 GB"),
                    choice = null,
                    foot = CardFoot.OnPhone(another = true),
                ),
            "Another size" to
                notHere.copy(
                    state = CardState("On this phone · Q4_K_M · 3.4 GB"),
                    // Q4_K_M is on the phone in both lists, as the app derives it (the card's fixture note)
                    choice =
                        basic.copy(
                            tiers = tiers.mapIndexed { i, t -> if (i == 1) t.copy(onPhone = true) else t },
                            quants = quants.map { if (it.quant == "Q4_K_M") it.copy(onPhone = true) else it },
                            selected = "Q3_K_M",
                            alreadyHere = "Q4_K_M",
                        ),
                    foot = CardFoot.Get("Get Q3_K_M · 2.6 GB", enabled = true, time = "about 6 min"),
                ),
            "Can't chat" to
                CardView(
                    CardHeader(
                        data.embedder.name,
                        data.embedder.maker,
                        data.embedder.repo,
                        // the state line says it can't chat; no "Embeddings, not chat" word too
                        data.embedder.capabilities - Capability.Embeddings,
                        null,
                    ),
                    CardState("Not for chat: this is an embedding model"),
                    null,
                    PageView.None,
                    CardFoot.CantChat,
                ),
        )
}

internal class CardStates : PreviewParameterProvider<Pair<String, CardView>> {
    override val values = CardPreviews.states.asSequence()

    override fun getDisplayName(index: Int): String = CardPreviews.states[index].first
}

@Composable
private fun CardInFrame(view: CardView) {
    PreviewFrame {
        Column(Modifier.gutterPadding()) {
            SheetFrame(pinned = { CardFootView(view.foot, CardActions()) }) { CardBody(view, CardActions()) }
        }
    }
}

@DemoPreviews
@Composable
internal fun ModelCardPreview(
    @PreviewParameter(CardStates::class) state: Pair<String, CardView>,
) = CardInFrame(state.second)

@Preview(name = "Light, 2x", widthDp = 360, fontScale = 2f)
@Preview(name = "Dark, 2x", widthDp = 360, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ModelCardLargeTextPreview(
    @PreviewParameter(CardStates::class) state: Pair<String, CardView>,
) = CardInFrame(state.second)

@DemoPreviews
@Composable
internal fun CancelDownloadPreview() {
    PreviewFrame {
        Column(Modifier.gutterPadding()) {
            DialogCard(
                "Cancel the download?",
                "0.9 GB has arrived. Cancelling deletes it.",
                "Cancel download",
                {},
                {},
                dismissLabel = "Keep",
                danger = true,
            )
        }
    }
}
