package com.suhel.llamabro.demo.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.suhel.llamabro.demo.chat.Knobs
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
    val keepLoaded: KeepLoaded = KeepLoaded.OneMinute,
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
                keepLoaded = KeepLoaded.entries.firstOrNull { it.name == p[KEEP_LOADED] } ?: KeepLoaded.OneMinute,
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

    suspend fun markFirstRunSeen() {
        store.edit { it[SEEN_FIRST_RUN] = true }
    }

    suspend fun addPendingDownload(modelKey: String) {
        store.edit { it[PENDING_DOWNLOADS] = it[PENDING_DOWNLOADS].orEmpty() + modelKey }
    }

    suspend fun removePendingDownloads(modelKeys: Collection<String>) {
        if (modelKeys.isEmpty()) return
        store.edit { it[PENDING_DOWNLOADS] = it[PENDING_DOWNLOADS].orEmpty() - modelKeys.toSet() }
    }

    private companion object {
        const val SAMPLING_PREFIX = "sampling:"
        val PENDING_DOWNLOADS = stringSetPreferencesKey("pending_downloads")
        val KEEP_LOADED = stringPreferencesKey("keep_loaded")
        val LAYOUT = stringPreferencesKey("layout")
        val THEME = stringPreferencesKey("theme")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val LAST_MODEL = stringPreferencesKey("last_model")
        val INSTRUCTIONS_ABOUT = stringPreferencesKey("instructions_about")
        val INSTRUCTIONS_STYLE = stringPreferencesKey("instructions_style")
        val SEEN_FIRST_RUN = booleanPreferencesKey("seen_first_run")
    }
}
