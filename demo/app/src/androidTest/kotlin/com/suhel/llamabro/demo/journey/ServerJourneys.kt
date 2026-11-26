package com.suhel.llamabro.demo.journey

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.server.ServingState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Server mode from its page (docs/design/server.md §3), over the fake engine: open Server from the drawer, start
 * serving the model on the phone, see where it answers, stop.
 */
@OptIn(ExperimentalTestApi::class)
class ServerJourneys : Journey() {
    private val swift = Catalog.picks[0].key

    /** A journey that failed halfway leaves nothing listening. */
    @After fun stopServing() = runBlocking { app.container.server.stop() }

    @Test fun startServingSeeTheAddressAndStop() {
        onPhone(swift)
        see("What's on your mind?")
        tapDescription("Chats")
        tapDescription("Server")
        seeTag("server_models")
        see("Qwen3.5 0.8B")
        shot("1-off")

        tapTag("server_start")
        seeTag("server_stop")
        compose.waitUntil(WAIT) { app.container.server.state.value is ServingState.Serving }
        val port = (app.container.server.state.value as ServingState.Serving).port
        // the address is the device's own Wi-Fi one; a device without Wi-Fi says it found none
        val address =
            app.container.network.addresses.value
                .firstOrNull()
        compose.waitUntilAtLeastOneExists(hasText("••••••••") and hasAnyAncestor(hasTestTag("server_key")), WAIT)
        if (address != null) {
            see("http://$address:$port")
            // no QR code (a laptop can't scan one); the key shows in four groups once the person asks
            goneTag("server_qr")
            shot("2-serving")
            tap("Show")
            gone("••••••••")
            shot("3-key-shown")
        } else {
            seeTag("server_no_address")
            shot("2-serving")
        }

        // the docked view: big numbers, no key; Back returns to the page, still serving
        // the row can sit under the Stop bar, which would take a tap on it: ask the row itself
        seeTag("server_dock")
        compose.onNodeWithTag("server_dock").performSemanticsAction(SemanticsActions.OnClick)
        seeTag("docked_page")
        seeTag("docked_speed")
        seeTag("docked_busy")
        goneTag("server_key")
        shot("4-docked")
        tapDescription("Back")
        seeTag("server_stop")

        tapTag("server_stop")
        seeTag("server_start")
        compose.waitUntil(WAIT) { app.container.server.state.value == ServingState.Off }
        shot("5-stopped")
    }
}
