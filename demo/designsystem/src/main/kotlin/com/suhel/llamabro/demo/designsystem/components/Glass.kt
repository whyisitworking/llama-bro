package com.suhel.llamabro.demo.designsystem.components

import android.graphics.RuntimeShader
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import com.suhel.llamabro.demo.designsystem.theme.BarGlass
import com.suhel.llamabro.demo.designsystem.theme.DemoColors
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.GlassPose
import com.suhel.llamabro.demo.designsystem.theme.GlassPress
import com.suhel.llamabro.demo.designsystem.theme.GlassStyle
import com.suhel.llamabro.demo.designsystem.theme.GlassTier
import com.suhel.llamabro.demo.designsystem.theme.LocalAnimatorDurationScale
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.LocalGlass
import com.suhel.llamabro.demo.designsystem.theme.LocalGlassTier
import com.suhel.llamabro.demo.designsystem.theme.animatorDurationScale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * A page that glass can be drawn over (D148): the layer the page is recorded into ([page], null before Android 12,
 * where nothing can blur), the colour it is drawn on, and where it sits on the screen, so a piece of glass anywhere
 * over it finds the part under itself. Made with [rememberBackdrop]; the page is the content of [backdropSource].
 */
@Stable
class Backdrop internal constructor(internal val page: GraphicsLayer?, val pageColor: Color) {
    internal var origin by mutableStateOf(Offset.Zero)
}

/**
 * Whether this phone draws a chip's or a panel's glass as a fill ([GlassStyle.bareVeil]) instead of a lens: before
 * Android 13, which has no runtime shaders.
 */
val GlassFillsInstead: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

