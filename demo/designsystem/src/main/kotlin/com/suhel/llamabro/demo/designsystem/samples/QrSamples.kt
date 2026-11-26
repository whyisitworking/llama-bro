package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.runtime.Composable
import com.suhel.llamabro.demo.designsystem.components.QrCode
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme

/** A QR code of a server's address and key, as the Server page shows it: dark on light in both themes. */
@Composable
internal fun QrSamples() {
    SampleFrame {
        QrCode(
            "{\"url\":\"http://192.168.1.20:8080\",\"key\":\"q8Vw3nX0dJk2LmP5sT7uY9zA1bC4eF6gH8iK0lM2nO4\"}",
            label = "QR code of the address and key",
        )
    }
}

@LightDarkPreviews
@Composable
private fun QrPreview() = DemoTheme { QrSamples() }
