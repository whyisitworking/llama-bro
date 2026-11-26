package com.suhel.llamabro.demo.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhel.llamabro.demo.designsystem.components.BottomBar
import com.suhel.llamabro.demo.designsystem.components.DemoDialog
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.LoadingSlot
import com.suhel.llamabro.demo.designsystem.components.Notice
import com.suhel.llamabro.demo.designsystem.components.PrimaryButton
import com.suhel.llamabro.demo.designsystem.components.Screen
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SectionNote
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.ThinBar
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.library.TextImport
import com.suhel.llamabro.demo.ui.Screens

/** What the Library page's buttons do. */
class LibraryActions(
    /** Opens the system's file picker. */
    val onImport: () -> Unit = {},
    /** Asks first, then removes the document with this id. */
    val onRemove: (String) -> Unit = {},
    val onRebuild: () -> Unit = {},
    val onGetEmbedder: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onBack: (() -> Unit)? = null,
)

/**
 * The Library page, stateful (docs/design/rag.md, the demo): the library through its view model, the system's file
 * picker and the question Remove asks. [onBack] is null where the page is a tab.
 */
@Composable
fun LibraryScreen(screens: Screens, onBack: (() -> Unit)?) {
    val vm = viewModel(key = "library") { screens.libraryViewModel() }
    val page by vm.page.collectAsStateWithLifecycle()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val actions =
        remember(vm, picker, onBack) {
            LibraryActions(
                onImport = { picker.launch(TextImport.PICKER_TYPES) },
                onRemove = { removing = it },
                onRebuild = vm::rebuild,
                onGetEmbedder = vm::getEmbedder,
                onDismissNotice = vm::dismissNotice,
                onBack = onBack,
            )
        }
    LibraryContent(page, actions)
    page?.documents?.firstOrNull { it.id == removing }?.let { document ->
        RemoveDialog(
            document.title,
            onConfirm = {
                removing = null
                vm.remove(document.id)
            },
            onDismiss = { removing = null },
        )
    }
}

/** "Remove <title>?": the file stays where it was, so it can be added again. */
@Composable
fun RemoveDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DemoDialog(
        removeTitle(title),
        REMOVE_TEXT,
        confirmLabel = "Remove",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        dismissLabel = "Cancel",
        danger = true,
    )
}

internal fun removeTitle(title: String) = "Remove $title?"

internal const val REMOVE_TEXT = "Its passages leave the library. The file stays on the phone, and can be added again."

/**
 * The Library page, stateless: what it is for, the embedder's state, a rebuild it needs, the work under way, why an
 * import didn't happen, and the documents; Add a document in the bar at its foot. Nothing shows before the library is
 * read ([page] null).
 */
@Composable
fun LibraryContent(page: LibraryPage?, actions: LibraryActions, modifier: Modifier = Modifier) {
    Screen(
        topBar = {
            TopBar(
                title = { TopBarTitle("Library") },
                start = actions.onBack?.let { back -> { IconAction(DemoIcons.Back, "Back", onClick = back) } },
            )
        },
        modifier = modifier.testTag("library_page"),
        bottomBar = {
            page?.let {
                BottomBar {
                    PrimaryButton(
                        "Add a document",
                        actions.onImport,
                        Modifier.fillMaxWidth().testTag("library_add"),
                        enabled = it.canImport,
                    )
                }
            }
        },
    ) {
        page?.let { LibraryPageContent(it, actions) }
    }
}

@Composable
private fun LibraryPageContent(page: LibraryPage, actions: LibraryActions) {
    Stack(Gap.Block) {
        Text("Your documents, searchable on this phone.", color = LocalDemoColors.current.ink, style = DemoType.body)
        Text(INTRO, color = LocalDemoColors.current.muted, style = DemoType.bodyMedium)
    }
    EmbedderState(page.embedder, actions)
    page.mismatch?.let { Notice(it.text, Modifier.testTag("library_mismatch"), it.action, actions.onRebuild) }
    page.work?.let { Work(it) }
    page.notice?.let { Notice(it, Modifier.testTag("library_notice"), "OK", actions.onDismissNotice) }
    Section("Documents") {
        if (page.documents.isEmpty()) {
            Notice("No documents yet.")
        } else {
            Documents(page.documents, actions.onRemove, removable = page.work == null && page.mismatch == null)
        }
    }
}

private const val INTRO =
    "Add text and Markdown files. Each is cut into passages and indexed here, so nothing leaves the phone. " +
        "PDF isn't supported yet."

@Composable
private fun EmbedderState(line: EmbedderLine, actions: LibraryActions) {
    when (line) {
        is EmbedderLine.Missing -> {
            Notice(
                "Indexing needs ${line.name}, a small model (${line.size}).",
                Modifier.testTag("library_get_embedder"),
                "Get",
                actions.onGetEmbedder,
            )
        }

        is EmbedderLine.Downloading -> {
            Work(WorkLine("Getting ${line.name}", line.progress))
        }

        is EmbedderLine.DoesNotFit -> {
            Notice(line.text, Modifier.testTag("library_no_fit"), failure = true)
        }

        EmbedderLine.Checking -> {
            Unit
        }

        is EmbedderLine.Ready -> {
            SectionNote("Indexed with ${line.name}.")
        }
    }
}

@Composable
private fun Work(work: WorkLine) {
    Stack(Gap.Inline, Modifier.testTag("library_work")) {
        Text(work.text, color = LocalDemoColors.current.ink, style = DemoType.bodyMedium)
        val progress = work.progress
        if (progress != null) ThinBar(progress, Modifier.fillMaxWidth()) else LoadingSlot(active = true)
    }
}

@Composable
private fun Documents(documents: List<DocumentRow>, onRemove: (String) -> Unit, removable: Boolean) {
    GroupedList(Modifier.testTag("library_documents")) {
        for (document in documents) {
            row(document.id) {
                ListRow(
                    document.title,
                    subtitle = document.detail,
                    end =
                        if (removable) {
                            { IconAction(DemoIcons.Close, "Remove ${document.title}", { onRemove(document.id) }) }
                        } else {
                            null
                        },
                )
            }
        }
    }
}
