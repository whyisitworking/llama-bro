package com.suhel.llamabro.demo.ui.chat

import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.demo.chat.Dictation
import com.suhel.llamabro.demo.chat.ModelHold
import com.suhel.llamabro.demo.chat.SampleSource
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The message bar's dictation (D140) around a real [Dictation] over FakeLlamaBro: leaving while it gets ready, a
 * microphone, recording or transcription that fails, a chat with no model to hold, and the model's hold let go once on
 * every way out.
 */
class DictationComposerTest {
    @get:Rule val folder = TemporaryFolder()

    // the view model's scope runs on the main thread: one thread, so the composer's own state needs no locks
    private val mainThread = Executors.newSingleThreadExecutor()
    private val main = mainThread.asCoroutineDispatcher()
    private val owner = CoroutineScope(SupervisorJob() + main)

    // where the dictation itself listens and transcribes
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val holdsAsked = AtomicInteger()
    private val releases = AtomicInteger()
    private val dictationsMade = AtomicInteger()
    private val texts = CopyOnWriteArrayList<String>()
    private val problems = CopyOnWriteArrayList<String>()

    @After
    fun end() {
        owner.cancel()
        work.cancel()
        mainThread.shutdownNow()
    }

    /** A microphone that says [blocks] in 50 ms blocks, paced a little as a real one is, then silence or its end. */
    private class Mic(private val blocks: List<Short> = emptyList(), private val endless: Boolean = true) {
        @Volatile var closed = false

        fun source() =
            object : SampleSource {
                private val next = blocks.iterator()
                override val rate = 16_000

                override fun read(buffer: ShortArray): Int {
                    val a: Short =
                        when {
                            next.hasNext() -> next.next()
                            endless -> 0
                            else -> return -1
                        }
                    Thread.sleep(PACE_MILLIS)
                    for (i in buffer.indices) buffer[i] = if (i % 2 == 0) a else (-a).toShort()
                    return buffer.size
                }

                override fun close() {
                    closed = true
                }
            }
    }

    private suspend fun model(script: FakeLlamaBro.() -> Unit = {}): Model =
        FakeLlamaBro(script)
            .apply { supportsAudio = true }
            .llamaBro
            .loadModel(ModelSource.file("/models/omni.gguf"))

    /** A hold on [model] that counts its release and, with a [mic], whether the microphone had stopped by then. */
    private fun holdOf(model: Model, mic: Mic? = null, micClosedAtRelease: CompletableDeferred<Boolean>? = null) =
        ModelHold(model) {
            mic?.let { micClosedAtRelease?.complete(it.closed) }
            releases.incrementAndGet()
        }

    private fun composer(
        hold: suspend () -> ModelHold?,
        open: () -> SampleSource = Mic()::source,
        clip: File = File(folder.root, "dictation.wav"),
    ) = DictationComposer(
        owner,
        hold = {
            holdsAsked.incrementAndGet()
            hold()
        },
        newDictation = { model, language ->
            dictationsMade.incrementAndGet()
            Dictation(model, open, clip, work, language)
        },
        language = { null },
        canDictate = { true },
        onText = { texts += it },
        onUsed = {},
        problem = { problems += it },
    )

    /** Calls [block] on the main thread, as the screen does. */
    private suspend fun DictationComposer.onMain(block: DictationComposer.() -> Unit) = withContext(main) { block() }

    /** Waits for everything the composer and its dictation started to end, so nothing more can come of them. */
    private suspend fun settled() =
        withTimeout(TIMEOUT) {
            owner.coroutineContext.job.children
                .toList()
                .joinAll()
            work.coroutineContext.job.children
                .toList()
                .joinAll()
        }

    private suspend fun DictationComposer.listening() = eventually("listening") { shown.value?.preparing == false }

    private fun DictationComposer.heardMillis(): Long = shown.value?.dictation?.millis ?: 0

    @Test
    fun leavingWhileTheModelIsHeldDropsTheDictationAndLetsTheLateHoldGo() =
        runBlocking {
            val model = model { streamsAudio = true }
            val asked = CompletableDeferred<Unit>()
            val loaded = CompletableDeferred<Unit>()
            val c =
                composer(hold = {
                    asked.complete(Unit)
                    loaded.await()
                    holdOf(model)
                })
            c.onMain { start() }
            assertEquals(true, c.shown.value?.preparing)
            withTimeout(TIMEOUT) { asked.await() }
            c.onMain { leave() }
            assertNull(c.shown.value)
            // the hold can't be stopped halfway: it arrives after the person left, and is let go
            loaded.complete(Unit)
            eventually("the late hold let go") { releases.get() == 1 }
            settled()
            assertEquals(1, releases.get())
            assertEquals(0, dictationsMade.get())
            assertNull(c.shown.value)
            assertTrue(texts.isEmpty())
        }

    @Test
    fun aChatWithNoModelToHoldClosesTheBarAndCanBeTriedAgain() =
        runBlocking {
            val c = composer(hold = { null })
            c.onMain { start() }
            eventually("the bar closed") { c.shown.value == null }
            settled()
            assertEquals(0, dictationsMade.get())
            assertTrue(texts.isEmpty())
            c.onMain { start() }
            eventually("a second tap asked again") { holdsAsked.get() == 2 }
            eventually("the bar closed again") { c.shown.value == null }
        }

