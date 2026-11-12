package com.suhel.llamabro.demo.ui.models

import android.os.StatFs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhel.llamabro.demo.AppContainer
import com.suhel.llamabro.demo.chat.Limits
import com.suhel.llamabro.demo.designsystem.components.DemoDialog
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DownloadRow
import com.suhel.llamabro.demo.designsystem.components.EmptyLine
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.LibraryRow
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.Screen
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.StorageBar
import com.suhel.llamabro.demo.designsystem.components.SwipeToRemove
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.testing.CountRecompositions
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.DemoScreenPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame
import com.suhel.llamabro.hub.HfModel

/**
 * The library: the stateful end. A download row asks the view model for its own status, so a tick recomposes that row
 * only.
 */
@Composable
fun LibraryRoute(container: AppContainer, onBack: () -> Unit, onGetMore: () -> Unit) {
    val viewModel =
        viewModel {
            LibraryViewModel(
                models = container.models,
                freeBytes = { StatFs(container.app.filesDir.path).availableBytes },
                clock = container.clock,
            )
        }
    val models by viewModel.rows.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val pendingRemove by viewModel.pendingRemove.collectAsStateWithLifecycle()

    LibraryScreen(
        storage = storage,
        models = models,
        downloads = downloads,
        pendingRemove = pendingRemove,
        lastUsed = viewModel::lastUsed,
        onAskRemove = viewModel::askRemove,
        onConfirmRemove = viewModel::confirmRemove,
        onDismissRemove = viewModel::dismissRemove,
        onGetMore = onGetMore,
        onBack = onBack,
    ) { key ->
        val status by viewModel.state(key).collectAsStateWithLifecycle()
        DownloadItem(key, status, onPause = {
            viewModel.pause(key)
        }, onResume = { viewModel.resume(key) }, onCancel = { viewModel.cancel(key) })
    }
}

/**
 * The screen, stateless: storage, the models on the phone (swipe to remove, which asks first), the downloads under way
 * and a way to get more. [downloadItem] draws one download from its own flow.
 */
@Composable
fun LibraryScreen(
    storage: StorageInfo?,
    models: List<LibraryModel>?,
    downloads: List<HfModel>,
    pendingRemove: LibraryModel?,
    lastUsed: (LibraryModel) -> String,
    onAskRemove: (LibraryModel) -> Unit,
    onConfirmRemove: () -> Unit,
    onDismissRemove: () -> Unit,
    onGetMore: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    downloadItem: @Composable (HfModel) -> Unit,
) {
    Screen(
        topBar = {
            TopBar(title = { TopBarTitle("Models") }, start = { IconAction(DemoIcons.Back, "Back", onClick = onBack) })
        },
        modifier = modifier.testTag("library").statusBarsPadding(),
    ) {
        if (models == null) return@Screen
        if (storage != null) StorageSummary(storage)
        if (models.isEmpty() && downloads.isEmpty()) EmptyLibrary()
        if (models.isNotEmpty()) {
            Section("On this phone") {
                for (model in models) {
                    key(
                        "model:${model.key}",
                    ) { LibraryModelItem(model, lastUsed(model), onRemove = { onAskRemove(model) }) }
                }
            }
        }
        if (downloads.isNotEmpty()) {
            Section("Downloading") {
                for (download in downloads) key("download:$download") { downloadItem(download) }
            }
        }
        GroupedList {
            row { ListRow("Get more models", leading = DemoIcons.Plus, chevron = true, onClick = onGetMore) }
        }
    }
    pendingRemove?.let { RemoveDialog(it, onConfirmRemove, onDismissRemove) }
}

/** "6.1 GB used · 120 GB free" over a bar of how much of the phone the models take. */
@Composable
private fun StorageSummary(storage: StorageInfo) {
    Stack(Gap.Block, Modifier.testTag("storage")) {
        Text(
            "${Format.storageGb(storage.usedBytes)} used · ${Format.storageGb(storage.freeBytes)} free",
            color = LocalDemoColors.current.muted,
            style = DemoType.bodyMedium,
        )
        StorageBar(storage.usedBytes, storage.usedBytes + storage.freeBytes)
    }
}

@Composable
private fun EmptyLibrary() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { EmptyLine("No models yet") }
}

/** A model on the phone, swipeable to remove: the name, the real name and size, when it was last used. */
@Composable
private fun LibraryModelItem(
    model: LibraryModel,
    lastUsed: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SwipeToRemove(label = "Remove", onRemove = onRemove, modifier = modifier.testTag("library_model")) {
        LibraryRow(
            title = model.name,
            subtitle = Format.sizeInGb(model.sizeBytes),
            trailing = lastUsed,
            defaultLabel = if (model.isDefault) "Default" else null,
        )
    }
}

/** A download in the library: Pause or Resume, ✕, the bar, how much and how long. */
@Composable
fun DownloadItem(
    key: HfModel,
    status: DownloadStatus,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    CountRecompositions("download-$key")
    val title = Catalog.nameOf(key)
    val (fraction, detail, timeLeft) =
        when (status) {
            is DownloadStatus.Downloading -> {
                Triple(
                    status.fraction(),
                    Format.progress(status.bytesDone, status.bytesTotal, status.bytesPerSecond, null),
                    status.etaSeconds?.let(Format::timeLeft).orEmpty(),
                )
            }

            is DownloadStatus.Partial -> {
                Triple(
                    status.bytesDone.toFloat() / status.bytesTotal.coerceAtLeast(1),
                    Format.progress(status.bytesDone, status.bytesTotal, null, null),
                    "Paused",
                )
            }

            is DownloadStatus.Failed -> {
                Triple(0f, Limits.forDownload(status).line, "")
            }

            DownloadStatus.Absent, DownloadStatus.Complete -> {
                Triple(0f, "Waiting to start", "")
            }
        }
    val running = status is DownloadStatus.Downloading || status == DownloadStatus.Absent
    DownloadRow(
        title = title,
        fraction = fraction,
        detail = detail,
        timeLeft = timeLeft,
        actionLabel = if (running) "Pause" else "Resume",
        onAction = if (running) onPause else onResume,
        cancelLabel = "Cancel",
        onCancel = onCancel,
        modifier = Modifier.testTag("download_row"),
    )
}

/** Asks first: the model named, and what removing it frees. */
@Composable
private fun RemoveDialog(model: LibraryModel, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DemoDialog(
        title = "Remove ${model.name}?",
        text = "This frees ${Format.sizeInGb(model.sizeBytes)}. You can get it again from Hugging Face.",
        confirmLabel = "Remove",
        onConfirm = onConfirm,
        dismissLabel = "Keep",
        onDismiss = onDismiss,
    )
}
