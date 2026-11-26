package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.qrInk
import com.suhel.llamabro.demo.designsystem.theme.qrPaper

/**
 * [text] as a QR code a phone's camera reads off the screen (the Server page's address and key): dark modules on a
 * light tile in both themes, with the standard four-module quiet zone, [DemoSize.qrCode] square on the card's corners.
 * A screen reader hears [label], never the code's text, which may hold a secret. Error correction M: a smudged or
 * glared screen still reads, and a short text stays a small code.
 */
@Composable
fun QrCode(text: String, label: String, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    val modules = remember(text) { QrModules.encode(text) }
    Box(
        modifier
            .size(DemoSize.qrCode)
            .semantics {
                contentDescription = label
                role = Role.Image
            }.clip(DemoShapes.card)
            .background(colors.qrPaper)
            .drawBehind {
                val ink = colors.qrInk
                val cell = size.width / (modules.size + 2 * QUIET_ZONE)
                // a dark run of a row is one rectangle: fewer draws, and no hairline seams between neighbours
                for (y in 0 until modules.size) {
                    var x = 0
                    while (x < modules.size) {
                        if (!modules[x, y]) {
                            x++
                            continue
                        }
                        val start = x
                        while (x < modules.size && modules[x, y]) x++
                        drawRect(
                            ink,
                            topLeft = Offset((start + QUIET_ZONE) * cell, (y + QUIET_ZONE) * cell),
                            size = Size((x - start) * cell, cell),
                        )
                    }
                }
            },
    )
}

/** The light ring of modules a scanner needs around a code (ISO/IEC 18004: four). */
private const val QUIET_ZONE = 4

/**
 * A QR code's modules, [size] by [size], true where dark: ZXing's encoder, without its quiet zone. Never changed once
 * encoded (the array is its own and never handed out), so Compose may skip what reads it.
 */
@Immutable
internal class QrModules private constructor(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]

    companion object {
        fun encode(text: String): QrModules {
            val hints =
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
                    EncodeHintType.MARGIN to 0,
                )
            // a size of 0 asks for the code's own size: one pixel a module
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            val size = matrix.width
            return QrModules(size, BooleanArray(size * size) { matrix[it % size, it / size] })
        }
    }
}
