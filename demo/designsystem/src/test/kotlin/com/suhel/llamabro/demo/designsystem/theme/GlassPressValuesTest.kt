package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How pressed glass is drawn at each point of its press and its drag (D155). */
class GlassPressValuesTest {
    private val press = GlassPress.Default.copy(swell = 1.1f, light = 0.5f, bend = 0.4f, stretch = 0.1f)
    private val chip = Size(40f, 40f)
    private val panel = Size(400f, 40f)

    @Test fun atRestNothingChanges() {
        assertEquals(GlassPose(Offset.Zero, chip, 1f, 1f), press.pose(chip, 0f, Offset.Zero, still = false, 6f))
        assertEquals(1f, press.bendAt(0f), 0f)
        assertEquals(0f, press.lightAt(0f), 0f)
    }

    @Test fun pressedAChipSwellsAboutItsMiddleItsContentWithIt() {
        val pose = press.pose(chip, 1f, Offset.Zero, still = false, 6f)
        assertEquals(44f, pose.size.width, 1e-4f)
        assertEquals(44f, pose.size.height, 1e-4f)
        assertEquals(-2f, pose.topLeft.x, 1e-4f)
        assertEquals(-2f, pose.topLeft.y, 1e-4f)
        assertEquals(1.1f, pose.scaleX, 1e-5f)
        assertEquals(1.4f, press.bendAt(1f), 1e-6f)
        assertEquals(0.5f, press.lightAt(1f), 1e-6f)
    }

    @Test fun aWidePanelGrowsByAsMuchAsAChipNotAShareOfItsWidth() {
        val pose = press.pose(panel, 1f, Offset.Zero, still = false, 6f)
        assertEquals(404f, pose.size.width, 1e-4f)
        assertEquals(44f, pose.size.height, 1e-4f)
        // its text barely grows: by what its width does
        assertEquals(1.01f, pose.scaleX, 1e-5f)
        assertEquals(1.01f, pose.scaleY, 1e-5f)
    }

    @Test fun draggedItStretchesAlongAndBarelyMoves() {
        val pose = press.pose(chip, 0f, Offset(54f, 0f), still = false, 6f)
        // a drag nine times the pull gives nine tenths of the stretch (a tenth of the 40 px side) and of the pull
        assertEquals(43.6f, pose.size.width, 1e-3f)
        assertEquals(36.4f, pose.size.height, 1e-3f)
        assertEquals(5.4f, pose.moved(chip).x, 1e-3f)
        assertTrue(pose.moved(chip).x < 6f)
        val down = press.pose(chip, 0f, Offset(0f, -54f), still = false, 6f)
        assertEquals(43.6f, down.size.height, 1e-3f)
        assertEquals(-5.4f, down.moved(chip).y, 1e-3f)
    }

    @Test fun aSpringPastFullOrBelowRestIsHeldToTheRange() {
        assertEquals(44f, press.pose(chip, 1.2f, Offset.Zero, still = false, 6f).size.width, 1e-4f)
        assertEquals(chip, press.pose(chip, -0.1f, Offset.Zero, still = false, 6f).size)
        assertEquals(0.5f, press.lightAt(1.1f), 0f)
        assertEquals(0f, press.lightAt(-0.1f), 0f)
        assertEquals(1f, press.bendAt(-0.1f), 0f)
    }

    @Test fun withAnimationsOffItNeitherSwellsNorMovesButStillLights() {
        assertEquals(GlassPose(Offset.Zero, chip, 1f, 1f), press.pose(chip, 1f, Offset(30f, 30f), still = true, 6f))
        assertEquals(0.5f, press.lightAt(1f), 1e-6f)
    }
}
