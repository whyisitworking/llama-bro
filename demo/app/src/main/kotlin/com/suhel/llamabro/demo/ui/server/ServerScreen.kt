package com.suhel.llamabro.demo.ui.server

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhel.llamabro.demo.designsystem.components.BottomBar
import com.suhel.llamabro.demo.designsystem.components.ChoiceChips
import com.suhel.llamabro.demo.designsystem.components.CodeBlock
import com.suhel.llamabro.demo.designsystem.components.DemoDialog
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.Inline
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.Notice
import com.suhel.llamabro.demo.designsystem.components.PrimaryButton
import com.suhel.llamabro.demo.designsystem.components.QrCode
import com.suhel.llamabro.demo.designsystem.components.QuietButton
import com.suhel.llamabro.demo.designsystem.components.Screen
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SectionNote
import com.suhel.llamabro.demo.designsystem.components.SegmentedSwitch
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.components.WakeHairline
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.ui.Screens
import com.suhel.llamabro.hub.HfModel

/** What the Server page's buttons do. */
class ServerActions(
    val onChoose: (HfModel) -> Unit = {},
    val onSlots: (Int) -> Unit = {},
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onReveal: (Boolean) -> Unit = {},
    /** Asks first: a new key drops the computers connected with the old one. */
    val onNewKey: () -> Unit = {},
    /** Puts the text on the clipboard, marked sensitive: most of what the page copies holds the key. */
    val onCopy: (String) -> Unit = {},
    val onGetModels: () -> Unit = {},
    val onBack: (() -> Unit)? = null,
)

/**
 * The Server page, stateful (docs/design/server.md §3): server mode's state through its view model, the clipboard,
 * the connect tab shown and the question a new key asks. [onBack] is null where the page is a tab.
 */
@Composable
fun ServerScreen(screens: Screens, onBack: (() -> Unit)?, onGetModels: () -> Unit) {
    val vm = viewModel(key = "server") { screens.serverViewModel() }
    val page by vm.page.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var askingNewKey by rememberSaveable { mutableStateOf(false) }
    val actions =
        remember(vm, context, onBack, onGetModels) {
            ServerActions(
                onChoose = vm::choose,
                onSlots = vm::setSlots,
                onStart = vm::start,
                onStop = vm::stop,
                onRetry = vm::retry,
                onReveal = vm::reveal,
                onNewKey = { askingNewKey = true },
                onCopy = { copySecret(context, it) },
                onGetModels = onGetModels,
                onBack = onBack,
            )
        }
    ServerContent(page, actions, tab, { tab = it })
    if (askingNewKey) {
        NewKeyDialog(
            onConfirm = {
                askingNewKey = false
                vm.renewKey()
            },
            onDismiss = { askingNewKey = false },
        )
    }
}

/** "Make a new key?": renewing drops every computer connected with the current key, so it asks first. */
@Composable
fun NewKeyDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DemoDialog(
        NEW_KEY_TITLE,
        NEW_KEY_TEXT,
        confirmLabel = "New key",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        dismissLabel = "Cancel",
    )
}

internal const val NEW_KEY_TITLE = "Make a new key?"
internal const val NEW_KEY_TEXT =
    "Computers using the current key are disconnected, and need the new one to connect again."

/**
 * The Server page, stateless: the top bar and the page in its state, with Start or Stop in the bar at its foot. Nothing
 * shows before the state is read ([page] null), so no state flashes by on the way to the real one. [tab] is the
 * connect snippet shown.
 */
@Composable
fun ServerContent(
    page: ServerPage?,
    actions: ServerActions,
    tab: Int,
    onTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Screen(
        topBar = {
            TopBar(
                title = { TopBarTitle("Server") },
                start = actions.onBack?.let { back -> { IconAction(DemoIcons.Back, "Back", onClick = back) } },
            )
        },
        modifier = modifier.testTag("server_page"),
        bottomBar = { page?.let { Foot(it, actions) } },
    ) {
        when (page) {
            is ServerPage.Off -> OffPage(page, actions)
            is ServerPage.Starting -> StartingPage(page)
            is ServerPage.Serving -> ServingPage(page, actions, tab, onTab)
            is ServerPage.Failed -> FailedPage(page, actions)
            null -> Unit
        }
    }
}

/** The page's one strong action, in the bar at its foot: Start serving, or Stop serving once it has begun. */
@Composable
private fun Foot(page: ServerPage, actions: ServerActions) {
    when (page) {
        is ServerPage.Off -> {
            BottomBar {
                PrimaryButton(
                    "Start serving",
                    actions.onStart,
                    Modifier.fillMaxWidth().testTag("server_start"),
                    enabled = page.chosen != null,
                )
            }
        }

        is ServerPage.Starting, is ServerPage.Serving -> {
            BottomBar {
                PrimaryButton("Stop serving", actions.onStop, Modifier.fillMaxWidth().testTag("server_stop"))
            }
        }

        is ServerPage.Failed -> {
            Unit
        }
    }
}

