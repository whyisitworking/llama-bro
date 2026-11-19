package com.suhel.llamabro.demo.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.suhel.llamabro.demo.designsystem.components.Centered
import com.suhel.llamabro.demo.designsystem.components.EmptyLine
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.Orb
import com.suhel.llamabro.demo.designsystem.components.OrbSize
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.StarterChips

/** The starter prompts of an empty chat (plain, everyday wording). */
val StarterPrompts = listOf("Plan my week", "Explain something simply", "Help me write a message")

/** A chat with no messages: the 40 dp orb, the serif line and three starter chips. */
@Composable
fun EmptyChat(onStarter: (String) -> Unit, modifier: Modifier = Modifier) {
    Centered(modifier) {
        Stack(Gap.Item, horizontalAlignment = Alignment.CenterHorizontally) {
            Orb(OrbSize.Empty)
            EmptyLine("What's on your mind?")
        }
        StarterChips(StarterPrompts, onStarter)
    }
}
