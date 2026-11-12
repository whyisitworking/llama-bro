package com.suhel.llamabro.demo.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SizesTest {
    private fun file(quant: String, mb: Long) = RepoFile("m-$quant.gguf", quant, mb * 1_000_000)

    private val full =
        listOf(
            file("Q2_K", 1300),
            file("Q3_K_M", 1550),
            file("Q4_K_S", 1900),
            file("Q4_K_M", 2100),
            file("Q5_K_M", 2400),
            file("Q6_K", 2800),
            file("Q8_0", 3600),
            RepoFile("mmproj-F16.gguf", "F16", 800_000_000),
        )

    @Test fun fullTreeGivesThreeTiers() {
        val tiers = Sizes.tiers(full)
        assertEquals("Q3_K_M", tiers.getValue(SizeTier.Smaller).quant)
        assertEquals("Q4_K_M", tiers.getValue(SizeTier.Recommended).quant)
        assertEquals("Q8_0", tiers.getValue(SizeTier.Best).quant)
        assertEquals(3, tiers.size)
    }

    @Test fun unslothOnlyTreeHasNoSmaller() {
        val tiers = Sizes.tiers(listOf(file("UD-Q4_K_XL", 2500), file("BF16", 7700)))
        assertEquals("UD-Q4_K_XL", tiers.getValue(SizeTier.Recommended).quant)
        assertEquals("BF16", tiers.getValue(SizeTier.Best).quant)
        assertNull(tiers[SizeTier.Smaller])
    }

    @Test fun mmprojIsNeverATier() {
        val tiers = Sizes.tiers(listOf(file("Q4_K_M", 2000), RepoFile("mmproj-Q8_0.gguf", "Q8_0", 9_000_000_000)))
        assertFalse(tiers.values.any { it.path.contains("mmproj") })
        assertNull(tiers[SizeTier.Best])
    }

    @Test fun recommendedPrefersQ4KMThenUdQ4KXlThenTheLargestQ4() {
        assertEquals(
            "Q4_K_M",
            Sizes
                .tiers(listOf(file("UD-Q4_K_XL", 2500), file("Q4_K_M", 2100)))
                .getValue(SizeTier.Recommended)
                .quant,
        )
        assertEquals(
            "Q4_0",
            Sizes
                .tiers(listOf(file("Q4_0", 2000), file("Q4_K_S", 1900), file("Q8_0", 3600)))
                .getValue(SizeTier.Recommended)
                .quant,
        )
    }

    @Test fun smallerPrefersQ3KMThenIq3ThenTheLargestUnder75Percent() {
        val rec = file("Q4_K_M", 4000)
        val q3 = file("Q3_K_M", 2900)
        val iq3 = file("IQ3_XS", 2950)
        val q2 = file("Q2_K", 2990)
        val tooBig = file("Q3_K_L", 3100)
        assertEquals(q3, Sizes.tiers(listOf(rec, q3, iq3, q2, tooBig)).getValue(SizeTier.Smaller))
        assertEquals(iq3, Sizes.tiers(listOf(rec, iq3, q2, tooBig)).getValue(SizeTier.Smaller))
        assertEquals(q2, Sizes.tiers(listOf(rec, q2, tooBig)).getValue(SizeTier.Smaller))
        assertNull(Sizes.tiers(listOf(rec, tooBig))[SizeTier.Smaller])
    }

    @Test fun bestFallsBackToQ6ThenTheLargest() {
        assertEquals(
            "Q6_K",
            Sizes
                .tiers(listOf(file("Q4_K_M", 2000), file("Q6_K", 2800), file("Q5_K_M", 2400)))
                .getValue(SizeTier.Best)
                .quant,
        )
        assertEquals(
            "Q5_K_M",
            Sizes
                .tiers(listOf(file("Q4_K_M", 2000), file("Q5_K_M", 2400)))
                .getValue(SizeTier.Best)
                .quant,
        )
    }

    @Test fun bestIsHiddenWhenItIsTheRecommendedFile() {
        assertNull(Sizes.tiers(listOf(file("Q4_K_M", 2000)))[SizeTier.Best])
    }

    @Test fun noFilesNoTiers() {
        assertEquals(emptyMap<SizeTier, RepoFile>(), Sizes.tiers(emptyList()))
    }
}
