package com.suhel.llamabro.demo.chat

import androidx.tracing.trace
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelInfo
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.RequestOptions
import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.conversation
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.MessageChange
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.NewMessage
import com.suhel.llamabro.demo.data.NewVoice
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.data.loadConfig
import com.suhel.llamabro.demo.data.modelForNewChat
import com.suhel.llamabro.demo.data.settings.CardSampling
import com.suhel.llamabro.demo.data.settings.Knobs
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DownloadFailure
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor

/**
 * How a turn asks: its Think [level] (null for a model that can't think), its [sampling] with the reply's length limit
 * (D60), and whether it [continues] the cut reply that ends the conversation instead of answering a new message.
 */
data class TurnOptions(
    val level: Think? = null,
    val sampling: Sampling = Sampling.Default,
    val continues: Boolean = false,
)

/**
 * How a turn reaches the model: the conversation, the message's text, its pictures and voice message, and the turn's
 * options.
 */
typealias Streamer = (Conversation, String, Array<Content>, TurnOptions) -> Flow<ChatEvent>

/**
 * What a turn streams: the SDK conversation, with the Think level and the sampling applied. A continue resends the
 * history, whose last message is the cut reply: llama-server then continues that message, its thinking or its answer,
 * instead of starting a new one (`prefill_assistant`, on by default). Journeys wrap it to hold a reply.
 */
internal fun sdkStream(
    convo: Conversation,
    text: String,
    media: Array<Content>,
    options: TurnOptions,
): Flow<ChatEvent> {
    val configure: RequestOptions.Builder.() -> Unit = {
        options.level?.let { level -> reasoning { Thinking.configure(level, this) } }
        sampling = options.sampling
    }
    return if (options.continues) convo.resume(configure) else convo.send(text, *media, options = configure)
}

/** The divider line for a switch to [key]. */
internal fun switchNotice(key: HfModel, name: String, now: Long) =
    NewMessage(MessageKind.Switch, "Switched to $name", modelKey = key.toString(), sentAt = now)

/** Whether choosing a model now switches from another one: only once the chat has a question in it. */
internal fun isSwitch(rows: List<StoredMessage>): Boolean = rows.any { it.kind == MessageKind.User }

/**
 * After a cut at [position] (an edit, a regenerate): the model to say "Switched to" for again, or null. The cut can
 * remove the divider that named the model answering, while the model in use stays the latest one; when the last
 * divider or reply that is left names another model than [current], the transcript must say so, or a reply would
 * stand under a divider for a model that did not write it.
 */
internal fun modelToRestate(rows: List<StoredMessage>, position: Int, current: HfModel?): HfModel? {
    val answering =
        rows
            .lastOrNull {
                it.position < position &&
                    (it.kind == MessageKind.Switch || it.kind == MessageKind.Assistant) &&
                    it.modelKey != null
            }?.modelKey
    return current?.takeIf { answering != null && answering != it.toString() }
}

/** Whether [reply] can be continued in a chat on [key]: cut at its length limit, and written by that model (D60). */
internal fun canContinue(reply: StoredMessage, key: HfModel): Boolean =
    reply.kind == MessageKind.Assistant && reply.state == MessageState.Cut && reply.modelKey == key.toString()

/** The state of a chat's model that isn't on the phone: still arriving, its download failed, or missing. */
internal fun missingOrDownloading(name: String, status: DownloadStatus): ChatModelState =
    when (status) {
        is DownloadStatus.Downloading, is DownloadStatus.Partial -> ChatModelState.Downloading(status)
        is DownloadStatus.Failed -> ChatModelState.DownloadFailed(name, status.failure)
        else -> ChatModelState.Missing(name)
    }

/**
 * The chat's model as the composer sees it. Loaded, the model says whether it sees, hears and talks; otherwise the
 * catalog says whether it sees, [heard] (the models that heard on their last load) whether it hears (D76), and
 * [talking] (those that spoke on their last load) whether it talks (D100).
 */
internal fun readyOrWaking(
    key: HfModel,
    name: String,
    hostState: HostState,
    heard: Set<String> = emptySet(),
    talking: Set<String> = emptySet(),
): ChatModelState =
    when {
        hostState is HostState.Waking && hostState.key == key -> {
            ChatModelState.Waking(hostState.progress)
        }

        hostState is HostState.Ready && hostState.key == key -> {
            ChatModelState.Ready(
                name,
                hostState.model.info.supportsVision,
                Thinking.canThink(hostState.model.info, Catalog.byKey(key)),
                hears = hostState.model.info.supportsAudio,
                talks = hostState.model.info.supportsAudio && hostState.model.info.supportsSpeech,
            )
        }

        else -> {
            // not loaded: what its last load showed, else the catalog's word for any quant of the repo, so a suggested
            // model that talks shows the mic and voice mode before its first load (a model that talks hears)
            val catalog = Catalog.byRepo(key.repo)
            val catalogTalks = catalog?.talks == true
            ChatModelState.Ready(
                name,
                catalog?.sees ?: false,
                catalog?.thinks ?: false,
                hears = key.toString() in heard || catalogTalks,
                talks = key.toString() in talking || catalogTalks,
            )
        }
    }

