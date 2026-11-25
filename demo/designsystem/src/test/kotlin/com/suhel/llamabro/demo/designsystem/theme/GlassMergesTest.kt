package com.suhel.llamabro.demo.designsystem.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which phones' bars draw the glass on them (D154). */
class GlassMergesTest {
    @Test fun leftToThePhoneOnlyALightPhoneMerges() {
        val glass = GlassTheme.Default.copy(mergedLens = null)
        assertFalse(glass.merges(GlassTier.Full))
        assertTrue(glass.merges(GlassTier.Light))
    }

    @Test fun aChoiceHoldsOnEveryPhone() {
        for (tier in GlassTier.entries) {
            assertTrue(GlassTheme.Default.copy(mergedLens = true).merges(tier))
            assertFalse(GlassTheme.Default.copy(mergedLens = false).merges(tier))
        }
    }
}
