package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled
import com.suhel.llamabro.demo.designsystem.theme.onPhotoScrim
import com.suhel.llamabro.demo.designsystem.theme.photoBackdrop
import com.suhel.llamabro.demo.designsystem.theme.photoScrim
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** One photo in the viewer: still decoding, ready, or one that couldn't be decoded. */
@Immutable
sealed interface ViewerPhoto {
    @Immutable
    data object Loading : ViewerPhoto

    @Immutable
    data class Ready(val bitmap: ImageBitmap) : ViewerPhoto

    @Immutable
    data object Unavailable : ViewerPhoto
}

/**
 * The [PhotoViewer] in its own full-screen window, edge to edge, over the whole app; back closes it. The window has no
 * dim of its own (the viewer draws its backdrop, which fades on a pull) and light-on-dark system bars in both themes.
 */
@Composable
fun PhotoViewerDialog(photos: List<ViewerPhoto>, startAt: Int, onPageChange: (Int) -> Unit, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let {
                it.setDimAmount(0f)
                WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
        PhotoViewer(photos, startAt, onClose, onPageChange = onPageChange)
    }
}

/**
 * A message's photos full-screen on a near-black backdrop (demo design §8.10), opening at [startAt]: swipe sideways
 * between them (each settled page is told to [onPageChange]), pinch or double-tap to zoom (and pan when zoomed), swipe
 * down at 1x to close, or the ✕. A photo still decoding, or one that can't be, does not zoom.
 */
@Composable
fun PhotoViewer(
    photos: List<ViewerPhoto>,
    startAt: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onPageChange: (Int) -> Unit = {},
) {
    val colors = LocalDemoColors.current
    val still = animationsDisabled()
    val pager = rememberPagerState(startAt.coerceIn(0, max(0, photos.size - 1))) { photos.size }
    val zooms = remember(photos.size) { List(photos.size) { ZoomState() } }
    val pageChange by rememberUpdatedState(onPageChange)
    LaunchedEffect(pager, zooms) {
        snapshotFlow { pager.settledPage }.collect { settled ->
            // a photo swiped past comes back at 1x
            zooms.forEachIndexed { i, zoom -> if (i != settled) zoom.value = PhotoZoom() }
            pageChange(settled)
        }
    }
    var height by remember { mutableFloatStateOf(0f) }
    val pull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val pullState =
        rememberDraggableState { delta ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) { pull.snapTo(pull.value + delta) }
        }
    Box(
        modifier
            .fillMaxSize()
            .testTag(DsTags.PHOTO_VIEWER)
            .onSizeChanged { height = it.height.toFloat() }
            .drawBehind {
                val backdrop = colors.photoBackdrop
                drawRect(backdrop.copy(alpha = backdrop.alpha * backdropAlpha(pull.value, height)))
            },
    ) {
        val zoomed = zooms.getOrNull(pager.currentPage)?.value?.zoomed == true
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed) { page ->
            Photo(
                photos[page],
                stringResource(R.string.ds_photo_position, page + 1, photos.size),
                zooms[page],
                still,
                Modifier
                    .graphicsLayer { translationY = if (page == pager.currentPage) pull.value else 0f }
                    .draggable(
                        pullState,
                        Orientation.Vertical,
                        enabled = !zooms[page].value.zoomed,
                        // a new pull takes the photo where it is, mid spring-back
                        onDragStarted = { pull.stop() },
                        onDragStopped = { velocity ->
                            val offset = pull.value
                            if (dismisses(offset, velocity, height)) {
                                val away = (if (offset != 0f) sign(offset) else sign(velocity)) * height
                                if (!still) pull.animateTo(away, exitSpec())
                                onClose()
                            } else if (still) {
                                pull.snapTo(0f)
                            } else {
                                pull.animateTo(0f, standardSpec())
                            }
                        },
                    ),
            )
        }
        CloseButton(
            onClose,
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(DemoSpacing.inline),
        )
    }
}

