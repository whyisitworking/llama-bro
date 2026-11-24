package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The size scale: every height, width and icon size a component uses, so related elements rank the same way on every
 * screen (docs/design/demo-app.md, section 8.6). Heights follow Material 3: a 48 dp touch target, 40 dp controls,
 * 48 dp buttons, 56 dp fields, 48 (dense), 56 and 72 dp list rows, a 64 dp top bar and an 80 dp navigation bar. Icons
 * come in two sizes: [icon] for actions and leading icons, [iconSmall] for marks inside text (a chevron, a check, a
 * remove cross).
 */
object DemoSize {
    /** An action or a leading icon. */
    val icon = 24.dp

    /** A mark inside a line of text, or on a thumbnail: one size for every small glyph. */
    val iconSmall = 18.dp

    /**
     * A model's fit symbol: one box and one visual weight for all five figures (models-screen design). It grows with
     * the font up to [fitSymbolMax], so at large font it still reads beside the name.
     */
    val fitSymbol = 20.dp
    val fitSymbolMax = 32.dp

    /** A capability icon on a model row's quiet line; it grows with the font up to [capabilityIconMax]. */
    val capabilityIcon = 16.dp
    val capabilityIconMax = 24.dp

    /**
     * Every button, primary or quiet, bordered or not: one touch target tall, so a pinned foot of
     * 64 dp holds one with 8 dp above and below.
     */
    val button = 48.dp

    /** The minimum touch target of anything tappable (Material, WCAG 2.5.8). */
    val target = 48.dp

    /** A compact control: an icon button's circle, a bordered button, an avatar. */
    val control = 40.dp

    /**
     * A circle of the message bar's button row (+, Think, send) and its glyph (D73): smaller than [control] so the
     * filled send reads lighter, still centred in a [target].
     */
    val barCircle = 36.dp
    val barGlyph = 20.dp

    /** A text field, a primary button, a one-line list row. */
    val field = 56.dp

    /** A two-line list row. */
    val rowTwoLine = 72.dp

    /** The top app bar. */
    val topBar = 64.dp

    /** The bottom navigation bar, and its selected tab's indicator (Material's 64 x 32 dp pill). */
    val navBar = 80.dp
    val navIndicatorWidth = 64.dp
    val navIndicatorHeight = 32.dp

    /** An attached picture's thumbnail. */
    val thumbnail = 64.dp

    /** A picture in a message: 4:3. */
    val messageImageWidth = 168.dp
    val messageImageHeight = 126.dp

    /** The brand mark in a hero, and in an empty state. */
    val markHero = 56.dp
    val markEmpty = 40.dp

    /** A starter chip (Material's 32 dp chip). */
    val chip = 32.dp

    /** The remove control on an attached picture: a scrim circle the size of an icon. */
    val badge = 24.dp

    /** A tag (a quant name, a "Recommended" badge): one line of small text in a pill. */
    val tag = 20.dp

    /** A download's ring, and its stroke. */
    val ring = 28.dp
    val ringStroke = 3.5.dp

    /**
     * The download panel's ring drawn around its Pause / Resume / Try again glyph (models-page design §4.5), centred in
     * a [target]: [panelRing] with a [panelGlyph] in the card's 64 dp foot, [panelRingCompact] with a
     * [panelGlyphCompact] on a model row, where the panel is one target tall.
     */
    val panelRing = 40.dp
    val panelGlyph = 20.dp
    val panelRingCompact = 34.dp
    val panelGlyphCompact = 16.dp

    /** Progress tracks: a thin line under a title, a download bar, the storage bar. */
    val trackThin = 2.dp
    val track = 4.dp
    val trackThick = 6.dp

    /** A typing dot: three of them stand in for a reply until its first word. */
    val dot = 6.dp

    /** A markdown list's bullet: a disc, a ring or a square by depth, drawn rather than a glyph (D74). */
    val bullet = 6.dp

