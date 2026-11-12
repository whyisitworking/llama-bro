package com.suhel.llamabro.demo.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.DayLabel
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.HeldNote
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.Inline
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlocks
import com.suhel.llamabro.demo.designsystem.components.MarkdownText
import com.suhel.llamabro.demo.designsystem.components.ModelBubble
import com.suhel.llamabro.demo.designsystem.components.OpenReply
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.SwitchDivider
import com.suhel.llamabro.demo.designsystem.components.ThoughtFold
import com.suhel.llamabro.demo.designsystem.components.ThoughtState
import com.suhel.llamabro.demo.designsystem.components.UserBubble
import com.suhel.llamabro.demo.designsystem.testing.CountRecompositions
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What a message's long-press or action row can do; the screen supplies each. */
class MessageActions(
    val copy: (String) -> Unit,
    val copyCode: (String) -> Unit,
    val share: (position: Int) -> Unit,
    val regenerate: (position: Int) -> Unit,
    val edit: (position: Int) -> Unit,
    val openMenu: (position: Int, isReply: Boolean) -> Unit,
)

@Composable
fun MessageRow(item: ChatItem, style: MessageStyle, actions: MessageActions, showActions: Boolean) {
    when (item) {
        is DayItem -> DayLabel(item.label)
        is SwitchItem -> SwitchDivider(item.text)
        is UserItem -> UserRow(item, actions)
        is ReplyItem -> ReplyRow(item, style, actions, showActions)
    }
}

@Composable
private fun UserRow(item: UserItem, actions: MessageActions) {
    CountRecompositions("row-${item.key}")
    Stack(Gap.Inline, Modifier.fillMaxWidth().testTag("user_message")) {
        val images = rememberImages(item.images)
        UserBubble(
            text = item.text,
            images = images,
            time = item.time,
            onLongClick = { actions.openMenu(item.position, false) },
        )
        if (item.heldNote != null) HeldNote(item.heldNote)
    }
}

@Composable
private fun ReplyRow(item: ReplyItem, style: MessageStyle, actions: MessageActions, showActions: Boolean) {
    CountRecompositions("row-${item.key}")
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Stack(Gap.Block, Modifier.fillMaxWidth().testTag("reply_message")) {
        if (item.reasoning != null) {
            ThoughtFold(ThoughtState(item.thoughtSeconds, expanded, item.reasoning), { expanded = !expanded })
        }
        ReplyBody(item.blocks, item.failure, item.stopped, item.speed, item.time, style, actions.copyCode) {
            actions.openMenu(item.position, true)
        }
        if (showActions && item.failure == null) {
            Inline(Gap.None, Modifier.fillMaxWidth()) {
                IconAction(DemoIcons.Copy, "Copy reply", { actions.copy(item.text) }, muted = true)
                IconAction(DemoIcons.Share, "Share reply", { actions.share(item.position) }, muted = true)
                IconAction(
                    DemoIcons.Regenerate,
                    "Regenerate reply",
                    { actions.regenerate(item.position) },
                    muted = true,
                )
            }
        }
    }
}

/** The reply being written: its own row, so a token redraws this and nothing above it. */
@Composable
fun StreamingRow(item: StreamingItem, style: MessageStyle, actions: MessageActions) {
    CountRecompositions("row-streaming")
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Stack(Gap.Block, Modifier.fillMaxWidth().testTag("streaming_reply")) {
        if (item.reasoning != null) {
            ThoughtFold(ThoughtState(if (item.thinking) null else item.thoughtSeconds, expanded, item.reasoning), {
                expanded =
                    !expanded
            })
        }
        if (item.text.isNotEmpty()) ReplyBody(item.blocks, null, false, null, null, style, actions.copyCode, null)
    }
}

@Composable
private fun ReplyBody(
    blocks: MarkdownBlocks,
    failure: String?,
    stopped: Boolean,
    speed: String?,
    time: String?,
    style: MessageStyle,
    onCopyCode: (String) -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val colors = LocalDemoColors.current
    val content: @Composable () -> Unit = {
        Stack(Gap.Block) {
            if (blocks.blocks.isNotEmpty()) MarkdownText(blocks, onCopyCode)
            if (stopped) Text("Stopped", color = colors.muted, style = DemoType.bodySmall)
            if (failure != null) Text(failure, color = colors.muted, style = DemoType.bodyMedium)
        }
    }
    when (style) {
        MessageStyle.Open -> OpenReply(content, speed = speed, onLongClick = onLongClick)
        MessageStyle.Bubbles -> ModelBubble(content, time = time ?: "", speed = speed, onLongClick = onLongClick)
    }
}

/** Decodes attached pictures at display size, off the main thread (a 168 x 126 dp thumbnail needs no more). */
@Composable
private fun rememberImages(files: List<File>): List<ImageBitmap> {
    val bitmaps by produceState(emptyList<ImageBitmap>(), files) {
        value =
            withContext(Dispatchers.IO) {
                files.mapNotNull { f ->
                    runCatching {
                        val options =
                            BitmapFactory
                                .Options()
                                .apply { inSampleSize = 2 }
                        BitmapFactory
                            .decodeFile(f.path, options)
                            ?.asImageBitmap()
                    }.getOrNull()
                }
            }
    }
    return bitmaps
}
