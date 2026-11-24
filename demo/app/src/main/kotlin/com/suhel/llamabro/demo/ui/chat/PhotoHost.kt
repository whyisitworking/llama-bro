package com.suhel.llamabro.demo.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalWindowInfo
import com.suhel.llamabro.demo.designsystem.components.PhotoViewerDialog
import com.suhel.llamabro.demo.designsystem.components.ViewerPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** The photo open full-screen: the [index]th of the user message at [position]. */
data class OpenPhoto(val position: Int, val index: Int)

val OpenPhotoSaver: Saver<OpenPhoto?, Any> =
    listSaver(
        save = { if (it == null) emptyList() else listOf(it.position, it.index) },
        restore = { if (it.isEmpty()) null else OpenPhoto(it[0], it[1]) },
    )

/** The viewer over the whole window; swiping keeps [onPageChange] told, so a rotation reopens the photo in view. */
@Composable
fun PhotoHost(target: OpenPhoto, rows: List<ChatItem>, onPageChange: (Int) -> Unit, onClose: () -> Unit) {
    val files = rows.firstNotNullOfOrNull { (it as? UserItem)?.takeIf { u -> u.position == target.position }?.images }
    if (files.isNullOrEmpty()) {
        LaunchedEffect(target) { onClose() } // the message is gone (an edit cut it away): close the viewer
        return
    }
    val window = LocalWindowInfo.current.containerSize
    val longSide = max(window.width, window.height)
    // the photo first shown decodes first; the page changing afterwards doesn't restart the decoding
    val first = remember(files) { target.index }
    PhotoViewerDialog(rememberFullImages(files, longSide, first), target.index, onClose, onPageChange)
}

/**
 * Decodes a message's pictures for the viewer, off the main thread, [first] first and each shown as it lands: at full
 * size when they fit the screen (a saved picture is at most 1024 px), halved until they do otherwise.
 */
@Composable
private fun rememberFullImages(files: List<File>, longSide: Int, first: Int): List<ViewerPhoto> {
    val photos by produceState(List<ViewerPhoto>(files.size) { ViewerPhoto.Loading }, files, longSide) {
        val order = listOf(first.coerceIn(files.indices)) + files.indices.filter { it != first }
        for (i in order) {
            val bitmap = withContext(Dispatchers.IO) { decode(files[i], longSide) }
            value = value.toMutableList().also { it[i] = bitmap?.let(ViewerPhoto::Ready) ?: ViewerPhoto.Unavailable }
        }
    }
    return photos
}

private fun decode(file: File, longSide: Int): ImageBitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val options =
            BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, longSide) }
        BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
    }.getOrNull()

/** The power-of-two sample size that brings a [width] x [height] picture's long side within [maxSide]. */
fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    val long = max(width, height)
    if (maxSide <= 0) return sample
    while (long / sample > maxSide) sample *= 2
    return sample
}
