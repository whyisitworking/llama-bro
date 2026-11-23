package com.suhel.llamabro

import com.suhel.llamabro.voice.SpeechAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** [WavStream] reads upstream's streamed WAV (one header with unknown sizes, then PCM) however the chunks fall. */
class WavStreamTest {
    /** Upstream's streamed header (`RIFF` and `data` sizes 0xFFFFFFFF), 24 kHz 16-bit mono, then [samples]. */
    private fun streamed(vararg samples: Short, rate: Int = 24000, channels: Int = 1, bits: Int = 16): ByteArray {
        val b = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(-1).put("WAVE".toByteArray())
        b
            .put("fmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(channels.toShort())
            .putInt(rate)
        b.putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        b.put("data".toByteArray()).putInt(-1)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    private fun feedAll(stream: WavStream, chunks: List<ByteArray>): List<SpeechAudio> =
        chunks.mapNotNull { stream.feed(it) }

    @Test
    fun aWholeStreamIsOnePiece() {
        val out = WavStream().feed(streamed(1, -2, 300))!!
        assertEquals(24000, out.sampleRate)
        assertEquals(1, out.channels)
        assertArrayEquals(shortArrayOf(1, -2, 300), out.pcm)
    }

    @Test
    fun aHeaderSplitAcrossChunksWaitsForItsEnd() {
        val all = streamed(7, 8)
        val stream = WavStream()
        assertNull(stream.feed(all.copyOfRange(0, 10)))
        assertNull(stream.feed(all.copyOfRange(10, 40)))
        val out = stream.feed(all.copyOfRange(40, all.size))!!
        assertArrayEquals(shortArrayOf(7, 8), out.pcm)
    }

    @Test
    fun anOddByteIsCarriedToTheNextChunk() {
        val all = streamed(0x1234, -1, 42)
        // header and one byte, then the rest a byte short, then the last byte
        val chunks =
            listOf(all.copyOfRange(0, 45), all.copyOfRange(45, all.size - 1), all.copyOfRange(all.size - 1, all.size))
        val pcm = feedAll(WavStream(), chunks).flatMap { it.pcm.toList() }
        assertEquals(listOf<Short>(0x1234, -1, 42), pcm)
    }

    @Test
    fun theFormatIsTheStreamsOwn() {
        val out = WavStream().feed(streamed(5, 6, rate = 16000, channels = 2))!!
        assertEquals(16000, out.sampleRate)
        assertEquals(2, out.channels)
    }

    @Test
    fun otherThan16BitPcmIsAnError() {
        assertThrows(IllegalStateException::class.java) { WavStream().feed(streamed(1, bits = 32)) }
    }

    @Test
    fun aBodyThatIsNotWavIsAnError() {
        assertThrows(IllegalStateException::class.java) { WavStream().feed(ByteArray(64) { 'x'.code.toByte() }) }
    }
}
