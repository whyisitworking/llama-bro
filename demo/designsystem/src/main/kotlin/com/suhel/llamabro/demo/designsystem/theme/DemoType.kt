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
 * Source Serif 4 (SIL OFL, res/raw/source_serif_4_ofl.txt) for the two hero roles only; the system sans for the rest.
 * Google ships it as one variable font, so Regular and Medium are two weights of the same file.
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
 * The type roles of the design system v2 spec (docs/sdk-v2/pd-design-v2-spec.md, section 1): Material 3's scale (size,
 * line height, tracking from `TypeScaleTokens`), the serif only where D48 says. A screen names a role and never a size,
 * a weight or a tracking: a role carries all of them, so the same kind of text is the same everywhere.
 *
 * | Role | Size / line | Weight | Face |
 * |---|---|---|---|
 * | [displayHero] | 28 / 36 | Medium | serif |
 * | [sheetTitle] | 22 / 28 | Medium | serif |
 * | [screenTitle] | 22 / 28 | Medium | sans |
 * | [title] | 16 / 24 | Medium | sans |
 * | [titleSmall] | 14 / 20 | Medium | sans |
 * | [body] | 16 / 24 | Regular | sans |
 * | [bodyMedium] | 14 / 20 | Regular | sans |
 * | [bodySmall] | 12 / 16 | Regular | sans |
 * | [label] | 14 / 20 | Medium | sans |
 * | [labelSmall] | 12 / 16 | Medium | sans |
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
    val bodyMedium = sans(FontWeight.Normal, size = 14, line = 20, tracking = 0.2)
    val bodySmall = sans(FontWeight.Normal, size = 12, line = 16, tracking = 0.4)
    val label = sans(FontWeight.Medium, size = 14, line = 20, tracking = 0.1)
    val labelSmall = sans(FontWeight.Medium, size = 12, line = 16, tracking = 0.5)

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
            fontFamily = FontFamily.SansSerif,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = line.sp,
            letterSpacing = tracking.sp,
        )
}