    /**
     * A voice message's level bars (D76): each bar [levelBar] wide with [levelGap] between, at most [levelHeight] tall
     * (one line of body text, so recording keeps the bar's height), and a sent clip's bars [voiceWaveWidth] across.
     */
    val levelBar = 3.dp
    val levelGap = 2.dp
    val levelHeight = 24.dp
    val voiceWaveWidth = 136.dp

    /**
     * Voice mode (D100): the orb at the voice view's size, the hold-to-talk button and its glyph, and how far the
     * caption's older lines fade at its top. Chosen: the orb leads the view as the hero orb leads the first run, and
     * the talk button is Material's large floating button rounded to a circle.
     */
    val orbVoice = 152.dp
    val talkButton = 80.dp
    val talkGlyph = 32.dp
    val captionFade = 36.dp

    /**
     * How far content fades at the end of a scroll that has more beyond it (a wide table, a sheet's page, a long
     * dialog): enough to read as "continues", not to hide a line.
     */
    val edgeFade = 24.dp

    /** How far the page blurs under a floating bar (D148): enough to lose its words but not its shapes, as on iOS. */
    val backdropBlur = 24.dp

    /** How far past a floating bar its blur and veil reach as they ease out (D148), so the blur ends gradually. */
    val barFade = 40.dp

    /**
     * The glass of the floating bars (D148), after Apple's Liquid Glass: how the page bends and splits into its colours
     * through a chip, and how far the colours split in the blur under a bar.
     */
    object Glass {
        /** How far in from a chip's rim the page bends. */
        val bezel = 14.dp

        /** How far the page is read from, further in, at a chip's very rim. */
        val refraction = 10.dp

        /** How much more red bends than green, and blue less, as a share of [refraction]: raise it for more colour. */
        const val DISPERSION = 0.6f

        /** The soft blur through a chip, before it bends. */
        val blur = 6.dp

        /** The light caught on a chip's upper rim. */
        val rim = 1.5.dp

        /** How far red and blue split in the blur under a bar, at its strongest. */
        val barSpread = 5.dp
    }

    /** A divider and an outline. */
    val hairline = 1.dp

    /**
     * The one "this is chosen" outline (D125), always in the accent on the chosen thing's own shape: a selected card,
     * a size or quant row, a layout choice and a segmented switch's chosen segment.
     */
    val selectedStroke = 2.dp

    /** A choice's radio mark (Material's 20 dp circle) and the dot inside it when chosen. */
    val radioMark = 20.dp
    val radioDot = 10.dp

    /** Shadow elevations: [elevationSoft] lifts a card or a raised segment, [elevationRaised] a floating bar. */
    val elevationSoft = 2.dp
    val elevationRaised = 6.dp

    /** A sheet's drag handle. */
    val handleWidth = 32.dp
    val handleHeight = 4.dp

    /** The small drawing of an app layout in Settings › Appearance: a canvas, a dot, and lines of text. */
    object LayoutDrawing {
        val height = 120.dp
        val dot = 18.dp
        val avatar = 12.dp
        val line = 6.dp
        val lineGap = 8.dp
    }

    /** The widest a reading column grows on a tablet (D48). */
    val contentMaxWidth = 640.dp

    /** A navigation drawer and the sidebar. */
    val drawerWidth = 320.dp

    /** The tallest a list inside a sheet grows before it scrolls. */
    val sheetListMaxHeight = 340.dp

    /** The tallest an expandable sheet (a model's card) opens before its content is pulled up to full screen. */
    val sheetCompactHeight = 560.dp

    /**
     * An expandable sheet's pinned foot, at least (models-page design §4): one [button] with 8 dp above and below, so
     * nothing above it moves when its action changes.
     */
    val sheetFoot = 64.dp

    /**
     * The quantisation table's size and fit columns, and a stacked row's line, in sp so they grow with the text they
     * hold (the model sheet's [com.suhel.llamabro.demo.designsystem.components.QuantRow]).
     */
    val quantSizeColumn = 68.sp
    val quantFitColumn = 96.sp
    val quantStackedLine = 18.sp
}
