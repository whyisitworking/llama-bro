package com.suhel.llamabro.demo.designsystem.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** How pressed glass is drawn at each point of its press (D155). */
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
}
