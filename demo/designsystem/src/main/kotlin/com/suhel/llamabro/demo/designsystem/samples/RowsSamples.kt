package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.runtime.Composable
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.components.SliderRow
import com.suhel.llamabro.demo.designsystem.components.Stack
import java.util.Locale

/** Settings as a grouped list, an action as a group of one, a selectable list, sliders: every use of [ListRow]. */
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
                    row { ListRow("llama.cpp", trailing = "b12479") }
                }
            }
            GroupedList {
                row { ListRow("Get more models", leading = DemoIcons.Plus, chevron = true, onClick = {}) }
            }
            Section("Sampling") {
                GroupedList {
                    row {
                        SliderRow(
                            "Temperature",
                            1f,
                            { "%.2f".format(Locale.ROOT, it) },
                            0f..2f,
                            steps = 39,
                            onValueChange = {},
                            note = "Model default",
                        )
                    }
                    row {
                        SliderRow(
                            "Top K",
                            40f,
                            { it.toInt().toString() },
                            1f..100f,
                            steps = 98,
                            onValueChange = {},
                            note = "Changed for this chat",
                        )
                    }
                }
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
