package com.suhel.llamabro.demo.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.demo.chat.Knobs
import com.suhel.llamabro.demo.chat.LoadConfig
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class AppLayout { Sidebar, ChatList }

/**
 * How long the loaded model stays in memory once the app is in the background (D59): [millis] after leaving, or, for
 * [UntilNeeded], until Android signals it needs the memory. Android's signal frees it under every choice (F134).
 */
enum class KeepLoaded(val millis: Long?) {
    OneMinute(60_000L),
    FiveMinutes(300_000L),
    UntilNeeded(null),
}

/**
 * The chip the person chose in Settings › Memory (D72). [Automatic] is the SDK's `Auto`; a chip is `Prefer`, so a
 * chip that can't load the model, or that this phone doesn't have, falls back in `Auto`'s order instead of failing.
 */
enum class ChipChoice(val kind: DeviceKind?) {
    Automatic(null),
    Npu(DeviceKind.Npu),
    Gpu(DeviceKind.Gpu),
    Cpu(DeviceKind.Cpu),
    ;

    val policy: DevicePolicy get() = kind?.let { DevicePolicy.Prefer(it) } ?: DevicePolicy.Auto
}

/**
 * The context length chosen in Settings › Memory: [Automatic] is the SDK's `Auto` (the most that fits in memory, up to
 * 16k); the others are `UpTo`, a ceiling the SDK still shrinks to fit memory, so a load never fails for it.
 */
enum class ContextChoice(val tokens: Int?) {
    Automatic(null),
    UpTo4k(4_096),
    UpTo8k(8_192),
    UpTo16k(16_384),
    UpTo32k(32_768),
    ;

    val size: ContextSize get() = tokens?.let { ContextSize.UpTo(it) } ?: ContextSize.Auto
}

/**
 * The reply length chosen in Settings › Sampling, for every model and chat: [Automatic] is the Think level's limit
 * (D60); [Short] and [Long] are a fixed limit, thinking and answer together; [NoLimit] sends none, so a reply runs
 * until the model stops or the context is full.
 */
enum class ReplyLength {
    Automatic,
    Short,
    Long,
    NoLimit,
}

/** What the next load asks for: the chip choice and the context length (Settings › Memory). */
val AppSettings.loadConfig: LoadConfig get() = LoadConfig(chip.policy, context.size)

data class AppSettings(
    val layout: AppLayout = AppLayout.Sidebar,
    val theme: ThemeMode = ThemeMode.System,
    val defaultModel: String? = null,
    val lastModel: String? = null,
    val instructionsAbout: String = "",
    val instructionsStyle: String = "",
    val seenFirstRun: Boolean = false,
    /** Model keys whose download was started and hasn't finished or been cancelled: the library's "Downloading". */
    val pendingDownloads: Set<String> = emptySet(),
    /** The pending downloads the person paused: kept paused across a restart, not started with the queue. */
    val pausedDownloads: Set<String> = emptySet(),
    /** The pending downloads Hugging Face refused for want of a token: saving one starts them, after a restart too. */
    val downloadsNeedingToken: Set<String> = emptySet(),
    val keepLoaded: KeepLoaded = KeepLoaded.OneMinute,
    val chip: ChipChoice = ChipChoice.Automatic,
    val context: ContextChoice = ContextChoice.Automatic,
    val replyLength: ReplyLength = ReplyLength.Automatic,
)

/** The model a new chat starts with: the pinned one, else the one used last (demo design §4.2). */
fun AppSettings.modelForNewChat(): String? = defaultModel ?: lastModel

/** DataStore Preferences `settings.preferences_pb`. */
class SettingsRepository(private val store: DataStore<Preferences>) {
    val settings: Flow<AppSettings> =
        store.data.map { p ->
            AppSettings(
                layout = if (p[LAYOUT] == "chat_list") AppLayout.ChatList else AppLayout.Sidebar,
                theme =
                    when (p[THEME]) {
                        "light" -> ThemeMode.Light
                        "dark" -> ThemeMode.Dark
                        else -> ThemeMode.System
                    },
                defaultModel = p[DEFAULT_MODEL],
                lastModel = p[LAST_MODEL],
                instructionsAbout = p[INSTRUCTIONS_ABOUT].orEmpty(),
                instructionsStyle = p[INSTRUCTIONS_STYLE].orEmpty(),
                seenFirstRun = p[SEEN_FIRST_RUN] ?: false,
                pendingDownloads = p[PENDING_DOWNLOADS].orEmpty(),
                pausedDownloads = p[PAUSED_DOWNLOADS].orEmpty(),
                downloadsNeedingToken = p[DOWNLOADS_NEEDING_TOKEN].orEmpty(),
                keepLoaded = KeepLoaded.entries.firstOrNull { it.name == p[KEEP_LOADED] } ?: KeepLoaded.OneMinute,
                chip = ChipChoice.entries.firstOrNull { it.name == p[CHIP] } ?: ChipChoice.Automatic,
                context = ContextChoice.entries.firstOrNull { it.name == p[CONTEXT] } ?: ContextChoice.Automatic,
                replyLength = ReplyLength.entries.firstOrNull { it.name == p[REPLY_LENGTH] } ?: ReplyLength.Automatic,
            )
        }

    suspend fun setLayout(layout: AppLayout) {
        store.edit { it[LAYOUT] = if (layout == AppLayout.ChatList) "chat_list" else "sidebar" }
    }

    suspend fun setTheme(theme: ThemeMode) {
        store.edit { it[THEME] = theme.name.lowercase() }
    }