/** One photo, fitted to the screen, that zooms about the fingers and pans within its own edges. */
@Composable
private fun Photo(
    photo: ViewerPhoto,
    description: String,
    zoom: ZoomState,
    still: Boolean,
    modifier: Modifier,
) {
    var container by remember { mutableStateOf(Size.Zero) }
    val bitmap = (photo as? ViewerPhoto.Ready)?.bitmap
    val fitted = bitmap?.let { fittedSize(Size(it.width.toFloat(), it.height.toFloat()), container) } ?: Size.Zero
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { container = it.toSize() }
            .zoomGestures(zoom, fitted, container)
            .doubleTapZoom(zoom, fitted, container) { target -> scope.launch { zoom.animateTo(target, still) } }
            .semantics {
                contentDescription = description
                role = Role.Image
            },
        contentAlignment = Alignment.Center,
    ) {
        when (photo) {
            is ViewerPhoto.Ready -> {
                Image(
                    photo.bitmap,
                    contentDescription = null,
                    modifier =
                        Modifier.fillMaxSize().graphicsLayer {
                            val z = zoom.value
                            scaleX = z.scale
                            scaleY = z.scale
                            translationX = z.pan.x
                            translationY = z.pan.y
                        },
                    contentScale = ContentScale.Fit,
                )
            }

            ViewerPhoto.Unavailable -> {
                Text(
                    stringResource(R.string.ds_photo_unavailable),
                    color = LocalDemoColors.current.onPhotoScrim,
                    style = DemoType.bodyMedium,
                )
            }

            ViewerPhoto.Loading -> {
                Unit
            }
        }
    }
}

/**
 * A pinch at once; one finger only once zoomed and past the touch slop, so a double tap's small wobble stays a tap. One
 * finger at 1x is left to the pager and the pull to close. Nothing drawn yet (or ever) has nothing to zoom.
 */
