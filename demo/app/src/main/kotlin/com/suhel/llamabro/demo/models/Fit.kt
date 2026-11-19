package com.suhel.llamabro.demo.models

import androidx.tracing.trace
import com.suhel.llamabro.DeviceKind
import kotlin.math.roundToInt

enum class FitLabel { RunsGreat, RunsWell, SlowHere, TooBig }

/** How much conversation fits: at least 16,384 tokens, at least 4,096, fewer than 4,096, or the model doesn't fit. */
enum class ContextRoom { Large, Normal, Small, None }

/** demo design §7.1, D101. Pure functions; the app reads the budget and the chip from the SDK once per screen. */
object Fit {
    private const val GIB = 1L shl 30
    private const val LARGE_HEADROOM_BYTES = GIB * 3 / 2
    private const val NORMAL_HEADROOM_BYTES = GIB / 2
    private const val SLOW_TOKENS_PER_SECOND = 8.0
    private const val GREAT_TOKENS_PER_SECOND = 20.0

    /** A token is about three quarters of a word. */
    const val WORDS_PER_TOKEN = 0.75

    /** The room: the headroom left in [budgetBytes] after [fileBytes] (the model plus its encoder, D101). */
    fun roomFromSize(fileBytes: Long, budgetBytes: Long): ContextRoom {
        val headroom = budgetBytes - fileBytes
        return when {
            headroom >= LARGE_HEADROOM_BYTES -> ContextRoom.Large
            headroom >= NORMAL_HEADROOM_BYTES -> ContextRoom.Normal
            headroom > 0 -> ContextRoom.Small
            else -> ContextRoom.None
        }
    }

    /**
     * Decode is memory-bound, so tokens/s is about `k(chip) / modelBytes`. The constants come from the phone's own
     * Qwen3.5 4B Q4_K_M (2.74 GB) measurements: NPU 15.5 t/s (F67), GPU 9.5 (F78), CPU about 11 (F27, F67, F78).
     */
    fun tokensPerSecond(modelBytes: Long, chip: DeviceKind): Double {
        val k =
            when (chip) {
                DeviceKind.Npu -> 42e9
                DeviceKind.Gpu -> 26e9
                DeviceKind.Cpu -> 30e9
            }
        return k / modelBytes.coerceAtLeast(1L)
    }

    fun label(room: ContextRoom, tokensPerSecond: Double): FitLabel =
        trace("Fit.label") { labelOf(room, tokensPerSecond) }

    private fun labelOf(room: ContextRoom, tokensPerSecond: Double): FitLabel =
        when {
            room == ContextRoom.None -> FitLabel.TooBig
            room == ContextRoom.Small || tokensPerSecond < SLOW_TOKENS_PER_SECOND -> FitLabel.SlowHere
            room == ContextRoom.Large && tokensPerSecond >= GREAT_TOKENS_PER_SECOND -> FitLabel.RunsGreat
            else -> FitLabel.RunsWell
        }

    /** A token is about three quarters of a word. */
    fun wordsPerSecond(tokensPerSecond: Double): Int = (tokensPerSecond * WORDS_PER_TOKEN).roundToInt()
}
