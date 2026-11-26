package com.suhel.llamabro.rag.store

/**
 * IEEE 754 binary16 by bit manipulation: `android.util.Half` and `Float.float16ToFloat` are Android or Java 20 only,
 * and the store moves to Kotlin Multiplatform. Rounding is to nearest, ties to even, as the hardware does it.
 */
internal object Float16 {
    private const val HALF_BITS = 0xffff
    private const val SIGN = 0x8000
    private const val HALF_INFINITY = 0x7c00
    private const val HALF_QUIET_NAN = 0x0200
    private const val FLOAT_EXPONENT = 0xff
    private const val FLOAT_MANTISSA = 0x7fffff
    private const val FLOAT_HIDDEN_BIT = 0x800000
    private const val FLOAT_BIAS = 127
    private const val HALF_BIAS = 15
    private const val HALF_MAX_EXPONENT = 0x1f
    private const val MANTISSA_SHIFT = 13
    private const val FLOAT_MANTISSA_BITS = 23
    private const val HALF_MANTISSA_BITS = 10
    private const val HALF_MANTISSA = 0x3ff

    // below 2^-25 a float rounds to zero; at 2^-25 the shift is 24
    private const val SUBNORMAL_SHIFT_BASE = 14
    private const val SMALLEST_HALF_EXPONENT = -10
    private const val SUBNORMAL_SCALE = 1f / (1 shl 24)

    /** Every half's float, so a scan decodes by lookup. */
    private val table = FloatArray(1 shl 16) { decode(it) }

    fun fromFloat(value: Float): Short {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and SIGN
        val exponent = (bits ushr FLOAT_MANTISSA_BITS) and FLOAT_EXPONENT
        val mantissa = bits and FLOAT_MANTISSA
        val magnitude =
            when {
                exponent == FLOAT_EXPONENT -> {
                    HALF_INFINITY or (if (mantissa != 0) HALF_QUIET_NAN or (mantissa ushr MANTISSA_SHIFT) else 0)
                }

                else -> {
                    finite(exponent - FLOAT_BIAS + HALF_BIAS, mantissa)
                }
            }
        return (sign or magnitude).toShort()
    }

    fun toFloat(half: Short): Float = table[half.toInt() and HALF_BITS]

    /** A finite float's half without its sign, [exponent] already rebiased for a half. */
    private fun finite(exponent: Int, mantissa: Int): Int =
        when {
            exponent >= HALF_MAX_EXPONENT -> HALF_INFINITY

            exponent < SMALLEST_HALF_EXPONENT -> 0

            // subnormal: the hidden bit joins the mantissa, shifted to units of 2^-24
            exponent <= 0 -> rounded(mantissa or FLOAT_HIDDEN_BIT, SUBNORMAL_SHIFT_BASE - exponent)

            // a carry out of the mantissa steps the exponent, and past the largest finite, to infinity
            else -> (exponent shl HALF_MANTISSA_BITS) + rounded(mantissa, MANTISSA_SHIFT)
        }

    private fun rounded(value: Int, shift: Int): Int {
        val kept = value ushr shift
        val rest = value and ((1 shl shift) - 1)
        val halfway = 1 shl (shift - 1)
        return if (rest > halfway || (rest == halfway && kept and 1 == 1)) kept + 1 else kept
    }

    private fun decode(half: Int): Float {
        val sign = (half and SIGN) shl 16
        val exponent = (half ushr HALF_MANTISSA_BITS) and HALF_MAX_EXPONENT
        val mantissa = half and HALF_MANTISSA
        return when (exponent) {
            0 -> {
                val magnitude = mantissa * SUBNORMAL_SCALE
                if (sign != 0) -magnitude else magnitude
            }

            HALF_MAX_EXPONENT -> {
                Float.fromBits(sign or (FLOAT_EXPONENT shl FLOAT_MANTISSA_BITS) or (mantissa shl MANTISSA_SHIFT))
            }

            else -> {
                Float.fromBits(
                    sign or ((exponent - HALF_BIAS + FLOAT_BIAS) shl FLOAT_MANTISSA_BITS) or
                        (mantissa shl MANTISSA_SHIFT),
                )
            }
        }
    }
}
