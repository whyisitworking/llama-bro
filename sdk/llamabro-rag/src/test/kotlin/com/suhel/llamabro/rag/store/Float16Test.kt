package com.suhel.llamabro.rag.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

class Float16Test {
    private fun half(f: Float): Int = Float16.fromFloat(f).toInt() and 0xffff

    private fun float(h: Int): Float = Float16.toFloat(h.toShort())

    @Test
    fun roundTripWithinAThousandth() {
        val random = Random(7)
        repeat(100_000) {
            // the normal range, 2^-14 to 65504, both signs
            val magnitude = 2f.pow(random.nextFloat() * 29.99f - 14f)
            val f = if (random.nextBoolean()) magnitude else -magnitude
            val back = float(half(f))
            assertTrue("$f came back as $back", abs(back - f) <= abs(f) * 1e-3f)
        }
    }

    @Test
    fun everyFiniteHalfRoundTripsExactly() {
        for (h in 0..0xffff) {
            if (h and 0x7c00 == 0x7c00) continue
            assertEquals("half 0x${h.toString(16)}", h, half(float(h)))
        }
    }

    @Test
    fun zeroKeepsItsSign() {
        assertEquals(0x0000, half(0f))
        assertEquals(0x8000, half(-0f))
        assertEquals(0f.toRawBits(), float(0x0000).toRawBits())
        assertEquals((-0f).toRawBits(), float(0x8000).toRawBits())
    }

    @Test
    fun subnormals() {
        val tiny = 2f.pow(-24)
        assertEquals(0x0001, half(tiny))
        assertEquals(tiny, float(0x0001))
        assertEquals(1023 * tiny, float(0x03ff))
        assertEquals(0x03ff, half(1023 * tiny))
        assertEquals(0x8001, half(-tiny))
        // half the smallest subnormal is a tie, and goes to the even zero; a little more rounds up
        assertEquals(0x0000, half(tiny / 2))
        assertEquals(0x0001, half(tiny * 0.75f))
        assertEquals(0x0000, half(tiny / 3))
        // a float far below the half range is zero
        assertEquals(0x0000, half(1e-30f))
    }

    @Test
    fun largestFinite() {
        assertEquals(0x7bff, half(65504f))
        assertEquals(0xfbff, half(-65504f))
        assertEquals(65504f, float(0x7bff))
        assertEquals(-65504f, float(0xfbff))
        assertEquals(0x7bff, half(65519f))
        // 65520 is halfway to the next step: it ties to even, which is infinity
        assertEquals(0x7c00, half(65520f))
        assertEquals(0xfc00, half(-1e10f))
    }

    @Test
    fun roundsToNearestEven() {
        val step = 2f.pow(-10)
        assertEquals(0x3c00, half(1f))
        assertEquals(0x3c00, half(1f + step / 2))
        assertEquals(0x3c02, half(1f + 3 * step / 2))
        assertEquals(0x3c01, half(1f + step / 2 + step / 8))
    }

    @Test
    fun infinityAndNaN() {
        assertEquals(0x7c00, half(Float.POSITIVE_INFINITY))
        assertEquals(0xfc00, half(Float.NEGATIVE_INFINITY))
        assertEquals(Float.POSITIVE_INFINITY, float(0x7c00))
        assertTrue(float(half(Float.NaN)).isNaN())
    }
}
