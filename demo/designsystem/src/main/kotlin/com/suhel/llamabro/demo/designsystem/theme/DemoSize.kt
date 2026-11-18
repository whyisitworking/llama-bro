package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.ui.unit.dp

/**
 * The size scale: every height, width and icon size a component uses, so related elements rank the same way on every
 * screen (docs/design/demo-app.md, section 8.6). Heights follow Material 3: a 48 dp touch target, 40 dp controls,
 * 56 dp fields and buttons, 48 (dense), 56 and 72 dp list rows, a 64 dp top bar and an 80 dp navigation bar. Icons
 * come in two sizes: [icon] for actions and leading icons, [iconSmall] for marks inside text (a chevron, a check, a
 * remove cross).
 */
object DemoSize {
    /** An action or a leading icon. */
    val icon = 24.dp

    /** A mark inside a line of text, or on a thumbnail: one size for every small glyph. */
    val iconSmall = 18.dp

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

    /** A dense one-line list row: the drawer's chats, a long list scanned by title. Still a whole [target]. */
    val rowDense = 48.dp

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

    /** A divider and an outline. */
    val hairline = 1.dp

    /** The outline of a selected card. */
    val selectedStroke = 2.dp

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
}
