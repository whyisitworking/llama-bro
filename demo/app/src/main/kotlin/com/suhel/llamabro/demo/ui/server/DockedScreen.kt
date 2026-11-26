package com.suhel.llamabro.demo.ui.server

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.IconAction
import com.suhel.llamabro.demo.designsystem.components.Screen
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.TopBar
import com.suhel.llamabro.demo.designsystem.components.TopBarTitle
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.ui.Screens

/** The window's brightness while docked: low enough for a bedside table, still readable across a desk. */
internal const val DOCKED_BRIGHTNESS = 0.1f

/**
 * The docked view, stateful: the Server page's state through its view model (docs/design/server.md §3). Back, or the
 * end of serving, returns to the Server page.
 */
@Composable
fun DockedScreen(screens: Screens, onBack: () -> Unit) {
    val vm = viewModel(key = "server") { screens.serverViewModel() }
    val docked by vm.docked.collectAsStateWithLifecycle()
    docked?.let { DockedHost(it, onBack) }
}

/**
 * The docked view with its window: the screen kept on and dim while it is shown, restored when it leaves, and a way
 * back once serving has ended ([docked] is [Docked.Ended]).
 */
@Composable
internal fun DockedHost(docked: Docked, onBack: () -> Unit) {
    DockedWindow()
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(docked is Docked.Ended) { if (docked is Docked.Ended) back() }
    DockedContent(docked as? Docked.Showing, onBack)
}

/** Keeps the screen on and sets [DOCKED_BRIGHTNESS] while composed; the window's own values come back after. */
@Composable
private fun DockedWindow() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val params = window.attributes
        val brightness = params.screenBrightness
        val wasOn = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = DOCKED_BRIGHTNESS }
        onDispose {
            if (!wasOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = brightness }
        }
    }
}

/** The docked view, stateless: three numbers to read from across a room, the last request and the address. */
@Composable
fun DockedContent(docked: Docked.Showing?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    Screen(
        topBar = {
            TopBar(
                title = { TopBarTitle("Docked") },
                start = { IconAction(DemoIcons.Back, "Back", onClick = onBack) },
            )
        },
        modifier = modifier.testTag("docked_page"),
        scrolls = false,
    ) {
        if (docked == null) return@Screen
        Column(
            Modifier.fillMaxSize(),
            Arrangement.SpaceEvenly,
            Alignment.CenterHorizontally,
        ) {
            Glance(docked.speed, "tokens per second", "docked_speed")
            Glance(docked.busy, "requests at once", "docked_busy")
            docked.temperature?.let { Glance(it, "temperature", "docked_temperature") }
            Stack(Gap.Inline, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    docked.last ?: "No requests yet",
                    color = colors.muted,
                    style = DemoType.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("docked_last"),
                )
                docked.url?.let { Text(it, color = colors.muted, style = DemoType.code) }
            }
        }
    }
}

/** One number as large as the scale goes, with what it counts under it. */
@Composable
private fun Glance(value: String, label: String, tag: String) {
    val colors = LocalDemoColors.current
    Stack(Gap.None, Modifier.testTag(tag), Alignment.CenterHorizontally) {
        Text(value, color = colors.ink, style = DemoType.glance)
        Text(label, color = colors.muted, style = DemoType.bodyMedium)
    }
}
