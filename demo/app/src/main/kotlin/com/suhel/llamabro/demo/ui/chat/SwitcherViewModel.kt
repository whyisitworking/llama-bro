package com.suhel.llamabro.demo.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.chat.InMemory
import com.suhel.llamabro.demo.chat.SamplingLayers
import com.suhel.llamabro.demo.data.settings.Knob
import com.suhel.llamabro.demo.data.settings.Knobs
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.LibraryModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the model switcher sheet and the model card opened from it show and write: the phone's library, the fit facts
 * (already for the chip chosen in Settings › Memory, D72, which the other pane can change meanwhile), the model in
 * memory, and the chat's own sampling. The sheet's writes run on [background], the app's scope: the sheet closes as
 * it writes ("Use for new chats", Unload), and its own scope ends with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitcherViewModel(
    library: Flow<List<LibraryModel>>,
    facts: Flow<DeviceFacts>,
    inMemory: Flow<InMemory?>,
    replying: StateFlow<Boolean>,
    chatId: Flow<Long>,
    /** The chat's own values; a chat with no message yet ([chatId] 0) has none. */
    chatSampling: (Long) -> Flow<Knobs>,
    private val pinDefault: suspend (String) -> Unit,
    private val unloadModel: suspend () -> Unit,
    private val background: CoroutineScope,
) : ViewModel() {
    private val shown = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS)

    /** The models on the phone; null until read (the model card waits for it). */
    val library: StateFlow<List<LibraryModel>?> = library.stateIn(viewModelScope, shown, null)

    val facts: StateFlow<DeviceFacts> = facts.stateIn(viewModelScope, shown, DeviceFacts.Fallback)

    val inMemory: StateFlow<InMemory?> = inMemory.stateIn(viewModelScope, shown, null)

    val replying: StateFlow<Boolean> = replying

    /** How many sampling values the chat changed; null for a chat with no row yet. */
    val chatSampling: StateFlow<Knobs?> =
        chatId
            .flatMapLatest { id -> if (id == 0L) flowOf(null) else chatSampling(id) }
            .stateIn(viewModelScope, shown, null)

    /** "Use for new chats" on [key] (a model's key as text). */
    fun pinDefault(key: String) {
        background.launch { pinDefault.invoke(key) }
    }

    /** Frees the model now unless a reply holds it. */
    fun unload() {
        background.launch { unloadModel() }
    }
}

private const val STOP_TIMEOUT_MILLIS = 5_000L

/**
 * The chat's sampling sheet: the model's values with the chat's own on top, and the edits. The edits run on
 * [background] (the app's scope), so closing the sheet can't cancel a write.
 */
class ChatSamplingViewModel(
    layers: Flow<SamplingLayers>,
    private val setKnob: suspend (Knob, Float?) -> Unit,
    private val resetAll: suspend () -> Unit,
    private val background: CoroutineScope,
) : ViewModel() {
    val layers: StateFlow<SamplingLayers?> =
        layers.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            null,
        )

    fun change(knob: Knob, value: Float?) {
        background.launch { setKnob(knob, value) }
    }

    fun reset() {
        background.launch { resetAll() }
    }
}
