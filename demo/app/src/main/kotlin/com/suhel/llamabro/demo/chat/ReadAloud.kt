package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.speak
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Reads a reply aloud on a model that speaks (D141): its text through `Model.speak`, in the voice chosen in Settings ›
 * Voice when the model has it, played as it is made. One reply at a time; reading another, or the same one again,
 * stops the one playing. The chat's model is held while it reads.
 */
class ReadAloud(
    private val scope: CoroutineScope,
    /** A hold on the chat's model; null when it can't be had. */
    private val hold: suspend () -> VoiceHold?,
    /** The voice chosen in Settings › Voice, or null. */
    private val voice: suspend () -> String?,
    /** Where speech at a rate plays. */
    private val output: (rate: Int) -> SpeechOutput,
    /** A plain line when the reply couldn't be read. */
    private val problem: (String) -> Unit,
) {
    // external event: the reply being read, as it plays
    private val _reading = MutableStateFlow<Long?>(null)

    /** The message being read aloud, or null. */
    val reading: StateFlow<Long?> = _reading.asStateFlow()

    private var job: Job? = null

    /** Reads message [id]'s [text], or stops it when it is the one being read. */
    fun toggle(id: Long, text: String) {
        val same = _reading.value == id
        stop()
        if (same) return
        _reading.value = id
        job = scope.launch { read(id, text) }
    }

    fun stop() {
        job?.cancel()
        job = null
        _reading.value = null
    }

    private suspend fun read(id: Long, text: String) {
        val held = hold()
        var out: SpeechOutput? = null
        try {
            if (held == null) return
            val chosen = voice()?.takeIf { it in held.model.info.voices }
            held.model.speak(spoken(text)) { this.voice = chosen }.collect { audio ->
                val track = out ?: output(audio.sampleRate).also { out = it }
                track.write(audio.pcm)
            }
            out?.drain()
        } catch (e: CancellationException) {
            out?.stop()
            throw e
        } catch (expected: LlamaBroException) {
            problem("That reply couldn't be read aloud.")
        } finally {
            out?.close()
            held?.release()
            _reading.compareAndSet(id, null)
        }
    }

    companion object {
        private val codeBlock = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
        private val link = Regex("""\[([^\]]*)]\([^)]*\)""")
        private val emphasis = Regex("""[*_`~]+""")
        private val lineMarks = Regex("""[#>|]+""")
        private val bullet = Regex("""(?m)^\s*(?:[-+]|\d+[.)])\s+""")
        private val space = Regex("""\s+""")

        /** A reply's Markdown as it is said: code blocks left out, links as their text, list bullets and marks gone. */
        fun spoken(markdown: String): String =
            markdown
                .replace(codeBlock, " ")
                .replace(bullet, " ")
                .replace(link, "$1")
                .replace(emphasis, "")
                .replace(lineMarks, " ")
                .replace(space, " ")
                .trim()
    }
}