    /** Pins [modelKey] for new chats; null unpins. */
    suspend fun pinDefault(modelKey: String?) {
        store.edit { if (modelKey == null) it.remove(DEFAULT_MODEL) else it[DEFAULT_MODEL] = modelKey }
    }

    suspend fun setLastModel(modelKey: String) {
        store.edit { it[LAST_MODEL] = modelKey }
    }

    suspend fun setInstructions(about: String, style: String) {
        store.edit {
            it[INSTRUCTIONS_ABOUT] = about
            it[INSTRUCTIONS_STYLE] = style
        }
    }

    suspend fun setKeepLoaded(keep: KeepLoaded) {
        store.edit { it[KEEP_LOADED] = keep.name }
    }

    suspend fun setChip(chip: ChipChoice) {
        store.edit { it[CHIP] = chip.name }
    }

    suspend fun setContext(context: ContextChoice) {
        store.edit { it[CONTEXT] = context.name }
    }

    suspend fun setReplyLength(length: ReplyLength) {
        store.edit { it[REPLY_LENGTH] = length.name }
    }

    /**
     * Each model's own sampling values over its card's (D60), by model key; a model without changes is not in the map.
     * Keys are the model's repo and file tag, so another size of the same model keeps its own.
     */
    val modelSampling: Flow<Map<String, Knobs>> =
        store.data.map { p ->
            p
                .asMap()
                .mapNotNull { (k, v) ->
                    k.name.takeIf { it.startsWith(SAMPLING_PREFIX) }?.let { name ->
                        name.removePrefix(SAMPLING_PREFIX) to Knobs.decode(v as? String)
                    }
                }.filter { !it.second.isEmpty }
                .toMap()
        }

    /** Sets [modelKey]'s sampling values; [Knobs.None] gives it back to its card's. */
    suspend fun setModelSampling(modelKey: String, knobs: Knobs) {
        val key = stringPreferencesKey(SAMPLING_PREFIX + modelKey)
        store.edit { if (knobs.isEmpty) it.remove(key) else it[key] = knobs.encode() }
    }

    /**
     * The models (by key) that heard on their last load (`ModelInfo.supportsAudio`), so a chat shows the mic before
     * its model is loaded again (D76): the SDK can only say once a model is loaded.
     */
    val hearingModels: Flow<Set<String>> = store.data.map { it[HEARING_MODELS].orEmpty() }

    /** Records whether [modelKey] heard when it loaded. */
    suspend fun setHears(modelKey: String, hears: Boolean) {
        store.edit {
            val now = it[HEARING_MODELS].orEmpty()
            if ((modelKey in now) != hears) it[HEARING_MODELS] = if (hears) now + modelKey else now - modelKey
        }
    }

    suspend fun markFirstRunSeen() {
        store.edit { it[SEEN_FIRST_RUN] = true }
    }

    suspend fun addPendingDownload(modelKey: String) {
        store.edit { it[PENDING_DOWNLOADS] = it[PENDING_DOWNLOADS].orEmpty() + modelKey }
    }

    /** A download that ends, is cancelled or arrives is neither pending nor paused, nor waiting for a token. */
    suspend fun removePendingDownloads(modelKeys: Collection<String>) {
        if (modelKeys.isEmpty()) return
        store.edit {
            it[PENDING_DOWNLOADS] = it[PENDING_DOWNLOADS].orEmpty() - modelKeys.toSet()
            it[PAUSED_DOWNLOADS] = it[PAUSED_DOWNLOADS].orEmpty() - modelKeys.toSet()
            it[DOWNLOADS_NEEDING_TOKEN] = it[DOWNLOADS_NEEDING_TOKEN].orEmpty() - modelKeys.toSet()
        }
    }

    suspend fun setDownloadPaused(modelKey: String, paused: Boolean) {
        store.edit {
            val now = it[PAUSED_DOWNLOADS].orEmpty()
            it[PAUSED_DOWNLOADS] = if (paused) now + modelKey else now - modelKey
        }
    }

    suspend fun setDownloadNeedsToken(modelKey: String, needs: Boolean) {
        store.edit {
            val now = it[DOWNLOADS_NEEDING_TOKEN].orEmpty()
            it[DOWNLOADS_NEEDING_TOKEN] = if (needs) now + modelKey else now - modelKey
        }
    }

    private companion object {
        const val SAMPLING_PREFIX = "sampling:"
        val PENDING_DOWNLOADS = stringSetPreferencesKey("pending_downloads")
        val PAUSED_DOWNLOADS = stringSetPreferencesKey("paused_downloads")
        val DOWNLOADS_NEEDING_TOKEN = stringSetPreferencesKey("downloads_needing_token")
        val HEARING_MODELS = stringSetPreferencesKey("hearing_models")
        val KEEP_LOADED = stringPreferencesKey("keep_loaded")
        val CHIP = stringPreferencesKey("chip")
        val CONTEXT = stringPreferencesKey("context")
        val REPLY_LENGTH = stringPreferencesKey("reply_length")
        val LAYOUT = stringPreferencesKey("layout")
        val THEME = stringPreferencesKey("theme")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val LAST_MODEL = stringPreferencesKey("last_model")
        val INSTRUCTIONS_ABOUT = stringPreferencesKey("instructions_about")
        val INSTRUCTIONS_STYLE = stringPreferencesKey("instructions_style")
        val SEEN_FIRST_RUN = booleanPreferencesKey("seen_first_run")
    }
}
