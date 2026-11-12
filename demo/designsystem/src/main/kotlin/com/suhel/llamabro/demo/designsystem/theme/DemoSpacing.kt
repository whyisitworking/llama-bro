package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing scale of the design system v2 spec (docs/design/demo-app.md, section 8.3): Material's 4 dp and
 * 8 dp grid, one name per use. The gap *around* a group is larger than the gap *inside* it, so a reader sees what
 * belongs together: [inline] inside one unit, [block] between the blocks of a unit, [item] between units, [section]
 * between sections and between turns, [hero] around a hero. [gutter] is the edge of every screen and sheet; the layout
 * primitives apply it, so a screen never does.
 */
object DemoSpacing {
    /** Inside one unit: a title and its subtitle, a message and its meta line, a label and its field. */
    val inline = 4.dp

    /** Between the blocks of one unit: a reply's paragraph, list and code block; the cards of one list. */
    val block = 8.dp

    /** Between units: two replies of one speaker, a card's inner padding, the parts of a row. */
    val item = 16.dp

    /** Between sections and between turns (the question and its answer). */
    val section = 24.dp

    /** Around a hero or an empty state. */
    val hero = 32.dp

    /** The left and right edge of every screen, sheet, composer and banner. */
    val gutter = 16.dp
}
