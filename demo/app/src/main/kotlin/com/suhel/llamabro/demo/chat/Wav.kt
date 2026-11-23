package com.suhel.llamabro.demo.chat

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono PCM WAV, as voice messages (D76) and dictation (D140) write it. */
internal object Wav {
    const val HEADER = 44

    /** The 44-byte header of a PCM WAV file: 16-bit mono at [rate], [samples] long. */
    fun header(rate: Int, samples: Long): ByteArray {
        val data = (samples * 2).toInt()
        return ByteBuffer
            .allocate(HEADER)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray())
            .putInt(36 + data)
            .put("WAVE".toByteArray())
            .put("fmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(rate)
            .putInt(rate * 2)
            .putShort(2)
            .putShort(16)
            .put("data".toByteArray())
            .putInt(data)
            .array()
    }

    /** [n] samples of [pcm] as little-endian bytes. */
    fun bytes(pcm: ShortArray, n: Int = pcm.size): ByteArray {
        val b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) b.putShort(pcm[i])
        return b.array()
    }

    /** A whole WAV file of [pcm] (little-endian 16-bit samples) at [rate]. */
    fun file(pcm: ByteArray, rate: Int): ByteArray = header(rate, pcm.size / 2L) + pcm
}
