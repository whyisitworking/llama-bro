package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * An expandable sheet's growth to its full height, asked for by its content rather than by a scroll. [grow] takes the
 * sheet to its full height with its scroll held, and returns once it stands there; [shows] says whether a part lies
 * wholly in the sheet's scroll as it is now.
 */
@Stable
class SheetGrowth internal constructor(private val stillNow: () -> Boolean) {
    internal val full: MutableState<Boolean> = mutableStateOf(false)

    /** The scroll's viewport in the root, written as it is placed; never read in composition. */
    internal var viewport: Rect = Rect.Zero

    fun shows(bounds: Rect): Boolean = bounds.top >= viewport.top && bounds.bottom <= viewport.bottom

    suspend fun grow() {
        if (full.value) return
        full.value = true
        // the body's height eases over the enter duration (ExpandableBody); one frame more and it stands there
        if (!stillNow()) delay(DemoMotion.ENTER_MILLIS.toLong())
        withFrameNanos { }
    }
}

/** The expandable sheet the content is in; null anywhere else. */
val LocalSheetGrowth = staticCompositionLocalOf<SheetGrowth?> { null }

/**
 * A part of an expandable sheet's content that a person's action brings into view (the card's "Get another size"
 * brings the size Get would fetch, D121): put [modifier] on it, then call [reveal].
 */
@Stable
class SheetReveal internal constructor(private val growth: SheetGrowth?) {
    private val requester = BringIntoViewRequester()

    /** The part's own bounds in the root, unclipped: where it is, on screen or not. */
    private var bounds: Rect? = null

    val modifier: Modifier =
        Modifier
            .bringIntoViewRequester(requester)
            .onGloballyPositioned { bounds = Rect(it.positionInRoot(), it.size.toSize()) }

    /**
     * One motion, never two at once: when the part isn't wholly in view the sheet grows with its scroll held, while
     * whatever opened the part unfolds for [settleMillis]; then, only if it is still not wholly in view, the least
     * scroll brings it there. Outside an expandable sheet, only the scroll.
     */
    suspend fun reveal(settleMillis: Long) {
        withFrameNanos { } // the part's place is known
        coroutineScope {
            val placed = bounds
            if (growth != null && placed != null && !growth.shows(placed)) launch { growth.grow() }
            if (settleMillis > 0) delay(settleMillis)
        }
        withFrameNanos { }
        requester.bringIntoView()
    }
}

/** A [SheetReveal] for the sheet the content is in. */
@Composable
fun rememberSheetReveal(): SheetReveal {
    val growth = LocalSheetGrowth.current
    return remember(growth) { SheetReveal(growth) }
}
