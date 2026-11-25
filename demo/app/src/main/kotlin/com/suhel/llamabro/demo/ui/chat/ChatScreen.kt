package com.suhel.llamabro.demo.ui.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhel.llamabro.demo.chat.LimitAction
import com.suhel.llamabro.demo.chat.Thinking
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.designsystem.components.ComposerVoiceActions
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.ui.Screens
import com.suhel.llamabro.demo.ui.models.ModelCardStack
import com.suhel.llamabro.demo.ui.models.ModelEntry
import com.suhel.llamabro.demo.ui.models.rememberSheetViewModelOwner
import com.suhel.llamabro.demo.ui.shell.ChatChrome
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.flow.flowOf

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
    screens: Screens,
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
            screens.chatViewModel(
                ChatViewModel.chatIdToOpen(saved, chatId),
                chrome.startModel,
                styleOverride?.let(::flowOf),
                saved,
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
    val voice = rememberComposerVoiceActions(vm.voice, vm.dictation, vm.voiceMode::open)
    val playing = vm.player.clip.collectAsStateWithLifecycle()
    val reading = vm.reading.collectAsStateWithLifecycle()
    val talks = vm.talks.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.readAloud?.stop() }

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
                canReadAloud = { vm.readAloud != null && talks.value },
                readAloud = { id, text -> vm.readAloud?.toggle(id, text) },
                reading = { reading.value },
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
        remember(vm) {
            ChatLive(
                streaming = { streaming.value },
                draft = { draft.value },
                composer = { composer.value },
                thumbnail = vm::thumbnail,
            )
        }

    ChatContent(state, live, actions, chrome.layout, style, modifier)

    ChatSheets(screens, vm, chrome, sheet, { sheet = it }, card, { card = it })
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
    screens: Screens,
    vm: ChatViewModel,
    chrome: ChatChrome,
    sheet: Sheet?,
    onSheet: (Sheet?) -> Unit,
    card: String?,
    onCard: (String?) -> Unit,
) {
    // the switcher's state serves the sheet and the card it opens, so it lives while either is shown
    val switcher = if (sheet == Sheet.Switcher || card != null) rememberSwitcher(screens, vm) else null
    when (sheet) {
        Sheet.Switcher -> {
            SwitcherHost(
                checkNotNull(switcher),
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
            SamplingHost(screens, vm, onDismiss = { onSheet(null) })
        }

        Sheet.Think -> {
            ThinkMenu(vm.composerState.value.think ?: ThinkLevel.Off, vm::setThink) { onSheet(null) }
        }

        Sheet.Smaller -> {
            SmallerHost(screens, vm, onDismiss = { onSheet(null) })
        }

        null -> {
            Unit
        }
    }
    card?.let { key -> CardHost(screens, checkNotNull(switcher), HfModel.parse(key), onDismiss = { onCard(null) }) }
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
private fun rememberComposerVoiceActions(
    voice: VoiceComposer?,
    dictation: DictationComposer?,
    onTalk: () -> Unit,
): ComposerVoiceActions {
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
        dictation?.leave()
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
            ComposerVoiceActions({}, {}, {}, onTalk = talk)
        } else {
            ComposerVoiceActions(
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
 * "Chat with": the chat already runs this model, and a card its page's links open has none either. Only a size of the
 * chat's own model moves the chat: Get on another model's card just downloads it.
 */
@Composable
private fun SmallerHost(screens: Screens, vm: ChatViewModel, onDismiss: () -> Unit) {
    val current by vm.handle.current.collectAsStateWithLifecycle()
    val name by vm.handle.name.collectAsStateWithLifecycle()
    val key = current ?: return
    ModelCardStack(
        screens,
        // the chat's own file, so the card follows it rather than another quant of its repo
        ModelEntry.ofKey(key, name).copy(key = key),
        onStarted = { started -> if (started.repo.equals(key.repo, ignoreCase = true)) vm.handle.switchModel(started) },
        onDismiss = onDismiss,
        onChatWith = null,
        startTier = SizeTier.Smaller,
    )
}

/** The switcher's view model, kept while the sheet or the card it opened is shown; clear of the screen's rotation. */
@Composable
private fun rememberSwitcher(screens: Screens, vm: ChatViewModel): SwitcherViewModel =
    viewModel(viewModelStoreOwner = rememberSheetViewModelOwner("switcher")) { screens.switcherViewModel(vm.handle) }

@Composable
private fun SwitcherHost(
    switcher: SwitcherViewModel,
    vm: ChatViewModel,
    chrome: ChatChrome,
    onSampling: () -> Unit,
    onInfo: (HfModel) -> Unit,
    onDismiss: () -> Unit,
) {
    val library by switcher.library.collectAsStateWithLifecycle()
    val current by vm.handle.current.collectAsStateWithLifecycle()
    val facts by switcher.facts.collectAsStateWithLifecycle()
    val inMemory by switcher.inMemory.collectAsStateWithLifecycle()
    val replying by switcher.replying.collectAsStateWithLifecycle()
    val chatSampling by switcher.chatSampling.collectAsStateWithLifecycle()
    ModelSwitcherSheet(
        library = library.orEmpty(),
        current = current,
        facts = facts,
        onSwitch = vm.handle::switchModel,
        onUseForNewChats = { key -> switcher.pinDefault(key.toString()) },
        onInfo = onInfo,
        onGetMore = chrome.onGetModels,
        onDismiss = onDismiss,
        memory = SheetMemory(inMemory, replying, switcher::unload),
        sampling = chatSampling?.let { SheetSampling(it.values.size, onSampling) },
    )
}

/**
 * The card of a model on the phone, opened from the switcher's info action (models-page design §7). No "Chat with":
 * switching is the switcher's own tap. Another size got here lands on the phone and shows in the switcher; the chat
 * stays on its model. A model removed meanwhile closes the card.
 */
@Composable
private fun CardHost(screens: Screens, switcher: SwitcherViewModel, key: HfModel, onDismiss: () -> Unit) {
    val library by switcher.library.collectAsStateWithLifecycle()
    val shelf = library ?: return // not read yet
    val model = shelf.firstOrNull { it.key == key }
    if (model == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    ModelCardStack(screens, ModelEntry.of(model), onStarted = {}, onDismiss = onDismiss, onChatWith = null)
}

/** The chat's sampling sheet, at the chat's Think level; it closes if the chat has no model or no row yet. */
@Composable
private fun SamplingHost(screens: Screens, vm: ChatViewModel, onDismiss: () -> Unit) {
    val key by vm.handle.current.collectAsStateWithLifecycle()
    val chatId by vm.handle.chatId.collectAsStateWithLifecycle()
    val think by vm.handle.think.collectAsStateWithLifecycle()
    val k = key
    if (k == null || chatId == 0L) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val sampling =
        viewModel(viewModelStoreOwner = rememberSheetViewModelOwner("sampling:$chatId:$k")) {
            screens.chatSamplingViewModel(chatId, k)
        }
    val layers by sampling.layers.collectAsStateWithLifecycle()
    layers?.let { l ->
        ChatSamplingSheet(
            l,
            Thinking.thinks(think),
            onChange = sampling::change,
            onReset = sampling::reset,
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
