package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Reading a reply aloud (D141) over FakeLlamaBro's speech, into a speaker that keeps what it was given. */
class ReadAloudTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun end() = scope.cancel()

    private class Speaker(val rate: Int) : SpeechOutput {
        val played = mutableListOf<Short>()
        var closed = false

        override fun write(pcm: ShortArray) {
            played += pcm.toList()
        }

        override val level: Float = 0f

        override suspend fun drain() = Unit

        override fun stop() = Unit

        override fun close() {
            closed = true
        }
    }

    private fun fake(script: FakeLlamaBro.() -> Unit) =
        FakeLlamaBro(script).apply {
            supportsAudio = true
            voices = listOf("female", "male")
        }

    @Test
    fun aRepliesMarkdownIsReadAsPlainWords() {
        assertEquals(
            "Use the step function, see the docs. Done.",
            ReadAloud.spoken(
                "**Use** the `step` function, see [the docs](https://x.y).\n```kotlin\nval x = 1\n```\nDone.",
            ),
        )
        assertEquals("Heading item one well-known item", ReadAloud.spoken("# Heading\n- item one\n1. well-known item"))
    }

    @Test
    fun theSpeechPlaysInTheChosenVoiceAndTheModelIsLetGo() =
        runBlocking {
            val pcm = ShortArray(6) { (it * 10).toShort() }
            val fake = fake { speech(pcm) }
            val model = fake.llamaBro.loadModel(ModelSource.file("/models/omni.gguf"))
            val released = AtomicBoolean(false)
            val speakers = mutableListOf<Speaker>()
            var problem: String? = null
            val reader =
                ReadAloud(
                    scope,
                    hold = { VoiceHold(model) { released.set(true) } },
                    voice = { "male" },
                    output = { rate -> Speaker(rate).also(speakers::add) },
                    problem = { problem = it },
                )
            reader.toggle(7L, "Hello **there**.")
            assertEquals(7L, reader.reading.value)
            withTimeout(TIMEOUT) { reader.reading.first { it == null } }
            assertTrue(released.get())
            assertNull(problem)
            val speaker = speakers.single()
            assertEquals(24000, speaker.rate)
            assertArrayEquals(pcm, speaker.played.toShortArray())
            assertTrue(speaker.closed)
            val body = Json.parseToJsonElement(fake.speechRequests.single()).jsonObject
            assertEquals("Hello there.", body["input"]!!.jsonPrimitive.content)
            assertEquals("male", body["voice"]!!.jsonPrimitive.content)
        }

    @Test
    fun aVoiceTheModelDoesNotHaveIsLeftOut() =
        runBlocking {
            val fake = fake { speech(ShortArray(2)) }
            val model = fake.llamaBro.loadModel(ModelSource.file("/models/omni.gguf"))
            val reader = ReadAloud(scope, { VoiceHold(model) {} }, { "robot" }, { Speaker(it) }, problem = {})
            reader.toggle(1L, "Hi.")
            withTimeout(TIMEOUT) { reader.reading.first { it == null } }
            val body = Json.parseToJsonElement(fake.speechRequests.single()).jsonObject
            assertFalse(body.containsKey("voice"))
        }

    @Test
    fun aSecondTapStopsItAndAFailureSaysSo() =
        runBlocking {
            // nothing scripted: the request fails, the line says so, and nothing reads
            val fake = fake {}
            val model = fake.llamaBro.loadModel(ModelSource.file("/models/omni.gguf"))
            var problem: String? = null
            val reader =
                ReadAloud(scope, { VoiceHold(model) {} }, { null }, { Speaker(it) }, problem = { problem = it })
            reader.toggle(1L, "Hi.")
            withTimeout(TIMEOUT) { reader.reading.first { it == null } }
            assertEquals("That reply couldn't be read aloud.", problem)
            reader.toggle(2L, "Again.")
            reader.toggle(2L, "Again.")
            assertNull(reader.reading.value)
        }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
