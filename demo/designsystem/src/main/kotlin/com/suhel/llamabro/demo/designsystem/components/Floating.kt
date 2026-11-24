package com.suhel.llamabro.demo.designsystem.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
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
 * its veil is a little denser instead.
 */
private val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Whether the blur is progressive (Android 13 and up, where a view can run its own shader): its radius eases from full
 * at the screen's edge to none past the bar, as iOS's bars do. On Android 12 one even blur is faded out instead.
 */
private val progressive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** How far from the screen's edge, as a share of a floating layer's height, the blur and the veil stay at full. */
private const val HOLD = 0.5f

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
        val fade = DemoSize.barFade.roundToPx()
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
 * tabs, which the keyboard covers), below it (bottom). Its fade band ([DemoSize.barFade]) lies on its inner side.
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
    val shaders = remember { if (progressive) ProgressiveBlur() else null }
    // where the layer sits in the frame, so it draws the part of the page that lies under it
    var y by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxWidth().onPlaced { y = it.positionInParent().y }) {
        if (page != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        renderEffect =
                            if (shaders != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                shaders.effect(edge, size.height, blur)
                            } else {
                                BlurEffect(blur, blur, TileMode.Clamp)
                            }
                        clip = true
                    }.drawWithContent {
                        // the layer holds the page's content only: on its own ground, so the sharp page under it
                        // doesn't show through the blurred copy's clear parts
                        drawRect(pageColor)
                        translate(top = -y) { drawLayer(page) }
                        // a progressive blur ends at none, the page itself; an even one is faded out into the page
                        if (shaders == null) drawRect(eased(edge, size.height, FadeMask), blendMode = BlendMode.DstIn)
                    },
            )
        }
        Box(
            Modifier.matchParentSize().drawBehind {
                drawRect(eased(edge, size.height, ground.copy(alpha = if (page != null) VEIL_ALPHA else BARE_ALPHA)))
            },
        )
        Column(Modifier.fillMaxWidth()) {
            if (edge == Edge.Top) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                CompositionLocalProvider(LocalOnFloatingBar provides true) { content() }
                Spacer(Modifier.height(DemoSize.barFade))
            } else {
                Spacer(Modifier.height(DemoSize.barFade))
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

/** The surface's veil over a blur: translucent, never solid, even behind the status bar. */
private const val VEIL_ALPHA = 0.55f

/** The veil with no blur under it (before Android 12, the Chat list tabs): denser, so text passing under is quiet. */
private const val BARE_ALPHA = 0.8f

/**
 * How strong a floating layer's blur and veil are at [fraction] of the way from the screen's edge to the layer's inner
 * end: full to [HOLD], then easing (smoothstep) to none. The shader uses the same curve.
 */
private fun strength(fraction: Float): Float {
    val t = ((fraction - HOLD) / (1f - HOLD)).coerceIn(0f, 1f)
    return 1f - t * t * (3f - 2f * t)
}

/** [color] over a layer [height] tall, at [strength] from its screen edge ([edge]) inward, in even steps. */
private fun eased(edge: Edge, height: Float, color: Color): Brush {
    val stops =
        Array(EASE_STEPS + 1) { i ->
            val at = i / EASE_STEPS.toFloat()
            val fromEdge = if (edge == Edge.Top) at else 1f - at
            at to color.copy(alpha = color.alpha * strength(fromEdge))
        }
    return Brush.verticalGradient(*stops, endY = height)
}

private const val EASE_STEPS = 12

/**
 * The progressive blur (Android 13 and up): a separable Gaussian whose radius follows [strength] from the screen's
 * edge, a horizontal pass then a vertical one, each its own runtime shader. Effects are made afresh when the layer's
 * size changes; the shaders are kept.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class ProgressiveBlur {
    private val across = RuntimeShader(PROGRESSIVE_BLUR)
    private val down = RuntimeShader(PROGRESSIVE_BLUR)

    fun effect(edge: Edge, height: Float, radius: Float): RenderEffect {
        for ((shader, dir) in listOf(across to (1f to 0f), down to (0f to 1f))) {
            shader.setFloatUniform("dir", dir.first, dir.second)
            shader.setFloatUniform("height", height)
            shader.setFloatUniform("fromTop", if (edge == Edge.Top) 1f else 0f)
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("hold", HOLD)
        }
        val first = android.graphics.RenderEffect.createRuntimeShaderEffect(across, "content")
        val second = android.graphics.RenderEffect.createRuntimeShaderEffect(down, "content")
        return android.graphics.RenderEffect
            .createChainEffect(second, first)
            .asComposeRenderEffect()
    }
}

/**
 * One pass of the progressive blur, along [dir]: a Gaussian of `radius` times the strength at this row (full to
 * `hold`, then smoothstep to none at the layer's inner end), in a fixed number of taps; at no radius, the page as is.
 */
private const val PROGRESSIVE_BLUR = """
uniform shader content;
uniform float2 dir;
uniform float height;
uniform float fromTop;
uniform float radius;
uniform float hold;

half4 main(float2 p) {
    float fraction = fromTop > 0.5 ? p.y / height : 1.0 - p.y / height;
    float t = clamp((fraction - hold) / (1.0 - hold), 0.0, 1.0);
    float r = radius * (1.0 - t * t * (3.0 - 2.0 * t));
    if (r < 0.5) {
        return content.eval(p);
    }
    float sigma = r / 2.0;
    half4 sum = half4(0.0);
    float total = 0.0;
    for (int i = -12; i <= 12; i++) {
        float x = float(i) * r / 12.0;
        float w = exp(-(x * x) / (2.0 * sigma * sigma));
        sum += content.eval(p + dir * x) * half(w);
        total += w;
    }
    return sum / half(total);
}
"""

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
