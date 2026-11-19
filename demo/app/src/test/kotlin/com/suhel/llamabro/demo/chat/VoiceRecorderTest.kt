package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.demo.data.AudioStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Voice messages' recordings (D76): a WAV file the SDK accepts, the length limit, and a source that stops. */
class VoiceRecorderTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { AudioStore(folder.newFolder("audio")) }

    @After fun end() = scope.cancel()

    /** A tone at a fixed amplitude, [blocks] reads long; then it fails, as a microphone taken by a call does. */
    private class Script(val blocks: Int, val amplitude: Short = 8_000, val rate: Int = 16_000) {
        var reads = 0
        var closed = false

        fun source() =
            object : SampleSource {
                override val rate = this@Script.rate

                override fun read(buffer: ShortArray): Int {
                    if (reads++ >= blocks) return -1
                    buffer.fill(amplitude)
                    return buffer.size
                }

                override fun close() {
                    closed = true
                }
            }
    }

    private fun recorder(script: Script, limitMillis: Long = VoiceRecorder.LIMIT_MILLIS) =
        VoiceRecorder(store, script::source, scope, limitMillis)

    private fun VoiceRecorder.stopped() = runBlocking { withTimeout(5_000) { take.first { it != null && !it.live } } }

    @Test fun aRecordingIsAWavFileOf16BitMonoAtTheSourcesRate() =
        runBlocking {
            val script = Script(blocks = 20) // a second at 20 blocks a second
            val recorder = recorder(script)
            assertTrue(recorder.start())
            recorder.stopped()
            val voice = recorder.finish()!!
            val bytes = store.file(voice.name).readBytes()
            val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals(bytes.size - 8, header.getInt(4))
            assertEquals("WAVE", String(bytes, 8, 4))
            assertEquals(1.toShort(), header.getShort(20)) // PCM
            assertEquals(1.toShort(), header.getShort(22)) // mono
            assertEquals(16_000, header.getInt(24))
            assertEquals(16.toShort(), header.getShort(34))
            assertEquals(16_000 * 2, header.getInt(40))
            assertEquals(1_000L, voice.millis)
            assertTrue(script.closed)
        }

    @Test fun aSourceThatFailsStopsTheRecordingAndKeepsIt() {
        val recorder = recorder(Script(blocks = 3))
        recorder.start()
        val take = recorder.stopped()!!
        assertFalse(take.live)
        assertEquals(150L, take.millis)
        assertEquals(3, take.levels.size)
        assertTrue(store.file(take.name).exists())
    }

    @Test fun aRecordingThatCantBeWrittenGoesAndSaysSo() =
        runBlocking {
            val audio = folder.newFolder("readonly")
            val recorder = VoiceRecorder(AudioStore(audio), Script(blocks = 3)::source, scope)
            audio.setWritable(false)
            val failure = CompletableDeferred<RecordingFailure>()
            try {
                recorder.start(onFailure = { failure.complete(it) })
                assertEquals(RecordingFailure.Storage, withTimeout(5_000) { failure.await() })
                assertNull(recorder.take.value)
            } finally {
                audio.setWritable(true)
            }
        }

    @Test fun aRecordingStopsAtTheLimitAndWaits() {
        val recorder = recorder(Script(blocks = 1_000), limitMillis = 500)
        recorder.start()
        val take = recorder.stopped()!!
        assertEquals(500L, take.millis)
        assertNotNull(recorder.take.value)
    }

    @Test fun discardDeletesTheFile() =
        runBlocking {
            val recorder = recorder(Script(blocks = 2))
            recorder.start()
            val name = recorder.stopped()!!.name
            recorder.discard()
            assertNull(recorder.take.value)
            assertFalse(store.file(name).exists())
        }

    @Test fun aSecondStartWaitsForTheFirstToBeSentOrDiscarded() =
        runBlocking {
            val recorder = recorder(Script(blocks = 2))
            assertTrue(recorder.start())
            recorder.stopped()
            assertFalse(recorder.start())
            recorder.finish()
            recorder.clear()
            assertNull(recorder.take.value)
        }

    @Test fun aSentClipKeepsAtMost64LevelsForItsBubble() =
        runBlocking {
            val recorder = recorder(Script(blocks = 200))
            recorder.start()
            recorder.stopped()
            assertEquals(VoiceRecorder.BUBBLE_LEVELS, recorder.finish()!!.levels.size)
        }

    @Test fun levelsRunFromSilenceToFullScale() {
        assertEquals(0f, VoiceRecorder.level(ShortArray(800), 800))
        assertEquals(1f, VoiceRecorder.level(ShortArray(800) { Short.MAX_VALUE }, 800))
        val quiet = VoiceRecorder.level(ShortArray(800) { 300 }, 800)
        assertTrue(quiet > 0f && quiet < 0.5f)
    }
}
