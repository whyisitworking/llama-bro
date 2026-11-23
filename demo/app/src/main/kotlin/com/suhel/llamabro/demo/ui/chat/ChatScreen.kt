package com.suhel.llamabro.demo.ui.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhel.llamabro.demo.AppContainer
import com.suhel.llamabro.demo.chat.Dictation
import com.suhel.llamabro.demo.chat.LimitAction
import com.suhel.llamabro.demo.chat.MediaVoicePlayer
import com.suhel.llamabro.demo.chat.Thinking
import com.suhel.llamabro.demo.chat.VoiceConversation
import com.suhel.llamabro.demo.chat.VoiceRecorder
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.designsystem.components.VoiceActions
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.ui.models.ModelEntry
import com.suhel.llamabro.demo.ui.models.ModelSheetRoute
import com.suhel.llamabro.demo.ui.shell.ChatChrome
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
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
    /** A fixed layout for the rows (screenshots); null follows the layout setting. */
    styleOverride: MessageStyle? = null,
) {
    val style = styleOverride ?: chrome.layout.messageStyle()
    val context = LocalContext.current
    val vm: ChatViewModel =
        viewModel {
            // after process death a new chat's route still says 0: its saved state holds the id its first message made
            val saved = createSavedStateHandle()
            // a new chat opened on a model (its card's "Chat with") starts on it; the chat is made at its first message
            chatViewModel(
                container,
                ChatViewModel.chatIdToOpen(saved, chatId),
                styleOverride?.let(::flowOf)
                    ?: container.settings.settings.map { it.layout.messageStyle() },
                context,
                saved,
                chrome.startModel,
            )
        }
    LaunchedEffect(vm) { vm.handle.chatId.collect { if (it != 0L) chrome.onChatResolved(it) } }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val streaming = vm.streaming.collectAsStateWithLifecycle()
    // snapshot state, read where it is drawn: a keystroke reaches the field in the same frame
    val draft = vm.composer
    val composer = vm.composerState
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }

    // the model whose card the switcher's info action opened, by its key
    var card by rememberSaveable { mutableStateOf<String?>(null) }
    // the menu and a share under way stay open across a rotation
    var menu by rememberSaveable(stateSaver = MenuTargetSaver) { mutableStateOf<MenuTarget?>(null) }
    var details by rememberSaveable { mutableStateOf<Int?>(null) }
    var photo by rememberSaveable(stateSaver = OpenPhotoSaver) { mutableStateOf<OpenPhoto?>(null) }
    var shareRequest by rememberSaveable(stateSaver = ShareRequestSaver) { mutableStateOf<ShareCardContent?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::attach) }
    val voice = rememberVoiceActions(vm.voice, vm.dictation, vm.voiceMode::open)
    val playing = vm.player.clip.collectAsStateWithLifecycle()

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
                toggleVoice = vm.player::toggle,
                playing = { playing.value },
            )
        }
    val actions =
        remember(vm, chrome, messageActions, voice) {
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
                    limitAction(action, vm, chrome, context, openSwitcher = { sheet = Sheet.Switcher }) {
                        sheet = Sheet.Smaller
                    }
                },
                voice = voice,
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

    ChatSheets(container, vm, chrome, sheet, { sheet = it }, card, { card = it })
    menu?.let { target -> MessageMenu(target, state.rows, messageActions, onDismiss = { menu = null }) }
    details?.let { position -> ReplyDetailsSheet(position, state.rows, onDismiss = { details = null }) }
    photo?.let { target ->
        PhotoHost(target, state.rows, onPageChange = { photo = target.copy(index = it) }, onClose = { photo = null })
    }
    VoiceOverlay(vm, state.bar.header.name) { action -> limitAction(action, vm, chrome, context, {}, {}) }
    ShareCardRenderer(shareRequest) { shareRequest = null }
}

/** The chat's sheets: one of [sheet] at a time, and the card the switcher's info action opened ([card], by key). */
@Composable
@Suppress("LongParameterList") // the two sheet states and their setters, kept by the screen across rotation
private fun ChatSheets(
    container: AppContainer,
    vm: ChatViewModel,
    chrome: ChatChrome,
    sheet: Sheet?,
    onSheet: (Sheet?) -> Unit,
    card: String?,
    onCard: (String?) -> Unit,
) {
    when (sheet) {
        Sheet.Switcher -> {
            SwitcherHost(
                container,
                vm,
                chrome,
                onSampling = { onSheet(Sheet.Sampling) },
                onInfo = { key ->
                    onSheet(null)
                    onCard(key.toString())
                },
                onDismiss = { onSheet(null) },
            )
        }

        Sheet.Sampling -> {
            SamplingHost(container, vm, onDismiss = { onSheet(null) })
        }

        Sheet.Think -> {
            ThinkMenu(vm.composerState.value.think ?: ThinkLevel.Off, vm::setThink) { onSheet(null) }
        }

        Sheet.Smaller -> {
            SmallerHost(container, vm, onDismiss = { onSheet(null) })
        }

        null -> {
            Unit
        }
    }
    card?.let { key -> CardHost(container, HfModel.parse(key), onDismiss = { onCard(null) }) }
}

