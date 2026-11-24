package com.suhel.llamabro.testing

import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.transcriptionSession
import com.suhel.llamabro.voiceSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** [FakeLlamaBro.realtimeEvents] records what a voice session and a transcription session send (D143). */
class FakeRealtimeEventsTest {
    @Test
    fun bothKindsOfSessionAreRecordedInOrder() =
        runBlocking {
            val fake =
                FakeLlamaBro {
                    supportsAudio = true
                    streamsAudio = true
                }
            val model = fake.llamaBro.loadModel(ModelSource.file("/models/omni.gguf"))
            model.voiceSession { language = "en" }.close()
            model.transcriptionSession { extra = """{"audio":{"input":{"noise_reduction":"near_field"}}}""" }.close()
            val events = fake.realtimeEvents.map { Json.parseToJsonElement(it).jsonObject }
            val sessions = events.map { it["session"]!!.jsonObject["type"]!!.jsonPrimitive.content }
            assertEquals(listOf("realtime", "transcription"), sessions)
            val patched = events[1]["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject
            assertEquals("near_field", patched["noise_reduction"]!!.jsonPrimitive.content)
            assertFalse("near_field" in events[0].toString())
        }
}
