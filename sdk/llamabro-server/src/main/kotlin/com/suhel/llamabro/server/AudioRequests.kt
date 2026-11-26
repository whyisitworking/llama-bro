package com.suhel.llamabro.server

import com.suhel.llamabro.audio.SpeechAudio
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import java.io.IOException

/** The response header that carries `audio/pcm`'s sample rate, which the bytes themselves don't say. */
internal const val SAMPLE_RATE_HEADER = "X-Sample-Rate"

private const val UPLOAD_CHUNK = 64 * 1024
private const val BYTE_MASK = 0xFF
private const val BYTE_BITS = 8
private const val WAV_FMT_SIZE = 16
private const val PCM_FORMAT = 1
private const val PCM_BITS = 16
private const val BYTES_PER_SAMPLE = 2
private const val WAV_STREAMING_SIZE = -1

/** A request the server turns away with [status]: [message] is the client's, [reason] the request log's. */
internal class Refusal(
    val status: HttpStatusCode,
    override val message: String,
    val reason: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private fun invalid(message: String) = Refusal(HttpStatusCode.BadRequest, message, message)

/** OpenAI's transcription form, as far as `Model.transcribe` has a setting for it. */
internal class TranscribeRequest(
    val model: String?,
    val language: String?,
    val prompt: String?,
    val temperature: Float?,
    val text: Boolean,
) {
    companion object {
        /**
         * Reads the multipart body of [call], writing the `file` part to [upload] as it arrives, never holding it in
         * memory, and counting every byte against [maxBytes]. The caller deletes [upload].
         */
        suspend fun read(call: ApplicationCall, upload: Path, maxBytes: Long): TranscribeRequest {
            val fields = call.readForm(upload, maxBytes)
            val format = fields["response_format"] ?: "json"
            if (format != "json" && format != "text") {
                throw invalid("response_format '$format' is not supported; use json or text")
            }
            val temperature =
                fields["temperature"]?.let { it.toFloatOrNull() ?: throw invalid("temperature must be a number") }
            return TranscribeRequest(
                fields["model"],
                fields["language"],
                fields["prompt"],
                temperature,
                format == "text",
            )
        }
    }
}

/** The form fields; the `file` part went to disk. */
private suspend fun ApplicationCall.readForm(upload: Path, maxBytes: Long): Map<String, String> {
    checkMultipart(maxBytes)
    val fields = mutableMapOf<String, String>()
    var received = 0L
    var hasFile = false
    try {
        receiveMultipart().forEachPart { part ->
            try {
                when {
                    part is PartData.FileItem && part.name == "file" && !hasFile -> {
                        received += part.saveTo(upload, maxBytes - received, maxBytes)
                        hasFile = true
                    }

                    part is PartData.FormItem -> {
                        received = fields.add(part, received, maxBytes)
                    }
                }
            } finally {
                part.dispose()
            }
        }
    } catch (e: IOException) {
        throw Refusal(HttpStatusCode.BadRequest, "the multipart body is malformed", "malformed multipart", e)
    }
    if (!hasFile) throw invalid("the form has no file part")
    return fields
}

/** Keeps a text field, counted against the limit like the file's bytes; returns the bytes received so far. */
private fun MutableMap<String, String>.add(part: PartData.FormItem, received: Long, maxBytes: Long): Long {
    val total = received + part.value.length
    if (total > maxBytes) throw tooLarge(maxBytes).asRefusal()
    part.name?.let { this[it] = part.value }
    return total
}

private fun ApplicationCall.checkMultipart(maxBytes: Long) {
    if (!request.contentType().match(ContentType.MultiPart.FormData)) {
        throw invalid("the body must be multipart/form-data")
    }
    val declared = request.contentLength()
    if (declared != null && declared > maxBytes) throw tooLarge(maxBytes).asRefusal()
}

private fun GuardedBody.Refused.asRefusal() = Refusal(status, message, reason)

/** Writes the part to [path], at most [limit] bytes: more is a 413, and the caller deletes what was written. */
private suspend fun PartData.FileItem.saveTo(path: Path, limit: Long, maxBytes: Long): Long =
    withContext(Dispatchers.IO) {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        val input = provider()
        val chunk = ByteArray(UPLOAD_CHUNK)
        var written = 0L
        SystemFileSystem.sink(path).buffered().use { sink ->
            while (true) {
                val read = input.readAvailable(chunk)
                if (read < 0) break
                written += read
                if (written > limit) throw tooLarge(maxBytes).asRefusal()
                sink.write(chunk, 0, read)
            }
        }
        written
    }

/**
 * OpenAI's speech request. Formats: `pcm` (the default) and `wav`, which the SDK's 16-bit PCM can be streamed as;
 * mp3, opus, aac and flac would need an encoder, so they are refused rather than answered in another format.
 */
internal class SpeechRequest(
    val model: String?,
    val input: String,
    val voice: String?,
    val speed: Float?,
    val wav: Boolean,
) {
    companion object {
        fun read(body: JsonObject?): SpeechRequest {
            if (body == null) throw invalid("the body must be a JSON object")
            val wav = isWav(body.text("response_format") ?: "pcm")
            return SpeechRequest(body.text("model"), inputOf(body), body.text("voice"), speedOf(body), wav)
        }

        private fun inputOf(body: JsonObject): String =
            (body["input"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
                ?: throw invalid("input is required: the text to speak")

        private fun speedOf(body: JsonObject): Float? =
            body["speed"]?.let { (it as? JsonPrimitive)?.floatOrNull ?: throw invalid("speed must be a number") }

        private fun isWav(format: String): Boolean =
            when (format) {
                "wav" -> true
                "pcm" -> false
                else -> throw invalid("response_format '$format' is not supported; use pcm or wav")
            }

        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    }
}

/** The pieces' samples as 16-bit little-endian bytes, the order PCM and WAV carry them in. */
internal fun SpeechAudio.littleEndian(): ByteArray {
    val bytes = ByteArray(pcm.size * BYTES_PER_SAMPLE)
    pcm.forEachIndexed { i, sample ->
        bytes[BYTES_PER_SAMPLE * i] = (sample.toInt() and BYTE_MASK).toByte()
        bytes[BYTES_PER_SAMPLE * i + 1] = (sample.toInt() shr BYTE_BITS).toByte()
    }
    return bytes
}

/**
 * A WAV header for speech of unknown length: the RIFF and data sizes are all ones, which players read as "to the end",
 * so the audio streams without being buffered to learn its size.
 */
internal fun wavHeader(sampleRate: Int, channels: Int): ByteArray {
    val blockAlign = channels * BYTES_PER_SAMPLE
    val out = java.io.ByteArrayOutputStream()

    fun int(value: Int) = repeat(Int.SIZE_BYTES) { out.write((value shr (BYTE_BITS * it)) and BYTE_MASK) }

    fun short(value: Int) = repeat(Short.SIZE_BYTES) { out.write((value shr (BYTE_BITS * it)) and BYTE_MASK) }
    out.write("RIFF".toByteArray())
    int(WAV_STREAMING_SIZE)
    out.write("WAVEfmt ".toByteArray())
    int(WAV_FMT_SIZE)
    short(PCM_FORMAT)
    short(channels)
    int(sampleRate)
    int(sampleRate * blockAlign)
    short(blockAlign)
    short(PCM_BITS)
    out.write("data".toByteArray())
    int(WAV_STREAMING_SIZE)
    return out.toByteArray()
}
