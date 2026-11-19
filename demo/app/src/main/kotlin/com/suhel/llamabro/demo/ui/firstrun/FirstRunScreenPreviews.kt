package com.suhel.llamabro.demo.ui.firstrun

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.ui.preview.DemoScreenPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame

// Previews of FirstRunScreen.kt: each state of the screen or part, in light and dark.

private fun picks(vararg labels: FitLabel) =
    Catalog.picks.zip(labels.toList()).map { (entry, label) ->
        PickCard(
            entry,
            FitView(label, 10),
            "${"%.1f".format((entry.modelBytes + entry.mmprojBytes) / 1e9)} GB",
        )
    }

internal class FirstRunStates : PreviewParameterProvider<FirstRunPreview> {
    override val values =
        sequenceOf(
            FirstRunPreview(
                "Morning, picks",
                "Good morning",
                picks(FitLabel.RunsGreat, FitLabel.RunsWell, FitLabel.RunsWell),
            ),
            FirstRunPreview(
                "Evening, a small phone",
                "Good evening",
                picks(FitLabel.RunsGreat, FitLabel.SlowHere, FitLabel.TooBig),
            ),
            FirstRunPreview("Late night, facts not read yet", "Hello", null),
        )
}

internal data class FirstRunPreview(val name: String, val greeting: String, val picks: List<PickCard>?) {
    override fun toString() = name
}

@DemoScreenPreviews
@Composable
internal fun FirstRunScreenPreview(
    @PreviewParameter(FirstRunStates::class) state: FirstRunPreview,
) {
    PreviewFrame {
        FirstRunScreen(state.greeting, state.picks, onPick = {
        }, onSearch = {}, onWhatsAModel = {}, modifier = Modifier.height(780.dp))
    }
}
