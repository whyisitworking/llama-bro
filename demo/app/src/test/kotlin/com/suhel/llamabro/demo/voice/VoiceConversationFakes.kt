package com.suhel.llamabro.demo.voice

import com.suhel.llamabro.demo.audio.SampleSource
import com.suhel.llamabro.demo.audio.SessionFocus
import com.suhel.llamabro.demo.audio.SpeechOutput
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// voice mode's test microphone, speaker and focus (VoiceConversationTest)

/**
 * A microphone giving a quiet tone in 50 ms blocks until it is closed, but only as many as the test [allow]s: how
 * long a press lasts is then the test's choice, not the speed of the machine running it.
 */
class ScriptedMic : SampleSource {
    override val rate = 16_000
    val reads = AtomicInteger()
    private val allowed = AtomicInteger()

    /** Blocks of a level each, read once the allowed ones are: a room, a voice, a pause, in order. */
    private val script = ConcurrentLinkedQueue<Short>()

    fun allow(blocks: Int) {
        allowed.set(blocks)
    }

    fun script(level: Short, blocks: Int) {
        repeat(blocks) { script.add(level) }
    }

    @Volatile private var closed = false

    /** How many times voice mode has opened it. */
    val opens = AtomicInteger()

    fun reopen() {
        closed = false
        opens.incrementAndGet()
    }

    /** With a gate, the first read past the allowed ones waits at it, then gives a block: one read under way. */
    @Volatile var gate: CountDownLatch? = null
    val atGate = CountDownLatch(1)

    override fun read(buffer: ShortArray): Int {
        if (closed) return -1
        if (allowed.getAndUpdate { maxOf(it - 1, 0) } == 0) {
            script.poll()?.let { level ->
                reads.incrementAndGet()
                buffer.fill(level)
                return buffer.size
            }
            gate?.let {
                gate = null
                atGate.countDown()
                it.await(5, TimeUnit.SECONDS)
                reads.incrementAndGet()
                buffer.fill(500)
                return buffer.size
            }
            // an empty read, as AudioRecord gives, so the reader still notices it was told to stop
            Thread.sleep(5)
            return 0
        }
        reads.incrementAndGet()
        buffer.fill(500)
        return buffer.size
    }

    @Volatile var closedOnce = false

    /** How many times it has been closed: once for each open, however listening ended. */
    val closes = AtomicInteger()

    override fun close() {
        closed = true
        closedOnce = true
        closes.incrementAndGet()
    }
}

/**
 * A speaker that counts what it plays; [holdDrain] keeps a reply "playing" until completed, and [blockWrites] makes
 * each write after the first wait, as a full AudioTrack buffer does, until [stop] releases it.
 */
class ScriptedSpeaker : SpeechOutput {
    val played = AtomicInteger()
    val stops = AtomicInteger()
    private val writes = AtomicInteger()

    /** The player has written the whole reply and waits for it to play out. */
    val draining = CountDownLatch(1)

    /** Writes that came after a stop: speech that went on playing although the reply was stopped. */
    val writesAfterStop = AtomicInteger()

    @Volatile var holdDrain: CompletableDeferred<Unit>? = null

    @Volatile var blockWrites: CountDownLatch? = null

    override fun write(pcm: ShortArray) {
        if (stops.get() > 0) writesAfterStop.incrementAndGet()
        played.addAndGet(pcm.size)
        if (writes.incrementAndGet() > 1) blockWrites?.await(5, TimeUnit.SECONDS)
    }

    @Volatile override var level: Float = 0.5f

    override suspend fun drain() {
        draining.countDown()
        holdDrain?.await()
    }

    override fun stop() {
        stops.incrementAndGet()
        holdDrain?.complete(Unit)
        blockWrites?.countDown()
    }

    override fun close() = Unit
}

/** A focus that counts what voice mode asks of it, given back only when taken, as the phone's is. */
class CountingFocus : SessionFocus {
    val takes = AtomicInteger()
    val releases = AtomicInteger()

    @Volatile var asked: Boolean? = null

    @Volatile private var held = false
    override val lost = false
    override val communication = false

    override fun take(communication: Boolean) {
        if (held) return
        held = true
        asked = communication
        takes.incrementAndGet()
    }

    override fun reclaim(): Boolean = true

    override fun release() {
        if (!held) return
        held = false
        releases.incrementAndGet()
    }
}
