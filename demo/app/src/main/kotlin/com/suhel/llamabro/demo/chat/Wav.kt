package com.suhel.llamabro.demo.chat

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono PCM WAV, as voice messages (D76) and dictation (D140) write it. */
internal object Wav {
    const val HEADER = 44
    private const val RIFF_PREAMBLE = 8
    private const val FMT_LENGTH = 16
    private const val PCM: Short = 1
    private const val MONO: Short = 1
    private const val BITS: Short = 16
    private const val BYTES_PER_SAMPLE = 2

    /** The 44-byte header of a PCM WAV file: 16-bit mono at [rate], [samples] long. */
    fun header(rate: Int, samples: Long): ByteArray {
        val data = (samples * BYTES_PER_SAMPLE).toInt()
        return ByteBuffer
            .allocate(HEADER)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray())
            .putInt(HEADER - RIFF_PREAMBLE + data) // the file's length after this field
            .put("WAVE".toByteArray())
            .put("fmt ".toByteArray())
            .putInt(FMT_LENGTH)
            .putShort(PCM)
            .putShort(MONO)
            .putInt(rate)
            .putInt(rate * BYTES_PER_SAMPLE) // bytes a second
            .putShort(BYTES_PER_SAMPLE.toShort()) // bytes a frame
            .putShort(BITS)
            .put("data".toByteArray())
            .putInt(data)
            .array()
    }

    /** [n] samples of [pcm] as little-endian bytes, into [into] when given (the first `2 * n` of it). */
    fun bytes(pcm: ShortArray, n: Int = pcm.size, into: ByteArray = ByteArray(n * BYTES_PER_SAMPLE)): ByteArray {
        for (i in 0 until n) {
            val v = pcm[i].toInt()
            into[2 * i] = v.toByte()
            into[2 * i + 1] = (v shr Byte.SIZE_BITS).toByte()
        }
        return into
    }

    /** A whole WAV file of [pcm] (little-endian 16-bit samples) at [rate]. */
    fun file(pcm: ByteArray, rate: Int): ByteArray = header(rate, pcm.size.toLong() / BYTES_PER_SAMPLE) + pcm
}
