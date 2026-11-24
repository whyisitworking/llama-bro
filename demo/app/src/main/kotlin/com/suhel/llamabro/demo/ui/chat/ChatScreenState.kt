package com.suhel.llamabro.demo.ui.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import com.suhel.llamabro.demo.chat.Limit
import com.suhel.llamabro.demo.chat.LimitAction
import com.suhel.llamabro.demo.designsystem.components.ComposerState
import com.suhel.llamabro.demo.designsystem.components.ComposerVoiceActions

/**
 * The top bar: the model's name, the chat's own title (the chat list layout), whether the model is waking, and how far
 * its load has come (0 to 1; null while the SDK hasn't said, and the hairline sweeps).
 */
@Immutable
data class ChatBar(val header: ChatHeader, val title: String, val waking: Boolean, val wakeProgress: Float? = null)

/** Under the messages: whether the chat can be continued, a plain problem line, and the limit line with its button. */
@Immutable
data class ChatBottom(val readOnly: Boolean, val problem: String?, val limit: Limit?)

/**
 * Everything the chat screen draws apart from the reply being written and the message bar ([ChatLive]): one derived
 * value, so the screen is a function of it.
 */
@Immutable
data class ChatScreenState(val bar: ChatBar, val rows: List<ChatItem>, val busy: Boolean, val bottom: ChatBottom)

/**
 * What changes faster than the screen: a token, a keystroke. Each is read where it is drawn (inside the streaming row,
 * inside the message bar), so only that part recomposes. [thumbnail] decodes a draft picture for its small preview.
 */
@Stable
class ChatLive(
    val streaming: () -> StreamingItem?,
    val draft: () -> ComposerDraft,
    val composer: () -> ComposerState,
    val thumbnail: suspend (String) -> ImageBitmap?,
)

/** The screen's own doings: the top bar's, the message bar's, a limit line's button, and a message's. */
@Immutable
class ChatScreenActions(
    val onMenu: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onNewChat: () -> Unit = {},
    val onSwitcher: () -> Unit = {},
    val onThink: () -> Unit = {},
    val onStarter: (String) -> Unit = {},
    val onText: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onAttach: () -> Unit = {},
    val onRemovePhoto: (String) -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onLimitAction: (LimitAction) -> Unit = {},
    val onDismissLimit: () -> Unit = {},
    /** The message bar's voice controls (D76): record (after the microphone permission), discard, send. */
    val voice: ComposerVoiceActions = ComposerVoiceActions.None,
    val message: MessageActions = MessageActions({}, {}, {}, {}, {}, { _, _ -> }),
)
