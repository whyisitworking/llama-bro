package com.suhel.llamabro.demo.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.EdgeChip
import com.suhel.llamabro.demo.designsystem.components.FitText
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.Inline
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.RowCard
import com.suhel.llamabro.demo.designsystem.components.ScrollStack
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SectionLabel
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.Fit
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.ui.models.text
import com.suhel.llamabro.demo.ui.models.tint
import com.suhel.llamabro.hub.HfModel

/**
 * The quick fit of a model that is already on the phone: its file size against the memory budget, and the chip's speed.
 */
fun quickFit(sizeBytes: Long, facts: DeviceFacts): FitLabel =
    Fit.label(Fit.roomFromSize(sizeBytes, facts.budgetBytes), Fit.tokensPerSecond(sizeBytes, facts.chip))

/**
 * "On this phone": the library as rows tinted by fit, the default outlined with a "Default" chip on its edge, a check
 * on the model this chat uses; long-press a row to use it for new chats; "Get more models" at the bottom.
 */
@Composable
fun ModelSwitcherSheet(
    library: List<LibraryModel>,
    current: HfModel?,
    facts: DeviceFacts,
    onSwitch: (HfModel) -> Unit,
    onUseForNewChats: (HfModel) -> Unit,
    onGetMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    DemoSheet(onDismissRequest = onDismiss) {
        ModelSwitcherContent(library, current, facts, onSwitch, onUseForNewChats, onGetMore, onDismiss)
    }
}

/** The sheet's contents, without the window. */
@Composable
fun ColumnScope.ModelSwitcherContent(
    library: List<LibraryModel>,
    current: HfModel?,
    facts: DeviceFacts,
    onSwitch: (HfModel) -> Unit,
    onUseForNewChats: (HfModel) -> Unit,
    onGetMore: () -> Unit,
    onDone: () -> Unit,
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
                val fit = quickFit(model.sizeBytes, facts)
                RowCard(
                    selected = model.key == current,
                    onClick = {
                        if (model.key != current) onSwitch(model.key)
                        onDone()
                    }, // the same model: nothing to switch
                    onLongClick = { pressed = model.key },
                ) {
                    ListRow(
                        title = model.name,
                        subtitle = Format.sizeInGb(model.sizeBytes),
                        end = {
                            Inline(Gap.Block) {
                                if (model.isDefault) EdgeChip("Default")
                                FitText(fit.text(), fit.tint())
                            }
                        },
                    )
                }
            }
        }
    }
    GroupedList {
        row {
            ListRow("Get more models", leading = DemoIcons.Plus, chevron = true, onClick = {
                onGetMore()
                onDone()
            })
        }
    }
}
