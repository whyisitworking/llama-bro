package com.suhel.llamabro.demo.ui.models

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.suhel.llamabro.demo.designsystem.components.DemoDialog
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DownloadPanel
import com.suhel.llamabro.demo.designsystem.components.FitLevel
import com.suhel.llamabro.demo.designsystem.components.ModelRow
import com.suhel.llamabro.demo.designsystem.testing.CountRecompositions
import com.suhel.llamabro.demo.models.Capability
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.Names

// The Models page's parts that are not the page: one model as a row, its words, and the dialogs the page asks with.

/** Every row of the page is tagged so: suggestions, results, downloads, models on the phone. */
internal const val MODEL_ROW = "model_row"

/**
 * A model as the page's one row (docs/design/models-page.md §3, D123): [ModelEntry] mapped to [ModelRow]'s typed
 * slots. The fit as a symbol only (its words are taught in "What's a model?"); line 2 the maker and [size], the whole
 * download, the capabilities as icons, a Hugging Face result's downloads ([fact]) and the state's small mark; and while
 * its repo downloads ([state]'s view), the compact ring panel, there in every download stage so the row keeps one
 * height.
 */
@Composable
internal fun EntryRow(
    entry: ModelEntry,
    fit: FitView?,
    size: SizeText?,
    state: RowState?,
    fact: String?,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // counted by model, as the tests that watch one row's ticks name it
    CountRecompositions("model-${entry.groupKey}")
    val download = state?.download
    ModelRow(
        name = entry.name,
        fit = if (entry.canChat) fit?.label?.level() else FitLevel.CantChat,
        fitDescription = if (entry.canChat) fit?.label?.text().orEmpty() else CANT_CHAT,
        // line 2 never renders empty: with no maker and no size known, the repo's owner,
        // through the one maker map
        maker = entry.maker ?: Names.makerOf(entry.repo, entry.repo.substringBefore('/')).takeIf { size == null },
        size = size?.text(),
        state = stateWord(entry, state),
        capabilities = capabilities(entry),
        fact = fact,
        modifier = modifier.testTag(MODEL_ROW),
        onClick = onClick,
        download = download?.let { view -> { DownloadPanel(view, onToggle, onCancel, compact = true) } },
    )
}

/** "3.4 GB", or "about 3.4 GB" for a size guessed from the parameter count (models-page design §7). */
internal fun SizeText.text(): String =
    when (this) {
        is SizeText.Exact -> Format.sizeInGb(bytes)
        is SizeText.About -> "about ${Format.sizeInGb(bytes)}"
    }

/**
 * The state's small mark on line 2 (§3). A model on the phone says only whether new chats use it ("Default"): under
 * "On this phone" an "On phone" pill repeats the label. While a download's footer is there the
 * footer alone says where it stands ("Downloading · Q4_K_M"), so there is no pill to repeat it (page 5). Any other row
 * says its repo has a file here ("On phone"), if it has.
 */
internal fun stateWord(entry: ModelEntry, state: RowState?): String? =
    when {
        entry.onPhone -> "Default".takeIf { entry.isDefault }
        state?.download != null -> null
        state?.badge == RowBadge.OnPhone -> "On phone"
        else -> null
    }

/**
 * Line 2's quiet last item: a Hugging Face result's downloads in words ("1.2M downloads"); nothing on any other row,
 * whose maker and size say enough (D123: the less text, the better).
 */
internal fun factOf(entry: ModelEntry, fromHub: Boolean): String? =
    entry.downloads?.takeIf { fromHub && !entry.onPhone }?.let { "${Format.downloads(it)} downloads" }

/**
 * What the row can do, as icons in the one fixed order, each with its words. A model that can't chat shows none: its
 * fit symbol already says so, and an embeddings glyph beside it said it twice.
 */
internal fun capabilities(row: ModelEntry): List<Pair<ImageVector, String>> =
    if (!row.canChat) {
        emptyList()
    } else {
        Capability.entries.filter { it in row.capabilities }.map { it.icon() to it.words() }
    }

internal fun Capability.icon(): ImageVector =
    when (this) {
        Capability.Thinks -> DemoIcons.CapabilityThinks
        Capability.Talks -> DemoIcons.CapabilityTalks
        Capability.SeesImages -> DemoIcons.CapabilityImages
        Capability.Coding -> DemoIcons.CapabilityCode
        Capability.Embeddings -> DemoIcons.CapabilityEmbeddings
    }

/** What a screen reader says for each icon; the screen shows the icon alone (D123). */
internal fun Capability.words(): String =
    when (this) {
        Capability.Thinks -> "Thinks"
        Capability.Talks -> "Talks"
        Capability.SeesImages -> "Sees images"
        Capability.Coding -> "Writes code"
        Capability.Embeddings -> "Embeddings"
    }

internal const val CANT_CHAT = "Can't chat"

/** A model on the phone as the remove dialog names it: its name and what removing it frees. */
internal fun ModelEntry.libraryModel(): LibraryModel? =
    key?.let {
        val total = size?.bytes ?: 0L
        val model = size?.modelBytes ?: total
        LibraryModel(it, name, model, lastUsed, isDefault, readerBytes = total - model)
    }

internal fun DownloadStatus.Downloading.fraction(): Float = bytesDone.toFloat() / bytesTotal.coerceAtLeast(1)

/** Asks first: the model named, and what removing it frees. Remove deletes, so it is in danger (§5, "Buttons"). */
@Composable
internal fun RemoveDialog(model: LibraryModel, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DemoDialog(
        title = "Remove ${keepSizeWithName(model.name)}?",
        text = "This frees ${Format.sizeInGb(model.totalBytes)}. You can get it again from Hugging Face.",
        confirmLabel = "Remove",
        onConfirm = onConfirm,
        dismissLabel = "Keep",
        onDismiss = onDismiss,
        danger = true,
    )
}

/**
 * A swipe on the model being served (docs/design/server.md §3): the delete would wait for serving to end, so this asks
 * to stop serving first, as the chat's switch does. The confirm stops serving and deletes, so it is in danger.
 */
@Composable
internal fun StopServingToRemoveDialog(model: LibraryModel, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DemoDialog(
        title = "Stop serving ${keepSizeWithName(model.name)} to delete it?",
        text =
            "Computers on the Wi-Fi using it lose their connection. Deleting it frees " +
                "${Format.sizeInGb(model.totalBytes)}.",
        confirmLabel = "Stop and delete",
        onConfirm = onConfirm,
        dismissLabel = "Keep",
        onDismiss = onDismiss,
        danger = true,
    )
}

/**
 * [name] with a no-break space before its last word, so a title never breaks the name from its size ("Remove
 * Qwen3.5 / 4B?" at 2x): the line breaks before the name instead.
 */
internal fun keepSizeWithName(name: String): String {
    val at = name.lastIndexOf(' ')
    return if (at <= 0) name else name.substring(0, at) + NO_BREAK_SPACE + name.substring(at + 1)
}

private const val NO_BREAK_SPACE = '\u00A0'

/** A row's ✕ past 300 MB arrived asks first, as the card's Cancel does: the confirm deletes, so it is in danger. */
@Composable
internal fun CancelDialog(question: CancelQuestion, onConfirm: () -> Unit, onKeep: () -> Unit) {
    DemoDialog(
        title = "Cancel the download?",
        text = question.text,
        confirmLabel = "Cancel download",
        onConfirm = onConfirm,
        dismissLabel = "Keep",
        onDismiss = onKeep,
        danger = true,
    )
}
