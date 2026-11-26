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
import com.suhel.llamabro.demo.data.settings.FiltersChange
import com.suhel.llamabro.demo.data.settings.Knobs
import com.suhel.llamabro.demo.data.settings.LoadConfig
import com.suhel.llamabro.demo.data.settings.SearchFilters
import com.suhel.llamabro.demo.data.settings.SearchOrder
import com.suhel.llamabro.demo.data.settings.SizeFilter
import com.suhel.llamabro.demo.data.settings.Theme
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
 * The chip the person chose in Settings › Processor (D72). [Automatic] is the SDK's `Auto`; a chip is `Prefer`, so a
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
 * The context length chosen in Settings › Inference: [Automatic] is the SDK's `Auto` (the most that fits in memory, up
 * to 16k); the others are `UpTo`, a ceiling the SDK still shrinks to fit memory, so a load never fails for it.
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
 * The reply length chosen in Settings › Inference, for every model and chat: [Automatic] is the Think level's limit
 * (D60); [Short] and [Long] are a fixed limit, thinking and answer together; [NoLimit] sends none, so a reply runs
 * until the model stops or the context is full.
 */
enum class ReplyLength {
    Automatic,
    Short,
    Long,
    NoLimit,
}

/** What the next load asks for: the chip choice and the context length (Settings › Processor and Inference). */
val AppSettings.loadConfig: LoadConfig get() = LoadConfig(chip.policy, context.size)

data class AppSettings(
    val layout: AppLayout = AppLayout.Sidebar,
    val theme: Theme = Theme.System,
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
    /** Settings › Voice: voice mode's voice, captions and turns, and dictation (D100, D140, D147). */
    val voice: VoicePrefs = VoicePrefs(),
    /** What Hugging Face search asks for (models-search design §5), remembered across launches. */
    val searchFilters: SearchFilters = SearchFilters(),
    /**
     * The app's glass as tuned in Settings › Appearance › Glass (D152), as the screens saved it (they read and write
     * it: this layer knows no design system, D144); null until it is tuned, or once it is reset.
     */
    val glass: String? = null,
)

/** The model a new chat starts with: the pinned one, else the one used last (demo design §4.2). */
fun AppSettings.modelForNewChat(): String? = defaultModel ?: lastModel

/** DataStore Preferences `settings.preferences_pb`; Settings › Voice's setters are [voice]'s. */
class SettingsRepository(private val store: DataStore<Preferences>) {
    val voice: VoiceSettings = VoiceSettings(store)

