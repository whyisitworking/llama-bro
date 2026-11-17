package com.suhel.llamabro.demo.ui.shell

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ChatSummary
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.ModelRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/** What both layouts read: the layout setting, the chats (searchable), how many models are on the phone. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModel(
    private val chatRepository: ChatRepository,
    settings: SettingsRepository,
    models: ModelRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Keeps the drawer's search across process death. */
    savedState: SavedStateHandle,
    /**
     * Deletes a chat; null deletes it here. The app's runs on its own scope, so the delete outlives this view model,
     * and stops a reply streaming into it first (ChatEngine.delete, D87).
     */
    private val deleteChat: ((Long) -> Unit)? = null,
) : ViewModel() {
    val layout: StateFlow<AppLayout?> =
        settings.settings
            .map<_, AppLayout?> { it.layout }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    val theme: StateFlow<ThemeMode> =
        settings.settings
            .map { it.theme }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), ThemeMode.System)

    /**
     * Whether the app starts on first run: nothing seen yet and no model on the phone. Null until both are known; the
     * shell reads it once, for the start destination.
     */
    val startsWithFirstRun: StateFlow<Boolean?> =
        combine(
            settings.settings.map { it.seenFirstRun },
            models.library.map { it.isEmpty() },
        ) { seen, empty -> !seen && empty }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private val query = savedState.getMutableStateFlow(QUERY_KEY, "")
    val searchText: StateFlow<String> = query

    val chats: StateFlow<List<ChatSummary>> =
        query
            .flatMapLatest { chatRepository.chats(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /** The chats in the drawer's groups. */
    val groups: StateFlow<List<ChatGroup>> =
        chats
            .map { ChatListFormat.group(it, clock(), zone) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    val modelCount: StateFlow<Int> =
        models.library
            .map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    fun search(q: String) {
        query.value = q
    }

    fun rename(id: Long, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { chatRepository.rename(id, title) }
    }

    fun delete(id: Long) {
        val delete = deleteChat
        if (delete != null) delete(id) else viewModelScope.launch { chatRepository.delete(id) }
    }

    fun now(): Long = clock()

    fun zone(): ZoneId = zone

    private companion object {
        const val STOP_TIMEOUT = 5_000L
        const val QUERY_KEY = "query"
    }
}
