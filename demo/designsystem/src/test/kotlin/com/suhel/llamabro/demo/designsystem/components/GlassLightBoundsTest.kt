package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/** A press's light stays on the glass when the finger leaves it (D158). */
class GlassLightBoundsTest {
    private val pane = Size(200f, 60f)
    private val corner = 30f

    @Test fun aFingerOnTheGlassIsWhereTheLightIs() {
        assertEquals(Offset(100f, 30f), Offset(100f, 30f).insideRoundRect(pane, corner))
        assertEquals(Offset(40f, 10f), Offset(40f, 10f).insideRoundRect(pane, corner))
    }

    @Test fun aFingerOffAnEdgeLeavesTheLightOnThatEdge() {
        assertEquals(Offset(100f, 0f), Offset(100f, -80f).insideRoundRect(pane, corner))
        assertEquals(Offset(120f, 60f), Offset(120f, 300f).insideRoundRect(pane, corner))
    }

    @Test fun aFingerPastACornerLeavesTheLightOnItsArc() {
        val held = Offset(-100f, -100f).insideRoundRect(pane, corner)
        // on the arc about (30, 30), toward the finger
        assertEquals(corner, (held - Offset(30f, 30f)).getDistance(), 1e-3f)
        assertEquals(held.x, held.y, 1e-3f)
    }
}