/** Whether this phone veils a bar's page without blurring it ([BarGlass.bareVeil]): before Android 12. */
val BarsVeilInstead: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

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
    // on the screen, as glass finds itself: glass in another window (a sheet's, D164) lines up with the page too
    return onGloballyPositioned { backdrop.origin = it.positionOnScreen() }
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
 * The whole window's page, for the bottom sheets' glass (D164), provided by [WindowGlass]. Only glass in a window of
 * its own (a sheet's) may read it: everything in the app's window is inside this page, so glass there drawing it would
 * draw itself. Internal, so the app can't. Null leaves the sheets plain.
 */
internal val LocalWindowBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * The app's window as the page under its bottom sheets' glass (D164): [content] fills the window and is recorded once a
 * frame, for the sheets, which open in windows of their own, to draw it as any glass does. Put it at the app's root,
 * around everything a sheet can open over.
 */
@Composable
fun WindowGlass(content: @Composable BoxScope.() -> Unit) {
    val window = rememberBackdrop()
    CompositionLocalProvider(LocalWindowBackdrop provides window) {
        Box(Modifier.fillMaxSize().backdropSource(window), content = content)
    }
}

/**
 * A piece of glass a floating bar draws in its own passes (D154): where it is on the screen ([rect]), its corners'
 * radius in pixels ([corner]), its [style], and how much more than the style says its edge bends while pressed
 * ([bend], D158); [rect] and [corner] are the pressed glass's, swollen.
 */
@Immutable
internal data class MergedShape(val rect: Rect, val corner: Float, val style: GlassStyle, val bend: Float = 1f)

/**
 * The glass a floating bar draws itself (D154): each piece of glass on the bar over the same [backdrop] puts its
 * [MergedShape] here, and while [active] (the glass's [GlassTheme.mergedLens], where the bar can run its shader) the
 * bar bends and blurs it in the passes it runs anyway, and the piece draws only its rim and its content.
 */
@Stable
internal class GlassContainer(val backdrop: Backdrop) {
    val shapes = mutableStateMapOf<Any, MergedShape>()
    var active by mutableStateOf(false)
}

/** The bar the content is on, which may draw its glass (D154); null off a floating bar. */
internal val LocalGlassContainer = staticCompositionLocalOf<GlassContainer?> { null }

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
 *
 * It answers a finger (D158, [GlassPress]): it swells, a light gathers on its rim around the finger and its edge
 * bends more, and dragged it stretches after it, then all of it eases back. With the [interactions] of what makes it
 * pressable (a clickable on the same bounds) it answers a key's press and a keyboard's focus too, and is the press's
 * indication, so the clickable needs no ripple of its own.
 */
fun Modifier.glass(
    backdrop: Backdrop?,
    style: GlassStyle,
    corner: Dp? = null,
    interactions: InteractionSource? = null,
): Modifier = this then GlassElement(backdrop, corner, style, interactions)

/**
 * [content] on a piece of glass ([glass]) over the page under it ([LocalBackdrop]), clipped to its shape: corners
 * [corner], a pill when null. With [onClick] it is a button whose glass answers the press (D158).
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    corner: Dp? = null,
    style: GlassStyle = LocalGlass.current.panel,
    backdrop: Backdrop? = LocalBackdrop.current,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = if (corner == null) CircleShape else RoundedCornerShape(corner)
    val interactions = if (onClick != null) remember { MutableInteractionSource() } else null
    Box(
        modifier
            .glass(backdrop, style, corner, interactions)
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.glassClickable(interactions, glass = true, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}

/**
 * A part of a floating bar as a chip of glass in [style], when on one ([backdrop]), answering presses from
 * [interactions] (D158); nothing otherwise.
 */
internal fun Modifier.chipGlass(
    backdrop: Backdrop?,
    style: GlassStyle,
    interactions: InteractionSource? = null,
): Modifier = if (backdrop == null) this else glass(backdrop, style, interactions = interactions)

/**
 * A button's click on glass that answers its press (D158): the glass is the indication, so no ripple is drawn over
 * it; off glass ([glass] false), the usual ripple.
 */
@Composable
internal fun Modifier.glassClickable(
    interactions: MutableInteractionSource?,
    glass: Boolean,
    onClick: () -> Unit,
): Modifier =
    if (glass) {
        clickable(interactions, indication = null, role = Role.Button, onClick = onClick)
    } else {
        clickable(role = Role.Button, onClick = onClick)
    }

private data class GlassElement(
    val backdrop: Backdrop?,
    val corner: Dp?,
    val style: GlassStyle,
    val interactions: InteractionSource?,
) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(backdrop, corner, style, interactions)

    override fun update(node: GlassNode) {
        node.update(backdrop, corner, style, interactions)
    }
}