    val settings: Flow<AppSettings> =
        store.data.map { p ->
            AppSettings(
                layout = if (p[LAYOUT] == "chat_list") AppLayout.ChatList else AppLayout.Sidebar,
                theme =
                    when (p[THEME]) {
                        "light" -> Theme.Light
                        "dark" -> Theme.Dark
                        else -> Theme.System
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
                voice = VoiceSettings.prefsOf(p),
                searchFilters = searchFiltersOf(p),
                glass = p[GLASS],
            )
        }

    suspend fun setLayout(layout: AppLayout) {
        store.edit { it[LAYOUT] = if (layout == AppLayout.ChatList) "chat_list" else "sidebar" }
    }

    suspend fun setTheme(theme: Theme) {
        store.edit { it[THEME] = theme.name.lowercase() }
    }

    /** Saves [glass] as the app's (D152); null forgets it, so the app's default applies, whatever it becomes. */
    suspend fun setGlass(glass: String?) {
        store.edit { if (glass == null) it.remove(GLASS) else it[GLASS] = glass }
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

    /**
     * Changes [modelKey]'s own sampling values in one edit, so two quick changes both land; [Knobs.None] gives it back
     * to its card's.
     */
    suspend fun updateModelSampling(modelKey: String, change: (Knobs) -> Knobs) {
        val key = stringPreferencesKey(SAMPLING_PREFIX + modelKey)
        store.edit {
            val knobs = change(Knobs.decode(it[key]))
            if (knobs.isEmpty) it.remove(key) else it[key] = knobs.encode()
        }
    }

    /**
     * What each model (by key) can do, so a chat shows the Think chip, photos and the mic before its model loads: read
     * from its header once it is on the phone (D155), then corrected by each load (D76).
     */
    val modelSenses: Flow<Map<String, ModelSenses>> =
        store.data.map { p ->
            p
                .asMap()
                .mapNotNull { (k, v) ->
                    k.name.takeIf { it.startsWith(SENSES_PREFIX) }?.let { name ->
                        name.removePrefix(SENSES_PREFIX) to decodeSenses(v as? String)
                    }
                }.toMap()
        }

    /**
     * Records what [modelKey] can do; [ifUnknown] writes only when nothing is recorded yet, so a header read never
     * overwrites what a load said meanwhile. The first write drops the hearing-only set it replaces.
     */
    suspend fun setSenses(modelKey: String, senses: ModelSenses, ifUnknown: Boolean = false) {
        val key = stringPreferencesKey(SENSES_PREFIX + modelKey)
        store.edit {
            if (!ifUnknown || it[key] == null) it[key] = encodeSenses(senses)
            it.remove(HEARING_MODELS)
        }
    }

    /**
     * The models (by key) that spoke on their last load, with their voices (`ModelInfo.voices`), so a chat shows the
     * voice-mode button before its model is loaded again and Settings › Voice can list the voices (D100), as for
     * hearing.
     */
    val talkingModels: Flow<Map<String, List<String>>> =
        store.data.map { p ->
            p[TALKING_MODELS].orEmpty().associate { entry ->
                entry.substringBefore(VOICES_AFTER) to
                    entry.substringAfter(VOICES_AFTER, "").split(VOICE_SEPARATOR).filter { it.isNotEmpty() }
            }
        }

    /** Records the voices [modelKey] spoke with when it loaded; none means it doesn't talk. */
    suspend fun setVoices(modelKey: String, voices: List<String>) {
        store.edit {
            val now = it[TALKING_MODELS].orEmpty()
            val others = now.filterNot { e -> e.substringBefore(VOICES_AFTER) == modelKey }.toSet()
            val next =
                if (voices.isEmpty()) {
                    others
                } else {
                    others +
                        (modelKey + VOICES_AFTER + voices.joinToString(VOICE_SEPARATOR))
                }
            if (next != now) it[TALKING_MODELS] = next
        }
    }

    suspend fun setSearchFilters(filters: SearchFilters) = updateSearchFilters { filters }

    /**
     * Applies [change] to the filters as stored, inside the store's one edit: two changes made at once both land, as
     * the store runs its edits one after the other on what the last one wrote (D128).
     */
    suspend fun updateSearchFilters(change: FiltersChange) {
        store.edit {
            val filters = change(searchFiltersOf(it))
            it[SEARCH_SORT] = filters.sort.name
            it[SEARCH_SIZE] = filters.size.encode()
            it[SEARCH_SEES_IMAGES] = filters.seesImages
        }
    }

    private fun searchFiltersOf(p: Preferences): SearchFilters =
        SearchFilters(
            sort = SearchOrder.entries.firstOrNull { it.name == p[SEARCH_SORT] } ?: SearchOrder.MostDownloaded,
            size = SizeFilter.decode(p[SEARCH_SIZE]),
            seesImages = p[SEARCH_SEES_IMAGES] ?: false,
        )

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
        const val SENSES_PREFIX = "senses:"
        val PENDING_DOWNLOADS = stringSetPreferencesKey("pending_downloads")
        val PAUSED_DOWNLOADS = stringSetPreferencesKey("paused_downloads")
        val DOWNLOADS_NEEDING_TOKEN = stringSetPreferencesKey("downloads_needing_token")

        /** Before D155: the models that heard on their last load. Each model's senses hold it now. */
        val HEARING_MODELS = stringSetPreferencesKey("hearing_models")
        val TALKING_MODELS = stringSetPreferencesKey("talking_models")

        /** A talking model's entry: its key, a tab, then its voices separated by commas. */
        const val VOICES_AFTER = "\t"
        const val VOICE_SEPARATOR = ","
        val KEEP_LOADED = stringPreferencesKey("keep_loaded")
        val CHIP = stringPreferencesKey("chip")
        val CONTEXT = stringPreferencesKey("context")
        val REPLY_LENGTH = stringPreferencesKey("reply_length")
        val LAYOUT = stringPreferencesKey("layout")
        val THEME = stringPreferencesKey("theme")
        val GLASS = stringPreferencesKey("glass")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val LAST_MODEL = stringPreferencesKey("last_model")
        val INSTRUCTIONS_ABOUT = stringPreferencesKey("instructions_about")
        val INSTRUCTIONS_STYLE = stringPreferencesKey("instructions_style")
        val SEEN_FIRST_RUN = booleanPreferencesKey("seen_first_run")
        val SEARCH_SORT = stringPreferencesKey("search_sort")
        val SEARCH_SIZE = stringPreferencesKey("search_size")
        val SEARCH_SEES_IMAGES = booleanPreferencesKey("search_sees_images")
    }
}

/** What a model can do: think (the Think chip), see pictures, hear voice messages (D76, D155). */
data class ModelSenses(val thinks: Boolean, val sees: Boolean, val hears: Boolean)

/** A model's senses as stored: the names of those it has, separated by commas. */
private fun encodeSenses(senses: ModelSenses): String =
    listOfNotNull(
        "thinks".takeIf { senses.thinks },
        "sees".takeIf { senses.sees },
        "hears".takeIf { senses.hears },
    ).joinToString(",")

private fun decodeSenses(stored: String?): ModelSenses {
    val names = stored.orEmpty().split(",")
    return ModelSenses(thinks = "thinks" in names, sees = "sees" in names, hears = "hears" in names)
}
