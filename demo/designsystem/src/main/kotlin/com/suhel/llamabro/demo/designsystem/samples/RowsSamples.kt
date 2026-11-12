package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.runtime.Composable
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.components.Stack

/** Settings as a grouped list, an action as a group of one, a selectable list: every use of [ListRow]. */
@Composable
internal fun RowsSamples() {
    SampleFrame {
        Stack(Gap.Section) {
            Section("Settings") {
                GroupedList {
                    row { ListRow("Appearance", subtitle = "Layout and theme", chevron = true, onClick = {}) }
                    row {
                        ListRow(
                            "Default model",
                            subtitle = "Which model new chats start with",
                            chevron = true,
                            onClick = {},
                        )
                    }
                    row { ListRow("About", subtitle = "Versions and licences", chevron = true, onClick = {}) }
                }
            }
            Section("About") {
                GroupedList {
                    row { ListRow("Version", trailing = "1.0") }
                    row { ListRow("llama.cpp", trailing = "b12052") }
                }
            }
            GroupedList {
                row { ListRow("Get more models", leading = DemoIcons.Plus, chevron = true, onClick = {}) }
            }
        }
    }
}

/** A menu on a sheet: the group is the canvas on the sheet's surface, a check on the chosen row, no dead space. */
@Composable
internal fun RowsSheetSamples() {
    SampleFrame {
        SheetFrame {
            Section("Think before answering") {
                GroupedList {
                    row { ListRow("Off", subtitle = "Answers straight away", selected = true, onClick = {}) }
                    row { ListRow("Quick", subtitle = "A short think first", onClick = {}) }
                    row { ListRow("Deep", subtitle = "Takes as long as it needs", onClick = {}) }
                }
            }
        }
    }
}
