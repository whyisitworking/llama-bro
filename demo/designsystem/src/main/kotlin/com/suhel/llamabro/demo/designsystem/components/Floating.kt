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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.layout.positionOnScreen
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
import com.suhel.llamabro.demo.designsystem.theme.LocalGlassTier
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
    /** Whether the bottom bar rides above the keyboard (a message bar) or stays put under it (the drawer's places). */
    bottomOverKeyboard: Boolean = true,
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
                { FloatingLayer(Edge.Bottom, ground, backdrop, overKeyboard = bottomOverKeyboard, content = bottom) },
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
    val style = LocalGlass.current.bar
    val shaders = remember { if (progressive) ProgressiveBlur() else null }
    // where the layer sits over the page, so it draws the part of the page that lies under it
    var at by remember { mutableStateOf(Offset.Zero) }
    // the glass on the bar, which the bar draws in its own passes when the glass says so (D154)
    val container = remember(backdrop) { backdrop?.let(::GlassContainer) }
    val merging =
        LocalGlass.current.merges(LocalGlassTier.current) && page != null && shaders != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    SideEffect { container?.active = merging }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { at = it.positionOnScreen() }) {
        val where = { at }
        if (page != null && shaders != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ShaderBlur(edge, ground, backdrop, shaders, where, container.takeIf { merging })
        } else if (page != null) {
            EvenBlur(edge, backdrop, where)
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
        CompositionLocalProvider(LocalBackdrop provides backdrop, LocalGlassContainer provides container) {
            LayerColumn(edge, style.fade, overKeyboard, content)
        }
    }
}

/**
 * A floating layer's [content] with the system bars' room beside it: the status bar's above it (top), the navigation
 * bar's and the keyboard's unless not [overKeyboard] below it (bottom), and the [fade] band on its inner side.
 */
@Composable
private fun LayerColumn(edge: Edge, fade: Dp, overKeyboard: Boolean, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (edge == Edge.Top) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            content()
            Spacer(Modifier.height(fade))
        } else {
            Spacer(Modifier.height(fade))
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

/**
 * The page under a bar at [at] on the screen, blurred progressively by [shaders] and veiled with [ground] (Android 13
 * and up), with the glass on the bar drawn in the same passes when it has a [container] (D154).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun BoxScope.ShaderBlur(
    edge: Edge,
    ground: Color,
    backdrop: Backdrop,
    shaders: ProgressiveBlur,
    at: () -> Offset,
    container: GlassContainer?,
) {
    val page = backdrop.page ?: return
    val style = LocalGlass.current.bar
    // the blur runs on a copy of the page as much smaller as the blur can hide (F185, D162): drawn back up, the blur
    // hides the copy's softness, and where the blur eases out the copy fades into the page
    val small = rememberGraphicsLayer()
    val scale = style.reduction(LocalGlassTier.current)
    Box(
        Modifier.matchParentSize().drawWithCache {
            val reduced = IntSize(ceil(size.width / scale).toInt(), ceil(size.height / scale).toInt())
            val glass =
                container
                    ?.shapes
                    ?.values
                    ?.map { it.onBar(at(), scale, this) }
                    .orEmpty()
            small.renderEffect = shaders.effect(BarPass(edge, reduced.toSize(), style, ground, scale, glass), this)
            onDrawBehind {
                // its place on the screen less the page's, read here so either moving redraws it
                val over = at() - backdrop.origin
                small.record(reduced) {
                    scale(1f / scale, pivot = Offset.Zero) {
                        // the page's content on its own ground, so no clear part of the copy lets the sharp page show
                        // through
                        drawRect(backdrop.pageColor, size = this@drawWithCache.size)
                        translate(-over.x, -over.y) { drawLayer(page) }
                    }
                }
                clipRect { scale(scale, pivot = Offset.Zero) { drawLayer(small) } }
            }
        },
    )
}

/** The page under a bar at [at] on the screen under one even blur, faded out into the page (Android 12). */
@Composable
private fun BoxScope.EvenBlur(edge: Edge, backdrop: Backdrop, at: () -> Offset) {
    val page = backdrop.page ?: return
    val style = LocalGlass.current.bar
    val blur = with(LocalDensity.current) { style.blur.toPx() }
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer {
                renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
                clip = true
            }.drawWithContent {
                drawRect(backdrop.pageColor)
                val over = at() - backdrop.origin
                translate(-over.x, -over.y) { drawLayer(page) }
                drawRect(eased(edge, size.height, style.hold, FadeMask, style::blurAt), blendMode = BlendMode.DstIn)
            },
    )
}

