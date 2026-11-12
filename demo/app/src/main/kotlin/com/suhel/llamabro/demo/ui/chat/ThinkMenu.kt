package com.suhel.llamabro.demo.ui.chat

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SectionLabel
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing

/** The four ways to think (demo design §4.4): a sheet of rows, the current one ticked. */
@Composable
fun ThinkMenu(current: ThinkLevel, onPick: (ThinkLevel) -> Unit, onDismiss: () -> Unit) {
    DemoSheet(onDismissRequest = onDismiss) { ThinkMenuContent(current, onPick, onDismiss) }
}

/** The sheet's contents, without the window: a row per level, then closes. */
@Composable
fun ColumnScope.ThinkMenuContent(current: ThinkLevel, onPick: (ThinkLevel) -> Unit, onDone: () -> Unit) {
    Section("Think before answering") {
        GroupedList {
            for (level in ThinkLevel.entries) {
                row {
                    ListRow(
                        title = level.title(),
                        subtitle = level.hint(),
                        selected = level == current,
                        onClick = {
                            onPick(level)
                            onDone()
                        },
                    )
                }
            }
        }
    }
}

private fun ThinkLevel.title() =
    when (this) {
        ThinkLevel.Off -> "Off"
        ThinkLevel.Quick -> "Quick"
        ThinkLevel.Balanced -> "Balanced"
        ThinkLevel.Deep -> "Deep"
    }

private fun ThinkLevel.hint() =
    when (this) {
        ThinkLevel.Off -> "Answers straight away"
        ThinkLevel.Quick -> "A short think first"
        ThinkLevel.Balanced -> "Thinks it through"
        ThinkLevel.Deep -> "Takes as long as it needs"
    }
