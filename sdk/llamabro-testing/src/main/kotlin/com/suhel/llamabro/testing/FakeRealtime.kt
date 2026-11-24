@file:OptIn(InternalLlamaBroApi::class)

package com.suhel.llamabro.testing

import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.spi.EngineRequest
import com.suhel.llamabro.spi.RequestSink
import com.suhel.llamabro.spi.SEND_CANCELLED
import com.suhel.llamabro.spi.SEND_OK
import com.suhel.llamabro.testing.FakeWire.q
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** Upstream's error type for a commit with no audio. */
private const val COMMIT_EMPTY = "input_audio_buffer_commit_empty"

/**
 * A realtime session as the fake answers it (server-realtime.cpp): every client event is recorded in
 * [FakeLlamaBro.realtimeEvents], then answered on the sending thread, one event at a time, by [handle].
 */
internal abstract class FakeRealtimeRequest(protected val fake: FakeLlamaBro, private val sink: RequestSink) :
    EngineRequest {
    private val open = AtomicBoolean(true)

    final override fun send(message: ByteArray): Int =
        synchronized(this) {
            if (!open.get()) return SEND_CANCELLED
            val text = String(message, Charsets.UTF_8)
            synchronized(fake.lock) { fake.realtime += text }
            val json = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
            if (json?.get("type") !is JsonPrimitive) event(error("invalid JSON")) else handle(json)
            SEND_OK
        }

    /** Answers one client event; [json] is an object with a `type`. */
    protected abstract fun handle(json: JsonObject)

    protected fun typeOf(json: JsonObject): String = (json["type"] as JsonPrimitive).content

    protected fun error(message: String, type: String = "invalid_request_error") =
        """{"type":"error","error":{"type":"$type","code":null,"message":${q(message)},"param":null}}"""

    protected fun event(json: String) = sink.onChunk(200, json.toByteArray(Charsets.UTF_8))

    protected fun end() {
        if (open.compareAndSet(true, false)) sink.onDone()
    }

    final override fun cancel() {
        synchronized(this) {
            if (open.compareAndSet(true, false)) sink.onCancelled()
        }
    }

    final override fun free() {}
}

/**
 * Upstream's realtime conversation as far as a voice app sees it (server-realtime.cpp): session setup, audio turns,
 * replies from [FakeLlamaBro]'s voice queue and errors. `Model.voiceSession` refuses a model that doesn't hear before
 * any request, so this one always opens.
 */
internal class FakeVoiceConversation(fake: FakeLlamaBro, sink: RequestSink) : FakeRealtimeRequest(fake, sink) {
    private var hasAudio = false
    private var items = 0
    private var voiceName: String? = null
    private var language: String? = null

    init {
        event("""{"type":"session.created","session":${session()}}""")
    }

    override fun handle(json: JsonObject) {
        when (typeOf(json)) {
            "session.update" -> {
                val out = ((json["session"] as? JsonObject)?.get("audio") as? JsonObject)?.get("output") as? JsonObject
                out?.get("voice")?.let { voiceName = (it as? JsonPrimitive)?.content }
                out?.get("language")?.let { language = (it as? JsonPrimitive)?.content }
                event("""{"type":"session.updated","session":${session()}}""")
            }

            "input_audio_buffer.append" -> {
                hasAudio = true
            }

            "input_audio_buffer.commit" -> {
                if (!hasAudio) {
                    event(error("input_audio_buffer.commit: the buffer is empty", COMMIT_EMPTY))
                } else {
                    hasAudio = false
                    event(
                        """{"type":"input_audio_buffer.committed","previous_item_id":null,""" +
                            """"item_id":"item_${++items}"}""",
                    )
                }
            }

            "conversation.item.create" -> {
                event("""{"type":"conversation.item.added","item":{"id":"item_${++items}"}}""")
            }

            "response.create" -> {
                reply()
            }

            "response.cancel" -> {
                Unit // replies are sent whole, so there is nothing left to stop
            }

            else -> {
                event(error("unknown event type: ${typeOf(json)}"))
            }
        }
    }

