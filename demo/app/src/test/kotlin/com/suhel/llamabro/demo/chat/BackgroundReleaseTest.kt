package com.suhel.llamabro.demo.chat

import android.content.ComponentCallbacks2
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundReleaseTest {
    /** The observer never reads its owner; the process's own owner needs a running app. */
    private val owner =
        object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = throw UnsupportedOperationException()
        }

    @Test fun theModelIsReleasedAfterAWhileInTheBackground() =
        runTest {
            var releases = 0
            val observer = BackgroundRelease(backgroundScope, { 60_000L }) { true.also { releases++ } }
            observer.onStop(owner)
            advanceTimeBy(59_999)
            runCurrent()
            assertEquals(0, releases)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(1, releases)
        }

    @Test fun comingBackBeforeTheDelayKeepsTheModel() =
        runTest {
            var releases = 0
            val observer = BackgroundRelease(backgroundScope, { 60_000L }) { true.also { releases++ } }
            observer.onStop(owner)
            advanceTimeBy(30_000)
            observer.onStart(owner)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(0, releases)
        }

    @Test fun aReplyUnderWayIsNeverCutOffAndTheReleaseWaitsForIt() =
        runTest {
            val answers = ArrayDeque(listOf(false, true)) // streaming at first, idle a minute later
            var asks = 0
            val observer =
                BackgroundRelease(backgroundScope, { 60_000L }) {
                    asks++
                    answers.removeFirst()
                }
            observer.onStop(owner)
            advanceTimeBy(60_001)
            runCurrent()
            assertEquals(1, asks)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(2, asks)
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(2, asks)
        }

    @Test fun keptUntilNeededIsNeverReleasedByTheClock() =
        runTest {
            var releases = 0
            val observer = BackgroundRelease(backgroundScope, { null }) { true.also { releases++ } }
            observer.onStop(owner)
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals(0, releases)
        }

    @Test fun androidShortOfMemoryReleasesAtOnceWhateverTheChoice() =
        runTest {
            var releases = 0
            val observer = BackgroundRelease(backgroundScope, { null }) { true.also { releases++ } }
            observer.onStop(owner)
            // hidden is not short of memory: it arrives on every Home (F134)
            observer.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            runCurrent()
            assertEquals(0, releases)
            observer.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            runCurrent()
            assertEquals(1, releases)
        }

    @Test fun onScreenTheAppKeepsItsModelWhateverAndroidSays() =
        runTest {
            var releases = 0
            val observer = BackgroundRelease(backgroundScope, { 60_000L }) { true.also { releases++ } }
            observer.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(0, releases)
        }
}