private class GlassNode(
    var backdrop: Backdrop?,
    var corner: Dp?,
    var style: GlassStyle,
    var interactions: InteractionSource?,
) : DelegatingNode(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode {
    private var at = Offset.Zero
    private var extent = IntSize.Zero
    private var glass: GraphicsLayer? = null
    private var lens: Any? = null // a Lens, made only where it can run

    /** The bar this glass is on, which draws its lens while it is [GlassContainer.active] (D154). */
    private var container: GlassContainer? = null

    /**
     * How far in the press is (D158): 0 at rest, 1 pressed, eased between; snapshot state read where the glass draws,
     * so a press redraws it frame by frame and recomposes nothing.
     */
    private var press = Animatable(0f)

    /**
     * Where the finger is, in the glass's own pixels, followed as it moves: the light gathers there and stays as the
     * press fades. Snapshot state, so a moving finger redraws the glass.
     */
    private var finger by mutableStateOf(Offset.Unspecified)

    /** How far the finger has been dragged since it touched down, while it is down (D158). */
    private var drag by mutableStateOf(Offset.Zero)

    /** Whether a finger is down on the glass: its drag is [drag], else [lifted]'s, easing out with the press. */
    private var held by mutableStateOf(false)

    /**
     * The drag as the finger lifted, eased back to rest by the press itself (D158): swell, stretch and movement go
     * back together on one curve, not the swell first and the rest after.
     */
    private var liftedDrag by mutableStateOf(Offset.Zero)

    /** Where the pane's drag is: the finger's [drag], caught up with a little after it moves (D158). */
    private var pane = Animatable(Offset.Zero, Offset.VectorConverter)

    /**
     * The glass follows the finger itself rather than through a click's press (D158), pressable or not, as every
     * panel answers a touch: a click's press reports only where the finger came down, and is cancelled as it slides
     * off, while the glass must follow it until it lifts. It watches the events on their way in and consumes none, so
     * what is on the glass (a click, a text field) still gets them all.
     */
    @Suppress("unused") // delegated: the node is attached with this one
    private val touches = delegate(SuspendingPointerInputModifierNode { follow() })

    /** Whether animations are off as the press began: the glass then doesn't swell (D158). */
    private var still = false
    private var pressedAt = 0L
    private var watching: Job? = null
    private var releasing: Job? = null

    fun update(backdrop: Backdrop?, corner: Dp?, style: GlassStyle, interactions: InteractionSource?) {
        val lensChanged = (backdrop?.page != null) != (this.backdrop?.page != null)
        val sourceChanged = interactions !== this.interactions
        this.backdrop = backdrop
        this.corner = corner
        this.style = style
        this.interactions = interactions
        if (lensChanged && isAttached) {
            release()
            prepare()
        }
        if (sourceChanged && isAttached) watch()
        register()
        invalidateDraw()
    }

    override fun onAttach() {
        prepare()
        watch()
    }

    override fun onDetach() {
        container?.shapes?.remove(this)
        container = null
        release()
        // the scope's jobs end with it; a node reused later starts at rest
        watching = null
        releasing = null
        press = Animatable(0f)
        pane = Animatable(Offset.Zero, Offset.VectorConverter)
        held = false
        drag = Offset.Zero
        liftedDrag = Offset.Zero
    }

    /** Follows each finger that touches the glass from down to up, sliding off it or not (D158). */
    private suspend fun PointerInputScope.follow() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touched(down.position)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val finger = event.changes.firstOrNull { it.id == down.id }
                    if (finger == null || !finger.pressed) break
                    moved(down.position, finger.position)
                }
            } finally {
                lifted()
            }
        }
    }

    private fun touched(at: Offset) {
        held = true
        drag = Offset.Zero
        coroutineScope.launch { pane.snapTo(Offset.Zero) }
        pressed(at, animationsOff())
    }

    /** The finger moved: the light follows it at once, the pane after it, as a heavy thing does (D158). */
    private fun moved(from: Offset, to: Offset) {
        finger = to
        drag = to - from
        coroutineScope.launch {
            pane.animateTo(drag, tween(DemoMotion.PANE_LAG_MILLIS, easing = DemoMotion.enter)) { register() }
        }
    }

    private fun lifted() {
        // a gesture ended by the node leaving has no scope left to settle in, nor need to
        if (!held || !isAttached) return
        // the drag eases back as the press does, from wherever the press is as the finger lifts
        liftedDrag = if (press.value > 0f) pane.value / press.value else Offset.Zero
        held = false
        drag = Offset.Zero
        coroutineScope.launch { pane.snapTo(Offset.Zero) }
        released()
    }

    /**
     * Follows the presses of [interactions], if any (D158), and a keyboard's focus, which lights the glass as a press
     * would but doesn't swell it: with no ripple, the glass is the only sign of either. A new source starts at rest,
     * or a press whose release went to the old one would hold the glass swollen.
     */
    private fun watch() {
        watching?.cancel()
        releasing?.cancel()
        if (press.value != 0f) coroutineScope.launch { press.snapTo(0f) }
        val source = interactions ?: return
        watching =
            coroutineScope.launch {
                source.interactions.collect {
                    when (it) {
                        // a finger's press is the one the glass follows itself; this is a key's
                        is PressInteraction.Press -> {
                            if (!held) pressed(it.pressPosition, animationsOff())
                        }

                        is FocusInteraction.Focus -> {
                            pressed(Offset.Unspecified, still = true)
                        }

                        is PressInteraction.Release, is PressInteraction.Cancel, is FocusInteraction.Unfocus -> {
                            if (!held) released()
                        }
                    }
                }
            }
    }

    /** A press at [at] (unspecified: the middle) begins; [still], it lights the glass without swelling it. */
    private fun pressed(at: Offset, still: Boolean) {
        releasing?.cancel()
        finger = at
        this.still = still
        pressedAt = SystemClock.uptimeMillis()
        coroutineScope.launch {
            press.animateTo(1f, tween(DemoMotion.PRESS_IN_MILLIS, easing = DemoMotion.standard)) { register() }
        }
    }

    private fun released() {
        releasing?.cancel()
        releasing =
            coroutineScope.launch {
                // a tap released the frame it lands still shows its press
                delay(DemoMotion.PRESS_HOLD_MILLIS - (SystemClock.uptimeMillis() - pressedAt))
                press.animateTo(0f, tween(DemoMotion.PRESS_OUT_MILLIS, easing = DemoMotion.standard)) { register() }
            }
    }

    /** Whether the person turned animations off, read as a press begins rather than observed (D158). */
    private fun animationsOff(): Boolean =
        (
            currentValueOf(LocalAnimatorDurationScale)
                ?: currentValueOf(LocalContext).contentResolver.animatorDurationScale()
        ) == 0f

    /** Where glass of [base] size is drawn this frame (D158): swollen, pulled and stretched as the finger has it. */
    private fun poseNow(feel: GlassPress, base: Size): GlassPose {
        val pulled = if (held) pane.value else liftedDrag * press.value
        return with(requireDensity()) {
            feel.pose(base, press.value, pulled, still, feel.pull.toPx(), feel.resistance.toPx())
        }
    }

    /** Its corners' radius drawn at [pose] from [base]: grown with the glass's shorter side, a pill at most. */
    private fun cornerOf(pose: GlassPose, base: Size, density: Density): Float {
        val pill = pose.size.minDimension / 2f
        val grown = pose.size.minDimension / base.minDimension.coerceAtLeast(1f)
        val radius = corner?.let { with(density) { it.toPx() } * grown }
        return radius?.coerceAtMost(pill) ?: pill
    }

    /**
     * Puts this glass's shape on the bar it is on, where the bar could draw it: a bar over the same page, on a phone
     * whose bar runs its shader (Android 13 and up).
     */
    private fun register() {
        if (!isAttached) return
        val bar =
            currentValueOf(LocalGlassContainer)?.takeIf {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backdrop?.page != null &&
                    it.backdrop === backdrop
            }
        if (bar !== container) {
            container?.shapes?.remove(this)
            container = bar
        }
        if (bar == null || extent == IntSize.Zero) return
        val feel = currentValueOf(LocalGlass).press
        val pose = poseNow(feel, extent.toSize())
        val shape =
            MergedShape(
                Rect(at + pose.topLeft, pose.size),
                cornerOf(pose, extent.toSize(), requireDensity()),
                style,
                feel.bendAt(press.value),
            )
        if (bar.shapes[this] != shape) bar.shapes[this] = shape
    }

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
        val now = coordinates.positionOnScreen()
        if (now != at || coordinates.size != extent) {
            at = now
            extent = coordinates.size
            register()
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val colors = currentValueOf(LocalDemoColors)
        val feel = currentValueOf(LocalGlass).press
        // pressed, the glass is drawn swollen, pulled and stretched (D158): its bounds, and what it lays out, stay put
        val pose = poseNow(feel, size)
        val bend = feel.bendAt(press.value)
        val grown = pose.size
        val shift = pose.topLeft
        val corner = CornerRadius(cornerOf(pose, size, this))
        // a bar drawing this glass in its own passes leaves it only the rim, the hairline and the content (D154)
        val merged = container?.active == true
        translate(shift.x, shift.y) {
            val lensed =
                merged ||
                    (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            drawLens(grown, shift, corner, bend, colors)
                    )
            if (!lensed) drawRoundRect(colors.surface.copy(alpha = style.bareVeil), size = grown, cornerRadius = corner)
            // the hairline under the light, not over it: over a pressed rim's wider light it split it in two lines
            drawRoundRect(
                colors.hairline,
                size = grown,
                cornerRadius = corner,
                style = Stroke(DemoSize.hairline.toPx()),
            )
            if (lensed) drawRimLight(grown, corner)
            drawPressLight(grown, shift, corner, feel)
        }
        if (pose.scaleX == 1f && pose.scaleY == 1f && shift == Offset.Zero) {
            drawContent()
        } else {
            val moved = pose.moved(size)
            translate(moved.x, moved.y) { scale(pose.scaleX, pose.scaleY) { this@draw.drawContent() } }
        }
    }

    /**
     * The lens over [grown] (the glass's size, posed when pressed, drawn at [shift] in its bounds): the page
     * under it, bent ([bend] times its refraction) and split, the veil and the light on its rim; false if it can't.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Suppress("LongParameterList") // what the swollen glass is drawn with
    private fun DrawScope.drawLens(
        grown: Size,
        shift: Offset,
        corner: CornerRadius,
        bend: Float,
        colors: DemoColors,
    ): Boolean {
        val backdrop = backdrop ?: return false
        val page = backdrop.page ?: return false
        val layer = glass ?: return false
        val lens = lens as? Lens ?: return false
        // the part of the page under the glass: its place on the screen less the page's
        val offset = at + shift - backdrop.origin
        layer.record(IntSize(ceil(grown.width).toInt(), ceil(grown.height).toInt())) {
            drawRect(backdrop.pageColor)
            translate(-offset.x, -offset.y) { drawLayer(page) }
        }
        val weaker = currentValueOf(LocalGlassTier) == GlassTier.Light
        layer.renderEffect =
            lens.effect(grown.width, grown.height, corner.x, style, bend, colors.surface, weaker, this)
        layer.setRoundRectOutline(Offset.Zero, grown, corner.x)
        layer.clip = true
        // the veil is blended into the glass by its shader, not laid over it
        drawLayer(layer)
        return true
    }

    /** The light on the glass: bright along the upper rim, fading down it. */
    private fun DrawScope.drawRimLight(grown: Size, corner: CornerRadius) {
        val light = GlassLight.copy(alpha = style.rimLight)
        drawEdge(
            Brush.verticalGradient(listOf(light, light.copy(alpha = 0f)), endY = grown.height * 0.6f),
            grown,
            corner,
            style.rim.toPx(),
        )
    }

    /**
     * A light along the glass's edge, [width] wide: its outer side on the hairline's, so the two read as one edge and
     * a wider light grows into the glass, not out past it.
     */
    private fun DrawScope.drawEdge(brush: Brush, grown: Size, corner: CornerRadius, width: Float) {
        val inset = ((width - DemoSize.hairline.toPx()) / 2f).coerceAtLeast(0f)
        drawRoundRect(
            brush,
            topLeft = Offset(inset, inset),
            size = Size(grown.width - 2f * inset, grown.height - 2f * inset),
            cornerRadius = CornerRadius((corner.x - inset).coerceAtLeast(0f)),
            style = Stroke(width),
        )
    }

    /**
     * The light a press sheds on the glass (D158), as light through a pane does: a sheen across all of it, brightest
     * under the finger and fading over the pane; the whole rim catching it; and the rim brightest near the finger, out
     * to [GlassPress.reach]. The light stays within the glass: a finger that slides off leaves it at the nearest edge.
     * The posed glass is drawn at [shift] in its bounds.
     */
    private fun DrawScope.drawPressLight(grown: Size, shift: Offset, corner: CornerRadius, feel: GlassPress) {
        val strength = feel.lightAt(press.value)
        if (strength <= 0f) return
        // a keyboard's focus has no finger: the light gathers in the middle
        val at = if (finger.isSpecified) (finger - shift).insideRoundRect(grown, corner.x) else grown.center
        val light = GlassLight.copy(alpha = strength)
        val sheen = GlassLight.copy(alpha = strength * PRESS_SHEEN)
        drawRoundRect(
            Brush.radialGradient(
                0f to sheen,
                SHEEN_FALL to sheen.copy(alpha = sheen.alpha * SHEEN_FAR),
                1f to sheen.copy(alpha = 0f),
                center = at,
                radius = grown.maxDimension * SHEEN_SPREAD,
            ),
            size = grown,
            cornerRadius = corner,
        )
        val rim = style.rim.toPx()
        drawEdge(SolidColor(GlassLight.copy(alpha = strength * PRESS_RIM_LIFT)), grown, corner, rim)
        val reach = maxOf(feel.reach.toPx(), grown.minDimension)
        drawEdge(
            Brush.radialGradient(listOf(light, light.copy(alpha = 0f)), center = at, radius = reach),
            grown,
            corner,
            rim * PRESS_RIM,
        )
    }
}

