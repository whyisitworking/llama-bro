package com.suhel.llamabro.demo.ui.chat

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SwitchRow

/**
 * What the message bar's **+** opens once the library has a document (docs/design/rag.md): a photo, and "Use my
 * library", saved with the chat. With no document the **+** opens the photo picker straight away, as before.
 */
@Composable
fun AttachMenu(useLibrary: Boolean, onPhoto: () -> Unit, onUseLibrary: (Boolean) -> Unit, onDismiss: () -> Unit) {
    DemoSheet(onDismissRequest = onDismiss) { AttachMenuContent(useLibrary, onPhoto, onUseLibrary, onDismiss) }
}

/** The sheet's contents, without the window: a photo (which closes it), and the library's switch (which doesn't). */
@Composable
fun ColumnScope.AttachMenuContent(
    useLibrary: Boolean,
    onPhoto: () -> Unit,
    onUseLibrary: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    Section("Add to your message", Modifier.testTag("attach_menu")) {
        GroupedList {
            row {
                ListRow(
                    title = "Photo",
                    leading = DemoIcons.Image,
                    onClick = {
                        onDone()
                        onPhoto()
                    },
                )
            }
            row {
                SwitchRow(
                    title = "Use my library",
                    checked = useLibrary,
                    onCheckedChange = onUseLibrary,
                    modifier = Modifier.testTag("use_library"),
                    subtitle = "Answers from your documents, with their sources",
                )
            }
        }
    }
}
