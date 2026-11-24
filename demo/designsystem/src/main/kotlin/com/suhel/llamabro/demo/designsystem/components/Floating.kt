package com.suhel.llamabro.demo.designsystem.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoColors
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.FadeMask
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * Whether the content is on a floating bar ([FloatingFrame]'s top): an [IconAction] and a tappable [TopBarTitle] there
 * are each their own raised surface, since the page scrolls on under them (D148).
 */
internal val LocalOnFloatingBar = staticCompositionLocalOf { false }

/**
 * How tall a floating layer outside the screen is over its bottom (the Chat list layout's tabs, fade band and all), so
 * a [FloatingFrame] keeps its last content clear of it. The shell provides it; zero where nothing floats there.
 */
val LocalBottomOverlay = compositionLocalOf { 0.dp }

/**
 * Whether a floating bar blurs the page under it (Android 12 and up, where a view can blur what it draws); below that,
 * its ground is near-solid instead.
 */
private val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * A screen whose content runs edge to edge under floating bars (D148): [top] (the top bar, and on some screens a
 * search field) over the status bar, [bottom] (a chat's message bar) over the navigation bar and the keyboard. The
 * content gets the bars' heights as [content]'s padding, to pad its scroll with, so it starts clear of them and scrolls
 * on beneath. Under each bar the page is blurred, as iOS's bars do, and veiled with [ground], a translucent surface;
 * both fade out over a short band past the bar, so what scrolls under it softens rather than cuts and
 * the bar's parts read as floating over the page. Before Android 12 there is no blur and the veil is a little denser.
 */
@Composable
fun FloatingFrame(
    top: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bottom: @Composable () -> Unit = {},
    ground: Color = LocalDemoColors.current.surface,
    /** The colour the page is drawn on (the canvas; the drawer's panel), under the blurred copy of the page. */
    pageColor: Color = LocalDemoColors.current.canvas,
    content: @Composable (PaddingValues) -> Unit,
) {
    val overlay = LocalBottomOverlay.current
    // the page, drawn once into a layer that the bars draw again, blurred, under themselves
    val page = if (blurs) rememberGraphicsLayer() else null
    SubcomposeLayout(modifier) { constraints ->
        val bars = constraints.copy(minWidth = constraints.maxWidth, minHeight = 0)
        val topBar =
            subcompose(
                FrameSlot.Top,
            ) { FloatingLayer(Edge.Top, ground, page, pageColor, content = top) }.map { it.measure(bars) }
        val bottomBar =
            subcompose(FrameSlot.Bottom) { FloatingLayer(Edge.Bottom, ground, page, pageColor, content = bottom) }
                .map { it.measure(bars) }
        // each layer's fade band lies over the content: the content clears the bar, not the band
        val fade = DemoSize.edgeFade.roundToPx()
        val topHeight = topBar.maxOfOrNull { it.height } ?: 0
        val bottomHeight = bottomBar.maxOfOrNull { it.height } ?: 0
        val padding =
            PaddingValues(
                top = (topHeight - fade).coerceAtLeast(0).toDp(),
                bottom = (maxOf(bottomHeight, overlay.roundToPx()) - fade).coerceAtLeast(0).toDp(),
            )
        val body =
            subcompose(FrameSlot.Content) {
                Box(Modifier.fillMaxSize().recordInto(page)) { content(padding) }
            }.map { it.measure(constraints) }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            body.forEach { it.place(0, 0) }
            topBar.forEach { it.place(0, 0) }
            bottomBar.forEach { it.place(0, height - bottomHeight) }
        }
    }
}

private enum class FrameSlot { Top, Bottom, Content }

internal enum class Edge { Top, Bottom }

/** Draws the content as usual, and records it into [page] first, for the bars to blur. */
private fun Modifier.recordInto(page: GraphicsLayer?): Modifier =
    if (page == null) {
        this
    } else {
        drawWithContent {
            page.record { this@drawWithContent.drawContent() }
            drawLayer(page)
        }
    }

/**
 * A floating layer at [edge]: the page under it blurred (from [page]) and veiled with [ground], then [content], with
 * the status bar's room above it (top) or the navigation bar's, and the keyboard's unless [overKeyboard] is false (the
 * tabs, which the keyboard covers), below it (bottom). Its fade band ([DemoSize.edgeFade]) lies on its inner side.
 */
@Composable
internal fun FloatingLayer(
    edge: Edge,
    ground: Color,
    page: GraphicsLayer?,
    pageColor: Color,
    overKeyboard: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val blur = with(density) { DemoSize.backdropBlur.toPx() }
    // where the layer sits in the frame, so it draws the part of the page that lies under it
    var y by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxWidth().onPlaced { y = it.positionInParent().y }) {
        if (page != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
                        clip = true
                    }.drawWithContent {
                        // the layer holds the page's content only: on its own ground, so the sharp page under it
                        // doesn't show through the blurred copy's clear parts
                        drawRect(pageColor)
                        translate(top = -y) { drawLayer(page) }
                        drawRect(bandMask(edge, size.height, DemoSize.edgeFade.toPx()), blendMode = BlendMode.DstIn)
                    },
            )
        }
        Box(
            Modifier.matchParentSize().drawBehind {
                drawRect(tint(edge, ground, page != null, size.height, DemoSize.edgeFade.toPx()))
            },
        )
        Column(Modifier.fillMaxWidth()) {
            if (edge == Edge.Top) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                CompositionLocalProvider(LocalOnFloatingBar provides true) { content() }
                Spacer(Modifier.height(DemoSize.edgeFade))
            } else {
                Spacer(Modifier.height(DemoSize.edgeFade))
                content()
                val insets =
                    if (overKeyboard) {
                        WindowInsets.navigationBars.union(
                            WindowInsets.ime,
                        )
                    } else {
                        WindowInsets.navigationBars
                    }
                Spacer(Modifier.windowInsetsBottomHeight(insets))
            }
        }
    }
}

