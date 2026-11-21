package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.suhel.llamabro.demo.designsystem.R

/**
 * Source Serif 4 (SIL OFL, res/raw/source_serif_4_ofl.txt) for the two hero roles, a card's name and the sidebar's
 * title only; [Geist] for the rest. Google ships it as one variable font, so Regular and Medium are two weights of the
 * same file.
 */
@OptIn(ExperimentalTextApi::class)
private val SourceSerif =
    FontFamily(
        Font(
            R.font.source_serif_4,
            FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            R.font.source_serif_4,
            FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
    )

/**
 * Geist (SIL OFL, res/raw/geist_ofl.txt), the app's sans in place of the system one (D125): one variable font, with an
 * entry for every weight a sans role uses (Light, Regular, Medium), so each renders at its own weight, not a synthesis.
 */
@OptIn(ExperimentalTextApi::class)
private val Geist =
    FontFamily(
        listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium).map { weight ->
            Font(
                R.font.geist,
                weight,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
            )
        },
    )

/**
 * The type roles of the design system v2 spec (docs/design/demo-app.md, section 8.2): Material 3's scale (size,
 * line height, tracking from `TypeScaleTokens`) in Geist (D125), the serif only where D48 says. A screen names a role
 * and never a size, a weight or a tracking: a role carries all of them, so the same kind of text is the same
 * everywhere.
 *
 * | Role | Size / line | Weight | Face |
 * |---|---|---|---|
 * | [displayHero] | 28 / 36 | Medium | serif |
 * | [sheetTitle] | 22 / 28 | Medium | serif |
 * | [screenTitle] | 22 / 28 | Medium | sans |
 * | [title] | 16 / 24 | Medium | sans |
 * | [titleSmall] | 14 / 20 | Medium | sans |
 * | [body] | 16 / 24 | Regular | sans |
 * | [replyBody] | 16 / 26 | Regular | sans |
 * | [bodyMedium] | 14 / 20 | Regular | sans |
 * | [bodySmall] | 12 / 16 | Regular | sans |
 * | [label] | 14 / 20 | Medium | sans |
 * | [labelSmall] | 12 / 16 | Medium | sans |
 *
 * The Models page's quiet roles (D123, the "ultra subtle" design): Medium only where something is emphasised.
 *
 * | Role | Size / line | Weight | Face |
 * |---|---|---|---|
 * | [cardTitle] | 24 / 32 | Regular | serif |
 * | [screenTitleLight] | 24 / 32 | Light | sans |
 * | [titleRegular] | 16 / 24 | Regular | sans |
 * | [quietLabel] | 13 / 20 | Regular | sans, no tracking |
 * | [pageBody] | 13 / 24 | Regular | sans |
 *
 * The sidebar's quiet roles (D126): [sidebarTitle] | 22 / 28 | Regular | serif; [rowLine] | 15 / 20 | Regular | sans.
 */
object DemoType {
    val displayHero =
        TextStyle(
            fontFamily = SourceSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 28.sp,
            lineHeight = 36.sp,
            letterSpacing = 0.sp,
        )
    val sheetTitle =
        TextStyle(
            fontFamily = SourceSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp,
        )
    val screenTitle = sans(FontWeight.Medium, size = 22, line = 28, tracking = 0.0)
    val title = sans(FontWeight.Medium, size = 16, line = 24, tracking = 0.2)
    val titleSmall = sans(FontWeight.Medium, size = 14, line = 20, tracking = 0.1)
    val body = sans(FontWeight.Normal, size = 16, line = 24, tracking = 0.5)

    /**
     * A reply's running text: [body] on a looser line (D125), so a long answer in Geist reads easily. Off the 4 dp
     * grid by design: chosen by eye (D125).
     */
    val replyBody = sans(FontWeight.Normal, size = 16, line = 26, tracking = 0.5)
    val bodyMedium = sans(FontWeight.Normal, size = 14, line = 20, tracking = 0.2)
    val bodySmall = sans(FontWeight.Normal, size = 12, line = 16, tracking = 0.4)
    val label = sans(FontWeight.Medium, size = 14, line = 20, tracking = 0.1)
    val labelSmall = sans(FontWeight.Medium, size = 12, line = 16, tracking = 0.5)

    /** A model card's name: the serif in Regular, a step over [sheetTitle]'s size and without its weight (D123). */
    val cardTitle =
        TextStyle(
            fontFamily = SourceSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.sp,
        )

    /**
     * The app's name at the head of the sidebar (D126): [sheetTitle]'s size in Regular, the serif of [cardTitle], so
     * it names the place without the weight of a title.
     */
    val sidebarTitle =
        TextStyle(
            fontFamily = SourceSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp,
        )

    /**
     * A one-line row of a long list, the sidebar's chats and places (D126): a step under [body], so a run of titles
     * reads as a list rather than as a column of headings.
     */
    val rowLine = sans(FontWeight.Normal, size = 15, line = 20, tracking = 0.1)

    /** The Models page's title: light and a step larger than [screenTitle], so it names the page without weight. */
    val screenTitleLight = sans(FontWeight.Light, size = 24, line = 32, tracking = 0.0)

    /** [title]'s size in Regular: a model row's name and a quiet button's label (D123). */
    val titleRegular = sans(FontWeight.Normal, size = 16, line = 24, tracking = 0.15)

    /** A quiet section label in sentence case ("On this phone"), a model page's headings and "About": no tracking. */
    val quietLabel = sans(FontWeight.Normal, size = 13, line = 20, tracking = 0.0)

    /** A model's page under its card: small and airy, so the app's own lines read first (D123). */
    val pageBody = sans(FontWeight.Normal, size = 13, line = 24, tracking = 0.2)

    /**
     * The reply's caption in voice mode (D100): [screenTitle]'s size in Regular, a long caption read at arm's length
     * without the weight of a title. Chosen; Material has no role for live captions.
     */
    val voiceCaption = sans(FontWeight.Normal, size = 22, line = 28, tracking = 0.0)

    /** A reply's thoughts when opened: [bodyMedium] in italics. */
    val thought = bodyMedium.copy(fontStyle = FontStyle.Italic)

    /** Code blocks and inline code: the system monospace at the [bodyMedium] size. */
    val code =
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.2.sp,
        )

    private fun sans(weight: FontWeight, size: Int, line: Int, tracking: Double) =
        TextStyle(
            fontFamily = Geist,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = line.sp,
            letterSpacing = tracking.sp,
        )
}
