package com.suhel.llamabro.demo.ui.settings

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.chat.InMemory
import com.suhel.llamabro.demo.chat.ModelMemory
import com.suhel.llamabro.demo.chat.SamplingLayers
import com.suhel.llamabro.demo.chat.SamplingStore
import com.suhel.llamabro.demo.chat.contextLine
import com.suhel.llamabro.demo.chat.runsOnLine
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.data.AppSettings
import com.suhel.llamabro.demo.data.ChipChoice
import com.suhel.llamabro.demo.data.ContextChoice
import com.suhel.llamabro.demo.data.KeepLoaded
import com.suhel.llamabro.demo.data.ReplyLength
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.TokenStore
import com.suhel.llamabro.demo.data.VoiceTurns
import com.suhel.llamabro.demo.data.loadConfig
import com.suhel.llamabro.demo.data.settings.Knob
import com.suhel.llamabro.demo.designsystem.theme.GlassTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.forChat
import com.suhel.llamabro.demo.ui.theme
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** What the settings screens read and change. The token's text never leaves [TokenStore]. */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val models: ModelRepository,
    private val memory: ModelMemory,
    private val sampling: SamplingStore,
    /** The phone's chips, for Settings › Processor's choice (D72); the SDK is asked once, by the container. */
    private val facts: suspend () -> DeviceFacts = { DeviceFacts.Fallback },
) : ViewModel() {
    val state: StateFlow<AppSettings> =
        settings.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT),
            AppSettings(),
        )

    /** The chat models on the phone, for the default model's choice ([forChat]: never the Library's embedder). */
    val library: StateFlow<List<LibraryModel>> =
        models.library.map { it.forChat() }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT),
            emptyList(),
        )

    /** The model in memory now, or null (Settings › Memory). */
    val inMemory: StateFlow<InMemory?> =
        memory.inMemory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /** A reply holds the model: Unload waits until it ends. */
    val replying: StateFlow<Boolean> get() = memory.replying

    /**
     * The line under the chip choice (D72): where the model runs and why when it landed off the chip chosen, or when a
     * model loaded for an earlier choice moves; null when there is nothing to say.
     */
    val runsOn: StateFlow<String?> =
        combine(memory.ready, settings.settings, memory.replying) { ready, s, replying ->
            runsOnLine(ready, s.chip, replying)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /** The line under the context length: when a model loaded at another length reloads at the one chosen. */
    val contextLine: StateFlow<String?> =
        combine(memory.ready, settings.settings, memory.replying) { ready, s, replying ->
            contextLine(ready, s.context, replying)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /** The kinds of chip this phone has; every kind until the SDK answers, so nothing is marked missing too soon. */
    val chips: StateFlow<Set<DeviceKind>> =
        flow { emit(facts().kinds) }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT),
            DeviceKind.entries.toSet(),
        )

    /** Settings › Inference's models, each with how many of its values were changed (D60). */
    val samplingRows: StateFlow<List<SamplingModelRow>> =
        combine(models.library, sampling.changed) { lib, changed ->
            samplingRows(lib, changed.mapValues { it.value.values.size })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /** [key]'s card and its own values. */
    fun samplingOf(key: HfModel): Flow<SamplingLayers> = sampling.model(key)

    fun setSampling(key: HfModel, knob: Knob, value: Float) =
        viewModelScope.launch { sampling.setModel(key, knob, value) }.let { }

    fun resetSampling(key: HfModel) = viewModelScope.launch { sampling.resetModel(key) }.let { }

    fun setKeepLoaded(keep: KeepLoaded) = viewModelScope.launch { settings.setKeepLoaded(keep) }.let { }

    fun unload() = viewModelScope.launch { memory.unload() }.let { }

    // the one wait for replies to end after a chip or context change: a newer choice of either replaces it
    private var loadChange: Job? = null

    /** Saves [chip] and frees a model loaded for another choice once no reply holds it (D72). */
    fun setChip(chip: ChipChoice) = changeLoad { settings.setChip(chip) }

    /** Saves [context] and, as for a chip (D72), frees a model loaded for another length once no reply holds it. */
    fun setContext(context: ContextChoice) = changeLoad { settings.setContext(context) }

    /**
     * Saves a choice, then waits to free a model loaded under another config. Only the wait is cancelled by a newer
     * choice: the save runs in its own job, so a chip changed just before a context change is never lost.
     */
    private fun changeLoad(save: suspend () -> Unit) {
        val saved = viewModelScope.launch { save() }
        loadChange?.cancel()
        loadChange =
            viewModelScope.launch {
                saved.join()
                memory.loadConfigChanged { settings.settings.first().loadConfig }
            }
    }

    /** Waits for the pending chip or context change, if any, to end (tests). */
    @VisibleForTesting
    internal suspend fun awaitLoadChange() {
        loadChange?.join()
    }

    fun setReplyLength(length: ReplyLength) = viewModelScope.launch { settings.setReplyLength(length) }.let { }

    /**
     * Voice mode's voices (D100): those of the models that spoke on their last load, in the order first met; empty
     * until a model that talks has loaded.
     */
    val voices: StateFlow<List<String>> =
        settings.talkingModels
            .map { models -> models.values.flatten().distinct() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    fun setVoice(voice: String) = viewModelScope.launch { settings.voice.setVoice(voice) }.let { }

    fun setCaptionYou(caption: Boolean) = viewModelScope.launch { settings.voice.setCaptionYou(caption) }.let { }

    fun setVoiceTurns(turns: VoiceTurns) = viewModelScope.launch { settings.voice.setVoiceTurns(turns) }.let { }

    fun setInterruptByTalking(interrupt: Boolean) =
        viewModelScope.launch { settings.voice.setInterruptByTalking(interrupt) }.let { }

    /** The phone's language, offered for dictation (D140); read once, as the app's locale stands when it opens. */
    val phoneLanguage: PhoneLanguage =
        PhoneLanguage.of(
            java.util.Locale
                .getDefault()
                .language,
        )

    fun setDictationLanguage(language: String?) =
        viewModelScope.launch { settings.voice.setDictationLanguage(language) }.let { }

    // a counter, not a value: every write asks the store again, so what is shown is what is stored
    private val tokenWrites = MutableStateFlow(0)

    /** Whether a token is saved: asked of the store at the start and after every write. */
    val hasToken: StateFlow<Boolean> =
        tokenWrites.map { tokens.read() != null }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT),
            false,
        )

    fun setLayout(layout: AppLayout) = viewModelScope.launch { settings.setLayout(layout) }.let { }

    fun setTheme(theme: ThemeMode) = viewModelScope.launch { settings.setTheme(theme.theme()) }.let { }

    /** Saves [glass] as the app's (D152); null goes back to the default. */
    fun setGlass(glass: GlassTheme?) =
        viewModelScope.launch { settings.setGlass(glass?.let(SavedGlass::encode)) }.let { }

    fun pinDefault(modelKey: String?) = viewModelScope.launch { settings.pinDefault(modelKey) }.let { }

    fun saveInstructions(about: String, style: String) =
        viewModelScope
            .launch {
                settings.setInstructions(about, style)
            }.let { }

    /** Saves a pasted token privately; blank input removes it. */
    fun saveToken(token: String) {
        viewModelScope.launch {
            val trimmed = token.trim()
            tokens.write(trimmed.ifEmpty { null })
            tokenWrites.update { it + 1 }
            if (trimmed.isNotEmpty()) models.tokenSaved()
        }
    }

    fun removeToken() = saveToken("")

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