/**
 * How strong a floating layer's blur and veil are at [fraction] of the way from the screen's edge to the layer's inner
 * end: full to [hold], then easing (smoothstep) to none. The shader uses the same curve.
 */
private fun strength(fraction: Float, hold: Float): Float {
    val t = ((fraction - hold) / (1f - hold)).coerceIn(0f, 1f)
    return 1f - t * t * (3f - 2f * t)
}

/**
 * [color] over a layer [height] tall, at [strength] from its screen edge ([edge]) inward, in even steps; a blur's
 * mask passes its [shape] of that strength (D175), so it eases out as the shader's does.
 */
private fun eased(
    edge: Edge,
    height: Float,
    hold: Float,
    color: Color,
    shape: (Float) -> Float = { it },
): Brush {
    val stops =
        Array(EASE_STEPS + 1) { i ->
            val at = i / EASE_STEPS.toFloat()
            val fromEdge = if (edge == Edge.Top) at else 1f - at
            at to color.copy(alpha = color.alpha * shape(strength(fromEdge, hold)))
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

    /** The blur of a bar's layer for [pass], at [density]. */
    fun effect(pass: BarPass, density: Density): RenderEffect {
        val style = pass.style
        val scale = pass.scale
        // the colours split and the veil blends along the vertical pass only, the last: a fringe on every edge across
        // the bar, at a third of the taps, and the tint over the finished blur; the glass on the bar bends there too
        across.setFloatUniform("spread", 0f)
        down.setFloatUniform("spread", with(density) { style.dispersion.toPx() } / scale)
        across.setFloatUniform("veil", 0f)
        down.setFloatUniform("veil", style.veil)
        across.setFloatUniform("fadeOut", 0f)
        // a reduced copy fades into the sharp page where the blur eases out (F185)
        down.setFloatUniform("fadeOut", if (scale > 1f) 1f else 0f)
        across.setFloatUniform("last", 0f)
        down.setFloatUniform("last", 1f)
        val radius = with(density) { style.blur.toPx() } / scale
        val glass = pass.glass.take(MAX_BAR_GLASS)
        val rects = FloatArray(MAX_BAR_GLASS * 4)
        val lenses = FloatArray(MAX_BAR_GLASS * 4)
        val looks = FloatArray(MAX_BAR_GLASS * 4)
        glass.forEachIndexed { i, g ->
            floatArrayOf(g.rect.left, g.rect.top, g.rect.right, g.rect.bottom).copyInto(rects, i * 4)
            floatArrayOf(g.corner, g.bezel, g.refraction, g.dispersion).copyInto(lenses, i * 4)
            floatArrayOf(g.reach, g.veil, g.dome, g.vibrancy).copyInto(looks, i * 4)
        }
        for ((shader, dir) in listOf(across to (1f to 0f), down to (0f to 1f))) {
            shader.setFloatUniform("tint", pass.tint.red, pass.tint.green, pass.tint.blue)
            shader.setFloatUniform("dir", dir.first, dir.second)
            shader.setFloatUniform("size", pass.size.width, pass.size.height)
            shader.setFloatUniform("height", pass.size.height)
            shader.setFloatUniform("fromTop", if (pass.edge == Edge.Top) 1f else 0f)
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("hold", style.hold)
            shader.setFloatUniform("easing", style.easing)
            shader.setFloatUniform("count", glass.size.toFloat())
            shader.setFloatUniform("shapeRect", rects)
            shader.setFloatUniform("shapeLens", lenses)
            shader.setFloatUniform("shapeLook", looks)
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
 * `hold`, then smoothstep to none at the layer's inner end) raised to `easing` (D175, [BarGlass.blurAt]), in a fixed
 * number of taps; at no radius, the page as is. With a `spread`, red is read that far ahead along [dir] and blue as
 * far behind, scaled by the same eased strength: the
 * colour fringes of light through glass (D148). With a `veil`, the `tint` (the surface) is overlay-blended into the
 * finished blur by that share times the strength: the blur's own lights and darks pulled toward the surface, not a
 * flat film laid over it.
 */
private const val PROGRESSIVE_BLUR =
    GLASS_INSIDE + """
uniform shader content;
uniform float2 dir;
uniform float2 size;
uniform float height;
uniform float fromTop;
uniform float radius;
uniform float hold;
uniform float easing;
uniform float spread;
uniform float3 tint;
uniform float veil;
uniform float fadeOut;
uniform float last;
uniform float count;
uniform float4 shapeRect[8];
uniform float4 shapeLens[8];
uniform float4 shapeLook[8];

half3 overlay(half3 base, half3 over) {
    return mix(2.0 * base * over, 1.0 - 2.0 * (1.0 - base) * (1.0 - over), step(0.5, base));
}

// read inside the layer only, as a clamped blur does: past its edge is nothing, and a half-clear sum there lets the
// sharp page under the bar show through
half4 tap(float2 q) {
    return content.eval(clamp(q, float2(0.5), size - 0.5));
}

// a Gaussian reaching r along dir around q, its taps no more than r / 12 apart: sparser, a strong blur shows ghost
// copies of text instead of a smear
half4 along(float2 q, float r) {
    if (r < 0.5) {
        return tap(q);
    }
    float sigma = r / 2.0;
    half4 sum = half4(0.0);
    float total = 0.0;
    for (int i = -12; i <= 12; i++) {
        float x = float(i) * r / 12.0;
        float w = exp(-(x * x) / (2.0 * sigma * sigma));
        sum += tap(q + dir * x) * half(w);
        total += w;
    }
    return sum / half(total);
}

// the bar's own blur at p, of the given strength: split and veiled on the last pass
half4 bar(float2 p, float strength) {
    // the radius eases out slower than the veil as it gets small, where the eye sees it most (D175)
    float blur = pow(strength, easing);
    float r = radius * blur;
    // a reduced copy fades out over the last tenth of the blur, where it is all but the page: the page shows sharp
    half keep = fadeOut > 0.5 ? half(smoothstep(0.0, 0.1, blur)) : 1.0;
    if (r < 0.5) {
        return tap(p) * keep;
    }
    float d = spread * blur;
    half4 c;
    if (d > 0.5) {
        float sigma = r / 2.0;
        half4 sum = half4(0.0);
        float total = 0.0;
        for (int i = -12; i <= 12; i++) {
            float x = float(i) * r / 12.0;
            float w = exp(-(x * x) / (2.0 * sigma * sigma));
            float2 q = p + dir * x;
            half4 s = tap(q);
            s.r = tap(q + dir * d).r;
            s.b = tap(q - dir * d).b;
            sum += s * half(w);
            total += w;
        }
        c = sum / half(total);
    } else {
        c = along(p, r);
    }
    if (veil > 0.0) {
        c.rgb = mix(c.rgb, overlay(c.rgb, half3(tint)), half(veil * strength));
    }
    return c * keep;
}

// a piece of glass on the bar at p, depth inside its rim (D154): its own blur, and on the last pass its lens, domed
// (D177), bent and split toward the rim as a convex edge bends light, its colours made richer, and its veil
half4 glass(float2 p, float4 rect, float4 lens, float4 look, float depth) {
    float r = look.x;
    if (last < 0.5) {
        return along(p, r);
    }
    half4 c;
    float bezel = lens.y;
    float2 centre = (rect.xy + rect.zw) * 0.5;
    float2 at = domed(p, centre, (rect.zw - rect.xy) * 0.5, depth, look.z);
    if (depth >= bezel) {
        c = along(at, r);
    } else {
        float2 rel = p - centre;
        float2 q = abs(rel) - ((rect.zw - rect.xy) * 0.5 - lens.x);
        float2 outer = max(q, 0.0);
        float2 n;
        if (q.x > 0.0 && q.y > 0.0) {
            n = normalize(outer);
        } else if (q.x > q.y) {
            n = float2(1.0, 0.0);
        } else {
            n = float2(0.0, 1.0);
        }
        n *= float2(rel.x < 0.0 ? -1.0 : 1.0, rel.y < 0.0 ? -1.0 : 1.0);
        float k = 1.0 - max(depth, 0.0) / bezel;
        float bend = lens.z * k * k;
        half4 g = along(at - n * bend, r);
        half red = along(at - n * bend * (1.0 + lens.w), r).r;
        half blue = along(at - n * bend * (1.0 - lens.w), r).b;
        c = half4(red, g.g, blue, g.a);
    }
    c.rgb = vivid(c.rgb, look.w);
    c.rgb = mix(c.rgb, overlay(c.rgb, half3(tint)), half(look.y));
    return c;
}

half4 main(float2 p) {
    float fraction = fromTop > 0.5 ? p.y / height : 1.0 - p.y / height;
    float t = clamp((fraction - hold) / (1.0 - hold), 0.0, 1.0);
    float strength = 1.0 - t * t * (3.0 - 2.0 * t);
    // the glass p is deepest inside, if any: a rounded rectangle's signed depth, positive inside
    float depth = -1.0;
    float4 rect = float4(0.0);
    float4 lens = float4(0.0);
    float4 look = float4(0.0);
    for (int i = 0; i < 8; i++) {
        if (float(i) >= count) {
            break;
        }
        float4 box = shapeRect[i];
        float corner = shapeLens[i].x;
        float2 q = abs(p - (box.xy + box.zw) * 0.5) - ((box.zw - box.xy) * 0.5 - corner);
        float d = corner - length(max(q, 0.0)) - min(max(q.x, q.y), 0.0);
        if (d > depth) {
            depth = d;
            rect = box;
            lens = shapeLens[i];
            look = shapeLook[i];
        }
    }
    // a pixel's width of edge, so the glass's outline is smooth at the layer's resolution
    float inside = clamp(depth + 0.5, 0.0, 1.0);
    if (inside <= 0.0) {
        return bar(p, strength);
    }
    half4 g = glass(p, rect, lens, look, depth);
    if (inside >= 1.0) {
        return g;
    }
    return mix(bar(p, strength), g, half(inside));
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

/** The most pieces of glass a bar draws in its own passes (D154): a bar's chips and panels, with room to spare. */
private const val MAX_BAR_GLASS = 8

/** What a bar's blur is drawn for: its [edge], its layer's [size] (reduced [scale] times), [style], [tint], [glass]. */
private class BarPass(
    val edge: Edge,
    val size: Size,
    val style: BarGlass,
    val tint: Color,
    val scale: Float,
    val glass: List<BarLens>,
)

/**
 * A piece of glass as the bar's shader draws it (D154), in the bar's layer's pixels: its [rect], [corner] radius,
 * the lens's [bezel], [refraction] and [dispersion], its blur's [reach] (twice the blur, a Gaussian's sigma, as
 * Android's blur takes it), [veil], and its inside's [dome] and [vibrancy] (D177).
 */
private class BarLens(
    val rect: Rect,
    val corner: Float,
    val bezel: Float,
    val refraction: Float,
    val dispersion: Float,
    val reach: Float,
    val veil: Float,
    val dome: Float,
    val vibrancy: Float,
)

/** This shape as a bar at [bar] on the screen, drawn [scale] times smaller, draws it. */
private fun MergedShape.onBar(bar: Offset, scale: Float, density: Density): BarLens =
    with(density) {
        val at = rect.translate(-bar)
        BarLens(
            rect = Rect(at.left / scale, at.top / scale, at.right / scale, at.bottom / scale),
            corner = corner / scale,
            bezel = minOf(style.bezel.toPx(), corner) / scale,
            refraction = style.refraction.toPx() * bend / scale,
            dispersion = style.dispersion,
            reach = 2f * style.blur.toPx() / scale,
            veil = style.veil,
            dome = style.dome,
            vibrancy = style.vibrancy,
        )
    }
