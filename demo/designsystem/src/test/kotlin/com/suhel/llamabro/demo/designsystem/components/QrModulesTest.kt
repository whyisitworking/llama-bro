package com.suhel.llamabro.demo.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The QR code's modules: a real code's size and its three finder patterns, as a scanner looks for them. */
class QrModulesTest {
    // the Server page's payload at its longest: a full IPv4 address, a five-digit port and a 43-character key
    private val payload =
        "{\"url\":\"http://192.168.100.200:65535\",\"key\":\"q8Vw3nX0dJk2LmP5sT7uY9zA1bC4eF6gH8iK0lM2nO4\"}"

    @Test fun theServerPayloadFitsAVersionSevenCodeOrSmaller() {
        val modules = QrModules.encode(payload)
        // version v is 17 + 4v modules a side
        assertEquals(0, (modules.size - 17) % 4)
        assertTrue("version ${(modules.size - 17) / 4}", modules.size <= 45)
    }

    @Test fun eachCornerButTheBottomEndHasAFinder() {
        val modules = QrModules.encode(payload)
        val far = modules.size - 7
        for ((x0, y0) in listOf(0 to 0, far to 0, 0 to far)) {
            // a dark 7x7 ring, a light ring inside it, a dark 3x3 centre
            for (i in 0 until 7) {
                assertTrue(modules[x0 + i, y0])
                assertTrue(modules[x0 + i, y0 + 6])
                assertTrue(modules[x0, y0 + i])
                assertTrue(modules[x0 + 6, y0 + i])
            }
            assertFalse(modules[x0 + 1, y0 + 1])
            assertTrue(modules[x0 + 3, y0 + 3])
        }
    }

    @Test fun theSameTextIsTheSameCode() {
        val a = QrModules.encode(payload)
        val b = QrModules.encode(payload)
        assertEquals(a.size, b.size)
        for (y in 0 until a.size) for (x in 0 until a.size) assertEquals(a[x, y], b[x, y])
    }
}
