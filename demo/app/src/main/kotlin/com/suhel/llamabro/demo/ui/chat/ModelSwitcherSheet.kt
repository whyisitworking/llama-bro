package com.suhel.llamabro.demo.ui.chat

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.suhel.llamabro.demo.chat.InMemory
import com.suhel.llamabro.demo.designsystem.components.DemoDialog
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.ModelRow
import com.suhel.llamabro.demo.designsystem.components.ScrollStack
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.Fits
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.ui.models.ModelEntry
import com.suhel.llamabro.demo.ui.models.capabilities
import com.suhel.llamabro.demo.ui.models.level
import com.suhel.llamabro.demo.ui.models.text
import com.suhel.llamabro.demo.ui.settings.InMemoryList
import com.suhel.llamabro.hub.HfModel

/**
 * "On this phone": the library as the one model row (models-page design §7), its state word "Current" on the model
 * this chat uses and "Default" on the one new chats use, the fit as a symbol; tap a row to switch, long-press it to use
 * it for new chats, its info action (under the fit symbol) to open its card; "Get more models" at the bottom.
 */
@Composable
fun ModelSwitcherSheet(
    library: List<LibraryModel>,
    current: HfModel?,
    facts: DeviceFacts,
    onSwitch: (HfModel) -> Unit,
    onUseForNewChats: (HfModel) -> Unit,
    onInfo: (HfModel) -> Unit,
    onGetMore: () -> Unit,
    onDismiss: () -> Unit,
    memory: SheetMemory = SheetMemory(),
    sampling: SheetSampling? = null,
) {
    DemoSheet(onDismissRequest = onDismiss) {
        ModelSwitcherContent(
            library,
            current,
            facts,
            onSwitch,
            onUseForNewChats,
            onInfo,
            onGetMore,
            onDismiss,
            memory,
            sampling,
        )
    }
}

/**
 * The chat's sampling row (D60): how many values the chat changed, opening its sheet. A chat with no message yet has
 * no row: its values would have no chat to belong to.
 */
data class SheetSampling(val changed: Int, val onOpen: () -> Unit)

/** The model in memory for the sheet's "In memory" row, and Unload (D59); nothing loaded shows no row. */
data class SheetMemory(val inMemory: InMemory? = null, val replying: Boolean = false, val onUnload: () -> Unit = {})

/** The sheet's contents, without the window. */
@Composable
fun ColumnScope.ModelSwitcherContent(
    library: List<LibraryModel>,
    current: HfModel?,
    facts: DeviceFacts,
    onSwitch: (HfModel) -> Unit,
    onUseForNewChats: (HfModel) -> Unit,
    onInfo: (HfModel) -> Unit,
    onGetMore: () -> Unit,
    onDone: () -> Unit,
    memory: SheetMemory = SheetMemory(),
    sampling: SheetSampling? = null,
) {
    var pressed by remember { mutableStateOf<HfModel?>(null) }
    Section("On this phone") {
        pressed?.let { key ->
            // a long-press offers what it does, in words, before it does it
            GroupedList {
                row {
                    ListRow("Use for new chats", onClick = {
                        onUseForNewChats(key)
                        pressed = null
                    })
                }
            }
        }
        ScrollStack(Gap.Block, Modifier.testTag("switcher")) {
            for (model in library) {
                SwitcherRow(
                    model,
                    current = model.key == current,
                    // the one fit formula the page's row and the card use
                    fit = Fits.quick(model, facts).label,
                    onClick = {
                        if (model.key != current) onSwitch(model.key) // the same model: nothing to switch
                        onDone()
                    },
                    onLongClick = { pressed = model.key },
                    onInfo = { onInfo(model.key) },
                )
            }
        }
    }
    sampling?.let {
        Section("This chat") {
            GroupedList(Modifier.testTag("chat_sampling_row")) {
                row {
                    ListRow(
                        "Inference",
                        subtitle =
                            when (it.changed) {
                                0 -> "The model's values"
                                1 -> "1 value changed for this chat"
                                else -> "${it.changed} values changed for this chat"
                            },
                        chevron = true,
                        onClick = it.onOpen,
                    )
                }
            }
        }
    }
    memory.inMemory?.let { Section("In memory") { InMemoryList(it, memory.replying, memory.onUnload) } }
    GroupedList {
        row {
            ListRow("Get more models", leading = DemoIcons.Plus, chevron = true, onClick = {
                onGetMore()
                onDone()
            })
        }
    }
}

/**
 * One model of the switcher as the one model row: the Models page's lines (maker and size, capabilities), "Current" for
 * this chat's model over "Default" as line 2's small mark, the fit as its symbol only (its words are in the card).
 */
@Composable
private fun SwitcherRow(
    model: LibraryModel,
    current: Boolean,
    fit: FitLabel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onInfo: () -> Unit,
) {
    val entry = ModelEntry.of(model)
    ModelRow(
        name = entry.name,
        fit = fit.level(),
        fitDescription = fit.text(),
        maker = entry.maker,
        size = entry.size?.text(),
        state =
            when {
                current -> "Current"
                model.isDefault -> "Default"
                else -> null
            },
        capabilities = capabilities(entry),
        // no "Used today" (D123): the less text, the better
        fact = null,
        onClick = onClick,
        onLongClick = onLongClick,
        onInfo = onInfo,
    )
}

/**
 * The switcher's question when the chat would move off the model being served (docs/design/server.md §3): the
 * switch would wait for serving to end, so the person chooses. Stop serving ends it and switches; Cancel keeps both
 * as they are.
 */
@Composable
fun StopServingDialog(served: String, other: String, onStop: () -> Unit, onCancel: () -> Unit) {
    DemoDialog(
        stopServingTitle(served, other),
        STOP_SERVING_TEXT,
        confirmLabel = "Stop serving",
        onConfirm = onStop,
        onDismiss = onCancel,
        dismissLabel = "Cancel",
    )
}

internal fun stopServingTitle(served: String, other: String) = "Stop serving $served to use $other?"

internal const val STOP_SERVING_TEXT = "Computers on the Wi-Fi using it lose their connection."
