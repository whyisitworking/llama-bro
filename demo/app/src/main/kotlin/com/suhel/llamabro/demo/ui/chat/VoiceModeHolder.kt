package com.suhel.llamabro.demo.ui.chat

import com.suhel.llamabro.demo.chat.VoiceChat
import com.suhel.llamabro.demo.chat.VoiceConversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Voice mode while it is open (D100), and whether its chat already had messages when it opened. */
data class OpenVoice(val conversation: VoiceConversation, val startsFresh: Boolean)

/**
 * A chat screen's voice mode (D100): [open] starts it in the chat [chat] gives (created now if it is new) through
 * [start]; [close] ends it. Upstream's conversation starts fresh, without the chat's earlier messages, so a chat that
 * [hasMessages] says so ([OpenVoice.startsFresh]).
 */
class VoiceModeHolder(
    private val scope: CoroutineScope,
    private val start: ((VoiceChat) -> VoiceConversation)?,
    private val chat: suspend () -> VoiceChat?,
    private val hasMessages: () -> Boolean,
) {
    // user input: voice mode opened or closed; it owns the open conversation
    private val _open = MutableStateFlow<OpenVoice?>(null)
    val open: StateFlow<OpenVoice?> = _open.asStateFlow()

    /** Whether voice mode can open here at all (a microphone and a speaker exist). */
    val available: Boolean get() = start != null

    fun open() {
        val begin = start ?: return
        if (_open.value != null) return
        scope.launch {
            val earlier = hasMessages()
            val target = chat() ?: return@launch
            if (_open.value != null) return@launch
            _open.value = OpenVoice(begin(target).also { it.open() }, startsFresh = earlier)
        }
    }

    fun close() {
        _open.value?.conversation?.close()
        _open.value = null
    }
}
