package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How pressed glass is drawn at each point of its press and its drag (D155). */
class GlassPressValuesTest {
    private val press = GlassPress.Default.copy(swell = 1.06f, light = 0.5f, bend = 0.4f)

    @Test fun atRestNothingChanges() {
        assertEquals(1f, press.scaleAt(0f, still = false), 0f)
        assertEquals(1f, press.bendAt(0f), 0f)
        assertEquals(0f, press.lightAt(0f), 0f)
    }

    @Test fun pressedItSwellsBendsMoreAndLights() {
        assertEquals(1.06f, press.scaleAt(1f, still = false), 1e-6f)
        assertEquals(1.4f, press.bendAt(1f), 1e-6f)
        assertEquals(0.5f, press.lightAt(1f), 1e-6f)
    }

    @Test fun withAnimationsOffItDoesntSwellButStillLights() {
        assertEquals(1f, press.scaleAt(1f, still = true), 0f)
        assertEquals(0.5f, press.lightAt(1f), 1e-6f)
    }

    @Test fun aSpringPastFullSwellsALittleMoreButTheLightHolds() {
        assertEquals(1.066f, press.scaleAt(1.1f, still = false), 1e-6f)
        assertEquals(0.5f, press.lightAt(1.1f), 0f)
        // and swinging back below rest, it neither darkens, bends less nor shrinks below rest
        assertEquals(0f, press.lightAt(-0.1f), 0f)
        assertEquals(1f, press.bendAt(-0.1f), 0f)
        assertEquals(1f, press.scaleAt(-0.1f, still = false), 0f)
    }

    private val base = Size(100f, 40f)

    @Test fun pressedInPlaceItSwellsAboutItsMiddle() {
        val pose = press.pose(base, press = 1f, drag = Offset.Zero, still = false, pullPx = 30f)
        assertEquals(Size(106f, 42.4f).width, pose.size.width, 1e-3f)
        assertEquals(42.4f, pose.size.height, 1e-3f)
        assertEquals(Offset.Zero, pose.moved(base).round())
    }

    @Test fun draggedItFollowsOnAnElasticThatNeverPassesItsPull() {
        val near = press.pose(base, 1f, Offset(30f, 0f), still = false, pullPx = 30f)
        assertEquals("a drag of the pull moves it half that", 15f, near.moved(base).x, 1e-3f)
        val far = press.pose(base, 1f, Offset(3_000f, 0f), still = false, pullPx = 30f)
        assertTrue(far.moved(base).x < 30f)
        assertTrue(far.moved(base).x > near.moved(base).x)
    }

    @Test fun draggedSidewaysItStretchesAlongAndNarrowsAcross() {
        val pose = press.copy(stretch = 0.1f).pose(base, 0f, Offset(30f, 0f), still = false, pullPx = 30f)
        assertEquals(1.05f, pose.scaleX, 1e-4f)
        assertEquals(0.95f, pose.scaleY, 1e-4f)
        val down = press.copy(stretch = 0.1f).pose(base, 0f, Offset(0f, -30f), still = false, pullPx = 30f)
        assertEquals(0.95f, down.scaleX, 1e-4f)
        assertEquals(1.05f, down.scaleY, 1e-4f)
        assertEquals(-15f, down.moved(base).y, 1e-3f)
    }

    @Test fun withAnimationsOffADragNeitherMovesNorStretchesIt() {
        val pose = press.pose(base, 1f, Offset(30f, 30f), still = true, pullPx = 30f)
        assertEquals(base, pose.size)
        assertEquals(Offset.Zero, pose.topLeft)
    }

    private fun Offset.round() = Offset(Math.round(x * 1000) / 1000f, Math.round(y * 1000) / 1000f)
}
