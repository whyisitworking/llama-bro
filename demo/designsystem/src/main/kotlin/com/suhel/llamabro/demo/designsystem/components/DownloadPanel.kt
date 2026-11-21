package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.R
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.animationsDisabled

/**
 * How far past the gutter a container lets its content reach on each side (its own padding is the gutter less this):
 * a [DownloadPanel] in it puts its targets' edges there, so the ring's edge and the ✕'s glyph meet the gutter while
 * their 48 dp targets stay inside the container's bounds (F130). Content that should sit on the gutter takes it back
 * with [OnGutter]. Zero outside such a container.
 */
val LocalEdgeBleed = compositionLocalOf { 0.dp }

/** [content] on the gutter inside a container that bleeds ([LocalEdgeBleed]): the bleed taken back as padding. */
@Composable
fun OnGutter(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.padding(horizontal = LocalEdgeBleed.current)) {
        CompositionLocalProvider(LocalEdgeBleed provides 0.dp, content = content)
    }
}

/** Where a download stands, as the panel draws it; the app maps its download status to one of these. */
enum class DownloadStage { Waiting, Downloading, Paused, Stopped }

/**
 * One download as the panel shows it: its [stage], the file's [quant] ("Q4_K_M", never a tier), how much has arrived
 * ([fraction], 0 to 1) and the [detail] line under it ("0.9 of 3.4 GB · 4 min left", "after Gemma 3 4B", or why it
 * stopped).
 */
@Immutable
data class DownloadView(val stage: DownloadStage, val quant: String, val fraction: Float, val detail: String)

/**
 * A download as one object (models-page design §4.5, the "B · Ring" option): the progress is a ring drawn around the
 * control that acts on it, Pause while it downloads, Resume when paused, Try again when stopped, and an hourglass
 * (no action) while it waits its turn; then two lines, "Downloading · Q4_K_M" over the [DownloadView.detail]; then
 * Cancel, a quieter ✕ set apart at the end. A paused or stopped ring is frozen in the muted ink; a waiting one is
 * empty; "Stopped" is in the failure colour. Both controls are 48 dp targets with their names, and the ring's amount is
 * announced on the control it surrounds.
 *
 * [onToggle] pauses, resumes or tries again, by stage. The panel is one height in every stage, so nothing around it
 * moves as a download changes: one [DemoSize.button], a button's height, so the card's pinned foot holds it in its
 * [DemoSize.sheetFoot] as it holds Get (D118); one [DemoSize.target] when [compact] (a model row's footer, the same
 * ring, text and icons smaller). At large font it grows rather than clips.
 */
@Composable
fun DownloadPanel(
    view: DownloadView,
    onToggle: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val fade = tween<Float>(if (animationsDisabled()) 0 else DemoMotion.CROSSFADE_MILLIS)
    // the 48 dp targets stay inside the panel, so accessibility sees all of them (F130). Optically the ring's edge and
    // the ✕'s glyph should meet the gutters: a container that gives the panel room past
    // its gutter ([LocalEdgeBleed]) has the panel use it, each target reaching as far past the gutter as its mark is
    // inset in it
    val bleed = LocalEdgeBleed.current
    val ringInset = (DemoSize.target - if (compact) DemoSize.panelRingCompact else DemoSize.panelRing) / 2
    val crossInset = (DemoSize.target - if (compact) DemoSize.iconSmall else DemoSize.icon) / 2
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = (bleed - ringInset).coerceAtLeast(0.dp), end = (bleed - crossInset).coerceAtLeast(0.dp))
            .heightIn(min = if (compact) DemoSize.target else DemoSize.button),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
    ) {
        // a new stage fades its two lines through (no double exposure); the bytes moving within a stage just update.
        // The ring draws its amount from the same value the words show, so "Waiting" fading out never sits beside a
        // moving arc, and the arc and "0.9 of 3.4 GB" change in the same frame
        val through = rememberFadeThrough(view) { it.stage }
        RingControl(view, through.drawn.fraction, onToggle, compact, fade)
        Box(Modifier.weight(1f).graphicsLayer { alpha = through.alpha }) { StageLines(through.drawn) }
        IconAction(
            DemoIcons.Close,
            stringResource(R.string.ds_cancel),
            onClick = onCancel,
            muted = true,
            small = compact,
        )
    }
}