/**
 * This point held within a rounded rectangle of [size] and corner [radius] (D158): unchanged inside it, else the
 * nearest point on its outline, so a press's light stays on the glass when the finger leaves it.
 */
internal fun Offset.insideRoundRect(size: Size, radius: Float): Offset {
    val r = radius.coerceIn(0f, size.minDimension / 2f)
    val x = x.coerceIn(0f, size.width)
    val y = y.coerceIn(0f, size.height)
    // in a corner's square, held to its arc
    val cx = x.coerceIn(r, size.width - r)
    val cy = y.coerceIn(r, size.height - r)
    val out = Offset(x - cx, y - cy)
    val far = out.getDistance()
    return if (far <= r || far == 0f) Offset(x, y) else Offset(cx, cy) + out * (r / far)
}

/** How much thicker the rim's light is near a pressing finger than the rim light at rest (D158). */
private const val PRESS_RIM = 2f

/** How brightly the whole rim catches a press's light, as a share of the light near the finger (D158). */
private const val PRESS_RIM_LIFT = 0.35f

/**
 * The sheen a press sheds across the pane (D158): its brightness under the finger, as a share of the light; how far
 * across it spreads, as a share of the pane's longer side; where it has fallen to [SHEEN_FAR] of that, which it keeps
 * to the far side, so the whole pane is lit, not a spot.
 */
