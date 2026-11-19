package com.suhel.llamabro.demo.designsystem.samples

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.EmptyLine
import com.suhel.llamabro.demo.designsystem.components.Greeting
import com.suhel.llamabro.demo.designsystem.components.ModelName
import com.suhel.llamabro.demo.designsystem.components.Orb
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/** The first run's head (56 dp orb), an empty chat's (40 dp), a model's name and a screen title. */
@Composable
internal fun BrandSamples() {
    val c = LocalDemoColors.current
    SampleFrame {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Orb(56.dp, Modifier.padding(top = 8.dp, bottom = 10.dp))
            Greeting("Good evening")
            Text(
                "Pick a mind to talk to. It lives on this phone: private, and it works offline.",
                Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp),
                color = c.muted,
                style = DemoType.body,
                textAlign = TextAlign.Center,
            )
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Orb(40.dp, Modifier.padding(bottom = 12.dp))
            EmptyLine("What's on your mind?")
        }
        ModelName("Qwen3.5 0.8B", realName = "by Alibaba · by Alibaba Qwen")
        ModelName("Qwen3.5 4B")
        Greeting("Chats", textAlign = TextAlign.Start)
    }
}

@LightDarkPreviews
@Composable
private fun BrandPreview() = DemoTheme { BrandSamples() }
