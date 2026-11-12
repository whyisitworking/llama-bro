package com.suhel.llamabro.demo.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.data.ChatSummary
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.PrimaryButton
import com.suhel.llamabro.demo.designsystem.components.TextArea
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing

/** What a long-press on a chat offers (Rename, Delete), as the two sheets it opens. [chat] is the pressed chat. */
@Composable
fun ChatActionsSheets(
    chat: ChatSummary?,
    onDismiss: () -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    if (chat == null) return
    var renaming by rememberSaveable(chat.id) { mutableStateOf(false) }
    var title by rememberSaveable(chat.id) { mutableStateOf(chat.title) }
    DemoSheet(onDismissRequest = onDismiss) {
        if (renaming) {
            RenameContent(title, { title = it }, onSave = { onRename(chat.id, title).also { onDismiss() } })
        } else {
            ActionsContent(onRename = { renaming = true }, onDelete = { onDelete(chat.id).also { onDismiss() } })
        }
    }
}

/** The first sheet: Rename or Delete. */
@Composable
fun ColumnScope.ActionsContent(onRename: () -> Unit, onDelete: () -> Unit) {
    GroupedList {
        row { ListRow("Rename", leading = DemoIcons.Edit, onClick = onRename) }
        row { ListRow("Delete", leading = DemoIcons.Close, onClick = onDelete) }
    }
}

/** The second sheet: the chat's name in a field, and Save (not for a blank name). */
@Composable
fun ColumnScope.RenameContent(title: String, onTitle: (String) -> Unit, onSave: () -> Unit) {
    TextArea(title, onTitle, "Chat name", Modifier.fillMaxWidth(), minLines = 1)
    PrimaryButton("Save", onClick = onSave, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth())
}
