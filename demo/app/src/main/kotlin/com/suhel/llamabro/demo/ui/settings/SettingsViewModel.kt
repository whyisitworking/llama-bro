package com.suhel.llamabro.demo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.data.AppSettings
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.TokenStore
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the settings screens read and change. The token's text never leaves [TokenStore]. */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val models: ModelRepository,
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