    private fun reply() {
        val next =
            synchronized(fake.lock) { fake.voiceQueue.removeFirstOrNull() }
                ?: return event(error("FakeLlamaBro: no voice reply scripted", "server_error"))
        val id = "resp_${++items}"
        event("""{"type":"response.created","response":{"id":"$id","status":"in_progress","output":[]}}""")
        val text = StringBuilder()
        for (part in next.parts) {
            if (part is String) {
                text.append(part)
                event("""{"type":"response.output_text.delta","response_id":"$id","delta":${q(part)}}""")
            } else {
                val pcm = part as ShortArray
                val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                pcm.forEach { bytes.putShort(it) }
                val b64 = Base64.getEncoder().encodeToString(bytes.array())
                event("""{"type":"response.output_audio.delta","response_id":"$id","delta":"$b64"}""")
            }
        }
        val tokens = next.outputTokens ?: maxOf(1, text.length / 4)
        event(
            """{"type":"response.done","response":{"id":"$id","status":"completed","output":[{"type":"message",""" +
                """"role":"assistant","content":[{"type":"output_text","text":${q(text.toString())}}]}],""" +
                """"usage":{"output_tokens":$tokens}}}""",
        )
    }

    private fun session(): String =
        """{"id":"sess_fake","type":"realtime","audio":{"input":{"format":{"type":"audio/pcm","rate":16000}},""" +
            """"output":{"format":{"type":"audio/pcm","rate":24000},"voice":${voiceName?.let(::q)},""" +
            """"language":${language?.let(::q)}}}}"""
}

/**
 * Upstream's realtime transcription as far as a dictating app sees it (server-context.cpp ws_realtime and
 * server-realtime.cpp): refused on a model that doesn't stream audio, else a live or a per-commit session whose items
 * take [FakeLlamaBro]'s transcription queue's texts. A live item's text is sent when it is committed, not as audio
 * arrives.
 */
internal class FakeTranscription(fake: FakeLlamaBro, sink: RequestSink, streams: Boolean, private val live: Boolean) :
    FakeRealtimeRequest(fake, sink) {
    private var hasAudio = false
    private var items = 0
    private var rate = 16000

    init {
        if (streams) {
            event("""{"type":"session.created","session":${session(if (live) "transcription" else "realtime")}}""")
        } else {
            event(error("The current model does not support realtime transcription."))
            end()
        }
    }

    override fun handle(json: JsonObject) {
        when (typeOf(json)) {
            "session.update" -> {
                val input = ((json["session"] as? JsonObject)?.get("audio") as? JsonObject)?.get("input") as? JsonObject
                val format = input?.get("format") as? JsonObject
                (format?.get("rate") as? JsonPrimitive)?.content?.toIntOrNull()?.let { rate = it }
                event("""{"type":"session.updated","session":${session("transcription")}}""")
            }

            "input_audio_buffer.append" -> {
                hasAudio = true
            }

            "input_audio_buffer.commit" -> {
                commit((json["final"] as? JsonPrimitive)?.content == "true")
            }

            else -> {
                event(error("unknown event type: ${typeOf(json)}"))
            }
        }
    }

    private fun commit(final: Boolean) {
        if (!hasAudio && !live) {
            if (!final) event(error("input_audio_buffer.commit: the buffer is empty", COMMIT_EMPTY))
            return
        }
        hasAudio = false
        val id = "item_${++items}"
        // upstream's live path ends the input without a committed event (server-context.cpp ws_realtime)
        if (!(live && final)) {
            event("""{"type":"input_audio_buffer.committed","previous_item_id":null,"item_id":"$id"}""")
        }
        val text =
            synchronized(fake.lock) { fake.transcriptionQueue.removeFirstOrNull() }
                ?: return event(error("FakeLlamaBro: no transcription scripted", "server_error"))
        if (text.isNotEmpty()) {
            event(
                """{"type":"conversation.item.input_audio_transcription.delta","item_id":"$id",""" +
                    """"content_index":0,"delta":${q(text)}}""",
            )
        }
        event(
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"$id",""" +
                """"content_index":0,"transcript":${q(text)}}""",
        )
    }

    private fun session(type: String): String =
        """{"id":"sess_fake","object":"realtime.transcription_session","type":"$type",""" +
            """"audio":{"input":{"format":{"type":"audio/pcm","rate":$rate},"turn_detection":null}}}"""
}
