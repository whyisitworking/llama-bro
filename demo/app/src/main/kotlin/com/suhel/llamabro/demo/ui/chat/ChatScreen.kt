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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhel.llamabro.demo.AppContainer
import com.suhel.llamabro.demo.chat.LimitAction
import com.suhel.llamabro.demo.chat.Thinking
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.ui.models.ModelRow
import com.suhel.llamabro.demo.ui.models.ModelSheetRoute
import com.suhel.llamabro.demo.ui.shell.ChatChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sidebar writes replies on the page; Chat list puts them in bubbles (demo design §4.2). */
fun AppLayout.messageStyle(): MessageStyle = if (this == AppLayout.Sidebar) MessageStyle.Open else MessageStyle.Bubbles

private enum class Sheet { Switcher, Think, Smaller, Sampling }

private data class MenuTarget(val position: Int, val isReply: Boolean)

private val MenuTargetSaver: Saver<MenuTarget?, Any> =
    listSaver(
        save = { if (it == null) emptyList() else listOf(it.position, it.isReply) },
        restore = { if (it.isEmpty()) null else MenuTarget(it[0] as Int, it[1] as Boolean) },
    )

private val ShareRequestSaver: Saver<ShareCardContent?, Any> =
    listSaver(
        save = { if (it == null) emptyList() else listOf(it.text, it.modelName, it.speed) },
        restore = { if (it.isEmpty()) null else ShareCardContent(it[0] as String, it[1] as String, it[2]) },
    )

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
    val vm: ChatViewModel =
        viewModel {
            // after process death a new chat's route still says 0: its saved state holds the id its first message made
            val saved = createSavedStateHandle()
            chatViewModel(container, ChatViewModel.chatIdToOpen(saved, chatId), style, context, saved)
        }
    LaunchedEffect(style) { vm.setStyle(style) }
    LaunchedEffect(vm) { vm.handle.chatId.collect { if (it != 0L) chrome.onChatResolved(it) } }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val streaming = vm.streaming.collectAsStateWithLifecycle()
    // snapshot state, read where it is drawn: a keystroke reaches the field in the same frame
    val draft = vm.composer
    val composer = vm.composerState
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    // the menu and a share under way stay open across a rotation
    var menu by rememberSaveable(stateSaver = MenuTargetSaver) { mutableStateOf<MenuTarget?>(null) }
    var details by rememberSaveable { mutableStateOf<Int?>(null) }
    var photo by rememberSaveable(stateSaver = OpenPhotoSaver) { mutableStateOf<OpenPhoto?>(null) }
    var shareRequest by rememberSaveable(stateSaver = ShareRequestSaver) { mutableStateOf<ShareCardContent?>(null) }
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
                details = { position -> details = position },
                continueReply = vm.handle::continueReply,
                openPhoto = { position, index -> photo = OpenPhoto(position, index) },
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
                onDismissLimit = { vm.notes.close(vm.limit.value) },
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
        Sheet.Switcher -> {
            SwitcherHost(container, vm, chrome, onSampling = { sheet = Sheet.Sampling }, onDismiss = { sheet = null })
        }

        Sheet.Sampling -> {
            SamplingHost(container, vm, onDismiss = { sheet = null })
        }

        Sheet.Think -> {
            ThinkMenu(composer.value.think ?: ThinkLevel.Off, vm::setThink) { sheet = null }
        }

        Sheet.Smaller -> {
            SmallerHost(container, vm, onDismiss = { sheet = null })
        }

        null -> {
            Unit
        }
    }
    menu?.let { target -> MessageMenu(target, state.rows, messageActions, onDismiss = { menu = null }) }
    details?.let { position -> ReplyDetailsSheet(position, state.rows, onDismiss = { details = null }) }
    photo?.let { target ->
        PhotoHost(target, state.rows, onPageChange = { photo = target.copy(index = it) }, onClose = { photo = null })
    }
    ShareCardRenderer(shareRequest) { shareRequest = null }
}

private fun chatViewModel(
    container: AppContainer,
    chatId: Long,
    style: MessageStyle,
    context: Context,
    saved: SavedStateHandle,
): ChatViewModel {
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
        savedState = saved,
        background = container.appScope,
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
        LimitAction.OpenMemorySettings -> chrome.onMemory()
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
private fun SwitcherHost(
    container: AppContainer,
    vm: ChatViewModel,
    chrome: ChatChrome,
    onSampling: () -> Unit,
    onDismiss: () -> Unit,
) {
    val library by container.models.library.collectAsStateWithLifecycle(emptyList())
    val current by vm.handle.current.collectAsStateWithLifecycle()
    // the estimates follow the chip chosen in Settings › Memory (D72), which the other pane can change meanwhile
    val chip by remember(container) { container.settings.settings.map { it.chip } }.collectAsStateWithLifecycle(null)
    val facts by produceState(DeviceFacts.Fallback, chip) { value = container.deviceFacts() }
    val inMemory by container.memory.inMemory.collectAsStateWithLifecycle(null)
    val replying by container.memory.replying.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val chatId by vm.handle.chatId.collectAsStateWithLifecycle()
    val chatSampling by remember(chatId) {
        if (chatId == 0L) flowOf(null) else container.chats.sampling(chatId)
    }.collectAsStateWithLifecycle(null)
    ModelSwitcherSheet(
        library = library,
        current = current,
        facts = facts,
        onSwitch = vm.handle::switchModel,
        onUseForNewChats = { key -> scope.launch { container.settings.pinDefault(key.toString()) } },
        onGetMore = chrome.onGetModels,
        onDismiss = onDismiss,
        memory = SheetMemory(inMemory, replying) { scope.launch { container.memory.unload() } },
        sampling = chatSampling?.let { SheetSampling(it.values.size, onSampling) },
    )
}

/** The chat's sampling sheet, at the chat's Think level; it closes if the chat has no model or no row yet. */
@Composable
private fun SamplingHost(container: AppContainer, vm: ChatViewModel, onDismiss: () -> Unit) {
    val key by vm.handle.current.collectAsStateWithLifecycle()
    val chatId by vm.handle.chatId.collectAsStateWithLifecycle()
    val think by vm.handle.think.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val k = key
    if (k == null || chatId == 0L) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val layers by remember(k, chatId) { container.sampling.chat(k, chatId) }.collectAsStateWithLifecycle(null)
    layers?.let { l ->
        ChatSamplingSheet(
            l,
            Thinking.thinks(think),
            onChange = { knob, value -> scope.launch { container.sampling.setChat(chatId, knob, value) } },
            onReset = { scope.launch { container.sampling.resetChat(chatId) } },
            onDismiss = onDismiss,
        )
    }
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

/** A reply's details, looked up in [rows] by its position; the sheet closes if the reply is gone. */
@Composable
private fun ReplyDetailsSheet(position: Int, rows: List<ChatItem>, onDismiss: () -> Unit) {
    val reply = rows.firstOrNull { it is ReplyItem && it.position == position } as ReplyItem?
    if (reply == null || reply.details.isEmpty()) {
        LaunchedEffect(position) { onDismiss() }
        return
    }
    DemoSheet(onDismissRequest = onDismiss) { ReplyDetailsContent(reply.details) }
}

/** The details of one reply: what ran it and what was measured, one row each. */
@Composable
fun ReplyDetailsContent(details: List<ReplyDetail>) {
    Section("Reply details", Modifier.testTag("reply_details")) {
        GroupedList {
            details.forEach { d -> row { ListRow(d.label, trailing = d.value) } }
        }
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
