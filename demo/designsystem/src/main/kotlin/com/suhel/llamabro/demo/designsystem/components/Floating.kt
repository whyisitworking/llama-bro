package com.suhel.llamabro.demo.designsystem.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.suhel.llamabro.demo.designsystem.theme.BarGlass
import com.suhel.llamabro.demo.designsystem.theme.FadeMask
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.LocalGlass
import kotlin.math.ceil

/**
 * How tall a floating layer outside the screen is over its bottom (the Chat list layout's tabs, fade band and all), so
 * a [FloatingFrame] keeps its last content clear of it. The shell provides it; zero where nothing floats there.
 */
val LocalBottomOverlay = compositionLocalOf { 0.dp }

/**
 * Whether the blur is progressive (Android 13 and up, where a view can run its own shader): its radius eases from full
 * at the screen's edge to none past the bar, as iOS's bars do. On Android 12 one even blur is faded out instead.
 */
private val progressive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * A screen whose content runs edge to edge under floating bars (D148): [top] (the top bar, and on some screens a
 * search field) over the status bar, [bottom] (a chat's message bar) over the navigation bar and the keyboard. The
 * content gets the bars' heights as [content]'s padding, to pad its scroll with, so it starts clear of them and scrolls
 * on beneath. The content is the bars' [Backdrop]: under each bar it is blurred, as iOS's bars do, and veiled with
 * [ground], both easing out past the bar ([LocalGlass]'s bar), so what scrolls under softens rather than cuts. The
 * glass on the bars, and on the [overlay] (what floats over the content but isn't a bar: a FAB, a jump to the latest
 * message; its padding clears the bars whole, and it is drawn over them), is drawn over it. Before Android 12 there
 * is no blur and the veil is denser.
 */
@Composable
fun FloatingFrame(
    top: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bottom: @Composable () -> Unit = {},
    ground: Color = LocalDemoColors.current.surface,
    /** The colour the page is drawn on (the canvas; the drawer's panel), under the blurred copy of the page. */
    pageColor: Color = LocalDemoColors.current.canvas,
    overlay: @Composable BoxScope.(PaddingValues) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val below = LocalBottomOverlay.current
    val fade = LocalGlass.current.bar.fade
    // the page, drawn once into a layer that the bars and the overlay draw again, blurred or bent, under themselves
    val backdrop = rememberBackdrop(pageColor)
    // the bars' heights reach the content and the overlay as padding filled in while the frame lays out, read where
    // they are measured: everything is composed as usual, not during layout (F184), and lays out with the bars
    // measured this frame
    val padding = remember { FramePadding() }
    val clear = remember { FramePadding() }
    Layout(
        contents =
            listOf(
                { FloatingLayer(Edge.Top, ground, backdrop, content = top) },
                { FloatingLayer(Edge.Bottom, ground, backdrop, content = bottom) },
                { Box(Modifier.fillMaxSize().backdropSource(backdrop)) { content(padding) } },
                {
                    // over the content and outside it, so its glass never draws itself; clear of the bars whole, fade
                    // band and all, and drawn after them, so nothing floating is hidden under a bar's blur
                    CompositionLocalProvider(LocalBackdrop provides backdrop) {
                        Box(Modifier.fillMaxSize()) { overlay(clear) }
                    }
                },
            ),
        modifier = modifier,
    ) { slots, constraints ->
        val bars = constraints.copy(minWidth = constraints.maxWidth, minHeight = 0)
        val topBar = slots[FrameSlot.TOP].map { it.measure(bars) }
        val bottomBar = slots[FrameSlot.BOTTOM].map { it.measure(bars) }
        // each layer's fade band lies over the content: the content clears the bar, not the band
        val band = fade.roundToPx()
        val topHeight = topBar.maxOfOrNull { it.height } ?: 0
        val bottomHeight = maxOf(bottomBar.maxOfOrNull { it.height } ?: 0, below.roundToPx())
        padding.set((topHeight - band).coerceAtLeast(0).toDp(), (bottomHeight - band).coerceAtLeast(0).toDp())
        clear.set(topHeight.toDp(), bottomHeight.toDp())
        val body = slots[FrameSlot.BODY].map { it.measure(constraints) }
        val over = slots[FrameSlot.OVER].map { it.measure(constraints) }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            body.forEach { it.place(0, 0) }
            topBar.forEach { it.place(0, 0) }
            bottomBar.forEach { it.place(0, height - (bottomBar.maxOfOrNull { b -> b.height } ?: 0)) }
            over.forEach { it.place(0, 0) }
        }
    }
}

/** The slots of a [FloatingFrame]'s layout, in the order its contents are listed. */
private object FrameSlot {
    const val TOP = 0
    const val BOTTOM = 1
    const val BODY = 2
    const val OVER = 3
}

/**
 * A [FloatingFrame]'s padding: its top and bottom are set as the frame lays out and are snapshot state, so what reads
 * them while it measures (a list's content padding, a padding modifier) is measured again when they change, with no
 * recomposition and no frame late.
 */
@Stable
private class FramePadding : PaddingValues {
    private var top by mutableStateOf(0.dp)
    private var bottom by mutableStateOf(0.dp)

    fun set(top: Dp, bottom: Dp) {
        if (this.top != top) this.top = top
        if (this.bottom != bottom) this.bottom = bottom
    }

    override fun calculateTopPadding(): Dp = top

    override fun calculateBottomPadding(): Dp = bottom

    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
}

internal enum class Edge { Top, Bottom }

/**
 * A floating layer at [edge]: the page under it ([backdrop]'s, wherever it sits over it) blurred and veiled with
 * [ground], then [content], with the status bar's room above it (top) or the navigation bar's, and the keyboard's
 * unless [overKeyboard] is false (the tabs, which the keyboard covers), below it (bottom). Its fade band
 * ([BarGlass.fade]) lies on its inner side.
 */
@Composable
internal fun FloatingLayer(
    edge: Edge,
    ground: Color,
    backdrop: Backdrop?,
    overKeyboard: Boolean = true,
    content: @Composable () -> Unit,
) {
    val page = backdrop?.page
    val pageColor = backdrop?.pageColor ?: Color.Transparent
    val style = LocalGlass.current.bar
    val density = LocalDensity.current
    val blur = with(density) { style.blur.toPx() }
    val shaders = remember { if (progressive) ProgressiveBlur() else null }
    // where the layer sits over the page, so it draws the part of the page that lies under it
    var at by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { at = it.positionInWindow() }) {
        if (page != null && shaders != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // a heavy blur runs on a copy of the page at half its resolution, a quarter of the pixels (F185): drawn
            // back up, the blur hides the copy's softness, and where the blur eases out the copy fades into the page
            val small = rememberGraphicsLayer()
            val scale = if (style.heavy) HALF_RESOLUTION else 1f
            Box(
                Modifier.matchParentSize().drawWithCache {
                    val reduced = IntSize(ceil(size.width / scale).toInt(), ceil(size.height / scale).toInt())
                    small.renderEffect =
                        shaders.effect(edge, reduced.toSize(), style, ground, scale, fadeOut = scale > 1f, this)
                    onDrawBehind {
                        // its place in the window less the page's, read here so either moving redraws it
                        val over = at - backdrop.origin
                        small.record(reduced) {
                            scale(1f / scale, pivot = Offset.Zero) {
                                // the page's content on its own ground, so no clear part of the copy lets the sharp
                                // page show through
                                drawRect(pageColor, size = this@drawWithCache.size)
                                translate(-over.x, -over.y) { drawLayer(page) }
                            }
                        }
                        clipRect { scale(scale, pivot = Offset.Zero) { drawLayer(small) } }
                    }
                },
            )
        } else if (page != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
                        clip = true
                    }.drawWithContent {
                        drawRect(pageColor)
                        val over = at - backdrop.origin
                        translate(-over.x, -over.y) { drawLayer(page) }
                        // an even blur is faded out into the page
                        drawRect(eased(edge, size.height, style.hold, FadeMask), blendMode = BlendMode.DstIn)
                    },
            )
        }
        // the veil: blended into the blur by the shader where it runs (D148); a flat layer over a plain blur or none
        if (shaders == null || page == null) {
            Box(
                Modifier.matchParentSize().drawBehind {
                    val veil = ground.copy(alpha = if (page != null) style.veil else style.bareVeil)
                    drawRect(eased(edge, size.height, style.hold, veil))
                },
            )
        }
        Column(Modifier.fillMaxWidth()) {
            if (edge == Edge.Top) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                CompositionLocalProvider(LocalBackdrop provides backdrop) { content() }
                Spacer(Modifier.height(style.fade))
            } else {
                Spacer(Modifier.height(style.fade))
                CompositionLocalProvider(LocalBackdrop provides backdrop) { content() }
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

/**
 * How strong a floating layer's blur and veil are at [fraction] of the way from the screen's edge to the layer's inner
 * end: full to [hold], then easing (smoothstep) to none. The shader uses the same curve.
 */
private fun strength(fraction: Float, hold: Float): Float {
    val t = ((fraction - hold) / (1f - hold)).coerceIn(0f, 1f)
    return 1f - t * t * (3f - 2f * t)
}

/** [color] over a layer [height] tall, at [strength] from its screen edge ([edge]) inward, in even steps. */
private fun eased(edge: Edge, height: Float, hold: Float, color: Color): Brush {
    val stops =
        Array(EASE_STEPS + 1) { i ->
            val at = i / EASE_STEPS.toFloat()
            val fromEdge = if (edge == Edge.Top) at else 1f - at
            at to color.copy(alpha = color.alpha * strength(fromEdge, hold))
        }
    return Brush.verticalGradient(*stops, endY = height)
}

private const val EASE_STEPS = 12

/**
 * How much smaller than the screen a heavy bar blur ([BarGlass.heavy]) is drawn: half across and down, a quarter of the
 * pixels (F185).
 */
private const val HALF_RESOLUTION = 2f

/**
 * The progressive blur (Android 13 and up): a separable Gaussian whose radius follows [strength] from the screen's
 * edge, a horizontal pass then a vertical one, each its own runtime shader. Effects are made afresh when the layer's
 * size changes; the shaders are kept.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class ProgressiveBlur {
    private val across = RuntimeShader(PROGRESSIVE_BLUR)
    private val down = RuntimeShader(PROGRESSIVE_BLUR)

    /**
     * The blur of a layer at [edge], [size] large, in [style], the veil's [tint] blended in, at [density] over [scale]
     * (the layer drawn that many times smaller than the screen); with [fadeOut], the blur fades to clear where it eases
     * out, for the sharp page under it to take over.
     */
    @Suppress("LongParameterList") // the shader's inputs, all of them
    fun effect(
        edge: Edge,
        size: Size,
        style: BarGlass,
        tint: Color,
        scale: Float,
        fadeOut: Boolean,
        density: Density,
    ): RenderEffect {
        // the colours split and the veil blends along the vertical pass only, the last: a fringe on every edge across
        // the bar, at a third of the taps, and the tint over the finished blur
        across.setFloatUniform("spread", 0f)
        down.setFloatUniform("spread", with(density) { style.dispersion.toPx() } / scale)
        across.setFloatUniform("veil", 0f)
        down.setFloatUniform("veil", style.veil)
        across.setFloatUniform("fadeOut", 0f)
        down.setFloatUniform("fadeOut", if (fadeOut) 1f else 0f)
        val radius = with(density) { style.blur.toPx() } / scale
        for ((shader, dir) in listOf(across to (1f to 0f), down to (0f to 1f))) {
            shader.setFloatUniform("tint", tint.red, tint.green, tint.blue)
            shader.setFloatUniform("dir", dir.first, dir.second)
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("height", size.height)
            shader.setFloatUniform("fromTop", if (edge == Edge.Top) 1f else 0f)
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("hold", style.hold)
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
 * With a `spread`, red is read that far ahead along [dir] and blue as far behind, scaled by the same strength: the
 * colour fringes of light through glass (D148). With a `veil`, the `tint` (the surface) is overlay-blended into the
 * finished blur by that share times the strength: the blur's own lights and darks pulled toward the surface, not a
 * flat film laid over it.
 */
private const val PROGRESSIVE_BLUR = """
uniform shader content;
uniform float2 dir;
uniform float2 size;
uniform float height;
uniform float fromTop;
uniform float radius;
uniform float hold;
uniform float spread;
uniform float3 tint;
uniform float veil;
uniform float fadeOut;

half3 overlay(half3 base, half3 over) {
    return mix(2.0 * base * over, 1.0 - 2.0 * (1.0 - base) * (1.0 - over), step(0.5, base));
}

half4 main(float2 p) {
    float fraction = fromTop > 0.5 ? p.y / height : 1.0 - p.y / height;
    float t = clamp((fraction - hold) / (1.0 - hold), 0.0, 1.0);
    float strength = 1.0 - t * t * (3.0 - 2.0 * t);
    float r = radius * strength;
    // a reduced copy fades out over the last tenth of the blur, where it is all but the page: the page shows sharp
    half keep = fadeOut > 0.5 ? half(smoothstep(0.0, 0.1, strength)) : 1.0;
    if (r < 0.5) {
        return content.eval(p) * keep;
    }
    float d = spread * strength;
    float sigma = r / 2.0;
    half4 sum = half4(0.0);
    float total = 0.0;
    // taps no more than r / 12 apart: sparser, a strong blur shows ghost copies of text instead of a smear
    for (int i = -12; i <= 12; i++) {
        float x = float(i) * r / 12.0;
        float w = exp(-(x * x) / (2.0 * sigma * sigma));
        // read inside the layer only, as a clamped blur does: past its edge is nothing, and a half-clear sum there
        // lets the sharp page under the bar show through
        float2 lo = float2(0.5);
        float2 hi = size - 0.5;
        float2 q = clamp(p + dir * x, lo, hi);
        half4 c = content.eval(q);
        if (d > 0.5) {
            c.r = content.eval(clamp(q + dir * d, lo, hi)).r;
            c.b = content.eval(clamp(q - dir * d, lo, hi)).b;
        }
        sum += c * half(w);
        total += w;
    }
    half4 c = sum / half(total);
    if (veil > 0.0) {
        c.rgb = mix(c.rgb, overlay(c.rgb, half3(tint)), half(veil * strength));
    }
    return c * keep;
}
"""

/**
 * The padding [FloatingFrame] gave, its top and bottom only, with [extra] more at each end; read where it is used (a
 * padding modifier, a list's content padding), so it follows the frame's as it lays out.
 */
fun PaddingValues.vertical(extra: Dp = 0.dp, horizontal: Dp = 0.dp): PaddingValues =
    FramedPadding(this, extra, horizontal, top = true, bottom = true)

/** The padding [FloatingFrame] gave, its bottom only: what floats at the bottom of its overlay. */
fun PaddingValues.bottomOnly(): PaddingValues = FramedPadding(this, 0.dp, 0.dp, top = false, bottom = true)

/** The padding [FloatingFrame] gave, its top only. */
fun PaddingValues.topOnly(): PaddingValues = FramedPadding(this, 0.dp, 0.dp, top = true, bottom = false)

/** [base]'s top and bottom (where kept) with [extra] more and [horizontal] at each side, read from [base] each time. */
private class FramedPadding(
    private val base: PaddingValues,
    private val extra: Dp,
    private val horizontal: Dp,
    private val top: Boolean,
    private val bottom: Boolean,
) : PaddingValues {
    override fun calculateTopPadding(): Dp = if (top) base.calculateTopPadding() + extra else 0.dp

    override fun calculateBottomPadding(): Dp = if (bottom) base.calculateBottomPadding() + extra else 0.dp

    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = horizontal

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = horizontal
}