/**
 * Why model [name] can't take a message's pictures or voice message, when it can't see or hear them, or null when it
 * can; [visionModel] is a model on the phone that sees.
 */
internal fun refusal(
    sees: Boolean,
    hears: Boolean,
    pictures: Boolean,
    voice: Boolean,
    name: String,
    visionModel: String?,
): Limit? =
    when {
        pictures && !sees -> Limits.noVision(name, visionModel)
        voice && !hears -> Limits.cantHear(name)
        else -> null
    }

/** Records whether the model heard on this load, so the mic shows before its next load (D76). */
private suspend fun rememberHearing(settings: SettingsRepository, key: HfModel, hears: Boolean) {
    val known = key.toString() in settings.hearingModels.first()
    if (known != hears) settings.setHears(key.toString(), hears)
}

/**
 * Records what the model heard and spoke with on this load (D76, D100): the mic and the voice-mode button show before
 * its next load, and Settings › Voice lists its voices.
 */
internal suspend fun rememberSenses(settings: SettingsRepository, key: HfModel, info: ModelInfo) {
    rememberHearing(settings, key, info.supportsAudio)
    val voices = if (info.supportsAudio) info.voices else emptyList()
    if (settings.talkingModels.first()[key.toString()].orEmpty() != voices) settings.setVoices(key.toString(), voices)
}

/**
 * What a chat knows of its model: not looked up yet ([Unknown]), looked up with none chosen ([None]: a new chat and no
 * model for new chats), or [Chosen], by the lookup, the first send or a switch.
 */
internal sealed interface ModelChoice {
    data object Unknown : ModelChoice

    data object None : ModelChoice

    data class Chosen(val key: HfModel) : ModelChoice
}

/** The choice once the lookup found [found]: a key the first send or a switch chose meanwhile stands. */
internal fun ModelChoice.lookedUp(found: HfModel?): ModelChoice =
    this as? ModelChoice.Chosen ?: found?.let(ModelChoice::Chosen) ?: ModelChoice.None

/** The chosen model's key; null while unknown or when none is chosen. */
internal val ModelChoice.key: HfModel? get() = (this as? ModelChoice.Chosen)?.key

/** What the chat's model state is derived from. */
private data class ModelInputs(
    val choice: ModelChoice,
    val library: List<LibraryModel>?,
    val host: HostState,
    val heard: Set<String>,
    val talking: Set<String>,
)

/** The reply being written right now. It is not in [ChatHandle.messages] until the row changes state. */
data class StreamingReply(
    val messageId: Long,
    val text: String,
    val reasoning: String?,
    /** Set once the model has answered after thinking: how long the thinking took. */
    val reasoningMillis: Long?,
    val thinking: Boolean,
)

/** The chat voice mode talks in (D100): its id, its model and the model's name. */
data class VoiceChat(val chatId: Long, val key: HfModel, val name: String)

/**
 * An audio feature's hold on the loaded [model] (voice mode D100, dictation D140, reading aloud D141): [release] it
 * once, when the feature is done with it. Features acquire it through [holdUncancelled].
 */
class ModelHold(val model: Model, private val onRelease: () -> Unit) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}

/**
 * [hold] run to its end even when the caller is cancelled meanwhile (a model load can't be stopped halfway by it): a
 * hold that arrives for a cancelled caller is released rather than dropped, and the caller then sees the cancellation.
 */
suspend fun holdUncancelled(hold: suspend () -> ModelHold?): ModelHold? {
    val held = withContext(NonCancellable) { hold() }
    if (!currentCoroutineContext().isActive) held?.release()
    currentCoroutineContext().ensureActive()
    return held
}

/** What the chat knows about its model, for the header, the composer and the held-message note. */
sealed interface ChatModelState {
    data class Downloading(val status: DownloadStatus?) : ChatModelState

    /** The model is loading; [progress] is how far, 0 to 1, once the SDK has said. */
    data class Waking(val progress: Float? = null) : ChatModelState

    /** [hears]: the model takes voice messages (D76); [talks]: it holds a spoken conversation, voice mode (D100). */
    data class Ready(
        val name: String,
        val sees: Boolean,
        val canThink: Boolean,
        val hears: Boolean = false,
        val talks: Boolean = false,
    ) : ChatModelState

    data class Missing(val name: String) : ChatModelState

    /** A new chat with no model chosen yet (none by default, none last used): nothing is loading. */
    data object NoModel : ChatModelState

    /** The model is on its way but the download stopped: [failure] says why (a token, the network, the disk). */
    data class DownloadFailed(val name: String, val failure: DownloadFailure) : ChatModelState
}

