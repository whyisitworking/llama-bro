package com.suhel.llamabro.demo.ui.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.CardTint
import com.suhel.llamabro.demo.designsystem.components.DemoCard
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.FactCard
import com.suhel.llamabro.demo.designsystem.components.FitText
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GapAbove
import com.suhel.llamabro.demo.designsystem.components.Inline
import com.suhel.llamabro.demo.designsystem.components.ModelName
import com.suhel.llamabro.demo.designsystem.components.PrimaryButton
import com.suhel.llamabro.demo.designsystem.components.Screen
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.CatalogEntry
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.ui.models.text
import com.suhel.llamabro.demo.ui.models.tint
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame

/** A first-run pick, closer: the sheet that opens over the picks. */
@Composable
fun PickSheet(state: PickSheetState, onGet: () -> Unit, onDismiss: () -> Unit) {
    DemoSheet(onDismissRequest = onDismiss) { PickSheetContent(state, onGet) }
}

/** The sheet's contents, without the window: names, about, the fit line, four facts, Get and the source. */
@Composable
fun ColumnScope.PickSheetContent(state: PickSheetState, onGet: () -> Unit) {
    val entry = state.entry
    Stack(Gap.Block) {
        ModelName(entry.realName, realName = "by ${entry.maker}")
        Text(entry.about, color = LocalDemoColors.current.muted, style = DemoType.body)
    }
    PickFitLine(state.fit)
    PickFacts(entry, state)
    PrimaryButton("Get ${entry.realName}", onGet, Modifier.fillMaxWidth())
    Text(
        "From Hugging Face",
        Modifier.fillMaxWidth(),
        color = LocalDemoColors.current.muted,
        style = DemoType.bodySmall,
        textAlign = TextAlign.Center,
    )
}

/** "Runs great on this phone · about 59 words a second": tinted by the fit. */
@Composable
private fun PickFitLine(fit: FitView) {
    val tint = fit.label.tint()
    val speed =
        if (fit.label == FitLabel.TooBig || fit.wordsPerSecond == 0) {
            ""
        } else {
            " · about ${fit.wordsPerSecond} words a second"
        }
    DemoCard(modifier = Modifier.fillMaxWidth()) {
        FitText(fit.label.text() + " on this phone" + speed, tint)
    }
}

/** Thinking, images, memory, download: two by two. */
@Composable
private fun PickFacts(entry: CatalogEntry, state: PickSheetState) {
    Stack(Gap.Block) {
        Inline(Gap.Block, verticalAlignment = Alignment.Top) {
            FactCard(
                "Thinking",
                if (entry.thinks) "Can think first, or not" else "Answers straight away",
                Modifier.weight(1f),
            )
            FactCard("Images", if (entry.sees) "Yes, it can see them" else "No, text only", Modifier.weight(1f))
        }
        Inline(Gap.Block, verticalAlignment = Alignment.Top) {
            FactCard("Memory", state.memoryText, Modifier.weight(1f))
            FactCard("Download", state.downloadText, Modifier.weight(1f))
        }
    }
}

private fun previewState(entry: CatalogEntry, label: FitLabel, wordsPerSecond: Int) =
    PickSheetState(
        entry = entry,
        fit = FitView(label, wordsPerSecond),
        memoryText = "Uses about 0.9 GB",
        downloadText = "0.7 GB · about 2 min",
    )

internal class PickSheetStates : PreviewParameterProvider<PickSheetState> {
    override val values =
        sequenceOf(
            previewState(Catalog.picks[0], FitLabel.RunsGreat, 59),
            previewState(Catalog.picks[1], FitLabel.RunsWell, 10),
            previewState(Catalog.picks[2], FitLabel.SlowHere, 7),
            previewState(Catalog.picks[2], FitLabel.TooBig, 0),
        )
}

@DemoPreviews
@Composable
internal fun PickSheetPreview(
    @PreviewParameter(PickSheetStates::class) state: PickSheetState,
) {
    PreviewFrame { SheetFrame { PickSheetContent(state, onGet = {}) } }
}
