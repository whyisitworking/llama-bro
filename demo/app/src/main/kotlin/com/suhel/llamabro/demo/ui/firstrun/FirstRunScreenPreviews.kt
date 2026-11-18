package com.suhel.llamabro.demo.ui.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhel.llamabro.demo.AppContainer
import com.suhel.llamabro.demo.designsystem.components.CardTint
import com.suhel.llamabro.demo.designsystem.components.DemoCard
import com.suhel.llamabro.demo.designsystem.components.FitText
import com.suhel.llamabro.demo.designsystem.components.Greeting
import com.suhel.llamabro.demo.designsystem.components.ModelName
import com.suhel.llamabro.demo.designsystem.components.Orb
import com.suhel.llamabro.demo.designsystem.components.SearchField
import com.suhel.llamabro.demo.designsystem.components.SectionLabel
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.CatalogEntry
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.ui.models.rememberAskNotificationsThen
import com.suhel.llamabro.demo.ui.models.text
import com.suhel.llamabro.demo.ui.models.tint
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
