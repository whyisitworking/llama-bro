package com.suhel.llamabro.demo.ui.shell

import com.suhel.llamabro.demo.designsystem.theme.GlassTier
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which phones draw the full glass (D153). */
class GlassTiersTest {
    @Test fun aPhoneOfAndroid13sPerformanceClassOrLaterDrawsTheFullGlass() {
        assertEquals(GlassTier.Full, glassTier(performanceClass = 33, lowRam = false))
        assertEquals(GlassTier.Full, glassTier(performanceClass = 35, lowRam = false))
    }

    @Test fun aPhoneThatDeclaresNoClassOrAnEarlierOneDrawsTheLightGlass() {
        assertEquals(GlassTier.Light, glassTier(performanceClass = 0, lowRam = false))
        assertEquals(GlassTier.Light, glassTier(performanceClass = 31, lowRam = false))
    }

    @Test fun aLowMemoryPhoneDrawsTheLightGlassWhateverItsClass() {
        assertEquals(GlassTier.Light, glassTier(performanceClass = 35, lowRam = true))
    }
}
