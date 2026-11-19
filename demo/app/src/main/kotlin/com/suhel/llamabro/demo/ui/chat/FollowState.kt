package com.suhel.llamabro.demo.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Whether the message list keeps a streaming reply's newest text in view (demo design §4.4). It follows from the
 * start; a scroll toward older messages stops it, and a scroll that reaches the very end, the reader sending a message
 * or Back to the latest starts it again.
 */
@Stable
class FollowState(following: Boolean = true) {
    var following by mutableStateOf(following)
        private set

    fun follow() {
        following = true
    }

    /** The reader moved the list toward older messages: it stays where they leave it. */
    fun leave() {
        following = false
    }

    /**
     * A scroll moved the list from [from] to [to], each the first visible item's index and its offset; [atEnd] is
     * whether it can scroll no further. Any scroll counts, the reader's touch or an accessibility service's action:
     * the list's own scrolls only ever move toward the end, so one that moves back is never ours.
     */
    fun onScroll(from: ListPosition, to: ListPosition, atEnd: Boolean) {
        following = atEnd || (following && to >= from)
    }

    companion object {
        val Saver = Saver<FollowState, Boolean>({ it.following }, ::FollowState)
    }
}

/** Where a list is scrolled to: its first visible item and how far into that item. */
data class ListPosition(val index: Int, val offset: Int) : Comparable<ListPosition> {
    override fun compareTo(other: ListPosition): Int = compareValuesBy(this, other, { it.index }, { it.offset })
}

/** Kept across rotation, so a reader who scrolled away is not pulled back to the end by it. */
@Composable
fun rememberFollowState(): FollowState = rememberSaveable(saver = FollowState.Saver) { FollowState() }
