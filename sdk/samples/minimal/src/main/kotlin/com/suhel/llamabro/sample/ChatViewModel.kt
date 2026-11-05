package com.suhel.llamabro.sample

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.conversation
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.conversation.canCompact
import com.suhel.llamabro.conversation.compact
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the model, so it outlives rotation, and closes it in [onCleared] (that returns at once; the native shutdown
 * runs in the background). Takes [LlamaBro] as a parameter so a test can pass `FakeLlamaBro`'s.
 */
class ChatViewModel(private val llama: LlamaBro, private val modelPath: String?) : ViewModel() {
    private var model: Model? = null
    private var chat: Conversation? = null
    private var sending: Job? = null

    private val _status = MutableStateFlow("")
    /** Loading progress, then the model's description, or why it didn't load. */
    val status: StateFlow<String> = _status.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _transcript = MutableStateFlow("")
    /** The conversation as text, including the reply while it streams (`Conversation.reply`). */
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _stats = MutableStateFlow<String?>(null)
    /** The last reply's speed, or an error. */
    val stats: StateFlow<String?> = _stats.asStateFlow()

    init {
        load()
    }

    private fun load() = viewModelScope.launch {
        val path = modelPath ?: run { _status.value = "No .gguf in the app's files"; return@launch }
        _status.value = "Loading ${File(path).name}…"
        try {
            val m = llama.loadModel(ModelSource.file(path)) {
                onProgress { _status.value = "Loading ${File(path).name}… ${(it * 100).toInt()}%" }
            }
            model = m
            val c = m.conversation(system = "You are a helpful assistant.")
            chat = c
            _status.value = "${m.info.description}, context ${m.info.contextSize}"
            launch { combine(c.messages, c.reply) { messages, reply -> render(messages, reply) }.collect { _transcript.value = it } }
            _ready.value = true
        } catch (e: LlamaBroException) {
            _status.value = "Load failed: ${e.message}"
        }
    }

    fun send(text: String) {
        val c = chat ?: return
        if (text.isBlank() || sending?.isActive == true) return
        sending = viewModelScope.launch {
            _stats.value = null
            try {
                collectReply(c.send(text))
            } catch (e: LlamaBroException.ContextExceeded) {
                // the context is full: summarise the older turns (tools/ui's compaction), then answer again
                if (!c.canCompact()) {
                    _stats.value = "[the conversation is too long: ${e.message}]"
                    return@launch
                }
                c.compact().collect()
                collectReply(c.resume())
            } catch (e: LlamaBroException) {
                _stats.value = "[error: ${e.message}]"
            }
        }
    }

    /** Stops the reply; the part streamed so far stays in the conversation. */
    fun stop() {
        sending?.cancel()
    }

    private suspend fun collectReply(events: Flow<ChatEvent>) = events.collect { e ->
        if (e is ChatEvent.Done) {
            e.timings?.let { _stats.value = "[%.1f t/s prefill, %.1f t/s decode]".format(it.promptPerSecond, it.predictedPerSecond) }
        }
    }

    override fun onCleared() {
        model?.close()
    }

    companion object {
        fun factory(app: Application, modelPath: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(LlamaBro.getInstance(app), modelPath) }
        }

        fun render(messages: List<Message>, reply: Message.Assistant?): String = buildString {
            for (m in messages + listOfNotNull(reply)) {
                when (m) {
                    is Message.User -> append("\n> ").append(m.content.filterIsInstance<Content.Text>().joinToString(" ") { it.text }).append('\n')
                    is Message.Assistant -> m.content?.let { append(it).append('\n') }
                    is Message.Compaction -> append("\n[older turns summarised]\n")
                    else -> Unit
                }
            }
        }
    }
}
