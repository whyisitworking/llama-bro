package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The photo viewer's zoom, pan and pull-to-close, without a screen. */
class PhotoGeometryTest {
    private val screen = Size(1000f, 2000f)
    private val fitted = fittedSize(Size(400f, 300f), screen) // 1000 x 750

    @Test fun aPhotoFitsTheScreenKeepingItsShape() {
        assertEquals(Size(1000f, 750f), fitted)
        assertEquals(Size(500f, 2000f), fittedSize(Size(100f, 400f), screen))
        assertEquals(Size.Zero, fittedSize(Size(0f, 10f), screen))
    }

    @Test fun zoomStaysBetweenOneAndTheMost() {
        assertEquals(1f, clampZoom(0.3f))
        assertEquals(MAX_ZOOM, clampZoom(40f))
        assertEquals(2f, clampZoom(2f))
    }

    @Test fun thereIsNoPanningAtOneX() {
        assertEquals(Offset.Zero, clampPan(Offset(300f, -200f), 1f, fitted, screen))
    }

    @Test fun panningStopsAtThePhotosEdges() {
        // at 2x: 2000 x 1500 on 1000 x 2000, so 500 px each way across and none up or down
        assertEquals(Offset(500f, 0f), clampPan(Offset(900f, 300f), 2f, fitted, screen))
        assertEquals(Offset(-500f, 0f), clampPan(Offset(-900f, -300f), 2f, fitted, screen))
        // at 4x: 4000 x 3000, so 1500 across and 500 up or down
        assertEquals(Offset(1500f, -500f), clampPan(Offset(2000f, -2000f), 4f, fitted, screen))
    }

    @Test fun aPinchKeepsThePointUnderTheFingers() {
        val centroid = Offset(200f, 100f)
        val zoom = PhotoZoom().transform(2f, Offset.Zero, centroid, fitted, screen)
        assertEquals(2f, zoom.scale)
        // the content point under the fingers at 1x (200, 100) is drawn at 2 * 200 + pan = 200 across; up and down
        // the 1500 px tall photo still fits the screen, so it stays centred
        assertEquals(Offset(-200f, 0f), zoom.pan)
    }

    @Test fun pinchingPastTheLimitsIsClamped() {
        assertEquals(PhotoZoom(), PhotoZoom().transform(0.5f, Offset(50f, 50f), Offset.Zero, fitted, screen))
        assertEquals(MAX_ZOOM, PhotoZoom(3f).transform(10f, Offset.Zero, Offset.Zero, fitted, screen).scale)
    }

    @Test fun aDoubleTapZoomsInAtTheTapAndAgainZoomsOut() {
        val tap = Offset(-100f, 50f)
        val inZoom = PhotoZoom().toggled(tap, fitted, screen)
        assertEquals(DOUBLE_TAP_ZOOM, inZoom.scale)
        assertTrue(inZoom.zoomed)
        assertEquals(tap.x, tap.x * inZoom.scale + inZoom.pan.x, 0.01f) // the tapped point stays where it was
        assertEquals(PhotoZoom(), inZoom.toggled(tap, fitted, screen))
    }

    @Test fun aDoubleTapNearAnEdgeStaysInsideThePhoto() {
        val zoom = PhotoZoom().toggled(Offset(500f, 900f), fitted, screen)
        assertEquals(clampPan(zoom.pan, zoom.scale, fitted, screen), zoom.pan)
    }

    @Test fun aShortPullSpringsBackAndALongOneCloses() {
        assertFalse(dismisses(300f, 0f, 2000f))
        assertTrue(dismisses(500f, 0f, 2000f))
        assertTrue(dismisses(-500f, 0f, 2000f))
        assertFalse(dismisses(0f, 0f, 0f))
    }

    @Test fun aFlickClosesButOneBackTowardsTheMiddleDoesNot() {
        assertTrue(dismisses(100f, 4000f, 2000f))
        assertFalse(dismisses(100f, -4000f, 2000f))
        assertFalse(dismisses(100f, 1000f, 2000f))
    }

    @Test fun theBackdropFadesWithThePull() {
        assertEquals(1f, backdropAlpha(0f, 2000f))
        assertEquals(0.5f, backdropAlpha(500f, 2000f), 0.001f)
        assertEquals(0f, backdropAlpha(-1500f, 2000f))
        assertEquals(1f, backdropAlpha(100f, 0f))
    }
}
