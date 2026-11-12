package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spacing steps and the corner scale of the verified spec (docs/sdk-v2/pd-design-v2-spec.md, sections 2 and 4). */
class SpacingAndShapesTest {
    @Test
    fun theStepsAreTheSpecsAndGrowInOrder() {
        val steps =
            listOf(DemoSpacing.inline, DemoSpacing.block, DemoSpacing.item, DemoSpacing.section, DemoSpacing.hero)
        assertEquals(listOf(4.dp, 8.dp, 16.dp, 24.dp, 32.dp), steps)
        assertEquals(16.dp, DemoSpacing.gutter)
    }

    @Test
    fun everyStepIsOnTheFourDpGrid() {
        val all =
            listOf(
                DemoSpacing.inline,
                DemoSpacing.block,
                DemoSpacing.item,
                DemoSpacing.section,
                DemoSpacing.hero,
                DemoSpacing.gutter,
            )
        for (step in all) assertTrue("$step is off the 4 dp grid", step.value % 4f == 0f)
    }

    @Test
    fun theGapAroundAGroupIsLargerThanTheGapInsideIt() {
        assertTrue(DemoSpacing.section > DemoSpacing.item)
        assertTrue(DemoSpacing.item > DemoSpacing.block)
        assertTrue(DemoSpacing.block > DemoSpacing.inline)
    }

    @Test
    fun cornersAreMaterialsScale() {
        assertEquals(RoundedCornerShape(12.dp), DemoShapes.card)
        assertEquals(RoundedCornerShape(8.dp), DemoShapes.chip)
        assertEquals(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), DemoShapes.sheet)
        assertEquals(RoundedCornerShape(28.dp), DemoShapes.composer)
    }
}