private const val PRESS_SHEEN = 0.3f
private const val SHEEN_SPREAD = 1.1f
private const val SHEEN_FALL = 0.45f
private const val SHEEN_FAR = 0.4f

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

    /**
     * The lens over a glass [width] by [height], its edge bending [bend] times the style's refraction (more while
     * pressed, D158); with [light] (a weaker phone, D153), without its own blur pass.
     */
    @Suppress("LongParameterList") // the shader's inputs, all of them
    fun effect(
        width: Float,
        height: Float,
        radius: Float,
        style: GlassStyle,
        bend: Float,
        tint: Color,
        light: Boolean,
        density: Density,
    ): RenderEffect {
        with(density) {
            shader.setFloatUniform("tint", tint.red, tint.green, tint.blue)
            shader.setFloatUniform("veil", style.veil)
            shader.setFloatUniform("size", width, height)
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("bezel", minOf(style.bezel.toPx(), radius))
            shader.setFloatUniform("refraction", style.refraction.toPx() * bend)
            shader.setFloatUniform("dispersion", style.dispersion)
            val bent = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
            val soft = style.blur.toPx()
            if (light || soft < 1f) return bent.asComposeRenderEffect()
            val blur =
                android.graphics.RenderEffect.createBlurEffect(
                    soft,
                    soft,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            return android.graphics.RenderEffect
                .createChainEffect(bent, blur)
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
