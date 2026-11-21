package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.min

/** The font scale from which rows, section labels and action rows take their stacked layout (models-page design §5). */
internal const val LARGE_TEXT_SCALE = 1.3f

/**
 * True when the person's font scale is [LARGE_TEXT_SCALE] or more: the point past which a line of a name and its
 * trailing parts no longer fits a phone's width, so the parts move under the name and the name keeps its width.
 */
@Composable
fun isLargeText(): Boolean = LocalDensity.current.fontScale >= LARGE_TEXT_SCALE

/**
 * [base] grown with the font, up to [max]: a mark that sits in a line of text (the fit symbol, a capability icon)
 * keeps its weight beside the text at large font, without outgrowing the row.
 */
@Composable
internal fun scaledWithText(base: Dp, max: Dp): Dp = min(base * LocalDensity.current.fontScale, max)
