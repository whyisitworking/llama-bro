package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The shapes of the design system v2 spec (docs/sdk-v2/pd-design-v2-spec.md, section 4): Material's corner scale. A
 * sheet is rounded 28 dp on top, a card 12 dp, a chip 8 dp; buttons and the search field are full pills. The
 * message bubble and the composer have no Material counterpart and are chosen: 20 dp and 28 dp.
 */
object DemoShapes {
    val card = RoundedCornerShape(12.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val chip = RoundedCornerShape(8.dp)
    val composer = RoundedCornerShape(28.dp)

    /** The floating action button (Material's Large corner). */
    val fab = RoundedCornerShape(16.dp)

    /** The user's bubble: the tail corner is bottom end. */
    val userBubble = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)

    /** The model's bubble in the chat list layout: the tail corner is bottom start. */
    val modelBubble = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp)

    /**
     * Material's roles. `extraLarge` (dialogs, among others) is rounded on every corner with the sheet's 28 dp, so a
     * dialog keeps its bottom corners; the app's sheets use [sheet] through `DemoSheet`.
     */
    internal val material =
        Shapes(
            extraSmall = chip,
            small = chip,
            medium = card,
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(28.dp),
        )
}
