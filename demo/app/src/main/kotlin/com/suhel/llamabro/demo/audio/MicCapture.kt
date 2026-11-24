package com.suhel.llamabro.demo.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * The source's audio in blocks of one level each (`rate / LEVELS_PER_SECOND` samples), each read on [io], where the
 * microphone blocks, for as long as [reading] says to go on. It is asked after each read, so the block read when the
 * answer turns is still emitted, and an empty read (which is skipped) still notices it. Once collected, the flow owns
 * the source: it is closed when the flow ends, by [reading], by the source's end (a negative count), by a collector
 * that stops taking, or by cancellation (the read under way finishes first, and its block is dropped). Voice messages,
 * dictation and voice mode all capture through it.
 */
fun SampleSource.blocks(io: CoroutineDispatcher, reading: () -> Boolean = { true }): Flow<ShortArray> =
    flow {
        use { source ->
            val buffer = ShortArray(source.rate / VoiceRecorder.LEVELS_PER_SECOND)
            do {
                val n = withContext(io) { source.read(buffer) }
                if (n < 0) break
                if (n > 0) emit(buffer.copyOf(n))
            } while (reading())
        }
    }

/** The loudness of each block of [samples] at [rate], one level per `rate / LEVELS_PER_SECOND` samples. */
internal fun levelsOf(samples: ShortArray, rate: Int): List<Float> {
    val block = rate / VoiceRecorder.LEVELS_PER_SECOND
    return (samples.indices step block).map { i ->
        val n = minOf(block, samples.size - i)
        VoiceRecorder.level(samples.copyOfRange(i, i + n), n)
    }
}