private fun Modifier.zoomGestures(zoom: ZoomState, fitted: Size, container: Size): Modifier =
    pointerInput(fitted, container, zoom) {
        if (fitted == Size.Zero) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var travelled = 0f
            var panning = false
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                if (event.changes.none { it.isConsumed }) {
                    if (fingers == 1 && zoom.value.zoomed && !panning) {
                        travelled += event.calculatePan().getDistance()
                        panning = travelled > viewConfiguration.touchSlop
                    }
                    if (fingers > 1 || panning) {
                        val centroid = event.calculateCentroid(useCurrent = true) - container.center()
                        zoom.value =
                            zoom.value.transform(
                                event.calculateZoom(),
                                event.calculatePan(),
                                centroid,
                                fitted,
                                container,
                            )
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
            // a pinch let go just above 1x is 1x, not a sliver of zoom that locks the pager
            if (!zoom.value.zoomed && zoom.value != PhotoZoom()) zoom.value = PhotoZoom()
        }
    }

/** A double tap zooms in at the tap, or back out; [onTarget] animates there. */
private fun Modifier.doubleTapZoom(
    zoom: ZoomState,
    fitted: Size,
    container: Size,
    onTarget: (PhotoZoom) -> Unit,
): Modifier =
    pointerInput(fitted, container, zoom, onTarget) {
        if (fitted == Size.Zero) return@pointerInput
        detectTapGestures(onDoubleTap = { tap ->
            onTarget(zoom.value.toggled(tap - container.center(), fitted, container))
        })
    }

/** The ✕: a white glyph on a dark scrim circle, in a 48 dp target, the same in both themes. */
@Composable
private fun CloseButton(onClose: () -> Unit, modifier: Modifier) {
    val colors = LocalDemoColors.current
    Box(
        modifier.size(DemoSize.target).clip(CircleShape).clickable(role = Role.Button, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(DemoSize.control).background(colors.photoScrim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                DemoIcons.Close,
                contentDescription = stringResource(R.string.ds_close_photo),
                tint = colors.onPhotoScrim,
                modifier = Modifier.size(DemoSize.icon),
            )
        }
    }
}

private fun <T> standardSpec() = tween<T>(DemoMotion.STANDARD_MILLIS, easing = DemoMotion.standard)

private fun <T> exitSpec() = tween<T>(DemoMotion.EXIT_MILLIS, easing = DemoMotion.exit)

private fun Size.center() = Offset(width / 2f, height / 2f)

/** One photo's zoom, kept while the viewer is open. */
@Stable
private class ZoomState {
    var value by mutableStateOf(PhotoZoom())

    suspend fun animateTo(target: PhotoZoom, still: Boolean) {
        if (still) {
            value = target
            return
        }
        val from = value
        animate(0f, 1f, animationSpec = standardSpec()) { t, _ ->
            value = PhotoZoom(from.scale + (target.scale - from.scale) * t, lerp(from.pan, target.pan, t))
        }
    }
}

/** How a photo is drawn: [scale] about the screen's centre, then moved by [pan] (px). */
@Immutable
internal data class PhotoZoom(val scale: Float = 1f, val pan: Offset = Offset.Zero) {
    val zoomed: Boolean get() = scale > 1f + ZOOM_EPSILON
}

/** The most a photo zooms in, and how far a double tap takes it. */
internal const val MAX_ZOOM = 4f
internal const val DOUBLE_TAP_ZOOM = 2.5f

/** A pull past this share of the screen's height closes the viewer, and so does a flick of this share per second. */
internal const val DISMISS_FRACTION = 0.2f
internal const val FLING_FRACTION_PER_SECOND = 1.5f

/** The backdrop is gone once the photo is pulled this share of the screen's height. */
internal const val FADE_FRACTION = 0.5f

private const val ZOOM_EPSILON = 0.01f

/** The size an [image] takes fitted inside a [container], keeping its proportions. */
internal fun fittedSize(image: Size, container: Size): Size {
    if (image.width <= 0f || image.height <= 0f) return Size.Zero
    val scale = min(container.width / image.width, container.height / image.height)
    return Size(image.width * scale, image.height * scale)
}

internal fun clampZoom(scale: Float): Float = scale.coerceIn(1f, MAX_ZOOM)

/**
 * The nearest [pan] that keeps a [fitted] photo at [scale] over its own edges: it can move only by as much as it
 * overflows the [container], so not at all at 1x.
 */
internal fun clampPan(pan: Offset, scale: Float, fitted: Size, container: Size): Offset {
    val maxX = max(0f, (fitted.width * scale - container.width) / 2f)
    val maxY = max(0f, (fitted.height * scale - container.height) / 2f)
    // + 0f turns a -0 into 0, so an unmoved photo is Offset.Zero
    return Offset(pan.x.coerceIn(-maxX, maxX) + 0f, pan.y.coerceIn(-maxY, maxY) + 0f)
}

/**
 * Zooms by [zoomChange] about [centroid] (from the container's centre), so the point under the fingers stays under
 * them, and moves by [panChange]; clamped.
 */
internal fun PhotoZoom.transform(
    zoomChange: Float,
    panChange: Offset,
    centroid: Offset,
    fitted: Size,
    container: Size,
): PhotoZoom {
    val newScale = clampZoom(scale * zoomChange)
    val newPan = centroid + panChange - (centroid - pan) * (newScale / scale)
    return PhotoZoom(newScale, clampPan(newPan, newScale, fitted, container))
}

/** A double tap at [tap] (from the container's centre): zooms in there, or back out to 1x when zoomed. */
internal fun PhotoZoom.toggled(tap: Offset, fitted: Size, container: Size): PhotoZoom =
    if (zoomed) PhotoZoom() else transform(DOUBLE_TAP_ZOOM / scale, Offset.Zero, tap, fitted, container)

/** Whether a pull of [offset] px, let go at [velocity] px/s, closes a viewer [height] px tall. */
internal fun dismisses(offset: Float, velocity: Float, height: Float): Boolean {
    if (height <= 0f) return false
    val far = abs(offset) > height * DISMISS_FRACTION
    val flung = abs(velocity) > height * FLING_FRACTION_PER_SECOND && (offset == 0f || sign(velocity) == sign(offset))
    return far || flung
}

/** The backdrop's opacity (0 to 1) for a pull of [offset] px. */
internal fun backdropAlpha(offset: Float, height: Float): Float =
    if (height <= 0f) 1f else 1f - min(1f, abs(offset) / (height * FADE_FRACTION))
