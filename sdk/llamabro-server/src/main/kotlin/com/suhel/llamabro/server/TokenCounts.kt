package com.suhel.llamabro.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A response's token counts, each null when the body doesn't say. */
internal data class Counts(val prompt: Int?, val predicted: Int?)

/**
 * Reads a response's token counts as its bytes pass through, from the final `timings` object (llama-server's
 * `prompt_n`, `predicted_n`) or else `usage` (OpenAI's `prompt_tokens`, `completion_tokens`; Anthropic's and the
 * Responses API's `input_tokens`, `output_tokens`). An event stream keeps only its last data event that carries one,
 * so a long stream is never held; a JSON body is read whole, up to [LIMIT] bytes, as is any one line of a stream.
 */
internal class TokenCounts(private val streaming: Boolean) {
    private val pending = Bytes()
    private var last: String? = null

    fun feed(bytes: ByteArray) {
        if (!streaming) {
            pending.append(bytes, 0, bytes.size)
            return
        }
        var start = 0
        bytes.forEachIndexed { i, byte ->
            if (byte == NEWLINE) {
                pending.append(bytes, start, i)
                // splitting at '\n' never splits a UTF-8 character
                pending.decode()?.let(::takeLine)
                pending.clear()
                start = i + 1
            }
        }
        pending.append(bytes, start, bytes.size)
    }

    fun counts(): Counts {
        val text = if (streaming) last else pending.decode()
        val json = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
        return json?.let(::countsIn) ?: Counts(null, null)
    }

    private fun takeLine(line: String) {
        if (!line.startsWith(DATA)) return
        val payload = line.removePrefix(DATA).trim()
        if ("\"timings\"" in payload || "\"usage\"" in payload) last = payload
    }

    private fun countsIn(json: JsonObject): Counts? =
        // the Responses API's last event nests its usage in the response it completes
        listOfNotNull(json, json["response"] as? JsonObject).firstNotNullOfOrNull { holder ->
            val timings = holder["timings"] as? JsonObject
            val usage = holder["usage"] as? JsonObject
            when {
                timings != null -> {
                    Counts(timings.int("prompt_n"), timings.int("predicted_n"))
                }

                usage != null -> {
                    Counts(
                        usage.int("prompt_tokens") ?: usage.int("input_tokens"),
                        usage.int("completion_tokens") ?: usage.int("output_tokens"),
                    )
                }

                else -> {
                    null
                }
            }
        }

    private fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull

    /** A growing byte run that gives up past [LIMIT]: a body too big to be a final event isn't one. */
    private class Bytes {
        private var buffer = ByteArray(INITIAL)
        private var size = 0
        private var overflowed = false

        fun append(from: ByteArray, start: Int, end: Int) {
            val count = end - start
            if (overflowed || size + count > LIMIT) {
                overflowed = true
                return
            }
            if (size + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + count))
            from.copyInto(buffer, size, start, end)
            size += count
        }

        fun decode(): String? = if (overflowed) null else buffer.decodeToString(0, size)

        fun clear() {
            size = 0
            overflowed = false
        }
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
        const val DATA = "data:"
        const val INITIAL = 256
        const val LIMIT = 1 shl 20
    }
}
