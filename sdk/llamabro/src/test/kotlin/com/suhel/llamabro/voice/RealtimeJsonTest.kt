package com.suhel.llamabro.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The client events a voice session sends and the server events it reads, as upstream's realtime handler has them. */
class RealtimeJsonTest {
    @Test
    fun anUnsetConfigSendsOnlyTheCaptionedOutput() {
        assertEquals(
            """{"type":"session.update","session":{"type":"realtime","output_modalities":["text","audio"]}}""",
            RealtimeJson.sessionUpdate(VoiceConfig.Default),
        )
    }

    @Test
    fun eachSetFieldGoesWhereUpstreamReadsIt() {
        val config =
            VoiceConfig {
                voice = "female"
                language = "en"
                instructions = "Answer the user's spoken question."
                outputModalities = setOf(OutputModality.Audio, OutputModality.Text)
                maxOutputTokens = 512
                inputSampleRate = 48000
            }
        assertEquals(
            """{"type":"session.update","session":{"type":"realtime","instructions":"Answer the user's spoken """ +
                """question.","output_modalities":["text","audio"],"max_output_tokens":512,"audio":{"input":""" +
                """{"format":{"type":"audio/pcm","rate":48000}},"output":{"voice":"female","language":"en"}}}}""",
            RealtimeJson.sessionUpdate(config),
        )
    }

    @Test
    fun extraMergesIntoTheSessionLast() {
        val config =
            VoiceConfig {
                outputModalities = setOf(OutputModality.Text)
                extra = """{"output_modalities":null,"tool_choice":"auto"}"""
            }
        assertEquals(
            """{"type":"session.update","session":{"type":"realtime","tool_choice":"auto"}}""",
            RealtimeJson.sessionUpdate(config),
        )
        assertThrows(IllegalArgumentException::class.java) { VoiceConfig { extra = """{"type":"transcription"}""" } }
        assertThrows(IllegalArgumentException::class.java) { VoiceConfig { extra = "[1]" } }
    }

    @Test
    fun theBuilderRefusesWhatUpstreamWould() {
        assertThrows(IllegalArgumentException::class.java) { VoiceConfig { outputModalities = emptySet() } }
        assertThrows(IllegalArgumentException::class.java) { VoiceConfig { maxOutputTokens = 0 } }
        assertThrows(IllegalArgumentException::class.java) { VoiceConfig { inputSampleRate = 4000 } }
        assertThrows(IllegalArgumentException::class.java) { VoiceConfig { inputSampleRate = 200_000 } }
    }

    @Test
    fun audioIsLittleEndianPcmInBase64AndComesBackTheSame() {
        val pcm = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 0x1234)
        val event = Json.parseToJsonElement(RealtimeJson.append(pcm, 1, 4)) as JsonObject
        assertEquals("input_audio_buffer.append", event.getValue("type").jsonPrimitive.content)
        // 1, -1, 32767, -32768 as little-endian bytes
        assertEquals("AQD///9/AIA=", event.getValue("audio").jsonPrimitive.content)
        assertArrayEquals(pcm.copyOfRange(1, 5), RealtimeJson.pcm("AQD///9/AIA="))
    }

    @Test
    fun aTypedMessageIsAUserItem() {
        assertEquals(
            """{"type":"conversation.item.create","item":{"type":"message","role":"user","content":""" +
                """[{"type":"input_text","text":"Hi \"there\""}]}}""",
            RealtimeJson.userText("Hi \"there\""),
        )
        assertEquals("""{"type":"input_audio_buffer.commit"}""", RealtimeJson.commit)
        assertEquals("""{"type":"response.create"}""", RealtimeJson.respond)
        assertEquals("""{"type":"response.cancel"}""", RealtimeJson.cancel)
    }

    // events as server-realtime.cpp writes them (event ids and unused fields shortened)
    @Test
    fun serverEventsDecodeToTheirTypes() {
        assertEquals(
            VoiceEvent.TurnCommitted("item_1"),
            decode("""{"type":"input_audio_buffer.committed","previous_item_id":null,"item_id":"item_1"}"""),
        )
        assertEquals(
            VoiceEvent.ReplyStarted("resp_1"),
            decode("""{"type":"response.created","response":{"id":"resp_1","status":"in_progress","output":[]}}"""),
        )
        assertEquals(
            VoiceEvent.ReplyText("resp_1", "Once"),
            decode(
                """{"type":"response.output_text.delta","response_id":"resp_1","item_id":"item_2",""" +
                    """"output_index":0,"content_index":0,"delta":"Once"}""",
            ),
        )
        assertEquals(
            VoiceEvent.ReplyAudio("resp_1", shortArrayOf(1, -1)),
            decode("""{"type":"response.output_audio.delta","response_id":"resp_1","delta":"AQD//w=="}"""),
        )
        assertEquals(
            VoiceEvent.Error("input_audio_buffer_commit_empty", "input_audio_buffer.commit: the buffer is empty"),
            decode(
                """{"type":"error","error":{"type":"input_audio_buffer_commit_empty","code":null,""" +
                    """"message":"input_audio_buffer.commit: the buffer is empty","param":null}}""",
            ),
        )
        val other = """{"type":"conversation.item.added","previous_item_id":null,"item":{"id":"item_1"}}"""
        assertEquals(VoiceEvent.Other("conversation.item.added", other), decode(other))
    }

    @Test
    fun aDoneReplyHasItsWholeTextAndHowItEnded() {
        val done =
            decode(
                """{"type":"response.done","response":{"id":"resp_1","object":"realtime.response",""" +
                    """"status":"cancelled","output":[{"id":"item_2","type":"message","role":"assistant",""" +
                    """"content":[{"type":"output_text","text":"Once upon"},{"type":"output_audio",""" +
                    """"transcript":""}]},{"type":"function_call","name":"f","arguments":"{}"}],""" +
                    """"usage":{"output_tokens":41}}}""",
            ) as VoiceEvent.ReplyDone
        assertEquals("resp_1", done.replyId)
        assertEquals("Once upon", done.text)
        assertTrue(done.cancelled)
        assertEquals(41, done.outputTokens)

        val bare =
            decode(
                """{"type":"response.done","response":{"id":"r","status":"completed"}}""",
            ) as VoiceEvent.ReplyDone
        assertEquals("", bare.text)
        assertFalse(bare.cancelled)
        assertEquals(null, bare.outputTokens)
    }

    private fun decode(text: String): VoiceEvent = RealtimeJson.decode(RealtimeJson.parse(text), text)
}
