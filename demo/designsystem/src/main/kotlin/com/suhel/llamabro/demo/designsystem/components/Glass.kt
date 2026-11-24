package com.suhel.llamabro.demo.designsystem.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.suhel.llamabro.demo.designsystem.theme.DemoColors
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.GlassStyle
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.LocalGlass

/**
 * A page that glass can be drawn over (D148): the layer the page is recorded into ([page], null before Android 12,
 * where nothing can blur), the colour it is drawn on, and where it sits in the window, so a piece of glass anywhere
 * over it finds the part under itself. Made with [rememberBackdrop]; the page is the content of [backdropSource].
 */
@Stable
class Backdrop internal constructor(internal val page: GraphicsLayer?, val pageColor: Color) {
    internal var origin by mutableStateOf(Offset.Zero)
}

/** A [Backdrop] for a page drawn on [pageColor]. */
@Composable
fun rememberBackdrop(pageColor: Color = LocalDemoColors.current.canvas): Backdrop {
    val page = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) rememberGraphicsLayer() else null
    return remember(page, pageColor) { Backdrop(page, pageColor) }
}

/**
 * Makes this content [backdrop]'s page: drawn as usual and recorded once per frame for the glass over it. Glass must
 * not be inside its own page: what it draws would draw itself.
 */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier {
    val page = backdrop.page ?: return this
    return onGloballyPositioned { backdrop.origin = it.positionInWindow() }
        .drawWithContent {
            page.record { this@drawWithContent.drawContent() }
            drawLayer(page)
        }
}

/**
 * The page under the floating layer the content is on (either of a [FloatingFrame]'s bars, its overlay, the Chat list
 * tabs), null off one: the glass there (a [TopBar]'s chips, the message bar, a floating field, a FAB) is drawn over it.
 */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Whether an [IconAction] or a tappable [TopBarTitle] is a chip of glass: in a [TopBar] or the drawer's head, not
 * inside a glass panel (a search field's filters) or the message bar's row.
 */
internal val LocalGlassChips = staticCompositionLocalOf { false }

/**
 * A piece of glass over [backdrop]'s page (D148), its corners [corner] (a pill when null), in [style]. From Android 13
 * it is a lens over the page: softly blurred, bent toward its rim as a convex edge bends light, the colours split apart
 * there (red bent more, blue less, as through Apple's Liquid Glass), under a veil of the surface, a light catching its
 * upper rim and a hairline round it. Before that, or with no [backdrop], a translucent fill and the hairline. Clip the
 * content to the same shape after it.
 */
fun Modifier.glass(backdrop: Backdrop?, style: GlassStyle, corner: Dp? = null): Modifier =
    this then GlassElement(backdrop, corner, style)

/**
 * [content] on a piece of glass ([glass]) over the page under it ([LocalBackdrop]), clipped to its shape: corners
 * [corner], a pill when null.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    corner: Dp? = null,
    style: GlassStyle = LocalGlass.current.panel,
    backdrop: Backdrop? = LocalBackdrop.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = if (corner == null) CircleShape else RoundedCornerShape(corner)
    Box(modifier.glass(backdrop, style, corner).clip(shape), content = content)
}

/** A part of a floating bar as a chip of glass in [style], when on one ([backdrop]); nothing otherwise. */
internal fun Modifier.chipGlass(backdrop: Backdrop?, style: GlassStyle): Modifier =
    if (backdrop == null) this else glass(backdrop, style)

private data class GlassElement(val backdrop: Backdrop?, val corner: Dp?, val style: GlassStyle) :
    ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(backdrop, corner, style)

    override fun update(node: GlassNode) {
        node.update(backdrop, corner, style)
    }
}