private fun chatViewModel(
    container: AppContainer,
    chatId: Long,
    styles: Flow<MessageStyle>,
    context: Context,
    saved: SavedStateHandle,
    startModel: HfModel? = null,
): ChatViewModel {
    val resolver = context.applicationContext.contentResolver
    return ChatViewModel(
        container.engine.open(chatId, startModel),
        styles,
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
        recorder = VoiceRecorder(container.audio, container.microphone, container.appScope),
        player = MediaVoicePlayer(container.appScope),
        voiceModeStart = { chat ->
            VoiceConversation(
                chat.chatId,
                chat.key,
                chat.name,
                { slots, limit -> container.engine.holdForVoice(chat.key, chat.name, slots, limit) },
                container.settings,
                container.chats,
                container.audio,
                container.voiceMicrophone,
                container.speech,
                container.appScope,
                Dispatchers.Main.immediate,
                clock = container.clock,
            )
        },
        dictationStart = { model, language ->
            Dictation(
                model,
                container.microphone,
                container.audio.file(container.audio.newName()),
                container.appScope,
                language,
            )
        },
        dictationLanguage = {
            container.settings.settings
                .first()
                .dictationLanguage
        },
        micHint = container.settings.settings.map { !it.seenMicHint },
        onMicHintSeen = { container.settings.setSeenMicHint() },
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
    context: Context,
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
        LimitAction.OpenAppSettings -> openAppSettings(context)
    }
}

/**
 * The bar's voice controls (D76): the mic asks for the microphone the first time, then records; refused, a line offers
 * Android's settings for the app. Leaving the screen stops a recording and keeps it in the bar, never sent by itself.
 * The voice-mode button asks the same way, then opens voice mode with [onTalk] (D100); refused, the same line.
 */
@Composable
private fun rememberVoiceActions(
    voice: VoiceComposer?,
    dictation: DictationComposer?,
    onTalk: () -> Unit,
): VoiceActions {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(MicUse.Record) }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            when {
                !granted -> voice?.refused()
                asked == MicUse.Talk -> onTalk()
                asked == MicUse.Dictate -> dictation?.start()
                else -> voice?.start()
            }
        }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        voice?.pause()
        dictation?.finish()
    }
    return remember(voice, dictation, context, ask, onTalk) {
        val withMicrophone = { use: MicUse, go: () -> Unit ->
            if (hasMicrophone(context)) {
                go()
            } else {
                asked = use
                ask.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        val talk = { withMicrophone(MicUse.Talk, onTalk) }
        if (voice == null) {
            VoiceActions({}, {}, {}, onTalk = talk)
        } else {
            VoiceActions(
                onRecord = { withMicrophone(MicUse.Record, voice::start) },
                onDiscard = voice::discard,
                onSend = voice::send,
                onTalk = talk,
                onDictate = dictation?.let { d -> { withMicrophone(MicUse.Dictate, d::start) } },
                onFinishDictation = { dictation?.finish() },
                onDiscardDictation = { dictation?.discard() },
            )
        }
    }
}

/** What the microphone permission was asked for. */
private enum class MicUse { Record, Talk, Dictate }

private fun hasMicrophone(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Android's own page for the app, where a refused permission is given (D76). */
private fun openAppSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Unit // a phone without that page: the line stays, and the system's own settings still have the switch
    }
}

/**
 * "Smaller size" (beside "Try again"): this model's own sheet, on Smaller; Get starts it and the chat moves to it. No
 * "Chat with": the chat already runs this model.
 */
@Composable
private fun SmallerHost(container: AppContainer, vm: ChatViewModel, onDismiss: () -> Unit) {
    val current by vm.handle.current.collectAsStateWithLifecycle()
    val name by vm.handle.name.collectAsStateWithLifecycle()
    val key = current ?: return
    ModelSheetRoute(
        container,
        // the chat's own file, so the card follows it rather than another quant of its repo
        ModelEntry.ofKey(key, name).copy(key = key),
        onStarted = vm.handle::switchModel,
        onDismiss = onDismiss,
        onChatWith = null,
        startTier = SizeTier.Smaller,
    )
}

@Composable
private fun SwitcherHost(
    container: AppContainer,
    vm: ChatViewModel,
    chrome: ChatChrome,
    onSampling: () -> Unit,
    onInfo: (HfModel) -> Unit,
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
        onInfo = onInfo,
        onGetMore = chrome.onGetModels,
        onDismiss = onDismiss,
        memory = SheetMemory(inMemory, replying) { scope.launch { container.memory.unload() } },
        sampling = chatSampling?.let { SheetSampling(it.values.size, onSampling) },
    )
}

/**
 * The card of a model on the phone, opened from the switcher's info action (models-page design §7). No "Chat with":
 * switching is the switcher's own tap. Another size got here lands on the phone and shows in the switcher; the chat
 * stays on its model. A model removed meanwhile closes the card.
 */
@Composable
private fun CardHost(container: AppContainer, key: HfModel, onDismiss: () -> Unit) {
    val library by container.models.library.collectAsStateWithLifecycle(null)
    val shelf = library ?: return // not read yet
    val model = shelf.firstOrNull { it.key == key }
    if (model == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    ModelSheetRoute(container, ModelEntry.of(model), onStarted = {}, onDismiss = onDismiss, onChatWith = null)
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
