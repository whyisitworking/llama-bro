package com.suhel.llamabro.demo.ui.chat

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The steps the message list's scrolls are told to [FollowState] in (ChatContent's followScrolls). */
class ScrollMovesTest {
    private val start = ListPosition(10, 500)

    private fun moves(vararg samples: ListSample): List<ScrollMove> =
        runBlocking { flowOf(*samples).scrollMoves(start).toList() }

    private fun at(index: Int, offset: Int, scrolling: Boolean, atEnd: Boolean = false) =
        ListSample(ListPosition(index, offset), scrolling, atEnd)

    @Test fun aStillListMovesNothing() = assertEquals(emptyList<ScrollMove>(), moves(at(10, 500, false)))

    @Test fun aScrollTellsEveryStepFromWhereTheListStood() =
        assertEquals(
            listOf(
                ScrollMove(start, ListPosition(10, 300), atEnd = false),
                ScrollMove(ListPosition(10, 300), ListPosition(9, 100), atEnd = false),
            ),
            moves(at(10, 300, true), at(9, 100, true)),
        )

    @Test fun theStepAfterAScrollStopsIsToldOnceAndThenNothing() =
        assertEquals(
            listOf(
                ScrollMove(start, ListPosition(12, 0), atEnd = false),
                ScrollMove(ListPosition(12, 0), ListPosition(12, 40), atEnd = true),
            ),
            moves(at(12, 0, true), at(12, 40, false, atEnd = true), at(12, 40, false, atEnd = true)),
        )

    @Test fun aListThatMovesWithNoScrollIsNotTold() =
        // the list's own growth (a new row) moves its position with no scroll in progress
        assertEquals(emptyList<ScrollMove>(), moves(at(11, 0, false), at(12, 0, false, atEnd = true)))

    @Test fun followingStopsOnAScrollTowardOlderMessagesAndResumesAtTheEnd() {
        val follow = FollowState()
        val toOlder = moves(at(10, 300, true), at(10, 300, false))
        toOlder.forEach { follow.onScroll(it.from, it.to, it.atEnd) }
        assertEquals(false, follow.following)
        runBlocking {
            flowOf(at(10, 400, true), at(11, 0, true, atEnd = true), at(11, 0, false, atEnd = true))
                .scrollMoves(ListPosition(10, 300))
                .toList()
        }.forEach { follow.onScroll(it.from, it.to, it.atEnd) }
        assertEquals(true, follow.following)
    }
}