private class GlassNode(var backdrop: Backdrop?, var corner: Dp?, var style: GlassStyle) :
    Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode {
    private var at = Offset.Zero
    private var glass: GraphicsLayer? = null
    private var lens: Any? = null // a Lens, made only where it can run

    fun update(backdrop: Backdrop?, corner: Dp?, style: GlassStyle) {
        val lensChanged = (backdrop?.page != null) != (this.backdrop?.page != null)
        this.backdrop = backdrop
        this.corner = corner
        this.style = style
        if (lensChanged && isAttached) {
            release()
            prepare()
        }
        invalidateDraw()
    }

    override fun onAttach() = prepare()

    override fun onDetach() = release()

    /** A lens needs a page to bend and a shader to bend it (Android 13 and up). */
    private fun prepare() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backdrop?.page != null) {
            glass = requireGraphicsContext().createGraphicsLayer()
            lens = Lens()
        }
    }

    private fun release() {
        glass?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        glass = null
        lens = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val now = coordinates.positionInWindow()
        if (now != at) {
            at = now
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val colors = currentValueOf(LocalDemoColors)
        val pill = size.minDimension / 2
        val corner = CornerRadius(this@GlassNode.corner?.toPx()?.coerceAtMost(pill) ?: pill)
        val lensed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && drawLens(corner, colors)
        if (!lensed) drawRoundRect(colors.surface.copy(alpha = style.bareVeil), cornerRadius = corner)
        drawRoundRect(colors.hairline, cornerRadius = corner, style = Stroke(DemoSize.hairline.toPx()))
        drawContent()
    }

    /** The lens: the page under the glass, bent and split, the veil and the light on its rim; false if it can't. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun ContentDrawScope.drawLens(corner: CornerRadius, colors: DemoColors): Boolean {
        val backdrop = backdrop ?: return false
        val page = backdrop.page ?: return false
        val layer = glass ?: return false
        val lens = lens as? Lens ?: return false
        // the part of the page under the glass: its place in the window less the page's
        val offset = at - backdrop.origin
        layer.record {
            drawRect(backdrop.pageColor)
            translate(-offset.x, -offset.y) { drawLayer(page) }
        }
        layer.renderEffect = lens.effect(size.width, size.height, corner.x, style, colors.surface, this)
        layer.setRoundRectOutline(Offset.Zero, size, corner.x)
        layer.clip = true
        // the veil is blended into the glass by its shader, not laid over it
        drawLayer(layer)
        // the light on the glass: bright along the upper rim, fading down it
        val light = GlassLight.copy(alpha = style.rimLight)
        drawRoundRect(
            Brush.verticalGradient(listOf(light, light.copy(alpha = 0f)), endY = size.height * 0.6f),
            cornerRadius = corner,
            style = Stroke(style.rim.toPx()),
        )
        return true
    }
}

/** The light caught on a lens's rim: light in either theme, as a highlight on glass is; the style sets its strength. */
@Suppress("DemoRawColour")
private val GlassLight = Color.White

/**
 * A glass's lens (Android 13 and up): a soft blur, then [LENS] bending and splitting the page toward its rim. The
 * shader is kept; the effect is made afresh for each draw, since it carries the glass's size and style.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class Lens {
    private val shader = RuntimeShader(LENS)

    fun effect(
        width: Float,
        height: Float,
        radius: Float,
        style: GlassStyle,
        tint: Color,
        density: Density,
    ): RenderEffect {
        with(density) {
            shader.setFloatUniform("tint", tint.red, tint.green, tint.blue)
            shader.setFloatUniform("veil", style.veil)
            shader.setFloatUniform("size", width, height)
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("bezel", minOf(style.bezel.toPx(), radius))
            shader.setFloatUniform("refraction", style.refraction.toPx())
            shader.setFloatUniform("dispersion", style.dispersion)
            val soft = style.blur.toPx()
            val blur =
                android.graphics.RenderEffect.createBlurEffect(
                    soft,
                    soft,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            val bend = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
            return android.graphics.RenderEffect
                .createChainEffect(bend, blur)
                .asComposeRenderEffect()
        }
    }
}

/**
 * A rounded rectangle of glass (`size`, corner `radius`; a circle or a pill): within `bezel` of the rim the page is
 * read from further in, by up to `refraction` at the rim on a squared curve, as a convex edge bends light toward the
 * middle; red bends `dispersion` more and blue as much less, so edges inside the rim fringe with colour. The middle is
 * the page as is. Everywhere the `tint` (the surface) is overlay-blended in by the `veil` share: the page's lights and
 * darks pulled toward the surface, not a film over it.
 */
private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bezel;
uniform float refraction;
uniform float dispersion;
uniform float3 tint;
uniform float veil;

half3 overlay(half3 base, half3 over) {
    return mix(2.0 * base * over, 1.0 - 2.0 * (1.0 - base) * (1.0 - over), step(0.5, base));
}

half4 veiled(half4 c) {
    c.rgb = mix(c.rgb, overlay(c.rgb, half3(tint)), half(veil));
    return c;
}

half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 rel = p - c;
    float2 q = abs(rel) - (c - radius);
    float2 outer = max(q, 0.0);
    float depth = radius - length(outer) - min(max(q.x, q.y), 0.0);
    if (depth >= bezel || depth <= 0.0) {
        return veiled(content.eval(p));
    }
    float2 n;
    if (q.x > 0.0 && q.y > 0.0) {
        n = normalize(outer);
    } else if (q.x > q.y) {
        n = float2(1.0, 0.0);
    } else {
        n = float2(0.0, 1.0);
    }
    n *= float2(rel.x < 0.0 ? -1.0 : 1.0, rel.y < 0.0 ? -1.0 : 1.0);
    float k = 1.0 - depth / bezel;
    float bend = refraction * k * k;
    // read inside the glass only: a strong bend at a tight corner can reach past the far side
    float2 lo = float2(0.5);
    float2 hi = size - 0.5;
    half4 g = content.eval(clamp(p - n * bend, lo, hi));
    half r = content.eval(clamp(p - n * bend * (1.0 + dispersion), lo, hi)).r;
    half b = content.eval(clamp(p - n * bend * (1.0 - dispersion), lo, hi)).b;
    return veiled(half4(r, g.g, b, g.a));
}
"""
