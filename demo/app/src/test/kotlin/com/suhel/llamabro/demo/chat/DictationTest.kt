package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.demo.letAWrongActionLand
import com.suhel.llamabro.testing.FakeLlamaBro
import com.suhel.llamabro.transcriptionSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dictation (D140) over FakeLlamaBro, on upstream's three routes: live text, a piece at each pause in a realtime
 * session, and pieces over `transcribe` for a model without one; then the last pass over the whole recording.
 */
class DictationTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun end() = scope.cancel()

    /**
     * Speech and silence in 50 ms blocks, paced a little as a microphone is; then it ends. Its first read waits for
     * [gate], so a test can hold the voice back until the session's route is known.
     */
    private class Mic(vararg val parts: Pair<Short, Int>, val gate: CountDownLatch = CountDownLatch(0)) {
        @Volatile var closed = false

        fun source() =
            object : SampleSource {
                private val blocks = parts.flatMap { (amplitude, n) -> List(n) { amplitude } }.iterator()
                override val rate = 16_000

                override fun read(buffer: ShortArray): Int {
                    gate.await()
                    if (!blocks.hasNext()) return -1
                    Thread.sleep(PACE_MILLIS)
                    val a = blocks.next()
                    // a tone, so the level is the amplitude's; silence is zeros
                    for (i in buffer.indices) buffer[i] = if (i % 2 == 0) a else (-a).toShort()
                    return buffer.size
                }

                override fun close() {
                    closed = true
                }
            }
    }

    /** Starts [dictation] and lets [mic] speak once the session's route is known, so the pauses meet it. */
    private suspend fun startAfterRoute(dictation: Dictation, mic: Mic) {
        dictation.start()
        withTimeout(TIMEOUT) { dictation.state.first { it.route != null } }
        mic.gate.countDown()
    }

    private val quiet: Short = 0
    private val loud: Short = 8_000

    /** Two phrases with a pause after each. */
    private fun twoPhrases() =
        Mic(quiet to 10, loud to 20, quiet to 12, loud to 20, quiet to 12, gate = CountDownLatch(1))

    /** The fake behind the test's [model], for what was asked of it. */
    private lateinit var fake: FakeLlamaBro

    private fun model(script: FakeLlamaBro.() -> Unit): Model =
        runBlocking {
            fake = FakeLlamaBro(script).apply { supportsAudio = true }
            fake.llamaBro.loadModel(ModelSource.file("/models/omni.gguf"))
        }

    private fun clip(): File = File(folder.root, "dictation.wav")

    /** Waits until [millis] of the scripted recording went in, as a person finishes speaking. */
    private suspend fun Dictation.heard(millis: Long) = withTimeout(TIMEOUT) { state.first { it.millis >= millis } }

    @Test
    fun aLiveSessionGivesItsTextAndNoSecondPass() =
        runBlocking {
            val model =
                model {
                    streamsAudio = true
                    transcribesLive = true
                    transcription("Remind me to call the bank.")
                }
            val mic = Mic(quiet to 10, loud to 20, quiet to 4)
            val dictation = Dictation(model, mic::source, clip(), scope)
            dictation.start()
            withTimeout(TIMEOUT) { dictation.state.first { it.route != null } }
            assertEquals(DictationRoute.Live, dictation.state.value.route)
            // the person has said it all before stopping: a stop within a fifth of a second is a tap
            dictation.heard(34 * 50)
            val text = withTimeout(TIMEOUT) { dictation.finish() }
            assertEquals(DictationResult.Heard("Remind me to call the bank."), text)
            assertFalse(clip().exists())
            assertTrue(mic.closed)
        }

    @Test
    fun aSessionPerPauseIsTranscribedOnceMoreAsAWhole() =
        runBlocking {
            val model =
                model {
                    streamsAudio = true
                    transcribesLive = false
                    transcription("Remind me.")
                    transcription("To call the bank.")
                    transcript("Remind me to call the bank.")
                }
            val mic = twoPhrases()
            val dictation = Dictation(model, mic::source, clip(), scope)
            startAfterRoute(dictation, mic)
            // the first piece's text shows while the person goes on
            withTimeout(TIMEOUT) { dictation.state.first { it.text.startsWith("Remind me.") } }
            assertEquals(DictationRoute.PerPause, dictation.state.value.route)
            dictation.heard(74 * 50)
            val text = withTimeout(TIMEOUT) { dictation.finish() }
            assertEquals(DictationResult.Heard("Remind me to call the bank."), text)
            assertEquals("Remind me to call the bank.", dictation.state.value.text)
        }

    @Test
    fun aModelWithoutASessionIsTranscribedInPiecesThenAsAWhole() =
        runBlocking {
            val model =
                model {
                    streamsAudio = false
                    transcript("language English<asr_text>Remind me.")
                    transcript("language English<asr_text>Remind me to call the bank.")
                }
            val dictation =
                Dictation(model, Mic(quiet to 10, loud to 20, quiet to 12, quiet to 30)::source, clip(), scope)
            dictation.start()
            // Qwen3-ASR's format is taken off, as upstream's client does
            withTimeout(TIMEOUT) { dictation.state.first { it.text == "Remind me." } }
            assertEquals(DictationRoute.Pieces, dictation.state.value.route)
            dictation.heard(72 * 50)
            assertEquals(
                DictationResult.Heard("Remind me to call the bank."),
                withTimeout(TIMEOUT) { dictation.finish() },
            )
        }

    @Test
    fun aFailedLastPassKeepsThePieces() =
        runBlocking {
            val model =
                model {
                    streamsAudio = false
                    transcript("Remind me.")
                    inputTooLong(1) // the last pass fails
                }
            val dictation =
                Dictation(model, Mic(quiet to 10, loud to 20, quiet to 12, quiet to 30)::source, clip(), scope)
            dictation.start()
            withTimeout(TIMEOUT) { dictation.state.first { it.text == "Remind me." } }
            dictation.heard(72 * 50)
            assertEquals(DictationResult.Heard("Remind me."), withTimeout(TIMEOUT) { dictation.finish() })
        }

    @Test
    fun theRecordingTimeAndLevelsAreShown() =
        runBlocking {
            val model =
                model {
                    streamsAudio = true
                    transcription("")
                }
            val dictation = Dictation(model, Mic(quiet to 4, loud to 6)::source, clip(), scope)
            dictation.start()
            val state = withTimeout(TIMEOUT) { dictation.state.first { it.millis >= 500 } }
            assertEquals(10, state.levels.size)
            assertTrue(state.levels.last() > state.levels.first())
            withTimeout(TIMEOUT) { dictation.finish() }
            Unit
        }

    @Test
    fun finishingTwiceGivesTheSameTextOnce() =
        runBlocking {
            val model =
                model {
                    streamsAudio = true
                    transcription("Once.")
                }
            val dictation = Dictation(model, Mic(quiet to 10, loud to 10, quiet to 4)::source, clip(), scope)
            dictation.start()
            dictation.heard(24 * 50)
            val (a, b) =
                withTimeout(
                    TIMEOUT,
                ) { listOf(async { dictation.finish() }, async { dictation.finish() }).awaitAll() }
            assertEquals(DictationResult.Heard("Once."), a)
            assertEquals(a, b)
        }

    @Test
    fun aMicrophoneThatWontOpenIsAFailureAndNoText() =
        runBlocking {
            val model = model { streamsAudio = true }
            var failed: RecordingFailure? = null
            val dictation = Dictation(model, { error("no microphone") }, clip(), scope)
            dictation.start { failed = it }
            assertEquals(DictationResult.RecordingFailed, withTimeout(TIMEOUT) { dictation.finish() })
            assertEquals(RecordingFailure.Microphone, failed)
        }

    @Test
    fun cancellingThrowsTheRecordingAway() =
        runBlocking {
            val model = model { streamsAudio = true }
            val mic = Mic(quiet to 200)
            val dictation = Dictation(model, mic::source, clip(), scope)
            dictation.start()
            withTimeout(TIMEOUT) { dictation.state.first { it.millis > 0 } }
            withTimeout(TIMEOUT) { dictation.cancel().join() }
            assertFalse(clip().exists())
            assertTrue(mic.closed)
        }

    @Test
    fun aTranscriptionThatFailsIsToldApartFromSilence() =
        runBlocking {
            // no realtime session, and nothing scripted for transcribe: every piece and the last pass fail
            val model = model { streamsAudio = false }
            val dictation = Dictation(model, Mic(quiet to 4, loud to 10, quiet to 4)::source, clip(), scope)
            dictation.start()
            dictation.heard(18 * 50)
            assertEquals(DictationResult.TranscriptionFailed, withTimeout(TIMEOUT) { dictation.finish() })
            assertFalse(clip().exists())
        }

    @Test
    fun aDictationFinishedWhileItsReaderStillRunsStopsTheMicrophone() =
        runBlocking {
            val model =
                model {
                    streamsAudio = true
                    transcribesLive = true
                    transcription("Still talking.")
                }
            // far longer than the test: the reader is mid-recording when finish comes
            val mic = Mic(loud to 100_000)
            val dictation = Dictation(model, mic::source, clip(), scope)
            dictation.start()
            dictation.heard(10 * 50)
            assertFalse(mic.closed)
            val result = withTimeout(TIMEOUT) { dictation.finish() }
            assertEquals(DictationResult.Heard("Still talking."), result)
            assertTrue(mic.closed)
            assertFalse(clip().exists())
            assertFalse(dictation.state.value.listening)
        }

    @Test
    fun aCancelAskedRightAfterFinishDiscardsItBeforeAnyLastPass() =
        runBlocking {
            // no realtime session, and no pause in the speech: the only transcription would be finish's last pass
            val model = model { streamsAudio = false }
            val mic = GatedMic(readsBeforeGate = 10)
            val dictation = Dictation(model, mic::source, clip(), scope)
            dictation.start()
            // the reader is mid-read, held at the gate: finish has a recording still going to stop
            assertTrue(mic.atGate.await(TIMEOUT, TimeUnit.MILLISECONDS))
            // UNDISPATCHED: finish has asked for its step before cancel asks for its own
            val finishing = async(start = CoroutineStart.UNDISPATCHED) { dictation.finish() }
            val cancelling = dictation.cancel()
            mic.gate.countDown()
            withTimeout(TIMEOUT) { cancelling.join() }
            assertEquals(DictationResult.Discarded, withTimeout(TIMEOUT) { finishing.await() })
            assertTrue("the last pass ran although the dictation was thrown away", fake.transcribeRequests.isEmpty())
            assertTrue(mic.closed)
            assertFalse(clip().exists())
            // asked again, it stays thrown away
            assertEquals(DictationResult.Discarded, withTimeout(TIMEOUT) { dictation.finish() })
        }

    @Test
    fun aRealtimeSessionThatRefusesEveryCommitIsAFailedTranscription() =
        runBlocking {
            // a live session with nothing scripted: the commit is answered with an error, and no text
            val model =
                model {
                    streamsAudio = true
                    transcribesLive = true
                }
            val dictation = Dictation(model, Mic(quiet to 4, loud to 10, quiet to 4)::source, clip(), scope)
            dictation.start()
            dictation.heard(18 * 50)
            assertEquals(DictationRoute.Live, dictation.state.value.route)
            assertEquals(DictationResult.TranscriptionFailed, withTimeout(TIMEOUT) { dictation.finish() })
        }

    @Test
    fun aQuickDoubleTapHearsNothingAndAsksTheModelNothing() =
        runBlocking {
            // nothing scripted: a transcription would fail and say so
            val model = model { streamsAudio = false }
            val dictation = Dictation(model, Mic(loud to 2)::source, clip(), scope)
            dictation.start()
            dictation.heard(2 * 50)
            assertEquals(DictationResult.Heard(""), withTimeout(TIMEOUT) { dictation.finish() })
            assertTrue(fake.transcribeRequests.isEmpty())
            assertFalse(clip().exists())
        }

    @Test
    fun aCancelDuringTheLastPassStopsItBeforeTheModelIsLetGo() =
        runBlocking {
            // no realtime session, and no pause in the speech: the only transcription is finish's last pass, held
            val model = model { streamsAudio = false }
            val passing = CompletableDeferred<Unit>()
            val passStopped = AtomicBoolean(false)
            val dictation =
                Dictation(
                    model,
                    Mic(loud to 10)::source,
                    clip(),
                    scope,
                    transcribeClip = {
                        passing.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            passStopped.set(true)
                        }
                    },
                )
            dictation.start()
            dictation.heard(10 * 50)
            val finishing = async { dictation.finish() }
            withTimeout(TIMEOUT) { passing.await() }
            withTimeout(TIMEOUT) { dictation.cancel().join() }
            // the composer lets the model go once cancel's job is done: the pass must have stopped by then
            assertTrue(passStopped.get())
            assertEquals(DictationResult.Discarded, withTimeout(TIMEOUT) { finishing.await() })
            assertEquals("", dictation.state.value.text)
            assertFalse(clip().exists())
        }

    @Test
    fun aTapWhileTheSessionOpensReturnsOnlyOnceTheOpenHasStopped() =
        runBlocking {
            val model = model { streamsAudio = true }
            val opening = CompletableDeferred<Unit>()
            val openStopping = CompletableDeferred<Unit>()
            val openStopped = CompletableDeferred<Unit>()
            val dictation =
                Dictation(
                    model,
                    Mic(loud to 2)::source,
                    clip(),
                    scope,
                    openSession = {
                        opening.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            // the open winds down slowly, as a connection being torn down does
                            withContext(NonCancellable) {
                                openStopping.complete(Unit)
                                openStopped.await()
                            }
                        }
                    },
                )
            dictation.start()
            withTimeout(TIMEOUT) { opening.await() }
            dictation.heard(2 * 50)
            val finishing = async { dictation.finish() }
            withTimeout(TIMEOUT) { openStopping.await() }
            letAWrongActionLand()
            assertTrue(
                "finish returned (and the model was let go) while the session was still opening",
                finishing.isActive,
            )
            openStopped.complete(Unit)
            assertEquals(DictationResult.Heard(""), withTimeout(TIMEOUT) { finishing.await() })
        }

    @Test
    fun anErrorThatAnswersNoCommitIsNotAFailedTranscription() =
        runBlocking {
            // the commit's transcription is empty; a bad event sent at the start is refused on its own
            val model =
                model {
                    streamsAudio = true
                    transcribesLive = true
                    transcription("")
                }
            val dictation =
                Dictation(
                    model,
                    Mic(quiet to 4, loud to 10, quiet to 4)::source,
                    clip(),
                    scope,
                    openSession = { rate ->
                        model.transcriptionSession { inputSampleRate = rate }.also {
                            it.sendRaw("""{"type":"not.an.event"}""")
                        }
                    },
                )
            dictation.start()
            dictation.heard(18 * 50)
            // nothing marks the refusal's arrival: the grace lets it land before the last commit goes out
            letAWrongActionLand()
            assertEquals(DictationResult.Heard(""), withTimeout(TIMEOUT) { dictation.finish() })
        }

    /** Loud blocks; read number [readsBeforeGate] + 1 waits at [gate], saying so through [atGate]. */
    private class GatedMic(private val readsBeforeGate: Int) {
        val atGate = CountDownLatch(1)
        val gate = CountDownLatch(1)

        @Volatile var closed = false

        fun source() =
            object : SampleSource {
                private var reads = 0
                override val rate = 16_000

                override fun read(buffer: ShortArray): Int {
                    if (reads++ == readsBeforeGate) {
                        atGate.countDown()
                        gate.await(TIMEOUT, TimeUnit.MILLISECONDS)
                    } else {
                        Thread.sleep(PACE_MILLIS)
                    }
                    buffer.fill(8_000)
                    return buffer.size
                }

                override fun close() {
                    closed = true
                }
            }
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val PACE_MILLIS = 3L
    }
}
