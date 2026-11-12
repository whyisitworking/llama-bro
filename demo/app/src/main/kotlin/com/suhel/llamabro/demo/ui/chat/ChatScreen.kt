package com.suhel.llamabro.demo.ui.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhel.llamabro.demo.AppContainer
import com.suhel.llamabro.demo.chat.LimitAction
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.ui.models.ModelRow
import com.suhel.llamabro.demo.ui.models.ModelSheetRoute
import com.suhel.llamabro.demo.ui.shell.ChatChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sidebar writes replies on the page; Chat list puts them in bubbles (demo design §4.2). */
fun AppLayout.messageStyle(): MessageStyle = if (this == AppLayout.Sidebar) MessageStyle.Open else MessageStyle.Bubbles

private enum class Sheet { Switcher, Think, Smaller }

private data class MenuTarget(val position: Int, val isReply: Boolean)

/**
 * The conversation, stateful: it opens the chat's engine handle in a view model, turns the view model's flows into
 * [ChatContent]'s state, and owns what is platform (the clipboard, the photo picker) and what is passing (a sheet, a
 * menu, a share card). [chatId] 0 is a new chat, created with its first message.
 */
@Composable
fun ChatScreen(
    container: AppContainer,
    chatId: Long,
    chrome: ChatChrome,
    modifier: Modifier = Modifier,
    style: MessageStyle = chrome.layout.messageStyle(),
) {
    val context = LocalContext.current
    val vm: ChatViewModel = viewModel { chatViewModel(container, chatId, style, context) }
    LaunchedEffect(style) { vm.setStyle(style) }
    LaunchedEffect(vm) { vm.handle.chatId.collect { if (it != 0L) chrome.onChatResolved(it) } }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val streaming = vm.streaming.collectAsStateWithLifecycle()
    val draft = vm.composer.collectAsStateWithLifecycle()
    val composer = vm.composerState.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var shareRequest by remember { mutableStateOf<ShareCardContent?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::attach) }

    val messageActions =
        remember(vm) {
            MessageActions(
                copy = { copyToClipboard(context, it) },
                copyCode = { copyToClipboard(context, it) },
                share = { position ->
                    vm.shareText(position)?.let {
                        shareRequest =
                            ShareCardContent(
                                it,
                                vm.shareModelName(position) ?: vm.header.value.name,
                                vm.shareSpeed(position),
                            )
                    }
                },
                regenerate = vm::regenerate,
                edit = vm::startEdit,
                openMenu = { position, isReply -> menu = MenuTarget(position, isReply) },
            )
        }
    val actions =
        remember(vm, chrome, messageActions) {
            ChatScreenActions(
                onMenu = chrome.onMenu,
                onBack = chrome.onBack,
                onNewChat = chrome.onNewChat,
                onSwitcher = { sheet = Sheet.Switcher },
                onThink = { sheet = Sheet.Think },
                onStarter = vm::sendStarter,
                onText = vm::onText,
                onSend = vm::send,
                onStop = vm::stop,
                onAttach = {
                    try {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (_: ActivityNotFoundException) {
                        vm.photoPickerMissing() // a phone with no picker at all: say so, don't crash
                    }
                },
                onRemovePhoto = vm::removeAttachment,
                onCancelEdit = vm::cancelEdit,
                onLimitAction = { action ->
                    limitAction(action, vm, chrome, openSwitcher = { sheet = Sheet.Switcher }) { sheet = Sheet.Smaller }
                },
                message = messageActions,
            )
        }
    val live =
        remember(container) {
            ChatLive(
                streaming = { streaming.value },
                draft = { draft.value },
                composer = { composer.value },
                thumbnail = { name -> container.thumbnail(name) },
            )
        }

    ChatContent(state, live, actions, chrome.layout, style, modifier)

    when (sheet) {
        Sheet.Switcher -> SwitcherHost(container, vm, chrome, onDismiss = { sheet = null })
        Sheet.Think -> ThinkMenu(composer.value.think ?: ThinkLevel.Off, vm::setThink) { sheet = null }
        Sheet.Smaller -> SmallerHost(container, vm, onDismiss = { sheet = null })
        null -> Unit
    }
    menu?.let { target -> MessageMenu(target, state.rows, messageActions, onDismiss = { menu = null }) }
    ShareCardRenderer(shareRequest) { shareRequest = null }
}

