package com.suhel.llamabro.demo.audio

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Milliseconds in a second, for the audio features' lengths and timings. */
internal const val MILLIS_PER_SECOND = 1_000L

/** 16-bit mono PCM WAV, as voice messages (D76), dictation (D140) and voice mode (D100) write it. */
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

/**
 * A WAV file of 16-bit mono at [rate] written as the audio comes, its header filled in by [close] once the length is
 * known. Created and used on an IO thread, by one coroutine at a time. Voice messages and dictation record through it.
 */
internal class WavWriter(file: File, private val rate: Int) : Closeable {
    private val out: RandomAccessFile
    private var bytes = ByteArray(0)

    /** Samples written so far. */
    var samples = 0L
        private set

    /** How long what was written plays. */
    val millis: Long get() = samples * MILLIS_PER_SECOND / rate

    init {
        // the store's folder exists only once something was recorded: this may be the first
        file.parentFile?.mkdirs()
        out = RandomAccessFile(file, "rw")
        try {
            out.setLength(0)
            out.write(ByteArray(Wav.HEADER))
        } catch (e: IOException) {
            out.close()
            throw e
        }
    }

    fun write(chunk: ShortArray) {
        if (bytes.size < 2 * chunk.size) bytes = ByteArray(2 * chunk.size)
        out.write(Wav.bytes(chunk, chunk.size, bytes), 0, 2 * chunk.size)
        samples += chunk.size
    }

    override fun close() {
        out.use {
            it.seek(0)
            it.write(Wav.header(rate, samples))
        }
    }
}
