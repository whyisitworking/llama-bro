package com.suhel.llamabro.demo.designsystem.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/** Reference K's recomposition counter, the one algorithm the recomposition tests of both modules share. */
@Stable
class RecompositionCounter {
    val counts = mutableMapOf<String, Int>()

    fun reset() = counts.clear()
}

/** Tests provide it; production leaves it null. */
val LocalRecompositionCounter = staticCompositionLocalOf<RecompositionCounter?> { null }

/**
 * Call at the top of a row composable; a no-op (one null check) unless a test provides the counter. Non-skippable: a
 * skipped call (same key as last time) would hide exactly the recompositions the counter is there to count.
 */
@NonSkippableComposable
@Composable
fun CountRecompositions(key: String) {
    val counter = LocalRecompositionCounter.current ?: return
    SideEffect { counter.counts[key] = (counter.counts[key] ?: 0) + 1 }
}
