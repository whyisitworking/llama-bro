package com.suhel.llamabro.demo.designsystem.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import com.suhel.llamabro.demo.designsystem.theme.DemoColors
import com.suhel.llamabro.demo.designsystem.theme.DemoSize

/**
 * The page under a [FloatingFrame]'s bars: the layer it is recorded into ([page], null where nothing can blur, before
 * Android 12), the colour it is drawn on, and where the frame sits in the window, so a piece of glass on a bar finds
 * the part of the page under itself.
 */
@Stable
internal class Backdrop(val page: GraphicsLayer?, val pageColor: Color) {
    var origin by mutableStateOf(Offset.Zero)
}

/**
 * A part of a floating bar as a piece of glass (D148), when on one ([backdrop]): a circle round an icon, a pill round
 * a title. From Android 13 it is a lens over the page: softly blurred, bent toward its rim as a convex edge bends
 * light, the colours split apart there (red bent more, blue less, as through Apple's Liquid Glass), under a light
 * veil, a light catching its upper rim and a hairline round it. Before that, a translucent fill and the hairline.
 */
internal fun Modifier.floatingChip(backdrop: Backdrop?, colors: DemoColors): Modifier =
    if (backdrop == null) this else this then GlassElement(backdrop, colors)

private data class GlassElement(val backdrop: Backdrop, val colors: DemoColors) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(backdrop, colors)

    override fun update(node: GlassNode) {
        node.backdrop = backdrop
        node.colors = colors
        node.invalidateDraw()
    }
}

private class GlassNode(var backdrop: Backdrop, var colors: DemoColors) :
    Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode {
    private var at = Offset.Zero
    private var glass: GraphicsLayer? = null
    private var lens: Any? = null // a Lens, made only where it can run

    override fun onAttach() {
        // a lens needs a page to bend and a shader to bend it (Android 13 and up)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backdrop.page != null) {
            glass = requireGraphicsContext().createGraphicsLayer()
            lens = Lens()
        }
    }

    override fun onDetach() {
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
        val corner = CornerRadius(size.minDimension / 2)
        val lensed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && drawLens(corner)
        if (!lensed) drawRoundRect(colors.surface.copy(alpha = CHIP_ALPHA), cornerRadius = corner)
        val hairline = requireDensity().run { DemoSize.hairline.toPx() }
        drawRoundRect(colors.hairline, cornerRadius = corner, style = Stroke(hairline))
        drawContent()
    }

    /** The lens: the page under the chip, bent and split, a light veil and the light on its rim; false if it can't. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun ContentDrawScope.drawLens(corner: CornerRadius): Boolean {
        val page = backdrop.page ?: return false
        val layer = glass ?: return false
        val lens = lens as? Lens ?: return false
        // the part of the page under the chip: the chip's place in the window less the frame's
        val offset = at - backdrop.origin
        layer.record {
            drawRect(backdrop.pageColor)
            translate(-offset.x, -offset.y) { drawLayer(page) }
        }
        layer.renderEffect = lens.effect(size.width, size.height, corner.x, requireDensity())
        layer.setRoundRectOutline(Offset.Zero, size, corner.x)
        layer.clip = true
        drawLayer(layer)
        drawRoundRect(colors.surface.copy(alpha = GLASS_VEIL), cornerRadius = corner)
        // the light on the glass: bright along the upper rim, fading down it
        drawRoundRect(
            Brush.verticalGradient(listOf(GlassLight, GlassLight.copy(alpha = 0f)), endY = size.height * 0.6f),
            cornerRadius = corner,
            style = Stroke(requireDensity().run { DemoSize.Glass.rim.toPx() }),
        )
        return true
    }
}

/** How opaque a chip is where it can't be a lens: denser than the bar's veil, still letting the page show through. */
private const val CHIP_ALPHA = 0.7f

/** The veil over a lens: light, so the bent, split page reads through it. */
private const val GLASS_VEIL = 0.35f

/** The light caught on a lens's rim: light whatever the theme, as a highlight on glass is. */
@Suppress("DemoRawColour")
private val GlassLight = Color.White.copy(alpha = 0.45f)

/**
 * A chip's lens (Android 13 and up): a soft blur, then [LENS] bending and splitting the page toward the chip's rim. The
 * shader is kept; the effect is made afresh for each draw, since it carries the chip's size.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class Lens {
    private val shader = RuntimeShader(LENS)

    fun effect(width: Float, height: Float, radius: Float, density: androidx.compose.ui.unit.Density): RenderEffect {
        with(density) {
            shader.setFloatUniform("size", width, height)
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("bezel", minOf(DemoSize.Glass.bezel.toPx(), radius))
            shader.setFloatUniform("refraction", DemoSize.Glass.refraction.toPx())
            shader.setFloatUniform("dispersion", DemoSize.Glass.DISPERSION)
            val soft = DemoSize.Glass.blur.toPx()
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
 * the page as is.
 */
private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bezel;
uniform float refraction;
uniform float dispersion;

half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 rel = p - c;
    float2 q = abs(rel) - (c - radius);
    float2 outer = max(q, 0.0);
    float depth = radius - length(outer) - min(max(q.x, q.y), 0.0);
    if (depth >= bezel || depth <= 0.0) {
        return content.eval(p);
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
    half4 g = content.eval(p - n * bend);
    half r = content.eval(p - n * bend * (1.0 + dispersion)).r;
    half b = content.eval(p - n * bend * (1.0 - dispersion)).b;
    return half4(r, g.g, b, g.a);
}
"""