/** "Downloading · Q4_K_M" over the detail; only the word "Stopped" is in the failure's colour. */
@Composable
private fun StageLines(view: DownloadView) {
    val colors = LocalDemoColors.current
    val stageWord =
        stringResource(
            when (view.stage) {
                DownloadStage.Waiting -> R.string.ds_download_waiting
                DownloadStage.Downloading -> R.string.ds_download_downloading
                DownloadStage.Paused -> R.string.ds_download_paused
                DownloadStage.Stopped -> R.string.ds_download_stopped
            },
        )
    val line = stringResource(R.string.ds_download_line, stageWord, view.quant)
    // at large font each line keeps two lines and grows past them rather than cut the fact: the panel is one height
    // within a mode, and never ellipsises "Downloading · Q4_K_M" or the bytes
    val large = isLargeText()
    val lines = if (large) LARGE_LINES else 1
    val most = if (large) Int.MAX_VALUE else 1
    Column(Modifier.fillMaxWidth()) {
        Text(
            buildAnnotatedString {
                append(line)
                // the quant is still which file it was, so only the word takes the failure's colour
                val at = line.indexOf(stageWord)
                if (view.stage == DownloadStage.Stopped && at >= 0) {
                    addStyle(SpanStyle(color = colors.danger), at, at + stageWord.length)
                }
            },
            color = colors.ink,
            style = DemoType.label,
            minLines = lines,
            maxLines = most,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            view.detail,
            color = colors.muted,
            style = DemoType.bodySmall,
            minLines = lines,
            maxLines = most,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The ring around the stage's control, centred in a 48 dp target. While waiting it is no control: an empty ring
 * around an hourglass, whose meaning the first line already says.
 */
@Composable
private fun RingControl(
    view: DownloadView,
    fraction: Float,
    onToggle: () -> Unit,
    compact: Boolean,
    fade: FiniteAnimationSpec<Float>,
) {
    val colors = LocalDemoColors.current
    val action: Pair<ImageVector, Int>? = view.stage.action()
    // the name sits on the target, not on the glyph, so a crossfade between glyphs never says two names
    val label = action?.let { stringResource(it.second) }
    val target =
        Modifier
            .size(DemoSize.target)
            .clip(CircleShape)
            .then(
                if (action != null) {
                    Modifier
                        .clickable(role = Role.Button, onClick = onToggle)
                        .semantics { contentDescription = label.orEmpty() }
                } else {
                    Modifier
                },
            )
    Box(target, contentAlignment = Alignment.Center) {
        DownloadRing(
            fraction = if (view.stage == DownloadStage.Waiting) 0f else fraction,
            size = if (compact) DemoSize.panelRingCompact else DemoSize.panelRing,
            frozen = view.stage != DownloadStage.Downloading,
            eases = false,
        )
        Crossfade(view.stage, animationSpec = fade, label = "download glyph") { stage ->
            val glyph = stage.action()
            Icon(
                glyph?.first ?: DemoIcons.Hourglass,
                contentDescription = null,
                tint = if (glyph == null) colors.muted else colors.ink,
                modifier = Modifier.size(if (compact) DemoSize.panelGlyphCompact else DemoSize.panelGlyph),
            )
        }
    }
}

/** Lines each of the panel's two lines keeps at large font, so the panel is one height within the mode. */
internal const val LARGE_LINES = 2

/** The stage's control, its glyph and its name; waiting has none. */
private fun DownloadStage.action(): Pair<ImageVector, Int>? =
    when (this) {
        DownloadStage.Waiting -> null
        DownloadStage.Downloading -> DemoIcons.Pause to R.string.ds_pause
        DownloadStage.Paused -> DemoIcons.Play to R.string.ds_resume
        DownloadStage.Stopped -> DemoIcons.Regenerate to R.string.ds_try_again
    }
