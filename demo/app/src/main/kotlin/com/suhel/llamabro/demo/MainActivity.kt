package com.suhel.llamabro.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.suhel.llamabro.demo.ui.chat.ChatScreen
import com.suhel.llamabro.demo.ui.shell.AppShell
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    /** Set once the shell knows its layout and start screen; until then the splash stays, so no blank frame shows. */
    @Volatile private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !ready }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as DemoApp).container
        // Readiness comes from the data the shell starts from (the settings and the first scan of the library), not
        // from its composition: under a Compose test rule nothing composes until the activity has drawn, and the
        // splash would hold that first frame forever. A failed read must not hold it either, hence the cap.
        lifecycleScope.launch {
            withTimeoutOrNull(SPLASH_MAX_MILLIS) {
                container.settings.settings.first()
                container.models.library.first()
            }
            ready = true
        }
        setContent {
            AppShell(
                container,
                chatScreen = { id, chrome -> ChatScreen(container, id, chrome) },
                onReady = { ready = true },
            )
        }
    }

    private companion object {
        /** The longest the splash waits for the shell's data. */
        const val SPLASH_MAX_MILLIS = 2_000L
    }
}
