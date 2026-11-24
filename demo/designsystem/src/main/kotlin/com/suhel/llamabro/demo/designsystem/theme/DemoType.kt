package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.suhel.llamabro.demo.designsystem.R

/**
 * Source Serif 4 (SIL OFL, res/raw/source_serif_4_ofl.txt) for the two hero roles, a card's name, the sidebar's
 * title and a reply's running text (D150); [NunitoSans] for the rest. Google ships it as one variable font, so Regular
 * and Medium are two weights of the same file.
 */
@OptIn(ExperimentalTextApi::class)
private fun sourceSerif(opticalSize: TextUnit?): FontFamily =
    FontFamily(
        // Regular, Medium and SemiBold, each its own weight of the variable font, never a synthesised bold
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold).map { weight ->
            val axes =
                listOfNotNull(FontVariation.weight(weight.weight), opticalSize?.let { FontVariation.opticalSizing(it) })
            Font(R.font.source_serif_4, weight, variationSettings = FontVariation.Settings(*axes.toTypedArray()))
        },
    )

/** The serif at its default optical size: the hero roles, a card's name, the sidebar's title. */
private val SourceSerif = sourceSerif(null)

/**
 * The serif cut for reading at a reply's size (D150): Source Serif 4's optical-size axis at 17, its text design, with
 * sturdier hairlines and looser fit than the display cut, for minutes of reading.
 */
private val SourceSerifText = sourceSerif(17.sp)

/** The serif cut for a reply's headings (D150): the display design, finer and tighter, as headings are set. */
private val SourceSerifDisplay = sourceSerif(24.sp)

/**
 * Nunito Sans (SIL OFL, res/raw/nunito_sans_ofl.txt), the app's sans (D150, in place of D125's Geist): a humanist face,
 * rounder and more open, for a calmer read. One variable font, with an entry for every weight a sans role uses (Light,
 * Regular, Medium), so each renders at its own weight, not a synthesis.
 */
@OptIn(ExperimentalTextApi::class)
private val NunitoSans =
    FontFamily(
        listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium).map { weight ->
            Font(
                R.font.nunito_sans,
                weight,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
            )
        },
    )

/**
 * The type roles of the design system v2 spec (docs/design/demo-app.md, section 8.2): Material 3's scale (size,
 * line height, tracking from `TypeScaleTokens`) in Nunito Sans (D150), the serif where D48 and D150 say. A screen
 * names a role and never a size, a weight or a tracking: a role carries all of them, so the same kind of text is the
 * same everywhere.
 *
 * | Role | Size / line | Weight | Face |
 * |---|---|---|---|
 * | [displayHero] | 28 / 36 | Medium | serif |
 * | [sheetTitle] | 22 / 28 | Medium | serif |
 * | [screenTitle] | 22 / 28 | Medium | sans |
 * | [title] | 16 / 24 | Medium | sans |
 * | [titleSmall] | 14 / 20 | Medium | sans |
 * | [body] | 16 / 24 | Regular | sans |
 * | [replyBody] | 17 / 28 | Regular | serif (text cut) |
 * | [replyH1] / [replyH2] / [replyH3] | 24 / 32, 21 / 28, 18 / 28 | SemiBold | serif (display cut) |
 * | [replyH4] | 17 / 28 | Medium | serif (text cut) |
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
     * A reply's running text in the serif (D150): a step larger than [body] on a generous line, so a long answer reads
     * like a well-set page while the controls around it stay in the sans.
     */
    val replyBody =
        TextStyle(
            fontFamily = SourceSerifText,
            fontWeight = FontWeight.Normal,
            fontSize = 17.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp,
        )

    /*
     * A reply's headings (D150), in the serif like its text, a ladder by size first and weight second: H1 to H3
     * SemiBold (600) at 24, 21 and 18 sp, H4 to H6 Medium (500) at the text's own size, so the deepest heading is told
     * from a bold phrase (also 600) by its own line, not by weight alone. Light on a dark ground reads heavier, so no
     * level goes past SemiBold.
     */

    /** A reply's H1. */
    val replyH1 = serifHeading(FontWeight.SemiBold, size = 24, line = 32)

    /** A reply's H2. */
    val replyH2 = serifHeading(FontWeight.SemiBold, size = 21, line = 28)

    /** A reply's H3. */
    val replyH3 = serifHeading(FontWeight.SemiBold, size = 18, line = 28)

    /** A reply's H4, H5 and H6: the text's size, a step heavier than it. */
    val replyH4 = replyBody.copy(fontWeight = FontWeight.Medium)
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

    private fun serifHeading(weight: FontWeight, size: Int, line: Int) =
        TextStyle(
            fontFamily = SourceSerifDisplay,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = line.sp,
            letterSpacing = 0.sp,
        )

    private fun sans(weight: FontWeight, size: Int, line: Int, tracking: Double) =
        TextStyle(
            fontFamily = NunitoSans,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = line.sp,
            letterSpacing = tracking.sp,
        )
}