/** Opaque over the layer, fading to clear across the fade band on its inner side. */
private fun bandMask(edge: Edge, height: Float, fade: Float): Brush {
    val inner = ((height - fade) / height).coerceIn(0f, 1f)
    return if (edge == Edge.Top) {
        Brush.verticalGradient(0f to FadeMask, inner to FadeMask, 1f to Color.Transparent, endY = height)
    } else {
        Brush.verticalGradient(0f to Color.Transparent, 1f - inner to FadeMask, 1f to FadeMask, endY = height)
    }
}

/**
 * The surface's veil over the layer: translucent, never solid, even behind the status bar, so the page shows through
 * (blurred where it can be); even across the bar, then fading to clear across the band on its inner side, as the blur
 * does. With no blur under it, it is a little denser, so text passing under stays quiet.
 */
private fun tint(
    edge: Edge,
    ground: Color,
    blurred: Boolean,
    height: Float,
    fade: Float,
): Brush {
    val veil = ground.copy(alpha = if (blurred) 0.55f else 0.8f)
    val clear = ground.copy(alpha = 0f)
    val inner = ((height - fade) / height).coerceIn(0f, 1f)
    return if (edge == Edge.Top) {
        Brush.verticalGradient(0f to veil, inner to veil, 1f to clear, endY = height)
    } else {
        Brush.verticalGradient(0f to clear, 1f - inner to veil, 1f to veil, endY = height)
    }
}

/**
 * A part of a floating bar as its own raised surface (D148), when [floating]: a circle round an icon, a pill round a
 * title. Translucent like the bar under it, so the blurred page shows through as frosted glass; a hairline edge, not
 * a shadow (which would show through the fill as a smudge), and the denser fill set it apart.
 */
internal fun Modifier.floatingChip(floating: Boolean, colors: DemoColors): Modifier =
    if (floating) {
        background(colors.surface.copy(alpha = CHIP_ALPHA), CircleShape)
            .border(DemoSize.hairline, colors.hairline, CircleShape)
    } else {
        this
    }

/** How opaque a floating bar's chip is: denser than the bar's veil, still letting the page show through. */
private const val CHIP_ALPHA = 0.7f

/** The padding [FloatingFrame] gave, its top and bottom only, with [extra] more at each end. */
fun PaddingValues.vertical(extra: Dp = 0.dp): PaddingValues =
    PaddingValues(top = calculateTopPadding() + extra, bottom = calculateBottomPadding() + extra)
