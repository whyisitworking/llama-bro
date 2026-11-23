package com.suhel.llamabro.demo.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * Transcript text as dictation shows it, ported from llama.cpp's web client (b12653 ui/lib/voice/src/transcript.ts,
 * realtime.ts joinPieces, dictation-span.ts; D140).
 */
object Transcripts {
    private val asrPrefix = Regex("""^\s*(?:language\s+[^<\n]{0,40})?<asr_text>""", RegexOption.IGNORE_CASE)
    private val asrTag = Regex("""</?asr_text>""", RegexOption.IGNORE_CASE)
    private val segmentsStart = Regex("""^\s*\[\s*(?:\{\s*(?:"|$)|$)""")
    private val segmentContent = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)""", RegexOption.IGNORE_CASE)
    private val unfinishedEscape = Regex("""\\(?:u[0-9a-f]{0,3})?$""", RegexOption.IGNORE_CASE)
    private val closingPunctuation = Regex("""^[,.;:!?)\]}]""")

    /**
     * A transcript as the speaker's words alone. Some speech-to-text models wrap them in a format of their own, which
     * the server passes on as it is: Qwen3-ASR names the language first (`language French<asr_text>Bonjour`),
     * VibeVoice-ASR answers with timed segments. Text in neither format is returned untouched, so text still streaming
     * keeps its spaces; segments cut short give the words they hold so far.
     */
    fun clean(text: String): String {
        if (segmentsStart.containsMatchIn(text)) return segmentsText(text)
        if (!asrTag.containsMatchIn(text)) return text
        return text.replaceFirst(asrPrefix, "").replace(asrTag, "").trim()
    }

    /** [piece] after [before], with a space between unless either already has one there. */
    fun join(before: String, piece: String): String =
        when {
            before.isEmpty() -> piece
            piece.isEmpty() -> before
            before.last().isWhitespace() || piece.first().isWhitespace() -> before + piece
            else -> "$before $piece"
        }

    /**
     * [dictated] written into what was typed: after [typed], with a space where the two would run together, none
     * before text that goes on with punctuation (", and then").
     */
    fun insert(typed: String, dictated: String): String {
        val body = dictated.trim()
        if (body.isEmpty()) return typed
        val lead = typed.isNotEmpty() && !typed.last().isWhitespace() && !closingPunctuation.containsMatchIn(body)
        return typed + (if (lead) " " else "") + body
    }

    private fun segmentsText(text: String): String =
        segmentContent
            .findAll(text)
            .map { unescape(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** The value of a JSON string's inside, which may stop in the middle of an escape. */
    private fun unescape(inside: String): String =
        decode(inside) ?: decode(inside.replace(unfinishedEscape, "")) ?: inside

    private fun decode(inside: String): String? =
        runCatching { Json.parseToJsonElement("\"$inside\"").jsonPrimitive.content }.getOrNull()
}
