package com.suhel.llamabro.demo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Voice messages (D76): WAV files in app-private storage, one per message, deleted with their chat and never before it,
 * since the SDK reads a message's file again on every request that sends the chat (D84).
 */
class AudioStore(private val dir: File) {
    /** A new file's name for a recording; the recorder creates the folder, off the main thread. */
    fun newName(): String = UUID.randomUUID().toString() + ".wav"

    fun file(name: String): File = File(dir, name)

    suspend fun delete(names: List<String>) {
        withContext(Dispatchers.IO) { names.forEach { file(it).delete() } }
    }
}

/** A voice message as stored: its file's [name], its length and its [levels] (0 to 1) for the bubble. */
data class NewVoice(val name: String, val millis: Long, val levels: List<Float>)

/** A stored voice message: its [file], its length and its [levels] (0 to 1). */
data class StoredVoice(val file: File, val millis: Long, val levels: List<Float>)

/** A clip's levels in one short string: a letter per level, `a` silent to `z` loudest. */
object Levels {
    private const val STEPS = 25

    fun encode(levels: List<Float>): String =
        levels.joinToString("") { ('a' + (it.coerceIn(0f, 1f) * STEPS).roundToInt()).toString() }

    fun decode(text: String?): List<Float> = text.orEmpty().map { ((it - 'a').coerceIn(0, STEPS)).toFloat() / STEPS }
}
