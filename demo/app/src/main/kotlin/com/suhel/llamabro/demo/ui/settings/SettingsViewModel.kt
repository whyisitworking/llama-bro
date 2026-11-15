package com.suhel.llamabro.demo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.chat.InMemory
import com.suhel.llamabro.demo.chat.Knob
import com.suhel.llamabro.demo.chat.ModelMemory
import com.suhel.llamabro.demo.chat.SamplingLayers
import com.suhel.llamabro.demo.chat.SamplingStore
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.data.AppSettings
import com.suhel.llamabro.demo.data.KeepLoaded
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.TokenStore
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the settings screens read and change. The token's text never leaves [TokenStore]. */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val models: ModelRepository,
    private val memory: ModelMemory,
    private val sampling: SamplingStore,
) : ViewModel() {
    val state: StateFlow<AppSettings> =
        settings.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT),
            AppSettings(),
        )
    val library: StateFlow<List<LibraryModel>> =
        models.library.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT),
            emptyList(),
        )

    /** The model in memory now, or null (Settings › Memory). */
    val inMemory: StateFlow<InMemory?> =
        memory.inMemory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /** A reply holds the model: Unload waits until it ends. */
    val replying: StateFlow<Boolean> get() = memory.replying

    /** Settings › Sampling's models, each with how many of its values were changed (D60). */
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

    fun setTheme(theme: ThemeMode) = viewModelScope.launch { settings.setTheme(theme) }.let { }

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
