package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The type roles, role by role, against the verified spec (docs/design/demo-app.md section 8.2; the sizes, line
 * heights and tracking are Material 3's `TypeScaleTokens`), and the rules that keep the scale a scale.
 */
class TypeScaleTest {
    /** Every role, by name. */
    private val allRoles =
        mapOf(
            "displayHero" to DemoType.displayHero,
            "sheetTitle" to DemoType.sheetTitle,
            "screenTitle" to DemoType.screenTitle,
            "title" to DemoType.title,
            "titleSmall" to DemoType.titleSmall,
            "body" to DemoType.body,
            "bodyMedium" to DemoType.bodyMedium,
            "bodySmall" to DemoType.bodySmall,
            "label" to DemoType.label,
            "labelSmall" to DemoType.labelSmall,
            "code" to DemoType.code,
        )

    private data class Spec(val size: Float, val line: Float, val weight: FontWeight, val tracking: Float)

    private val spec =
        mapOf(
            "displayHero" to Spec(28f, 36f, FontWeight.Medium, 0f),
            "sheetTitle" to Spec(22f, 28f, FontWeight.Medium, 0f),
            "screenTitle" to Spec(22f, 28f, FontWeight.Medium, 0f),
            "title" to Spec(16f, 24f, FontWeight.Medium, 0.2f),
            "titleSmall" to Spec(14f, 20f, FontWeight.Medium, 0.1f),
            "body" to Spec(16f, 24f, FontWeight.Normal, 0.5f),
            "bodyMedium" to Spec(14f, 20f, FontWeight.Normal, 0.2f),
            "bodySmall" to Spec(12f, 16f, FontWeight.Normal, 0.4f),
            "label" to Spec(14f, 20f, FontWeight.Medium, 0.1f),
            "labelSmall" to Spec(12f, 16f, FontWeight.Medium, 0.5f),
        )

    @Test
    fun everyRoleIsMaterialsSizeLineHeightWeightAndTracking() {
        for ((name, want) in spec) {
            val style = allRoles.getValue(name)
            assertEquals("$name size", want.size, style.fontSize.value, 0f)
            assertEquals("$name line height", want.line, style.lineHeight.value, 0f)
            assertEquals("$name weight", want.weight, style.fontWeight)
            assertEquals("$name tracking", want.tracking, style.letterSpacing.value, 0f)
        }
    }

    @Test
    fun everyLineHeightIsOnTheFourDpGrid() {
        for ((name, style) in allRoles) {
            assertEquals("$name line height is not a multiple of 4", 0f, style.lineHeight.value % 4f, 0f)
        }
    }

    @Test
    fun noRoleIsSmallerThanTwelve() {
        for ((name, style) in allRoles) assertTrue("$name is below 12 sp", style.fontSize.value >= 12f)
    }

    @Test
    fun onlyTheTwoHeroRolesAreSerif() {
        val serif =
            allRoles.filter {
                it.value.fontFamily != FontFamily.SansSerif &&
                    it.value.fontFamily != FontFamily.Monospace
            }
        assertEquals(setOf("displayHero", "sheetTitle"), serif.keys)
        assertEquals(FontFamily.Monospace, DemoType.code.fontFamily)
    }

    @Test
    fun neighbouringRolesDifferInSizeOrWeight() {
        // the audit's finding: 15, 13 and 11 sp were too close to read as levels; a role differs from the next in size
        // by 2 sp or more, or in weight
        val ladder = listOf("body", "bodyMedium", "bodySmall").map { allRoles.getValue(it) }
        for ((a, b) in ladder.zipWithNext()) assertTrue(a.fontSize.value - b.fontSize.value >= 2f)
        assertNotEquals(DemoType.bodyMedium.fontWeight, DemoType.label.fontWeight)
        assertNotEquals(DemoType.bodySmall.fontWeight, DemoType.labelSmall.fontWeight)
    }
}