    @Test
    fun aMicrophoneThatWontOpenSaysSoAndLetsTheModelGo() =
        runBlocking {
            val model = model { streamsAudio = true }
            val c = composer(hold = { holdOf(model) }, open = { error("no microphone") })
            c.onMain { start() }
            eventually("the model let go") { releases.get() == 1 }
            eventually("the bar closed") { c.shown.value == null }
            settled()
            assertEquals(1, releases.get())
            assertEquals(listOf("The microphone couldn't be opened."), problems)
            assertTrue(texts.isEmpty())
        }

    @Test
    fun aRecordingThatCantBeWrittenSaysThereIsNoRoomAndLetsTheModelGo() =
        runBlocking {
            val model = model { streamsAudio = true }
            val mic = Mic()
            // a file where the recording's folder should be: the clip can't be created
            val clip = File(folder.newFile("taken"), "dictation.wav")
            val c = composer(hold = { holdOf(model) }, open = mic::source, clip = clip)
            c.onMain { start() }
            eventually("the model let go") { releases.get() == 1 }
            eventually("the bar closed") { c.shown.value == null }
            settled()
            assertEquals(1, releases.get())
            assertEquals(listOf("There's no room to listen. Free some space and try again."), problems)
            assertTrue(mic.closed)
            assertTrue(texts.isEmpty())
        }

    @Test
    fun aTranscriptionThatFailsWritesNothingAndLetsTheModelGo() =
        runBlocking {
            // no realtime session, and nothing scripted for transcribe: every piece and the last pass fail
            val model = model { streamsAudio = false }
            val mic = Mic(List(10) { LOUD } + List(10) { QUIET }, endless = false)
            val c = composer(hold = { holdOf(model) }, open = mic::source)
            c.onMain { start() }
            c.listening()
            eventually("all of it heard") {
                c.heardMillis() >= 20 * 50
            }
            c.onMain { finish() }
            eventually("the model let go") { releases.get() == 1 }
            eventually("the bar closed") { c.shown.value == null }
            settled()
            assertEquals(1, releases.get())
            assertTrue(texts.isEmpty())
            // a failed transcription is not silence: the line says so
            assertEquals(listOf("That couldn't be written down. Try again."), problems)
        }

    @Test
    fun finishingWritesTheWordsOnceAndLetsTheModelGoOnce() =
        runBlocking {
            val model =
                model {
                    streamsAudio = true
                    transcribesLive = true
                    transcription("Call the bank.")
                }
            val mic = Mic(List(10) { QUIET } + List(20) { LOUD } + List(4) { QUIET }, endless = false)
            val c = composer(hold = { holdOf(model) }, open = mic::source)
            c.onMain { start() }
            c.listening()
            eventually("all of it heard") {
                c.heardMillis() >= 34 * 50
            }
            c.onMain {
                finish()
                finish()
                leave()
            }
            eventually("the words written") { texts.isNotEmpty() }
            eventually("the model let go") { releases.get() == 1 }
            settled()
            assertEquals(listOf("Call the bank."), texts)
            assertEquals(1, releases.get())
            assertNull(c.shown.value)
            assertTrue(problems.isEmpty())
        }

    @Test
    fun leavingWhileListeningKeepsTheWords() =
        runBlocking {
            val model =
                model {
                    streamsAudio = true
                    transcribesLive = true
                    transcription("Call the bank.")
                }
            val mic = Mic(List(10) { QUIET } + List(20) { LOUD } + List(4) { QUIET }, endless = false)
            val c = composer(hold = { holdOf(model) }, open = mic::source)
            c.onMain { start() }
            c.listening()
            eventually("all of it heard") {
                c.heardMillis() >= 34 * 50
            }
            c.onMain { leave() }
            eventually("the words written") { texts.isNotEmpty() }
            eventually("the model let go") { releases.get() == 1 }
            settled()
            assertEquals(listOf("Call the bank."), texts)
            assertEquals(1, releases.get())
        }

    @Test
    fun discardingWhileListeningLetsTheModelGoOnceTheMicrophoneStopped() =
        runBlocking {
            val model = model { streamsAudio = true }
            val mic = Mic()
            val micClosedAtRelease = CompletableDeferred<Boolean>()
            val c = composer(hold = { holdOf(model, mic, micClosedAtRelease) }, open = mic::source)
            c.onMain { start() }
            c.listening()
            c.onMain { discard() }
            assertNull(c.shown.value)
            assertTrue("the model was let go under the microphone", withTimeout(TIMEOUT) { micClosedAtRelease.await() })
            settled()
            assertEquals(1, releases.get())
            assertTrue(texts.isEmpty())
            assertTrue(problems.isEmpty())
        }

    private companion object {
        const val TIMEOUT = 10_000L
        const val PACE_MILLIS = 3L
        const val QUIET: Short = 0
        const val LOUD: Short = 8_000
    }
}
