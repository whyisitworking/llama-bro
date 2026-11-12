package com.suhel.llamabro.internal

import java.io.ByteArrayOutputStream

/**
 * Splits a server-sent-events byte stream into `data:` payloads. Works on bytes and decodes only complete
 * events, so a multi-byte UTF-8 character split across native chunks arrives intact. Comment lines
 * (`: ping`) and other fields are skipped; several `data:` lines in one event are joined with `\n` (SSE spec).
 */
internal class SseParser {
    private val pending = ByteArrayOutputStream()

    fun feed(bytes: ByteArray): List<String> {
        pending.write(bytes)
        val buf = pending.toByteArray()
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i + 1 < buf.size) {
            if (buf[i] == '\n'.code.toByte() && buf[i + 1] == '\n'.code.toByte()) {
                event(String(buf, start, i - start, Charsets.UTF_8))?.let { out += it }
                start = i + 2
                i = start
            } else {
                i++
            }
        }
        pending.reset()
        pending.write(buf, start, buf.size - start)
        return out
    }

    private fun event(text: String): String? {
        val data =
            text
                .split('\n')
                .map { it.removeSuffix("\r") }
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").removePrefix(" ") }
        return if (data.isEmpty()) null else data.joinToString("\n")
    }
}