@Composable
private fun OffPage(page: ServerPage.Off, actions: ServerActions) {
    Stack(Gap.Block) {
        Text(
            "Serve a model to the computers on this Wi-Fi.",
            color = LocalDemoColors.current.ink,
            style = DemoType.body,
        )
        Text(OFF_EXPLAINER, color = LocalDemoColors.current.muted, style = DemoType.bodyMedium)
    }
    Section("Model") {
        if (page.models.isEmpty()) {
            Notice("No models on this phone yet.", action = "Get a model", onAction = actions.onGetModels)
        } else {
            GroupedList(Modifier.testTag("server_models")) {
                for (model in page.models) {
                    row(model.key) {
                        ListRow(
                            model.name,
                            subtitle = model.detail,
                            selected = model.key == page.chosen,
                            onClick = { actions.onChoose(model.key) },
                        )
                    }
                }
            }
        }
    }
    Section("Requests at once") {
        SegmentedSwitch(
            page.slotChoices.map(Int::toString),
            page.slotChoices.indexOf(page.slots),
            { actions.onSlots(page.slotChoices[it]) },
            Modifier.fillMaxWidth(),
        )
        SectionNote("More at once split the model's context: each request can hold a shorter chat.")
    }
}

private const val OFF_EXPLAINER =
    "They talk to it as they would to llama-server: plain HTTP on this Wi-Fi, with a key only this page shows. " +
        "Keep the phone on a charger while it serves."

@Composable
private fun StartingPage(page: ServerPage.Starting) {
    Stack(Gap.Block, Modifier.testTag("server_starting")) {
        Text("Loading ${page.name}", color = LocalDemoColors.current.ink, style = DemoType.body)
        WakeHairline(active = true, progress = page.progress)
    }
}

@Composable
private fun FailedPage(page: ServerPage.Failed, actions: ServerActions) {
    Stack(Gap.Block, Modifier.testTag("server_failed")) {
        Notice(
            "Couldn't start serving: ${page.reason.trimEnd('.')}.",
            action = "Try again",
            onAction = actions.onRetry,
            failure = true,
        )
        QuietButton("Choose another model", actions.onStop)
    }
}

@Composable
private fun ServingPage(page: ServerPage.Serving, actions: ServerActions, tab: Int, onTab: (Int) -> Unit) {
    if (page.urls.isEmpty()) {
        Notice(
            "No Wi-Fi address found. Guest networks often block devices from reaching each other.",
            Modifier.testTag("server_no_address"),
        )
    }
    if (!page.charging) {
        Notice("Put the phone on a charger to serve for long.", Modifier.testTag("server_not_charging"))
    }
    if (page.urls.isNotEmpty()) Address(page, actions)
    Key(page.key, actions)
    Now(page)
    if (page.snippets.isNotEmpty()) Connect(page.snippets, tab, onTab, actions)
    Log(page.log)
}

/** Each address the server answers on, with Copy, and the QR code of the first and the key. */
@Composable
private fun Address(page: ServerPage.Serving, actions: ServerActions) {
    Section("Address") {
        GroupedList(Modifier.testTag("server_urls")) {
            for (url in page.urls) {
                row(url) {
                    ListRow(
                        url,
                        titleStyle = DemoType.code,
                        control = { IconAction(DemoIcons.Copy, "Copy $url", { actions.onCopy(url) }, muted = true) },
                    )
                }
            }
        }
        page.qr?.let { payload ->
            Stack(Gap.Block, Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(payload, "QR code of the address and key", Modifier.testTag("server_qr"))
            }
            SectionNote("The QR code holds the address and the key.")
        }
    }
}

@Composable
private fun Key(key: KeyView, actions: ServerActions) {
    Section("Key") {
        GroupedList(Modifier.testTag("server_key")) {
            row {
                ListRow(
                    key.shown,
                    titleStyle = DemoType.code,
                    control = {
                        Inline(Gap.Inline) {
                            QuietButton(if (key.revealed) "Hide" else "Show", { actions.onReveal(!key.revealed) })
                            IconAction(DemoIcons.Copy, "Copy key", { actions.onCopy(key.copy) }, muted = true)
                        }
                    },
                )
            }
            row {
                ListRow(
                    "New key",
                    subtitle = "Disconnects the computers using this one",
                    onClick = actions.onNewKey,
                )
            }
        }
    }
}

/** Live status: the model, its slots, the last request's speed and the phone's heat. */
@Composable
private fun Now(page: ServerPage.Serving) {
    Section("Now") {
        GroupedList(Modifier.testTag("server_now")) {
            row { ListRow("Serving", trailing = page.name) }
            row { ListRow("Requests at once", trailing = page.slots.toString()) }
            row { ListRow("Speed", trailing = page.speed ?: "No requests yet") }
            page.heat?.let { heat -> row { ListRow("Heat", subtitle = heat) } }
        }
    }
}

/** The connect snippets, one tool at a time; Copy copies the snippet with the key in it. */
@Composable
private fun Connect(snippets: List<SnippetView>, tab: Int, onTab: (Int) -> Unit, actions: ServerActions) {
    val shown = snippets[tab.coerceIn(snippets.indices)]
    Section("Connect") {
        ChoiceChips(snippets.map { it.tool.label }, snippets.indexOf(shown), onTab)
        CodeBlock(
            shown.shown,
            onCopy = { actions.onCopy(shown.copy) },
            modifier = Modifier.testTag("server_snippet"),
            language = shown.tool.label,
        )
    }
}

@Composable
private fun Log(rows: List<LogRow>) {
    Section("Requests") {
        if (rows.isEmpty()) {
            Notice("No requests yet.")
        } else {
            GroupedList(Modifier.testTag("server_log")) {
                for (line in rows) row { ListRow(line.title, subtitle = line.line, trailing = line.status) }
            }
        }
    }
}

/** On the clipboard, marked sensitive (Android 13 and up) so the system's preview doesn't show the key. */
private fun copySecret(context: Context, text: String) {
    val clip = ClipData.newPlainText("Llama Bro", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