private fun chatViewModel(container: AppContainer, chatId: Long, style: MessageStyle, context: Context): ChatViewModel {
    val resolver = context.applicationContext.contentResolver
    return ChatViewModel(
        container.engine.open(chatId),
        style,
        saveImage = { container.images.save(it, resolver) },
        discardImages = { container.images.delete(it) },
        clock = container.clock,
        titles = { id ->
            if (id ==
                0L
            ) {
                emptyFlow()
            } else {
                container.chats.chats().map { chats -> chats.firstOrNull { it.id == id }?.title }
            }
        },
    )
}

/** A draft picture, downscaled once more for its 64 dp preview (the saved one is at most 1024 px). */
private suspend fun AppContainer.thumbnail(name: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = 2 } // half of 1024 px is sharp for 64 dp
            BitmapFactory.decodeFile(images.file(name).path, options)?.asImageBitmap()
        }.getOrNull()
    }

/** What a limit line's button does. */
private fun limitAction(
    action: LimitAction,
    vm: ChatViewModel,
    chrome: ChatChrome,
    openSwitcher: () -> Unit,
    openSmaller: () -> Unit,
) {
    when (action) {
        LimitAction.TrySmallerSize -> openSmaller()
        LimitAction.SwitchModel, LimitAction.UseVisionModel -> openSwitcher()
        LimitAction.GetVisionModel -> chrome.onGetModels()
        LimitAction.AddToken -> chrome.onToken()
        LimitAction.StartNewChat -> chrome.onNewChat()
        LimitAction.CloseOtherAppsAndRetry, LimitAction.Retry -> vm.retry()
    }
}

/** "Smaller size" (beside "Try again"): this model's own sheet, on Smaller; Get starts it and the chat moves to it. */
@Composable
private fun SmallerHost(container: AppContainer, vm: ChatViewModel, onDismiss: () -> Unit) {
    val current by vm.handle.current.collectAsStateWithLifecycle()
    val name by vm.handle.name.collectAsStateWithLifecycle()
    val key = current ?: return
    ModelSheetRoute(
        container,
        ModelRow.ofKey(key, name),
        onStarted = vm.handle::switchModel,
        onDismiss = onDismiss,
        startTier = SizeTier.Smaller,
    )
}

@Composable
private fun SwitcherHost(container: AppContainer, vm: ChatViewModel, chrome: ChatChrome, onDismiss: () -> Unit) {
    val library by container.models.library.collectAsStateWithLifecycle(emptyList())
    val current by vm.handle.current.collectAsStateWithLifecycle()
    val facts by produceState(DeviceFacts.Fallback) { value = container.deviceFacts() }
    val scope = rememberCoroutineScope()
    ModelSwitcherSheet(
        library = library,
        current = current,
        facts = facts,
        onSwitch = vm.handle::switchModel,
        onUseForNewChats = { key -> scope.launch { container.settings.pinDefault(key.toString()) } },
        onGetMore = chrome.onGetModels,
        onDismiss = onDismiss,
    )
}

/** The long-press menu of a message: its text is looked up in [rows], and the menu closes if the message is gone. */
@Composable
private fun MessageMenu(target: MenuTarget, rows: List<ChatItem>, actions: MessageActions, onDismiss: () -> Unit) {
    val text = rows.firstNotNullOfOrNull { messageText(it, target) }
    if (text == null) {
        LaunchedEffect(target) { onDismiss() } // the message is gone (a regenerate, an edit): close the menu
        return
    }
    DemoSheet(onDismissRequest = onDismiss) {
        MessageMenuContent(text, target.position, target.isReply, actions, onDismiss)
    }
}

private fun messageText(item: ChatItem, target: MenuTarget): String? =
    when {
        item is UserItem && !target.isReply && item.position == target.position -> item.text
        item is ReplyItem && target.isReply && item.position == target.position -> item.text
        else -> null
    }

/** A reply can be copied, shared and regenerated; a question can be copied and edited. */
@Composable
fun MessageMenuContent(
    text: String,
    position: Int,
    isReply: Boolean,
    actions: MessageActions,
    onDone: () -> Unit,
) {
    GroupedList(Modifier.testTag("message_menu")) {
        row { ListRow("Copy", leading = DemoIcons.Copy, onClick = { actions.copy(text).also { onDone() } }) }
        if (isReply) {
            row { ListRow("Share", leading = DemoIcons.Share, onClick = { actions.share(position).also { onDone() } }) }
            row {
                ListRow(
                    "Regenerate",
                    leading = DemoIcons.Regenerate,
                    onClick = { actions.regenerate(position).also { onDone() } },
                )
            }
        } else {
            row { ListRow("Edit", leading = DemoIcons.Edit, onClick = { actions.edit(position).also { onDone() } }) }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Llama Bro", text))
}
