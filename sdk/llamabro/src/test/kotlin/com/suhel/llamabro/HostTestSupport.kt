package com.suhel.llamabro

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the host tests on upstream's tiny LFM3 (voice, transcription, speech) share. */
internal object HostTestSupport {
    const val CONTEXT = 4096
    const val SEED = 42L
    const val TIMEOUT_MS = 300_000L

    /** The 16-bit samples of a WAV's `data` chunk (the test clip is 16 kHz mono). */
    fun pcmOf(wav: ByteArray): ShortArray {
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        while (at + 8 <= wav.size) {
            val id = String(wav, at, 4, Charsets.US_ASCII)
            val size = b.getInt(at + 4)
            if (id == "data") {
                val samples = ShortArray(minOf(size, wav.size - at - 8) / 2)
                b.position(at + 8)
                b.asShortBuffer().get(samples)
                return samples
            }
            at += 8 + size + (size and 1)
        }
        error("no data chunk in the clip")
    }
}
