package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.demo.eventually
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Reading a reply aloud (D141) over FakeLlamaBro's speech, into a speaker that keeps what it was given. */
class ReadAloudTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun end() = scope.cancel()

    /**
     * A speaker that keeps what it plays. With [blocking], each write waits, as on a full AudioTrack buffer, until
     * [stop] releases it; [writing] counts down at the first write.
     */
    private class Speaker(val rate: Int, private val blocking: Boolean = false, private val onClose: () -> Unit = {}) :
        SpeechOutput {
        val played = CopyOnWriteArrayList<Short>()
        val writing = CountDownLatch(1)
        private val released = CountDownLatch(1)

        @Volatile var closed = false

        override fun write(pcm: ShortArray) {
            writing.countDown()
            if (blocking) released.await(TIMEOUT, TimeUnit.MILLISECONDS)
            played += pcm.toList()
        }

        override val level: Float = 0f

        override suspend fun drain() = Unit

        override fun stop() {
            released.countDown()
        }

        override fun close() {
            closed = true
            onClose()
        }
    }

    /** Holds and speakers that count how many are out at once, at most. */
    private class Counts {
        val holds = AtomicInteger()
        private val speakers = AtomicInteger()
        val mostHolds = AtomicInteger()
        val mostSpeakers = AtomicInteger()

        fun hold(model: Model): ModelHold {
            mostHolds.accumulateAndGet(holds.incrementAndGet(), ::maxOf)
            return ModelHold(model) { holds.decrementAndGet() }
        }

        fun opened() {
            mostSpeakers.accumulateAndGet(speakers.incrementAndGet(), ::maxOf)
        }

        fun closed() {
            speakers.decrementAndGet()
        }
    }

    private fun fake(script: FakeLlamaBro.() -> Unit) =
        FakeLlamaBro(script).apply {
            supportsAudio = true
            voices = listOf("female", "male")
        }

    private fun FakeLlamaBro.inputs() =
        speechRequests.map {
            Json
                .parseToJsonElement(it)
                .jsonObject["input"]!!
                .jsonPrimitive.content
        }

    @Test
    fun aRepliesMarkdownIsReadAsPlainWords() {
        assertEquals(
            "Use the step function, see the docs. Done.",
            ReadAloud.spoken(
                "**Use** the `step` function, see [the docs](https://x.y).\n```kotlin\nval x = 1\n```\nDone.",
            ),
        )
        assertEquals("call snake_case in C# code", ReadAloud.spoken("call `snake_case` in C# _code_"))
        assertEquals("Heading item one well-known item", ReadAloud.spoken("# Heading\n- item one\n1. well-known item"))
    }

    @Test
    fun theSpeechPlaysInTheChosenVoiceAndTheModelIsLetGo() =
        runBlocking {
            val pcm = ShortArray(6) { (it * 10).toShort() }
            val fake = fake { speech(pcm) }
            val model = fake.llamaBro.loadModel(ModelSource.file("/models/omni.gguf"))
            val released = AtomicBoolean(false)
            val speakers = CopyOnWriteArrayList<Speaker>()
            val problem = AtomicReference<String?>(null)
            val reader =
                ReadAloud(
                    scope,
                    hold = { ModelHold(model) { released.set(true) } },
                    voice = { "male" },
                    output = { rate -> Speaker(rate).also(speakers::add) },
                    problem = { problem.set(it) },
                )
            withTimeout(TIMEOUT) { reader.toggle(7L, "Hello **there**.").join() }
            eventually("the model let go") { released.get() }
            withTimeout(TIMEOUT) { reader.reading.first { it == null } }
            assertNull(problem.get())
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
            val released = AtomicBoolean(false)
            val reader =
                ReadAloud(
                    scope,
                    { ModelHold(model) { released.set(true) } },
                    { "robot" },
                    { Speaker(it) },
                    problem = {},
                )
            reader.toggle(1L, "Hi.")
            eventually("read") { released.get() }
            val body = Json.parseToJsonElement(fake.speechRequests.single()).jsonObject
            assertFalse(body.containsKey("voice"))
        }

    @Test
    fun aSecondTapStopsItAndAFailureSaysSo() =
        runBlocking {
            // nothing scripted: the request fails, the line says so, and nothing reads
            val fake = fake {}
            val model = fake.llamaBro.loadModel(ModelSource.file("/models/omni.gguf"))
            val problem = AtomicReference<String?>(null)
            // a speaker whose buffer stays full until stopped: the second reply is still playing at the second tap
            val speakers = CopyOnWriteArrayList<Speaker>()
            val reader =
                ReadAloud(
                    scope,
                    { ModelHold(model) {} },
                    { null },
                    { Speaker(it, blocking = true).also(speakers::add) },
                    problem = { problem.set(it) },
                )
            reader.toggle(1L, "Hi.")
            eventually("the line") { problem.get() != null }
            assertEquals("That reply couldn't be read aloud.", problem.get())
            withTimeout(TIMEOUT) { reader.reading.first { it == null } }
            fake.speech(ShortArray(4_800))
            reader.toggle(2L, "Again.")
            eventually("the reply playing") { speakers.singleOrNull()?.writing?.count == 0L }
            withTimeout(TIMEOUT) { reader.toggle(2L, "Again.").join() }
            assertNull(reader.reading.value)
            assertTrue(speakers.single().closed)
        }

    @Test
    fun aNewReadingWaitsUntilTheOneBeforeLetItsHoldAndSpeakerGo() =
        runBlocking {
            val fake =
                fake {
                    speech(ShortArray(4_800) { 100 })
                    speech(ShortArray(6) { 7 })
                }
            val model = fake.llamaBro.loadModel(ModelSource.file("/models/omni.gguf"))
            val counts = Counts()
            val speakers = CopyOnWriteArrayList<Speaker>()
            val reader =
                ReadAloud(
                    scope,
                    hold = { counts.hold(model) },
                    voice = { null },
                    // the first speaker's buffer stays full until it is stopped
                    output = { rate ->
                        counts.opened()
                        Speaker(rate, blocking = speakers.isEmpty(), onClose = counts::closed).also(speakers::add)
                    },
                    problem = {},
                )
            reader.toggle(1L, "First.")
            eventually("the first reply playing") { speakers.isNotEmpty() && speakers[0].writing.count == 0L }
            assertEquals(1L, reader.reading.value)
            reader.toggle(2L, "Second.")
            eventually("the second reply read") { speakers.size == 2 && speakers[1].closed }
            withTimeout(TIMEOUT) { reader.reading.first { it == null } }
            assertEquals(1, counts.mostHolds.get())
            assertEquals(1, counts.mostSpeakers.get())
            assertTrue(speakers[0].closed)
            assertArrayEquals(ShortArray(6) { 7 }, speakers[1].played.toShortArray())
            assertEquals(listOf("First.", "Second."), fake.inputs())
        }

    @Test
    fun twoQuickTogglesReadOnlyTheSecondAndNeverHoldTwice() =
        runBlocking {
            val fake =
                fake {
                    speech(ShortArray(6) { 1 })
                    speech(ShortArray(6) { 2 })
                }
            val model = fake.llamaBro.loadModel(ModelSource.file("/models/omni.gguf"))
            val counts = Counts()
            val reader =
                ReadAloud(
                    scope,
                    hold = { counts.hold(model) },
                    voice = { null },
                    output = { Speaker(it) },
                    problem = {},
                )
            reader.toggle(1L, "First.")
            val second = reader.toggle(2L, "Second.")
            withTimeout(TIMEOUT) { second.join() }
            eventually("the second reply read") { fake.inputs().lastOrNull() == "Second." }
            withTimeout(TIMEOUT) { reader.reading.first { it == null } }
            eventually("every hold let go") { counts.holds.get() == 0 }
            assertEquals(1, counts.mostHolds.get())
        }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
