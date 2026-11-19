package com.suhel.llamabro.demo.ui.chat

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.ModelName
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.gutterPadding
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What a share card holds (demo design §4.4): the reply's text, already cut at 600 characters, and who said it. */
data class ShareCardContent(val text: String, val modelName: String, val speed: String?)

/** The card: canvas background, 16 dp padding; the model's name in serif; "<n> words/s · on this phone". */
@Composable
fun ShareCard(content: ShareCardContent, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    Stack(Gap.Item, modifier.fillMaxWidth().background(colors.canvas).gutterPadding()) {
        Text(content.text, color = colors.ink, style = DemoType.body)
        ModelName(content.modelName)
        Text(
            listOfNotNull(content.speed, "on this phone").joinToString(" · "),
            color = colors.muted,
            style = DemoType.bodySmall,
        )
    }
}

/** How a finished card leaves the app: an intent the system shows as a chooser. A test supplies its own sink. */
val LocalIntentSink = androidx.compose.runtime.staticCompositionLocalOf<((Intent) -> Unit)?> { null }

/**
 * Renders [content] off-screen at 1080 px wide into a bitmap (`GraphicsLayer`), writes it as a PNG under the cache's
 * `share/` folder and hands its `FileProvider` URI to the system's share sheet; [onDone] runs after. The card is
 * composed while the request lives and draws nothing on screen.
 */
@Composable
fun ShareCardRenderer(content: ShareCardContent?, onDone: () -> Unit) {
    if (content == null) return
    val layer = rememberGraphicsLayer()
    val context = LocalContext.current
    val density = LocalDensity.current
    val sink = LocalIntentSink.current
    val done by rememberUpdatedState(onDone)
    val width = with(density) { 1080.toDp() }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .size(1.dp)
            .drawWithContent {
                // record the whole card (1080 px wide), not this 1 dp box; nothing is drawn on screen
                if (cardSize != IntSize.Zero) layer.record(size = cardSize) { this@drawWithContent.drawContent() }
            },
    ) {
        // 1080 px wide and as tall as its text needs, whatever the 1 dp box around it allows
        Box(Modifier.requiredWidth(width).wrapContentHeight(Alignment.Top, unbounded = true)) {
            ShareCard(content, Modifier.onSizeChanged { cardSize = it })
        }
    }
    LaunchedEffect(content) {
        try {
            // one frame later the layer has recorded the card
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            val recorded = layer.toImageBitmap().asAndroidBitmap()
            // a hardware bitmap can't always be compressed; draw it into a plain one
            val bitmap =
                if (recorded.config == Bitmap.Config.HARDWARE) {
                    recorded.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    recorded
                }
            val uri = withContext(Dispatchers.IO) { writePng(context, bitmap) }
            val send =
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("", uri) // the read permission follows the clip on every API level
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            if (sink != null) {
                sink(send)
            } else {
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // a full disk or a failed encode: no card, no crash
            Log.w("ShareCard", "could not share the card: ${e.javaClass.simpleName}")
        } finally {
            done()
        }
    }
}

private fun writePng(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    // cards the other app may still be reading are kept for a while; older ones are no use
    val cutoff = System.currentTimeMillis() - 10 * 60_000
    dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    val file = File(dir, "reply-${System.currentTimeMillis()}.png")
    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    return FileProvider.getUriForFile(context, "${context.packageName}.share", file)
}
