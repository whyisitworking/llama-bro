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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhel.llamabro.demo.AppContainer
import com.suhel.llamabro.demo.designsystem.components.CardTint
import com.suhel.llamabro.demo.designsystem.components.DemoCard
import com.suhel.llamabro.demo.designsystem.components.FitText
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GapAbove
import com.suhel.llamabro.demo.designsystem.components.Greeting
import com.suhel.llamabro.demo.designsystem.components.Inline
import com.suhel.llamabro.demo.designsystem.components.ModelCard
import com.suhel.llamabro.demo.designsystem.components.ModelName
import com.suhel.llamabro.demo.designsystem.components.Orb
import com.suhel.llamabro.demo.designsystem.components.OrbSize
import com.suhel.llamabro.demo.designsystem.components.Screen
import com.suhel.llamabro.demo.designsystem.components.SearchField
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SectionLabel
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
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

/** The line under the greeting, verbatim from the spec. */
internal const val FIRST_RUN_LINE = "Pick a mind to talk to. It lives on this phone: private, and it works offline."

/**
 * First run: the stateful end. It owns the view model, the notification question and the two small overlays (a pick's
 * sheet, "What's a model?"); everything drawn is [FirstRunScreen]'s.
 */
@Composable
fun FirstRunRoute(container: AppContainer, onOpenChat: (Long) -> Unit, onSearch: (String) -> Unit) {
    val viewModel =
        viewModel {
            FirstRunViewModel(
                facts = container::deviceFacts,
                estimate = container.hub::estimate,
                chats = container.chats,
                settings = container.settings,
                models = container.models,
            )
        }
    val picks by viewModel.picks.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    var explaining by rememberSaveable { mutableStateOf(false) }
    val get = rememberAskNotificationsThen(container.asksForNotifications) { sheet?.entry?.let(viewModel::get) }
    LaunchedEffect(viewModel) { viewModel.chatOpened.collect(onOpenChat) }

    FirstRunScreen(
        greeting = viewModel.greeting,
        picks = picks,
        onPick = viewModel::open,
        onSearch = {
            viewModel.markSeen()
            onSearch(it)
        },
        onWhatsAModel = { explaining = true },
    )
    sheet?.let { PickSheet(it, onGet = get, onDismiss = viewModel::close) }
    if (explaining) WhatsAModelDialog(onDismiss = { explaining = false })
}

/**
 * The first screen, stateless: what to draw and what the user can do. [picks] is null until the phone's facts are read.
 */
@Composable
fun FirstRunScreen(
    greeting: String,
    picks: List<PickCard>?,
    onPick: (CatalogEntry) -> Unit,
    onSearch: (String) -> Unit,
    onWhatsAModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Screen(
        topBar = {},
        modifier = modifier.testTag("first_run").statusBarsPadding().imePadding(),
    ) {
        GapAbove(Gap.Hero)
        FirstRunHeader(greeting)
        FirstRunSearch(onSearch)
        Section("Picked for your phone", action = "What's a model?", onAction = onWhatsAModel) {
            picks?.forEach { PickCardItem(it, onClick = { onPick(it.entry) }) }
        }
    }
}

/** The orb, the greeting by the hour and the one line about what a model is here. */
@Composable
private fun FirstRunHeader(greeting: String) {
    Stack(Gap.Section, Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Orb(OrbSize.Hero)
        Stack(Gap.Block, horizontalAlignment = Alignment.CenterHorizontally) {
            Greeting(greeting)
            Text(
                FIRST_RUN_LINE,
                color = LocalDemoColors.current.muted,
                style = DemoType.body,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The search bar: typing is the field's own business, searching (the keyboard's key) hands the text over. */
@Composable
private fun FirstRunSearch(onSearch: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    SearchField(text, {
        text = it
    }, "Search Hugging Face", Modifier.fillMaxWidth().testTag("first_run_search"), onSearch = { onSearch(text) })
}

/** One pick, as every model card: the name and how it runs here, the plain line, the size. No icon. */
@Composable
private fun PickCardItem(card: PickCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ModelCard(
        name = card.entry.realName,
        line = card.entry.line,
        modifier = modifier.testTag("pick_card"),
        fit = card.fit.label.text() to card.fit.label.tint(),
        facts = card.sizeText,
        onClick = onClick,
    )
}
