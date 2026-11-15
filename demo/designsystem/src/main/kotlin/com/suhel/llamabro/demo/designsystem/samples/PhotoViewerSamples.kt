package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.PhotoViewer
import com.suhel.llamabro.demo.designsystem.components.UserBubble
import com.suhel.llamabro.demo.designsystem.components.ViewerPhoto
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/**
 * A message's photos and the viewer they open in, at the second of three (the third couldn't be decoded); it looks the
 * same in both themes.
 */
@Composable
internal fun PhotoViewerSamples() {
    val photo = rememberSamplePhoto()
    SampleFrame {
        UserBubble("Which of these is healthiest?", images = listOf(photo, photo, photo), onImageClick = {})
        Box(Modifier.fillMaxWidth().height(560.dp)) {
            PhotoViewer(
                listOf(ViewerPhoto.Ready(photo), ViewerPhoto.Ready(photo), ViewerPhoto.Unavailable),
                startAt = 1,
                onClose = {},
            )
        }
    }
}

@Preview(name = "Photo viewer", widthDp = 360, heightDp = 640)
@Composable
private fun PhotoViewerPreview() =
    DemoTheme {
        Box { PhotoViewer(listOf(ViewerPhoto.Ready(rememberSamplePhoto())), 0, onClose = {}) }
    }

@LightDarkPreviews
@Composable
private fun PhotoViewerSamplesPreview() = DemoTheme { PhotoViewerSamples() }
