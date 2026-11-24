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
            "replyBody" to DemoType.replyBody,
            "replyH1" to DemoType.replyH1,
            "replyH2" to DemoType.replyH2,
            "replyH3" to DemoType.replyH3,
            "replyH4" to DemoType.replyH4,
            "bodyMedium" to DemoType.bodyMedium,
            "bodySmall" to DemoType.bodySmall,
            "label" to DemoType.label,
            "labelSmall" to DemoType.labelSmall,
            "code" to DemoType.code,
            "cardTitle" to DemoType.cardTitle,
            "screenTitleLight" to DemoType.screenTitleLight,
            "titleRegular" to DemoType.titleRegular,
            "quietLabel" to DemoType.quietLabel,
            "pageBody" to DemoType.pageBody,
            "sidebarTitle" to DemoType.sidebarTitle,
            "rowLine" to DemoType.rowLine,
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
            // a reply's looser line (D125)
            "replyBody" to Spec(17f, 28f, FontWeight.Normal, 0f),
            // a reply's headings (D150): size first, weight second, none past SemiBold
            "replyH1" to Spec(24f, 32f, FontWeight.SemiBold, 0f),
            "replyH2" to Spec(21f, 28f, FontWeight.SemiBold, 0f),
            "replyH3" to Spec(18f, 28f, FontWeight.SemiBold, 0f),
            "replyH4" to Spec(17f, 28f, FontWeight.Medium, 0f),
            "bodyMedium" to Spec(14f, 20f, FontWeight.Normal, 0.2f),
            "bodySmall" to Spec(12f, 16f, FontWeight.Normal, 0.4f),
            "label" to Spec(14f, 20f, FontWeight.Medium, 0.1f),
            "labelSmall" to Spec(12f, 16f, FontWeight.Medium, 0.5f),
            // the Models page's quiet roles (D123)
            "cardTitle" to Spec(24f, 32f, FontWeight.Normal, 0f),
            "screenTitleLight" to Spec(24f, 32f, FontWeight.Light, 0f),
            "titleRegular" to Spec(16f, 24f, FontWeight.Normal, 0.15f),
            "quietLabel" to Spec(13f, 20f, FontWeight.Normal, 0f),
            "pageBody" to Spec(13f, 24f, FontWeight.Normal, 0.2f),
            // the sidebar's quiet roles (D126)
            "sidebarTitle" to Spec(22f, 28f, FontWeight.Normal, 0f),
            "rowLine" to Spec(15f, 20f, FontWeight.Normal, 0.1f),
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
    fun onlyTheHeroRolesTheCardsNameTheSidebarsTitleAndTheReplyAndItsHeadingsAreSerif() {
        val sans = DemoType.body.fontFamily
        val serif = allRoles.filter { it.value.fontFamily != sans && it.value.fontFamily != FontFamily.Monospace }
        assertEquals(
            setOf("displayHero", "sheetTitle", "cardTitle", "sidebarTitle", "replyBody") +
                setOf("replyH1", "replyH2", "replyH3", "replyH4"),
            serif.keys,
        )
        assertNotEquals(sans, DemoType.displayHero.fontFamily)
        // the sans is the bundled face (D150), not the system's
        assertNotEquals(FontFamily.SansSerif, sans)
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

    @Test
    fun aReplysHeadingsStepDownAndNoneIsPastSemiBold() {
        // D150: each level smaller than the one above, or as large and lighter; light on dark reads heavy, so 600 at most
        val ladder = listOf("replyH1", "replyH2", "replyH3", "replyH4", "replyBody").map { allRoles.getValue(it) }
        for ((a, b) in ladder.zipWithNext()) {
            val smaller = b.fontSize.value < a.fontSize.value
            val lighter = b.fontSize == a.fontSize && b.fontWeight!!.weight < a.fontWeight!!.weight
            assertTrue("${a.fontSize} ${a.fontWeight} then ${b.fontSize} ${b.fontWeight}", smaller || lighter)
        }
        for (style in ladder) assertTrue(style.fontWeight!!.weight <= FontWeight.SemiBold.weight)
    }
}
