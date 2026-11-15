package com.suhel.llamabro.demo.ui.chat

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowStateTest {
    private val follow = FollowState()
    private var at = ListPosition(10, 500)

    /** A scroll to [index] and [offset], ending at the list's end or not. */
    private fun scrollTo(index: Int, offset: Int, end: Boolean) {
        val to = ListPosition(index, offset)
        follow.onScroll(at, to, end)
        at = to
    }

    @Test fun followsFromTheStart() = assertTrue(follow.following)

    @Test fun scrollingTowardOlderMessagesStopsFollowing() {
        scrollTo(10, 300, end = false)
        assertFalse(follow.following)
    }

    @Test fun scrollingBackAnItemStopsFollowing() {
        scrollTo(9, 900, end = false)
        assertFalse(follow.following)
    }

    @Test fun scrollingTowardNewerButShortOfTheEndDoesNotResume() {
        scrollTo(10, 100, end = false)
        scrollTo(10, 300, end = false)
        assertFalse(follow.following)
    }

    @Test fun reachingTheEndResumes() {
        scrollTo(10, 100, end = false)
        scrollTo(10, 500, end = true)
        assertTrue(follow.following)
    }

    @Test fun aScrollThatDoesNotMoveKeepsFollowing() {
        // the reply grew under a touch at the end: the list is no longer at its end, but nobody scrolled back
        scrollTo(10, 500, end = false)
        assertTrue(follow.following)
    }

    @Test fun backToTheLatestResumes() {
        scrollTo(10, 100, end = false)
        follow.follow()
        assertTrue(follow.following)
    }

    @Test fun notFollowingSurvivesBeingSaved() {
        scrollTo(10, 100, end = false)
        val saved = with(FollowState.Saver) { SaverScope { true }.save(follow) }!!
        assertFalse(FollowState.Saver.restore(saved)!!.following)
    }
}