/** A new chat's header before any model is chosen: what the title's switcher is for. */
internal const val NO_MODEL_TITLE = "Choose a model"

/**
 * Drives chats through the SDK's `Conversation`. Room is the source of truth: a conversation is rebuilt from stored
 * messages after an edit, a regenerate, a switch or a reopen, and reused for a plain send so the server's prompt
 * cache keeps hitting. One instance per app; [open] gives a handle per chat.
 */
class ChatEngine(
    private val chats: ChatRepository,
    private val host: ModelHost,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val frameMillis: Long = 16,
    private val writeMillis: Long = 250,
    /** How a turn reaches the model; tests replace it to script timing. */
    private val streamer: Streamer = ::sdkStream,
    /**
     * Awaited before every turn: the app's recovery of replies a dead process left streaming, so it can never mark a
     * reply this process has just started as stopped.
     */
    private val beforeTurn: suspend () -> Unit = {},
    /**
     * A model's card values (D60), at once: the catalog's; the app adds the hub's for other models once looked up
     * ([SamplingStore.card], which never holds a turn).
     */
    private val card: (HfModel) -> CardSampling? = { Catalog.byRepo(it.repo)?.sampling },
) {
    /** Turns under way in every chat, from the load to the end of the reply: the engine's own count, in and out. */
    private val activeTurns = MutableStateFlow(0)

    /**
     * A reply is being loaded for or written, in any chat: the model can't be unloaded now, and a reply that goes on
     * in the background keeps the app alive while it does (D59).
     */
    val replying: StateFlow<Boolean> =
        activeTurns.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * The loaded model whose note (memory or chip) was shown: once per load, in whichever chat loads it first. A model
     * loaded again (after it was freed, or after a switch) is a new object, so its note is shown again.
     */
    @Volatile private var noted: java.lang.ref.WeakReference<Model>? = null

    /**
     * A lease on the model for [key] (shown as [name]) on the chip and context chosen in Settings › Memory (D72), or
     * null when it didn't load: [limit] then says why. When the SDK loaded it although its memory check doubted it
     * (F128), or on another chip than the one chosen, [limit] says so on every load of it: the note travels with the
     * loaded model into every turn, so a turn on a model already loaded doesn't say it again. The turn releases the
     * lease when it ends: until then the model stays open, and another chat's load of another model waits for it.
     */
    private suspend fun load(
        key: HfModel,
        name: String,
        limit: MutableStateFlow<Limit?>,
        slots: Int = 1,
    ): ModelHost.Lease? {
        val chosen =
            settings.settings
                .first()
                .loadConfig
                .copy(slots = slots)
        val lease =
            try {
                // this turn is counted: another one may be streaming from the model, so a chip or context changed
                // meanwhile waits for it (ModelMemory.loadConfigChanged) instead of closing the model under that reply
                host.hold(key, chosen) { activeTurns.value > 1 }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                limit.value = Limits.forLoadFailure(name, e)
                    ?: Limit("$name didn't start. Try again.", LimitAction.Retry, "Retry")
                return null
            }
        var handedOver = false
        try {
            val loaded = lease.model
            // one line holds one note: the memory note wins, since it warns that other apps may close and offers a
            // smaller size; where the model runs stays in Settings › Memory and the model sheet
            val report = loaded.loadReport
            val note =
                if (report.memoryWarning != null) {
                    Limits.memoryWarning(name)
                } else {
                    report.fallbackFrom(lease.config.policy)?.let { Limits.chipFallback(name, it) }
                }
            if (note != null && firstNote(loaded)) limit.value = note
            handedOver = true
            return lease
        } finally {
            // a throw here hands the turn no lease to release, so it must not leave the model held
            if (!handedOver) lease.release()
        }
    }

    @Synchronized
    private fun firstNote(loaded: Model): Boolean {
        if (noted?.get() === loaded) return false
        noted = java.lang.ref.WeakReference(loaded)
        return true
    }

    /**
     * A hold on [key]'s model (shown as [name]) for an audio feature, loaded with [slots] (two while voice mode
     * captions what the person says, D99; one otherwise, through [ChatHandle.holdModel]), or null when it didn't load:
     * [limit] then says why. The hold counts as a turn until [ModelHold.release]: the model isn't freed under it, and
     * the app stays alive while it runs (D59).
     */
    suspend fun holdModel(key: HfModel, name: String, slots: Int, limit: MutableStateFlow<Limit?>): ModelHold? {
        activeTurns.update { it + 1 }
        var lease: ModelHost.Lease? = null
        var handedOver = false
        try {
            lease = load(key, name, limit, slots) ?: return null
            rememberSenses(settings, key, lease.model.info)
            handedOver = true
        } finally {
            // no model, or a throw after the load: the caller gets no hold to release, so nothing may stay held
            if (!handedOver) {
                lease?.release()
                activeTurns.update { it - 1 }
            }
        }
        val held = checkNotNull(lease)
        return ModelHold(held.model) {
            held.release()
            activeTurns.update { it - 1 }
        }
    }

    /**
     * Releases the loaded model unless a turn is loading it or streaming from it (the app went to the background); the
     * next send loads it again. True when it was released.
     */
    suspend fun releaseModelIfIdle(): Boolean = host.release { activeTurns.value > 0 }

    /** The system prompt from Settings › Instructions as it is now, or null when there are none. */
    private suspend fun systemPrompt(): String? =
        settings.settings.first().let { Instructions.system(it.instructionsAbout, it.instructionsStyle) }

    /** A turn at [level]: sampling from the chat, the model and the card, and the reply's length limit (D60). */
    private suspend fun turnOptions(id: Long, key: HfModel, level: Think?, continues: Boolean): TurnOptions {
        val layers =
            SamplingLayers(
                card = card(key),
                model = settings.modelSampling.first()[key.toString()] ?: Knobs.None,
                chat = chats.sampling(id).first(),
            )
        val thinks = Thinking.thinks(level)
        val length = settings.settings.first().replyLength
        return TurnOptions(level, layers.sampling(thinks, Thinking.replyTokens(level, length)), continues)
    }

    /** The chats open now: each registers when opened and leaves when closed. */
    private val open: MutableSet<ChatHandle> = ConcurrentHashMap.newKeySet()

    /**
     * [chatId] 0 is a new chat: it is created with the first message, on [startModel] when one is given (a model card's
     * "Chat with"), else on the model for new chats. [startModel] is only held in memory until then, so a new chat
     * left without a message leaves no chat behind; it is ignored for a chat that exists.
     */
    fun open(chatId: Long, startModel: HfModel? = null): ChatHandle = ChatHandle(chatId, startModel).also { open += it }

    /**
     * Stops every reply under way, keeping what arrived, as Stop would: the reply notification's Stop, and Android's
     * limit on how long a reply may run in the background (D59).
     */
    fun stopAllReplies() = open.forEach { it.stop() }

    /**
     * Deletes [chatId] from outside its screen (the drawer), on the app's scope so leaving the screen doesn't stop it:
     * a reply streaming into it is stopped first, and no other held message of it is answered after, since a reply
     * reads the chat's pictures and recordings from their files until it ends (D87).
     */
    fun delete(chatId: Long): Job =
        scope.launch {
            open.filter { it.chatId.value == chatId }.forEach { it.stopAndWait(forDelete = true) }
            chats.delete(chatId)
        }

    // the chat's facade, as Conversation is the SDK's: each public function is one thing the chat screen does
    @Suppress("TooManyFunctions")
    @OptIn(ExperimentalCoroutinesApi::class)
    inner class ChatHandle internal constructor(initialChatId: Long, private val startModel: HfModel? = null) {
        /**
         * One thread at a time for everything this handle runs: [running], [conversation] and [conversationKey] are
         * plain fields, read and written only on it, so a turn, a stop and an edit never see each other half done.
         */
        private val confined: CoroutineDispatcher =
            (scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default)
                .limitedParallelism(1)

        /**
         * Everything this handle runs lives in its own scope, under the engine's: [close] ends it, so a chat that was
         * left stops observing the database, stops pumping held messages and stops its reply (which is saved as
         * stopped).
         */
        private val hs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + confined)

        /** Ends this handle. Call once, when the chat screen is gone. */
        fun close() {
            open -= this
            hs.cancel()
        }

        // engine's own source: a new chat gets its id with its first message
        private val _chatId = MutableStateFlow(initialChatId)
        val chatId: StateFlow<Long> = _chatId.asStateFlow()

        val messages: StateFlow<List<StoredMessage>> =
            _chatId
                .flatMapLatest { if (it == 0L) emptyFlow() else chats.messages(it) }
                .stateIn(hs, SharingStarted.Eagerly, emptyList())

        // engine's own source: the turn writes the reply as it streams
        private val _streaming = MutableStateFlow<StreamingReply?>(null)
        val streaming: StateFlow<StreamingReply?> = _streaming.asStateFlow()

        // engine's own source: the turn marks itself under way
        private val _busy = MutableStateFlow(false)
        val busy: StateFlow<Boolean> = _busy.asStateFlow()

        // engine's own source: the turn's refusals and failures
        private val _limit = MutableStateFlow<Limit?>(null)
        val limit: StateFlow<Limit?> = _limit.asStateFlow()

        // user input and engine: the Think level chosen, null for a model that can't think
        private val _think = MutableStateFlow<Think?>(null)

        /** Null when the model can't think. */
        val think: StateFlow<Think?> = _think.asStateFlow()

        // the engine's own source: written by the lookup, the first send and a switch
        private val choice = MutableStateFlow<ModelChoice>(ModelChoice.Unknown)
        private val library: StateFlow<List<LibraryModel>?> =
            models.library.stateIn(hs, SharingStarted.Eagerly, null)

        /** The model this chat uses (null until it is known), for the switcher's check mark. */
        val current: StateFlow<HfModel?> =
            choice
                .map { it.key }
                .distinctUntilChanged()
                .stateIn(hs, SharingStarted.Eagerly, null)

        /**
         * The model's name for the header, whatever state it is in; empty until the model is looked up, then
         * [NO_MODEL_TITLE] for a new chat with no model chosen, which left the header blank.
         */
        val name: StateFlow<String> =
            choice
                .map { chosen ->
                    when (chosen) {
                        ModelChoice.Unknown -> ""
                        ModelChoice.None -> NO_MODEL_TITLE
                        is ModelChoice.Chosen -> Names.of(chosen.key)
                    }
                }.stateIn(hs, SharingStarted.Eagerly, "")

        val model: StateFlow<ChatModelState> =
            combine(
                choice,
                library,
                host.state,
                settings.hearingModels,
                settings.talkingModels,
            ) { chosen, lib, hostState, heard, talking ->
                ModelInputs(chosen, lib, hostState, heard, talking.keys)
            }.flatMapLatest { inputs ->
                val key = inputs.choice.key
                val lib = inputs.library
                val hostState = inputs.host
                when {
                    // looked up and none chosen: nothing to load, so no wake line
                    key == null -> {
                        if (inputs.choice == ModelChoice.None) flowOf(ChatModelState.NoModel) else emptyFlow()
                    }

                    lib == null -> {
                        emptyFlow()
                    }

                    else -> {
                        val name = Names.of(key)
                        if (lib.none { it.key == key }) {
                            models.state(key).map { missingOrDownloading(name, it) }
                        } else {
                            flowOf(readyOrWaking(key, name, hostState, inputs.heard, inputs.talking))
                        }
                    }
                }
            }.stateIn(hs, SharingStarted.Eagerly, ChatModelState.Waking())

        private val turn = Mutex()

        /** The turn under way, load and reply both; volatile only because [stop] cancels it from the UI's thread. */
        @Volatile private var running: Job? = null
        private var conversation: Conversation? = null
        private var conversationKey: HfModel? = null

        /**
         * The loaded model [conversation] was made on. A conversation lives inside one load: after a release (the app
         * went to the background, or the person unloaded it) the same key loads a new model and the old conversation
         * is closed with the old one, so it is rebuilt from the stored rows.
         */
        private var conversationModel: Model? = null

        /**
         * The system prompt [conversation] was made with. Settings › Instructions opens over the chat, which stays
         * open: a turn that finds the setting changed since rebuilds the conversation, so the new instructions apply at
         * once.
         */
        private var conversationSystem: String? = null

        init {
            hs.launch {
                val found = initialModelKey()
                choice.update { it.lookedUp(found) }
                syncThink()
            }
            // a model that arrives (a download finishes) answers what was held
            hs.launch { library.collect { if (it != null) pump() } }
            // the hub's values for a model the catalog lacks are looked up once the chat's model is on the phone, so
            // its first reply has them as the later ones do; never before, since the lookup would then read the file's
            // header over the network (D64); it runs in the background and never holds a turn
            hs.launch {
                combine(current.filterNotNull(), library.filterNotNull()) { key, lib ->
                    key.takeIf { lib.any { model -> model.key == key } }
                }.filterNotNull()
                    .distinctUntilChanged()
                    .collect { card(it) }
            }
        }

        // ---- the user's actions -------------------------------------------------------------------------

        /**
         * Whether the message was taken: false while a reply is coming, for an empty message, when the chat has no
         * model at all, and for pictures or a [voice] message sent to a model that can't see or hear them (the limit
         * line says so). The caller keeps the draft when this is false. A taken message is stored as held and answered
         * as soon as its model is on the phone.
         */
        suspend fun send(text: String, images: List<String> = emptyList(), voice: NewVoice? = null): Boolean {
            // a chat deleted from the drawer takes nothing more: its rows would point at a chat that is gone
            if (_busy.value || deleting) return false
            if (text.isBlank() && images.isEmpty() && voice == null) return false
            val key =
                choice.value.key ?: initialModelKey()?.also { choice.value = ModelChoice.Chosen(it) } ?: return false
            val names = images.take(MAX_IMAGES)
            val ready = model.value as? ChatModelState.Ready
            // the model's own answer once it is loaded; until then what the catalog says about it
            val sees = ready?.sees ?: Catalog.byKey(key)?.sees ?: false
            val hears = ready?.hears == true
            refusal(sees, hears, names.isNotEmpty(), voice != null, currentName(), visionModelName())?.let {
                _limit.value = it
                return false
            }
            val id = ensureChat() ?: return false
            _limit.value = null
            chats.commitDraftImages(names)
            try {
                chats.append(
                    id,
                    NewMessage(
                        MessageKind.User,
                        text.trim(),
                        names,
                        state = MessageState.Held,
                        sentAt = clock(),
                        voice = voice,
                    ),
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // not stored: the draft keeps its pictures (even when this call was cancelled)
                withContext(NonCancellable) { chats.returnDraftImages(names) }
                throw e
            }
            pump()
            return true
        }

        /** Stops the turn, whether the model is still waking (the message stays held) or the reply is streaming. */
        fun stop() {
            running?.cancel()
        }

        /** Asks again from [position]. */
        fun regenerate(position: Int) {
            hs.launch {
                stopAndWait()
                beforeTurn() // startup recovery must not run between the cut and the append
                if (deleting) return@launch
                _limit.value = null
                val id = _chatId.value.takeIf { it != 0L } ?: return@launch
                // the user's message first: with none before this reply there is nothing to ask again
                val user =
                    messages.value.lastOrNull { it.position < position && it.kind == MessageKind.User }
                        ?: return@launch
                val before = messages.value
                chats.truncateFrom(id, position)
                invalidate()
                modelToRestate(before, position, choice.value.key)?.let {
                    chats.append(id, switchNotice(it, Names.of(it), clock()))
                }
                chats.update(user.id, MessageChange(state = MessageState.Held))
                pump()
            }
        }

        fun edit(position: Int, text: String) {
            if (text.isBlank()) return
            hs.launch {
                stopAndWait()
                beforeTurn() // startup recovery must not run between the cut and the append
                if (deleting) return@launch
                _limit.value = null
                val id = _chatId.value.takeIf { it != 0L } ?: return@launch
                val before = messages.value
                val old = before.firstOrNull { it.position == position } ?: return@launch
                chats.truncateFrom(id, position, keepImages = old.images.mapTo(mutableSetOf()) { it.name })
                invalidate()
                modelToRestate(before, position, choice.value.key)?.let {
                    chats.append(id, switchNotice(it, Names.of(it), clock()))
                }
                chats.append(
                    id,
                    NewMessage(
                        MessageKind.User,
                        text.trim(),
                        old.images.map {
                            it.name
                        },
                        state = MessageState.Held,
                        sentAt = clock(),
                    ),
                )
                pump()
            }
        }

        fun switchModel(key: HfModel) {
            hs.launch {
                stopAndWait()
                if (deleting) return@launch
                // an empty chat has no model to switch from: choosing one just sets it, with no divider
                val switching = isSwitch(messages.value)
                // the in-memory model changes first, so a message sent right after is answered by the new one, and a
                // new chat with no model yet is created on the one chosen
                invalidate()
                _limit.value = null
                choice.value = ModelChoice.Chosen(key)
                val id = ensureChat() ?: return@launch
                syncThink()
                if (switching) chats.append(id, switchNotice(key, Names.of(key), clock()))
                chats.setModel(id, key.toString())
                settings.setLastModel(key.toString())
                pump()
            }
        }

        fun setThink(level: Think) {
            if (_think.value != null) _think.value = level
        }

        // ---- the engine ----------------------------------------------------------------------------------

        private fun invalidate() {
            conversation = null
            conversationKey = null
            conversationModel = null
            conversationSystem = null
        }

        /**
         * Stops the turn and waits for it to end; on this handle's thread, so the engine may call it too. [forDelete]
         * also ends the answering of held messages for good ([deleting]), before the turn is cancelled.
         */
        internal suspend fun stopAndWait(forDelete: Boolean = false) =
            withContext(confined) {
                if (forDelete) deleting = true
                running?.cancel()
                running?.join()
            }

        /**
         * The chat is being deleted: [pump] answers nothing more, and the person's actions are refused. Written on
         * [confined] only; volatile because [send] reads it from the screen's thread.
         */
        @Volatile private var deleting = false

        /** Answers every held user message in order, once, as soon as its model is on the phone. */
        private fun pump() {
            hs.launch {
                turn.withLock {
                    while (!deleting) {
                        val id = _chatId.value.takeIf { it != 0L } ?: return@withLock
                        val key = choice.value.key ?: return@withLock
                        val lib = library.value ?: return@withLock
                        if (lib.none { it.key == key }) return@withLock
                        val held =
                            chats.messages(id).first().firstOrNull {
                                it.kind == MessageKind.User &&
                                    it.state == MessageState.Held
                            }
                                ?: return@withLock
                        // the turn, load and reply both, is the one job [stop] cancels: it is [running]
                        // before the load starts, so a Stop while the model wakes ends the turn (the message
                        // stays held, no reply row is made), and an edit, a regenerate or a switch waits for
                        // the load as it waits for a reply
                        // the chat may have started going while its rows were read
                        if (deleting) return@withLock
                        var replied = false
                        val job = hs.launch { answer(id, key, held) { replied = true } }
                        running = job
                        job.join()
                        if (running === job) running = null
                        if (!replied) return@withLock
                    }
                }
            }
        }

        /** Answers [user]; [onReply] runs once the reply row exists (the load worked and was not stopped). */
        private suspend fun answer(id: Long, key: HfModel, user: StoredMessage, onReply: () -> Unit) {
            activeTurns.update { it + 1 }
            _busy.value = true
            var lease: ModelHost.Lease? = null
            try {
                beforeTurn()
                val held = load(key, Names.of(key), _limit) ?: return
                lease = held
                val loaded = held.model
                syncThink(loaded.info)
                val hears = loaded.info.supportsAudio
                rememberSenses(settings, key, loaded.info)
                val rows = chats.messages(id).first()
                val convo = conversationFor(loaded, key, rows, user, hears)
                val replyId =
                    chats.append(
                        id,
                        NewMessage(
                            MessageKind.Assistant,
                            "",
                            modelKey = key.toString(),
                            state = MessageState.Streaming,
                            sentAt = clock(),
                        ),
                    )
                onReply()
                // a model that can't hear gets a note for each voice message, and the line says so (D76)
                if (!hears && rows.any { it.voice != null && it.position <= user.position }) {
                    _limit.value = Limits.cantHear(currentName())
                }
                val parts = (History.userMessage(user, hears) as Message.User).content
                val text = parts.filterIsInstance<Content.Text>().joinToString("") { it.text }
                val media = parts.filterNot { it is Content.Text }.toTypedArray()
                val report = loaded.loadReport
                val draft = ReplyDraft(clock, report.device.label, contextSize = report.contextSize)
                stream(
                    convo,
                    replyId,
                    user,
                    text,
                    media,
                    turnOptions(id, key, _think.value, continues = false),
                    draft,
                )
            } finally {
                lease?.release()
                _busy.value = false
                activeTurns.update { it - 1 }
            }
        }

        /**
         * The conversation for [user]'s turn: the one kept from the last turn while the model, its load and the system
         * prompt are the same, else a new one from [rows].
         */
        private suspend fun conversationFor(
            loaded: Model,
            key: HfModel,
            rows: List<StoredMessage>,
            user: StoredMessage,
            hears: Boolean,
        ): Conversation {
            val system = systemPrompt()
            conversation
                ?.takeIf { conversationKey == key && conversationModel === loaded && conversationSystem == system }
                ?.let { return it }
            // what the model has seen: answered rows before this question, and replies; other held questions wait
            // their turn
            val seen =
                rows.filter {
                    it.id != user.id && it.state != MessageState.Held &&
                        (it.position < user.position || it.kind == MessageKind.Assistant)
                }
            return loaded.conversation(system, History.forModel(seen, hears = hears)).also {
                conversation = it
                conversationKey = key
                conversationModel = loaded
                conversationSystem = system
            }
        }

        /**
         * Continues the cut reply at [position] (D60): the chat's last row, cut at its length limit, written by the
         * chat's model. The model reads the chat with that reply as its last message and goes on writing it, its
         * thinking or its answer; the new words join the same row, which may be cut again and continued again.
         */
        fun continueReply(position: Int) {
            hs.launch {
                turn.withLock {
                    if (deleting) return@withLock
                    val id = _chatId.value.takeIf { it != 0L } ?: return@withLock
                    val key = choice.value.key ?: return@withLock
                    val rows = chats.messages(id).first()
                    val reply = rows.lastOrNull()?.takeIf { it.position == position && canContinue(it, key) }
                    val user = rows.lastOrNull { it.kind == MessageKind.User && it.position < position }
                    if (reply == null || user == null) return@withLock
                    _limit.value = null
                    val job = hs.launch { continueTurn(id, key, rows, reply, user) }
                    running = job
                    job.join()
                    if (running === job) running = null
                }
            }
        }

        private suspend fun continueTurn(
            id: Long,
            key: HfModel,
            rows: List<StoredMessage>,
            reply: StoredMessage,
            user: StoredMessage,
        ) {
            activeTurns.update { it + 1 }
            _busy.value = true
            var lease: ModelHost.Lease? = null
            try {
                beforeTurn()
                val held = load(key, Names.of(key), _limit) ?: return
                lease = held
                val loaded = held.model
                syncThink(loaded.info)
                // the chat before the reply, then the reply as far as it got, its thinking with it
                val history =
                    History.forModel(rows, upTo = reply.position, hears = loaded.info.supportsAudio) +
                        Message.Assistant(content = reply.text, reasoning = reply.reasoning)
                // a one-off: kept, this conversation would hold the reply twice once the next message is sent
                invalidate()
                val convo = loaded.conversation(systemPrompt(), history)
                chats.update(reply.id, MessageChange(state = MessageState.Streaming))
                val draft =
                    ReplyDraft(
                        clock,
                        loaded.loadReport.device.label,
                        ReplyDraft.Continued(reply.text, reply.reasoning, reply.reasoningMillis),
                        loaded.loadReport.contextSize,
                    )
                stream(
                    convo,
                    reply.id,
                    user,
                    "",
                    emptyArray(),
                    turnOptions(id, key, _think.value, continues = true),
                    draft,
                )
                invalidate()
            } finally {
                lease?.release()
                _busy.value = false
                activeTurns.update { it - 1 }
            }
        }

        private suspend fun stream(
            convo: Conversation,
            replyId: Long,
            user: StoredMessage,
            text: String,
            media: Array<Content>,
            options: TurnOptions,
            draft: ReplyDraft,
        ) {
            val ticker = hs.launch { showAndSave(draft, replyId) }
            try {
                streamer(convo, text, media, options).collect { trace("ChatEngine.onDelta") { draft.accept(it) } }
                ticker.cancel()
                if (draft.filledContext) {
                    // no room to continue: it ends as a chat too long for the model, with its way out
                    val limit = Limits.contextExceeded(currentName())
                    finish(replyId, user, draft.failed(limit.line))
                    _limit.value = limit
                    invalidate()
                } else {
                    finish(replyId, user, draft.ended(MessageState.Done))
                }
            } catch (e: CancellationException) {
                ticker.cancel()
                withContext(NonCancellable) {
                    finish(replyId, user, draft.ended(MessageState.Stopped))
                }
                invalidate()
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                ticker.cancel()
                val limit = Limits.forLoadFailure(currentName(), e)
                finish(replyId, user, draft.failed(limit?.line ?: "That didn't work."))
                _limit.value = limit
                invalidate()
            }
        }

        /** Every frame, shows what arrived; every [writeMillis], saves it too. Runs until the reply ends. */
        private suspend fun showAndSave(draft: ReplyDraft, replyId: Long) {
            var lastWrite = clock()
            while (currentCoroutineContext().isActive) {
                delay(frameMillis)
                if (!draft.takeIfDirty()) continue
                _streaming.value = draft.shown(replyId)
                if (clock() - lastWrite >= writeMillis) {
                    lastWrite = clock()
                    chats.update(replyId, draft.progress())
                }
            }
        }

        /** Stores how the reply ended, marks the question answered and stops showing the stream. */
        private suspend fun finish(replyId: Long, user: StoredMessage, change: MessageChange) {
            chats.update(replyId, change)
            chats.update(user.id, MessageChange(state = MessageState.Done))
            _streaming.value = null
        }

        // ---- helpers -------------------------------------------------------------------------------------

        private suspend fun initialModelKey(): HfModel? {
            val id = _chatId.value
            if (id == 0L && startModel != null) return startModel
            val stored = if (id != 0L) chats.modelKey(id) else settings.settings.first().modelForNewChat()
            return stored?.let { runCatching { HfModel.parse(it) }.getOrNull() }
        }

        /**
         * A hold on the chat's model for dictation (D140) or reading a reply aloud (D141), loaded as for a turn (one
         * slot), so a send after it finds the model as it was; null with no model chosen or when it didn't load, [limit]
         * then saying why.
         */
        suspend fun holdModel(): ModelHold? {
            val key = choice.value.key ?: return null
            return holdModel(key, Names.of(key), 1, _limit)
        }

        /**
         * Voice mode's chat (D100): this one, created now if it is new, with its model and the model's name; null when
         * no model is known yet.
         */
        suspend fun voiceChat(): VoiceChat? {
            val id = ensureChat() ?: return null
            val key = choice.value.key ?: return null
            return VoiceChat(id, key, Names.of(key))
        }

        /** Creates the chat on the first message; null when no model is known yet. */
        private suspend fun ensureChat(): Long? {
            _chatId.value.takeIf { it != 0L }?.let { return it }
            val key =
                choice.value.key ?: initialModelKey()?.also { choice.value = ModelChoice.Chosen(it) } ?: return null
            return chats.createChat(key.toString()).also { _chatId.value = it }
        }

        private fun syncThink(info: ModelInfo? = null) {
            val key = choice.value.key ?: return
            val catalog = Catalog.byKey(key)
            val can = if (info != null) Thinking.canThink(info, catalog) else catalog?.thinks == true
            _think.update { current -> if (can) current ?: Think.Off else null }
        }

        private fun currentName(): String = choice.value.key?.let(Names::of) ?: "This model"

        /** Starts the model's download again after a failure; what had arrived is kept. */
        fun retryDownload() {
            choice.value.key?.let(models::get)
        }

        private fun visionModelName(): String? =
            library.value?.firstOrNull { Catalog.byKey(it.key)?.sees == true }?.name
    }

    companion object {
        /** Builder's call, settled here: at most four images per message. */
        const val MAX_IMAGES = 4
    }
}
